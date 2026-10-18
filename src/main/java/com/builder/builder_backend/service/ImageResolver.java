package com.builder.builder_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Remplace le champ « imageQuery » des images générées par une vraie photo libre de droits (CC0),
 * trouvée sur Openverse parmi les photos de StockSnap. Sans résultat, l'image reste vide
 * et l'éditeur affiche son emplacement « Ajoutez une image ».
 * Le champ « backgroundQuery » d'un bloc fait de même pour sa photo de fond : elle est ajoutée
 * sous les calques déjà présents dans « background-image » (voile sombre, dégradé).
 */
@Service
public class ImageResolver {

    private static final Logger log = LoggerFactory.getLogger(ImageResolver.class);
    private static final String SEARCH_URL =
            "https://api.openverse.org/v1/images/?q={q}&source=stocksnap&license_type=commercial&page_size=20";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImageResolver() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(4));
        // Openverse peut mettre plusieurs secondes sur une requête jamais faite (pas encore en cache)
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** Résout toutes les images de l'arbre, sans jamais réutiliser la même photo dans une page. */
    public void resolveImages(JsonNode widgets) {
        List<Target> targets = new ArrayList<>();
        collect(widgets, targets);
        if (targets.isEmpty()) return;

        // Une recherche par requête distincte, toutes en parallèle : la page attend la plus lente, pas leur somme
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(targets.size(), 6));
        try {
            Map<String, CompletableFuture<List<String>>> searches = new LinkedHashMap<>();
            for (Target target : targets) {
                searches.computeIfAbsent(target.query(), q -> CompletableFuture.supplyAsync(() -> searchWithFallback(q), executor));
            }

            Set<String> used = new HashSet<>();
            for (Target target : targets) {
                List<String> urls = searches.get(target.query()).join();
                String url = urls.stream().filter(u -> !used.contains(u)).findFirst()
                        .orElse(urls.isEmpty() ? null : urls.get(0));
                if (url == null) continue;
                used.add(url);
                if (target.background()) setBackground(target.node(), url);
                else target.node().put("src", url);
            }
        } finally {
            executor.shutdown();
        }
    }

    /** Une image à chercher : la config d'un bloc média, ou le bloc lui-même pour une photo de fond. */
    private record Target(ObjectNode node, boolean background, String query) {
    }

    private void collect(JsonNode widgets, List<Target> targets) {
        for (JsonNode widget : widgets) {
            if (widget.path("config") instanceof ObjectNode config) {
                String query = config.path("imageQuery").asText("").trim().toLowerCase();
                config.remove("imageQuery");
                if ("media".equals(widget.path("type").asText()) && !query.isEmpty() && !config.has("src")) {
                    targets.add(new Target(config, false, query));
                }
                String background = config.path("backgroundQuery").asText("").trim().toLowerCase();
                config.remove("backgroundQuery");
                if (!background.isEmpty()) targets.add(new Target((ObjectNode) widget, true, background));
            }
            collect(widget.path("children"), targets);
        }
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

    private List<String> searchWithFallback(String query) {
        List<String> urls = search(query);
        // Requête trop précise : on retente avec le mot principal
        if (urls.isEmpty() && query.contains(" ")) {
            urls = search(query.substring(query.lastIndexOf(' ') + 1));
        }
        return urls;
    }

    private List<String> search(String query) {
        List<String> urls = new ArrayList<>();
        try {
            String body = restClient.get().uri(SEARCH_URL, query).retrieve().body(String.class);
            for (JsonNode result : objectMapper.readTree(body).path("results")) {
                String url = result.path("url").asText("");
                if (url.startsWith("https://")) urls.add(url);
            }
        } catch (Exception e) {
            // Une image manquante ne doit pas faire échouer toute la génération
            log.warn("Recherche d'image impossible pour « {} » : {}", query, e.getMessage());
        }
        return urls;
    }
}
