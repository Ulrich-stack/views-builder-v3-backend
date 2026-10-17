package com.builder.builder_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class GeminiService {

    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 3000;

    private final String apiKey;
    private final String apiUrl;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    // Consignes de design (règles inspirées d'impeccable) et directions artistiques par thème (ui-ux-pro-max)
    private final String systemPrompt;
    private final JsonNode themeBriefs;

    public GeminiService(
            @Value("${gemini.api.key}") String apiKey,
            @Value("${gemini.api.base-url}") String baseUrl,
            @Value("${gemini.model}") String model
    ) throws IOException {
        this.apiKey = apiKey;
        this.apiUrl = baseUrl + "/models/" + model + ":generateContent";
        this.restClient = RestClient.create();
        this.objectMapper = new ObjectMapper();
        this.systemPrompt = readResource("ai/system-prompt.md");
        this.themeBriefs = objectMapper.readTree(readResource("ai/theme-briefs.json"));
    }

    /**
     * Génère des sections de page pour l'éditeur.
     *
     * @param userPrompt la demande de l'utilisateur
     * @param themeName  le nom du thème actif dans l'éditeur (« Minuit », « Nature »...) ou null s'il est personnalisé
     * @return le JSON { "mode": ..., "widgets": [...] } déjà nettoyé
     */
    public String generateWidgets(String userPrompt, String themeName) {
        JsonNode brief = themeBriefs.has(themeName == null ? "" : themeName)
                ? themeBriefs.get(themeName)
                : themeBriefs.get("_default");

        String contextualPrompt = """
                Demande de l'utilisateur : %s

                Thème actif : %s — direction « %s ».
                %s
                À éviter avec ce thème : %s
                """.formatted(
                userPrompt,
                themeName == null || themeName.isBlank() ? "personnalisé" : themeName,
                brief.path("style").asText(),
                brief.path("brief").asText(),
                brief.path("avoid").asText()
        );

        Map<String, Object> requestBody = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", contextualPrompt)))),
                "generationConfig", Map.of(
                        "response_mime_type", "application/json",
                        "temperature", 0.7
                )
        );

        String response = callGemini(requestBody);

        try {
            JsonNode generated = objectMapper.readTree(extractText(response));
            return objectMapper.writeValueAsString(WidgetSanitizer.sanitize(generated));
        } catch (IOException e) {
            throw new IllegalStateException("L'IA a renvoyé un JSON invalide", e);
        }
    }

    private String callGemini(Map<String, Object> requestBody) {
        for (int attempt = 1; ; attempt++) {
            try {
                return restClient.post()
                        .uri(apiUrl)
                        // La clé passe dans un en-tête plutôt que dans l'URL, pour ne pas finir dans les logs
                        .header("x-goog-api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);
            } catch (HttpServerErrorException.ServiceUnavailable e) {
                // Modèle surchargé : ces pics sont brefs, on retente une fois après une courte pause
                if (attempt >= MAX_ATTEMPTS) {
                    throw new AiUnavailableException("L'IA est très sollicitée en ce moment, réessayez dans quelques instants.", e);
                }
                sleep(RETRY_DELAY_MS);
            } catch (Exception e) {
                throw new AiUnavailableException("Erreur de communication avec l'IA : " + e.getMessage(), e);
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException("Génération interrompue", e);
        }
    }

    private String extractText(String rawResponse) throws IOException {
        JsonNode parts = objectMapper.readTree(rawResponse)
                .path("candidates").path(0).path("content").path("parts");
        // Les modèles récents peuvent renvoyer plusieurs parties : on garde le texte de la réponse
        for (JsonNode part : parts) {
            if (part.has("text") && !part.path("thought").asBoolean(false)) {
                return part.get("text").asText();
            }
        }
        throw new IllegalStateException("La réponse de l'IA est vide");
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
