package com.builder.builder_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Recherche sur Unsplash : photos de grande qualité, gratuites. Demande une clé d'accès (UNSPLASH_ACCESS_KEY),
 * limitée à 50 recherches par heure en mode démo.
 * Règles d'Unsplash respectées : les photos sont affichées depuis leurs serveurs, chaque photo placée dans une page
 * est signalée (markUsed), et le photographe est crédité (« Nom sur Unsplash »).
 */
public class UnsplashSearch implements PhotoSearch {

    private static final Logger log = LoggerFactory.getLogger(UnsplashSearch.class);
    private static final String SEARCH_URL =
            "https://api.unsplash.com/search/photos?query={q}&per_page=15&content_filter=high{orientation}";
    // Paramètre demandé par Unsplash sur les liens de crédit
    private static final String UTM = "?utm_source=views_builder&utm_medium=referral";

    private final String accessKey;
    private final RestClient restClient;

    public UnsplashSearch(String accessKey) {
        this.accessKey = accessKey;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(4));
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public List<Photo> search(String query, Orientation orientation) {
        try {
            String body = restClient.get()
                    .uri(SEARCH_URL, query, orientationParam(orientation))
                    .header("Authorization", "Client-ID " + accessKey)
                    .header("Accept-Version", "v1")
                    .retrieve().body(String.class);
            return parse(body);
        } catch (Exception e) {
            // Limite horaire atteinte ou réseau indisponible : la banque suivante prend le relais
            log.warn("Recherche Unsplash impossible pour « {} » : {}", query, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void markUsed(Photo photo) {
        if (photo.trackUrl() == null || !photo.trackUrl().startsWith("https://api.unsplash.com/")) return;
        // Envoyé en arrière-plan : la page n'attend pas ce signalement
        CompletableFuture.runAsync(() -> {
            try {
                restClient.get().uri(photo.trackUrl())
                        .header("Authorization", "Client-ID " + accessKey)
                        .retrieve().toBodilessEntity();
            } catch (Exception e) {
                log.debug("Signalement Unsplash impossible : {}", e.getMessage());
            }
        });
    }

    static String orientationParam(Orientation orientation) {
        return switch (orientation) {
            case LANDSCAPE -> "&orientation=landscape";
            case PORTRAIT -> "&orientation=portrait";
            case SQUARE -> "&orientation=squarish";
            case ANY -> "";
        };
    }

    /** Lit la réponse d'Unsplash : 1080 px de large pour la page, 1920 px pour une photo de fond. */
    static List<Photo> parse(String json) throws Exception {
        List<Photo> photos = new ArrayList<>();
        for (JsonNode result : new ObjectMapper().readTree(json).path("results")) {
            String raw = result.path("urls").path("raw").asText("");
            String regular = result.path("urls").path("regular").asText("");
            if (!regular.startsWith("https://")) continue;
            String large = raw.startsWith("https://")
                    ? raw + (raw.contains("?") ? "&" : "?") + "w=1920&q=80&fm=jpg&fit=crop"
                    : regular;
            String name = result.path("user").path("name").asText("").trim();
            String profile = result.path("user").path("links").path("html").asText("");
            photos.add(new Photo(regular, large,
                    name.isEmpty() ? "Unsplash" : name + " sur Unsplash",
                    profile.startsWith("https://") ? profile + UTM : "https://unsplash.com/" + UTM,
                    result.path("links").path("download_location").asText(null)));
        }
        return photos;
    }
}
