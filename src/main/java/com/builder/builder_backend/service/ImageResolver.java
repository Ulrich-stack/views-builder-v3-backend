package com.builder.builder_backend.service;

import com.builder.builder_backend.service.PhotoSearch.Orientation;
import com.builder.builder_backend.service.PhotoSearch.Photo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remplace le champ « imageQuery » des images générées par une vraie photo libre de droits.
 * Banques interrogées dans l'ordre : Unsplash (UNSPLASH_ACCESS_KEY), Pexels (PEXELS_API_KEY) si leur clé est
 * configurée, puis Openverse en secours. Le crédit du photographe est rangé dans la config (« credit », « creditUrl »).
 * Chaque recherche tient compte de la forme de l'emplacement (portrait, paysage, carré).
 * Sans résultat, l'image reste vide et l'éditeur affiche son emplacement « Ajoutez une image ».
 * Le champ « backgroundQuery » d'un bloc fait de même pour sa photo de fond : elle est ajoutée
 * sous les calques déjà présents dans « background-image » (voile sombre, dégradé).
 */
@Service
public class ImageResolver {

    private static final Logger log = LoggerFactory.getLogger(ImageResolver.class);
    private static final Pattern RATIO = Pattern.compile("([\\d.]+)\\s*/\\s*([\\d.]+)");

    private static final int CACHE_SIZE = 300;

    /** Banques interrogées dans l'ordre : la première qui trouve une photo l'emporte. */
    private final List<PhotoSearch> sources;

    /** Recherches déjà faites : économise la limite horaire d'Unsplash quand une requête revient. */
    private final Map<String, Found> cache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Found> eldest) {
            return size() > CACHE_SIZE;
        }
    });

    @Autowired
    public ImageResolver(@Value("${unsplash.access-key:}") String unsplashKey, @Value("${pexels.api.key:}") String pexelsKey) {
        this(sourcesFor(unsplashKey, pexelsKey));
    }

    ImageResolver(List<PhotoSearch> sources) {
        this.sources = sources;
    }

    private static List<PhotoSearch> sourcesFor(String unsplashKey, String pexelsKey) {
        List<PhotoSearch> sources = new ArrayList<>();
        if (unsplashKey != null && !unsplashKey.isBlank()) sources.add(new UnsplashSearch(unsplashKey.trim()));
        if (pexelsKey != null && !pexelsKey.isBlank()) sources.add(new PexelsSearch(pexelsKey.trim()));
        if (sources.isEmpty()) log.info("Aucune clé Unsplash ni Pexels : les photos viendront d'Openverse, banque plus réduite");
        sources.add(new OpenverseSearch());
        return List.copyOf(sources);
    }

    /** Photos trouvées et la banque qui les a fournies (pour lui signaler celles qui sont utilisées). */
    record Found(PhotoSearch source, List<Photo> photos) {
        static final Found NONE = new Found(null, List.of());
    }

    /** Résout toutes les images de l'arbre, sans jamais réutiliser la même photo dans une page. */
    public void resolveImages(JsonNode widgets) {
        List<Target> targets = new ArrayList<>();
        collect(widgets, targets);
        if (targets.isEmpty()) return;

        // Une recherche par requête et par forme, toutes en parallèle : la page attend la plus lente, pas leur somme
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(targets.size(), 6));
        try {
            Map<String, CompletableFuture<Found>> searches = new LinkedHashMap<>();
            for (Target target : targets) {
                searches.computeIfAbsent(target.key(),
                        k -> CompletableFuture.supplyAsync(() -> searchWithFallback(target.query(), target.orientation()), executor));
            }

            Set<String> used = new HashSet<>();
            for (Target target : targets) {
                Found found = searches.get(target.key()).join();
                List<Photo> photos = found.photos();
                Photo photo = photos.stream().filter(p -> !used.contains(p.url())).findFirst()
                        .orElse(photos.isEmpty() ? null : photos.get(0));
                if (photo == null) continue;
                used.add(photo.url());
                if (target.background()) setBackground(target.node(), photo.large());
                else target.node().put("src", photo.url());
                setCredit(target, photo);
                found.source().markUsed(photo);
            }
        } finally {
            executor.shutdown();
        }
    }

    /** Une image à chercher : la config d'un bloc média, ou le bloc lui-même pour une photo de fond. */
    private record Target(ObjectNode node, boolean background, String query, Orientation orientation) {
        String key() {
            return query + "|" + orientation;
        }
    }

    private void collect(JsonNode widgets, List<Target> targets) {
        for (JsonNode widget : widgets) {
            if (widget.path("config") instanceof ObjectNode config) {
                String query = config.path("imageQuery").asText("").trim().toLowerCase();
                config.remove("imageQuery");
                if ("media".equals(widget.path("type").asText()) && !query.isEmpty() && !config.has("src")) {
                    targets.add(new Target(config, false, query, orientationOf(widget.path("styles"))));
                }
                String background = config.path("backgroundQuery").asText("").trim().toLowerCase();
                config.remove("backgroundQuery");
                // Une photo de fond couvre une section large : on la veut en paysage
                if (!background.isEmpty()) targets.add(new Target((ObjectNode) widget, true, background, Orientation.LANDSCAPE));
            }
            collect(widget.path("children"), targets);
        }
    }

    /** Forme de l'emplacement d'après son « aspect-ratio » (4 / 5 → portrait, 16 / 7 → paysage, 1 / 1 → carré). */
    static Orientation orientationOf(JsonNode styles) {
        Matcher m = RATIO.matcher(styles.path("aspect-ratio").asText(""));
        if (!m.find()) return Orientation.ANY;
        double ratio = Double.parseDouble(m.group(1)) / Double.parseDouble(m.group(2));
        if (ratio < 0.9) return Orientation.PORTRAIT;
        if (ratio > 1.15) return Orientation.LANDSCAPE;
        return Orientation.SQUARE;
    }

    /**
     * Cherche dans chaque banque, d'abord la requête complète puis son premier mot, qui porte le sujet
     * (« tea room » → « tea », « yoga class » → « yoga ») : le dernier mot donnait des photos hors sujet.
     */
    Found searchWithFallback(String query, Orientation orientation) {
        String key = query + "|" + orientation;
        Found cached = cache.get(key);
        if (cached != null) return cached;
        List<String> queries = query.contains(" ") ? List.of(query, query.substring(0, query.indexOf(' '))) : List.of(query);
        for (PhotoSearch source : sources) {
            for (String q : queries) {
                List<Photo> photos = source.search(q, orientation);
                if (!photos.isEmpty()) {
                    Found found = new Found(source, photos);
                    cache.put(key, found);
                    return found;
                }
            }
        }
        return Found.NONE;
    }

    /** Range le crédit du photographe sur l'image (ou sur le bloc pour une photo de fond). */
    private static void setCredit(Target target, Photo photo) {
        if (photo.credit() == null) return;
        ObjectNode config = target.background()
                ? (target.node().path("config") instanceof ObjectNode c ? c : target.node().putObject("config"))
                : target.node();
        config.put("credit", photo.credit());
        if (photo.creditUrl() != null) config.put("creditUrl", photo.creditUrl());
    }

    /** Ajoute la photo sous les calques existants (voile) ; l'adresse est encodée pour rester une url() sûre. */
    private static void setBackground(ObjectNode block, String url) {
        ObjectNode styles = block.path("styles") instanceof ObjectNode existing ? existing : block.putObject("styles");
        String safe = url.replace("'", "%27").replace("(", "%28").replace(")", "%29").replace(" ", "%20");
        String layers = styles.path("background-image").asText("").trim();
        styles.put("background-image", (layers.isEmpty() ? "" : layers + ", ") + "url('" + safe + "')");
        if (!styles.has("background-size")) styles.put("background-size", "cover");
        if (!styles.has("background-position")) styles.put("background-position", "center");
    }
}
