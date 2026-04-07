package com.builder.builder_backend.controller;

import com.builder.builder_backend.service.GeminiService;
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
        // 1. On récupère le prompt de l'utilisateur
        String prompt = request.get("prompt");

        // 2. On récupère la vibe (ou on met une valeur par défaut si elle n'est pas envoyée)
        String vibe = request.getOrDefault("vibe", "Minimaliste et moderne");

        if (prompt == null || prompt.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Le prompt est vide"));
        }

        try {
            // 3. On passe bien les DEUX arguments au service !
            String generatedJson = geminiService.generateWidget(prompt, vibe);

            return ResponseEntity.ok(generatedJson);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}