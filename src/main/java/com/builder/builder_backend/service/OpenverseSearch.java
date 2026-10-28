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
 * Recherche sur Openverse, parmi les photos CC0 de StockSnap : sans clé, mais banque réduite et parfois lente.
 * Sert de secours quand Pexels n'est pas configuré ou ne trouve rien. La forme demandée est ignorée :
 * filtrer une banque aussi réduite la laisserait souvent sans résultat.
 */
public class OpenverseSearch implements PhotoSearch {

    private static final Logger log = LoggerFactory.getLogger(OpenverseSearch.class);
    private static final String SEARCH_URL =
            "https://api.openverse.org/v1/images/?q={q}&source=stocksnap&license_type=commercial&page_size=20";

    private final RestClient restClient;

    public OpenverseSearch() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(4));
        // Openverse peut mettre plusieurs secondes sur une requête jamais faite (pas encore en cache)
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public List<Photo> search(String query, Orientation orientation) {
        List<Photo> photos = new ArrayList<>();
        try {
            String body = restClient.get().uri(SEARCH_URL, query).retrieve().body(String.class);
            for (JsonNode result : new ObjectMapper().readTree(body).path("results")) {
                String url = result.path("url").asText("");
                if (url.startsWith("https://")) photos.add(new Photo(url, url));
            }
        } catch (Exception e) {
            log.warn("Recherche Openverse impossible pour « {} » : {}", query, e.getMessage());
        }
        return photos;
    }
}
