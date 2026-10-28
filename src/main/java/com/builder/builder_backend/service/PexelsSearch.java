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

/**
 * Recherche sur Pexels : grande banque de photos utilisables gratuitement, y compris en commercial.
 * Demande une clé d'API gratuite (variable PEXELS_API_KEY), limitée à 200 recherches par heure.
 */
public class PexelsSearch implements PhotoSearch {

    private static final Logger log = LoggerFactory.getLogger(PexelsSearch.class);
    private static final String SEARCH_URL = "https://api.pexels.com/v1/search?query={q}&per_page=15{orientation}";

    private final String apiKey;
    private final RestClient restClient;

    public PexelsSearch(String apiKey) {
        this.apiKey = apiKey;
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
                    .header("Authorization", apiKey)
                    .retrieve().body(String.class);
            return parse(body);
        } catch (Exception e) {
            // Une photo manquante ne doit pas faire échouer la génération : on passera à la banque suivante
            log.warn("Recherche Pexels impossible pour « {} » : {}", query, e.getMessage());
            return List.of();
        }
    }

    static String orientationParam(Orientation orientation) {
        return switch (orientation) {
            case LANDSCAPE -> "&orientation=landscape";
            case PORTRAIT -> "&orientation=portrait";
            case SQUARE -> "&orientation=square";
            case ANY -> "";
        };
    }

    /** Lit la réponse de Pexels : « large » (940 px de large) pour la page, « large2x » pour un fond. */
    static List<Photo> parse(String json) throws Exception {
        List<Photo> photos = new ArrayList<>();
        for (JsonNode photo : new ObjectMapper().readTree(json).path("photos")) {
            String url = photo.path("src").path("large").asText("");
            String large = photo.path("src").path("large2x").asText(url);
            if (!url.startsWith("https://")) continue;
            String name = photo.path("photographer").asText("").trim();
            String profile = photo.path("photographer_url").asText("");
            photos.add(new Photo(url, large.startsWith("https://") ? large : url,
                    name.isEmpty() ? "Pexels" : name + " sur Pexels",
                    profile.startsWith("https://") ? profile : "https://www.pexels.com/", null));
        }
        return photos;
    }
}
