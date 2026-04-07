package com.builder.builder_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class GeminiService {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GeminiService() {
        this.restClient = RestClient.create();
        this.objectMapper = new ObjectMapper();
    }

    public String generateWidget(String userPrompt, String vibe) {
        String systemPrompt = """
            Tu es un Directeur Artistique avant-gardiste primé. Tu génères des composants web en JSON strict.
            RÈGLES DE DESIGN (INTERDICTIONS) :
            - N'utilise JAMAIS la structure classique 'Titre centré + texte + bouton'.
            - Évite la symétrie parfaite. Joue sur l'asymétrie avec des grilles.
            RÈGLES DE DESIGN (OBLIGATIONS) :
            - Utilise l'espace négatif (des paddings importants).
            - Joue sur des contrastes de taille extrêmes (ex: var(--font-size-2xl)).
            CONTRAINTES TECHNIQUES :
            - Interface requise : { "type": "container"|"text"|"action"|"media", "element": "div"|"h1", "name": "...", "content": "...", "styles": {}, "children": [] }
            - Variables CSS autorisées : var(--primary), var(--surface), var(--text), var(--text-muted), var(--spacing-base), var(--radius-main).
            - Ne renvoie QUE du JSON.
            """;

        String contextualPrompt = String.format(
                "Demande : '%s'. Style imposé : '%s'. Surprends-moi.",
                userPrompt, vibe
        );

        Map<String, Object> requestBody = Map.of(
                "system_instruction", Map.of("parts", Map.of("text", systemPrompt)),
                "contents", List.of(Map.of("parts", List.of(Map.of("text", contextualPrompt)))),
                "generationConfig", Map.of(
                        "response_mime_type", "application/json",
                        "temperature", 0.9
                )
        );

        try {
            String response = restClient.post()
                    .uri(apiUrl + "?key=" + apiKey)
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            return extractJsonFromResponse(response);
        } catch (Exception e) {
            throw new RuntimeException("Erreur de communication avec l'IA : " + e.getMessage());
        }
    }

    private String extractJsonFromResponse(String rawResponse) throws Exception {
        JsonNode rootNode = objectMapper.readTree(rawResponse);
        return rootNode.path("candidates").get(0)
                .path("content").path("parts").get(0)
                .path("text").asText();
    }
}