package com.builder.builder_backend.controller;

import com.builder.builder_backend.service.GeminiService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@CrossOrigin(origins = "http://localhost:4200") // Indispensable pour Angular !
public class AiController {

    private final GeminiService geminiService;

    public AiController(GeminiService geminiService) {
        this.geminiService = geminiService;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generateComponent(@RequestBody Map<String, String> request) {
        String prompt = request.get("prompt");
        // « vibe » reste accepté pour compatibilité avec l'ancien format
        String theme = request.getOrDefault("theme", request.get("vibe"));

        if (prompt == null || prompt.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Le prompt est vide"));
        }

        try {
            String generatedJson = geminiService.generateWidgets(prompt.trim(), theme);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(generatedJson);
        } catch (GeminiService.AiUnavailableException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
