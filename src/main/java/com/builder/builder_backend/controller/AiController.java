package com.builder.builder_backend.controller;

import com.builder.builder_backend.service.GeminiService;
import com.builder.builder_backend.service.GeminiService.IncompleteEvent;
import com.builder.builder_backend.service.GeminiService.ModeEvent;
import com.builder.builder_backend.service.GeminiService.PaletteEvent;
import com.builder.builder_backend.service.GeminiService.PageRequest;
import com.builder.builder_backend.service.GeminiService.ReferenceImage;
import com.builder.builder_backend.service.GeminiService.SectionEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@CrossOrigin(origins = "http://localhost:4200") // Indispensable pour Angular !
public class AiController {

    private static final int DEFAULT_PAGE_WIDTH = 800;
    private static final long STREAM_TIMEOUT_MS = 180_000;

    private final GeminiService geminiService;
    private final TaskExecutor taskExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiController(GeminiService geminiService, TaskExecutor taskExecutor) {
        this.geminiService = geminiService;
        this.taskExecutor = taskExecutor;
    }

    /** Génère toute la page et la renvoie en une fois. */
    @PostMapping("/generate")
    public ResponseEntity<?> generateComponent(@RequestBody Map<String, Object> request) {
        try {
            PageRequest pageRequest = toPageRequest(request);
            String generatedJson = geminiService.generateWidgets(pageRequest);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(generatedJson);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (GeminiService.AiUnavailableException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Génère la page en direct (Server-Sent Events) : un événement « section » par section dès qu'elle est prête,
     * puis « done » ou « error ».
     */
    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> generateStream(@RequestBody Map<String, Object> request) {
        PageRequest pageRequest;
        try {
            pageRequest = toPageRequest(request);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body(json(Map.of("error", e.getMessage())));
        }

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        taskExecutor.execute(() -> {
            try {
                int count = geminiService.streamWidgets(pageRequest, event -> {
                    if (event instanceof SectionEvent section) send(emitter, "section", section.widgetJson());
                    else if (event instanceof ModeEvent mode) send(emitter, "mode", json(Map.of("mode", mode.mode())));
                    else if (event instanceof PaletteEvent palette) send(emitter, "palette", palette.toJson().toString());
                    else if (event instanceof IncompleteEvent incomplete) send(emitter, "incomplete", json(Map.of("received", incomplete.received())));
                });
                send(emitter, "done", json(Map.of("count", count)));
            } catch (Exception e) {
                send(emitter, "error", json(Map.of("error", e.getMessage() == null ? "La génération a échoué." : e.getMessage())));
            } finally {
                emitter.complete();
            }
        });
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    /** Modifie le bloc sélectionné selon la consigne de l'utilisateur. */
    @PostMapping("/edit")
    public ResponseEntity<?> editWidget(@RequestBody Map<String, Object> request) {
        String instruction = request.get("instruction") instanceof String s ? s.trim() : "";
        JsonNode widget = objectMapper.valueToTree(request.get("widget"));
        if (instruction.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "La consigne est vide"));
        }
        if (widget == null || !widget.isObject()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Aucun bloc à modifier"));
        }
        try {
            String edited = geminiService.editWidget(instruction, widget, stringOrNull(request.get("theme")), pageWidth(request));
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"widget\":" + edited + "}");
        } catch (GeminiService.AiUnavailableException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private PageRequest toPageRequest(Map<String, Object> request) {
        String prompt = request.get("prompt") instanceof String p ? p.trim() : "";
        // « vibe » reste accepté pour compatibilité avec l'ancien format
        String theme = stringOrNull(request.containsKey("theme") ? request.get("theme") : request.get("vibe"));
        ReferenceImage image = null;
        if (request.get("image") instanceof Map<?, ?> img) {
            String data = img.get("data") instanceof String d ? d : "";
            // Accepte aussi une data: URL complète
            int comma = data.startsWith("data:") ? data.indexOf(',') : -1;
            image = new ReferenceImage(String.valueOf(img.get("mimeType")), comma >= 0 ? data.substring(comma + 1) : data);
        }
        if (prompt.isEmpty() && image == null) {
            throw new IllegalArgumentException("Le prompt est vide");
        }
        boolean chooseColors = Boolean.TRUE.equals(request.get("chooseColors"));
        return new PageRequest(prompt, theme, stringOrNull(request.get("personality")), chooseColors, pageWidth(request), image);
    }

    private static int pageWidth(Map<String, Object> request) {
        // Largeur de la page cible, bornée aux tailles proposées par l'éditeur (mobile → ordinateur)
        return request.get("pageWidth") instanceof Number n
                ? Math.max(320, Math.min(n.intValue(), 1600))
                : DEFAULT_PAGE_WIDTH;
    }

    private static String stringOrNull(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            return "{}";
        }
    }

    private static void send(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException e) {
            // Le navigateur a fermé la connexion : la génération continue sans effet
        }
    }
}
