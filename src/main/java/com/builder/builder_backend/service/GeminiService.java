package com.builder.builder_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Service
public class GeminiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);
    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 3000;
    private static final int SECTION_WORKERS = 4;
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    // Environ 5 Mo d'image une fois décodée
    private static final int MAX_IMAGE_BASE64_LENGTH = 7_000_000;
    private static final String IMAGE_PLACEHOLDER = "@image:";
    private static final String QUOTA_MESSAGE =
            "Quota de l'API Gemini atteint. Patientez une minute, ou vérifiez les limites de votre clé dans Google AI Studio.";

    private final String apiKey;
    private final String generateUrl;
    private final String streamUrl;
    private final String thinkingLevel;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    // Consignes de design (règles inspirées d'impeccable) et directions artistiques par thème (ui-ux-pro-max)
    private final String pagePrompt;
    private final String editPrompt;
    private final JsonNode themeBriefs;
    private final ImageResolver imageResolver;
    private final SectionComposer sectionComposer;

    /** Capture d'écran de référence envoyée par l'utilisateur, encodée en base64. */
    public record ReferenceImage(String mimeType, String base64) {
        public ReferenceImage {
            if (!IMAGE_TYPES.contains(mimeType)) {
                throw new IllegalArgumentException("Format d'image non pris en charge : utilisez PNG, JPEG ou WebP.");
            }
            if (base64 == null || base64.isBlank() || base64.length() > MAX_IMAGE_BASE64_LENGTH) {
                throw new IllegalArgumentException("L'image est vide ou trop lourde (5 Mo maximum).");
            }
        }
    }

    /** Une demande de page : texte, thème actif, largeur de la page et capture facultative. */
    /** @param personality famille de styles envoyée par l'éditeur (utile pour un thème personnalisé), ou null */
    public record PageRequest(String prompt, String themeName, String personality, boolean chooseColors, int pageWidth,
                              ReferenceImage image) {
    }

    /** Événements envoyés pendant une génération en direct. */
    public sealed interface StreamEvent permits ModeEvent, SectionEvent, IncompleteEvent, PaletteEvent {
    }

    /** La réponse de l'IA s'est arrêtée avant la fin : seules les premières sections sont arrivées. */
    public record IncompleteEvent(int received) implements StreamEvent {
    }

    /** Palette choisie par l'IA : couleur principale (#rrggbb) et fond clair ou sombre ; l'éditeur en tire le reste. */
    public record PaletteEvent(String primary, String mode) implements StreamEvent {
        private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

        /** Palette valide tirée de la réponse de l'IA, ou null si elle est absente ou inutilisable. */
        public static PaletteEvent from(JsonNode palette) {
            if (palette == null || !palette.isObject()) return null;
            String primary = palette.path("primary").asText("").trim();
            if (!HEX.matcher(primary).matches()) return null;
            return new PaletteEvent(primary.toLowerCase(), "dark".equals(palette.path("mode").asText()) ? "dark" : "light");
        }

        public ObjectNode toJson() {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("primary", primary);
            node.put("mode", mode);
            return node;
        }
    }

    public record ModeEvent(String mode) implements StreamEvent {
    }

    public record SectionEvent(String widgetJson) implements StreamEvent {
    }

    public GeminiService(
            @Value("${gemini.api.key}") String apiKey,
            @Value("${gemini.api.base-url}") String baseUrl,
            @Value("${gemini.model}") String model,
            @Value("${gemini.thinking-level:low}") String thinkingLevel,
            ImageResolver imageResolver,
            SectionComposer sectionComposer
    ) throws IOException {
        this.apiKey = apiKey;
        this.generateUrl = baseUrl + "/models/" + model + ":generateContent";
        this.streamUrl = baseUrl + "/models/" + model + ":streamGenerateContent?alt=sse";
        this.thinkingLevel = thinkingLevel;
        this.imageResolver = imageResolver;
        this.sectionComposer = sectionComposer;
        this.restClient = RestClient.create();
        this.objectMapper = new ObjectMapper();
        // Le catalogue des sections est inséré dans le prompt : il reste toujours synchronisé avec les modèles
        this.pagePrompt = readResource("ai/system-prompt.md").replace("{{CATALOG}}", sectionComposer.catalog());
        this.editPrompt = readResource("ai/edit-prompt.md");
        this.themeBriefs = objectMapper.readTree(readResource("ai/theme-briefs.json"));
    }

    // ---------- Génération d'une page ----------

    /** Génère toute la page en une fois : { "mode": ..., "widgets": [...] } déjà nettoyé, images comprises. */
    public String generateWidgets(PageRequest request) {
        String text = extractText(callGemini(pageRequestBody(request)));
        try {
            JsonNode generated = objectMapper.readTree(text);
            ObjectNode result = WidgetSanitizer.sanitize(composeIfNeeded(generated));
            imageResolver.resolveImages(result.path("widgets"));
            PaletteEvent palette = request.chooseColors() ? PaletteEvent.from(generated.get("palette")) : null;
            if (palette != null) result.set("palette", palette.toJson());
            return objectMapper.writeValueAsString(result);
        } catch (IOException e) {
            throw new IllegalStateException("L'IA a renvoyé un JSON invalide", e);
        }
    }

    /**
     * Génère la page en direct : chaque section est envoyée à {@code onEvent} dès qu'elle est prête,
     * sans attendre la fin de la réponse de l'IA. Renvoie le nombre de sections envoyées.
     */
    public int streamWidgets(PageRequest request, Consumer<StreamEvent> onEvent) {
        Map<String, Object> body = pageRequestBody(request);
        for (int attempt = 1; ; attempt++) {
            SectionStreamParser parser = new SectionStreamParser();
            // Chaque section cherche ses photos en parallèle ; elles sont envoyées dans l'ordre dès qu'elles sont prêtes
            ExecutorService workers = Executors.newFixedThreadPool(SECTION_WORKERS);
            Deque<CompletableFuture<String>> pending = new ArrayDeque<>();
            int[] sent = {0};
            boolean[] modeSent = {false};
            boolean[] paletteSent = {!request.chooseColors()};
            Runnable sendReady = () -> {
                while (!pending.isEmpty() && pending.peekFirst().isDone()) {
                    String widget = pending.pollFirst().join();
                    if (widget != null) {
                        onEvent.accept(new SectionEvent(widget));
                        sent[0]++;
                    }
                }
            };
            try {
                streamGemini(body, chunk -> {
                    for (JsonNode section : parser.feed(chunk)) {
                        if (!modeSent[0] && parser.mode() != null) {
                            onEvent.accept(new ModeEvent(parser.mode()));
                            modeSent[0] = true;
                        }
                        if (!paletteSent[0] && parser.palette() != null) {
                            PaletteEvent palette = PaletteEvent.from(parser.palette());
                            if (palette != null) onEvent.accept(palette);
                            paletteSent[0] = true;
                        }
                        pending.addLast(CompletableFuture.supplyAsync(() -> buildSection(section), workers));
                    }
                    sendReady.run();
                });
                // Fin de la réponse : on attend les dernières sections, toujours dans l'ordre
                while (!pending.isEmpty()) {
                    pending.peekFirst().join();
                    sendReady.run();
                }
            } catch (HttpServerErrorException.ServiceUnavailable e) {
                // Modèle surchargé avant le premier envoi : on retente une fois après une courte pause
                if (attempt >= MAX_ATTEMPTS || sent[0] > 0) {
                    throw new AiUnavailableException("L'IA est très sollicitée en ce moment, réessayez dans quelques instants.", e);
                }
                sleep(RETRY_DELAY_MS);
                continue;
            } finally {
                workers.shutdown();
            }
            if (sent[0] > 0 && !parser.isComplete()) {
                // Le flux s'est arrêté au milieu de la réponse : on garde les sections reçues et on prévient
                log.warn("Flux de l'IA interrompu après {} sections", sent[0]);
                onEvent.accept(new IncompleteEvent(sent[0]));
            }
            if (sent[0] == 0) {
                // Réponse dans un autre format que prévu : on tente l'analyse de la réponse complète
                sent[0] = fallbackFromFullText(parser.fullText(), request.chooseColors(), onEvent);
            }
            return sent[0];
        }
    }

    private String buildSection(JsonNode section) {
        ArrayNode widgets = sectionComposer.compose(objectMapper.createArrayNode().add(section));
        if (widgets.isEmpty()) return null;
        ObjectNode root = objectMapper.createObjectNode();
        root.set("widgets", widgets);
        try {
            ObjectNode clean = WidgetSanitizer.sanitize(root);
            imageResolver.resolveImages(clean.path("widgets"));
            return objectMapper.writeValueAsString(clean.path("widgets").path(0));
        } catch (Exception e) {
            log.warn("Section ignorée ({}) : {}", section.path("template").asText("?"), e.getMessage());
            return null; // Section inutilisable : on passe à la suivante
        }
    }

    private int fallbackFromFullText(String text, boolean chooseColors, Consumer<StreamEvent> onEvent) {
        try {
            JsonNode generated = objectMapper.readTree(text);
            ObjectNode result = WidgetSanitizer.sanitize(composeIfNeeded(generated));
            imageResolver.resolveImages(result.path("widgets"));
            onEvent.accept(new ModeEvent(result.path("mode").asText("")));
            PaletteEvent palette = chooseColors ? PaletteEvent.from(generated.get("palette")) : null;
            if (palette != null) onEvent.accept(palette);
            for (JsonNode widget : result.path("widgets")) {
                onEvent.accept(new SectionEvent(objectMapper.writeValueAsString(widget)));
            }
            return result.path("widgets").size();
        } catch (Exception e) {
            log.warn("Réponse de l'IA inexploitable ({}) : {}", e.getMessage(),
                    text.length() > 600 ? text.substring(0, 600) + "…" : text);
            throw new IllegalStateException("L'IA n'a renvoyé aucune section utilisable", e);
        }
    }

    private JsonNode composeIfNeeded(JsonNode generated) {
        // Les sections choisies par l'IA sont assemblées à partir des modèles, puis nettoyées comme le reste
        if (!generated.path("sections").isArray()) return generated;
        ObjectNode composed = objectMapper.createObjectNode();
        composed.put("mode", generated.path("mode").asText(""));
        composed.set("widgets", sectionComposer.compose(generated.get("sections")));
        return composed;
    }

    private Map<String, Object> pageRequestBody(PageRequest request) {
        JsonNode brief = briefFor(request.themeName());
        String prompt = request.prompt() == null || request.prompt().isBlank()
                ? "Crée une page inspirée de la capture d'écran jointe."
                : request.prompt();
        StringBuilder text = new StringBuilder("""
                Demande de l'utilisateur : %s

                Largeur de la page dans l'éditeur : %d px.
                Thème actif : %s — direction « %s ».
                %s
                À éviter avec ce thème : %s
                """.formatted(
                prompt,
                request.pageWidth(),
                themeLabel(request.themeName()),
                brief.path("style").asText(),
                brief.path("brief").asText(),
                brief.path("avoid").asText()
        ));
        text.append(personalityText(request));
        text.append(request.chooseColors() ? """

                Couleurs : choisis la palette de la page. Ajoute "palette" (voir le format de sortie) avec une couleur \
                principale qui évoque le sujet et la personnalité du thème, et un fond clair ou sombre selon l'ambiance. \
                L'éditeur en tire toutes les autres couleurs et vérifie leur lisibilité.
                """ : """

                Couleurs : garde celles du thème, n'ajoute pas de "palette".
                """);

        List<Map<String, Object>> parts = new ArrayList<>();
        if (request.image() != null) {
            text.append("""

                    Une capture d'écran de référence est jointe. Reproduis sa structure (ordre et nature des sections), \
                    sa densité et son ambiance avec les sections du catalogue, en adaptant le contenu à la demande. \
                    N'en reprends pas les couleurs ni les polices : le thème de l'éditeur s'applique.
                    """);
        }
        parts.add(Map.of("text", text.toString()));
        if (request.image() != null) {
            parts.add(Map.of("inline_data", Map.of("mime_type", request.image().mimeType(), "data", request.image().base64())));
        }
        return requestBody(pagePrompt, parts);
    }

    // ---------- Modification d'un bloc sélectionné ----------

    /** Modifie un bloc selon la consigne et renvoie le bloc nettoyé (JSON). */
    public String editWidget(String instruction, JsonNode widget, String themeName, int pageWidth) {
        // Les images (souvent des data: URL très lourdes) sont remplacées par des repères avant l'envoi à l'IA
        Map<String, String> images = new HashMap<>();
        JsonNode masked = maskImages(widget.deepCopy(), images);

        JsonNode brief = briefFor(themeName);
        String text = """
                Consigne de l'utilisateur : %s

                Thème actif : %s — direction « %s ». %s
                Largeur de la page : %d px.

                Bloc sélectionné :
                %s
                """.formatted(instruction, themeLabel(themeName), brief.path("style").asText(),
                brief.path("brief").asText(), pageWidth, masked.toString());

        String response = extractText(callGemini(requestBody(editPrompt, List.of(Map.of("text", text)))));
        try {
            JsonNode generated = objectMapper.readTree(response);
            JsonNode edited = generated.has("widget") ? generated.get("widget") : generated;
            restoreImages(edited, images);
            ObjectNode root = objectMapper.createObjectNode();
            root.set("widgets", objectMapper.createArrayNode().add(edited));
            ObjectNode clean = WidgetSanitizer.sanitize(root, WidgetSanitizer.Options.EDIT);
            imageResolver.resolveImages(clean.path("widgets"));
            return objectMapper.writeValueAsString(clean.path("widgets").path(0));
        } catch (IOException e) {
            throw new IllegalStateException("L'IA a renvoyé un JSON invalide", e);
        }
    }

    private JsonNode maskImages(JsonNode node, Map<String, String> images) {
        if (node.path("config") instanceof ObjectNode config && config.path("src").isTextual() && !config.get("src").asText().isEmpty()) {
            String key = IMAGE_PLACEHOLDER + (images.size() + 1);
            images.put(key, config.get("src").asText());
            config.put("src", key);
        }
        if (node instanceof ObjectNode obj) obj.remove("id");
        for (JsonNode child : node.path("children")) maskImages(child, images);
        return node;
    }

    private void restoreImages(JsonNode node, Map<String, String> images) {
        if (node.path("config") instanceof ObjectNode config && config.path("src").isTextual()) {
            String original = images.get(config.get("src").asText());
            if (original != null) config.put("src", original);
        }
        for (JsonNode child : node.path("children")) restoreImages(child, images);
    }

    /**
     * Personnalité du thème : la famille de sections à privilégier. Un thème prêt à l'emploi a la sienne ;
     * pour un thème personnalisé, l'éditeur la déduit des polices et des arrondis.
     */
    private String personalityText(PageRequest request) {
        String family = briefFor(request.themeName()).path("family").asText(null);
        if (family == null) family = request.personality();
        JsonNode description = family == null ? null : themeBriefs.path("_families").get(family);
        List<String> sections = sectionComposer.sectionsOf(family);
        if (description == null || sections.isEmpty()) return "";
        return """
                Personnalité du thème : « %s ». %s
                Sections de ce style à privilégier : %s. Choisis d'abord parmi elles le bandeau, les points forts,                 la galerie et l'appel à l'action ; les autres sections du catalogue restent permises pour le reste.
                """.formatted(description.path("label").asText(), description.path("brief").asText(),
                String.join(", ", sections.stream().map(id -> "\"" + id + "\"").toList()));
    }

    private JsonNode briefFor(String themeName) {
        return themeName != null && themeBriefs.has(themeName) ? themeBriefs.get(themeName) : themeBriefs.get("_default");
    }

    private static String themeLabel(String themeName) {
        return themeName == null || themeName.isBlank() ? "personnalisé" : themeName;
    }

    // ---------- Appels à Gemini ----------

    private Map<String, Object> requestBody(String systemPrompt, List<Map<String, Object>> parts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        body.put("contents", List.of(Map.of("role", "user", "parts", parts)));
        body.put("generationConfig", Map.of(
                "response_mime_type", "application/json",
                "temperature", 0.7,
                // Réflexion légère : la qualité reste bonne et la réponse arrive bien plus vite
                "thinkingConfig", Map.of("thinkingLevel", thinkingLevel)
        ));
        return body;
    }

    private String callGemini(Map<String, Object> requestBody) {
        for (int attempt = 1; ; attempt++) {
            try {
                return restClient.post()
                        .uri(generateUrl)
                        // La clé passe dans un en-tête plutôt que dans l'URL, pour ne pas finir dans les logs
                        .header("x-goog-api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                throw new AiUnavailableException(QUOTA_MESSAGE, e);
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

    /** Appel en flux (SSE) : chaque morceau de texte de la réponse est transmis à {@code onText}. */
    private void streamGemini(Map<String, Object> requestBody, Consumer<String> onText) {
        try {
            restClient.post()
                    .uri(streamUrl)
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .exchange((req, res) -> {
                        if (res.getStatusCode().value() == 503) {
                            throw HttpServerErrorException.create(res.getStatusCode(), "Service Unavailable", res.getHeaders(), new byte[0], null);
                        }
                        if (res.getStatusCode().value() == 429) {
                            throw new AiUnavailableException(QUOTA_MESSAGE, null);
                        }
                        if (res.getStatusCode().isError()) {
                            String error = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            throw new AiUnavailableException("Erreur de communication avec l'IA : " + res.getStatusCode() + " " + error, null);
                        }
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(res.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.startsWith("data:")) continue;
                                JsonNode candidate = objectMapper.readTree(line.substring(5).trim()).path("candidates").path(0);
                                String finish = candidate.path("finishReason").asText("");
                                if (!finish.isEmpty() && !finish.equals("STOP")) log.warn("Génération arrêtée par l'IA : {}", finish);
                                JsonNode parts = candidate.path("content").path("parts");
                                for (JsonNode part : parts) {
                                    if (part.has("text") && !part.path("thought").asBoolean(false)) onText.accept(part.get("text").asText());
                                }
                            }
                        }
                        return null;
                    });
        } catch (HttpServerErrorException.ServiceUnavailable | AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new AiUnavailableException("Erreur de communication avec l'IA : " + e.getMessage(), e);
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

    private String extractText(String rawResponse) {
        try {
            JsonNode parts = objectMapper.readTree(rawResponse)
                    .path("candidates").path(0).path("content").path("parts");
            // Les modèles récents peuvent renvoyer plusieurs parties : on garde le texte de la réponse
            for (JsonNode part : parts) {
                if (part.has("text") && !part.path("thought").asBoolean(false)) {
                    return part.get("text").asText();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Réponse de l'IA illisible", e);
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
