package com.builder.builder_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Valide les interactions d'un bloc (config.actions) contre la liste blanche de l'éditeur.
 * Aucune action inconnue ni aucun champ libre ne passe : il n'y a donc jamais de code à isoler.
 */
public final class ActionSanitizer {

    public static final int MAX_ACTIONS = 10;
    private static final int MAX_MESSAGE = 200;

    private static final Set<String> KINDS = Set.of("validate", "show", "hide", "toggle", "toast", "link", "scrollTo");
    private static final Set<String> TARGETED = Set.of("validate", "show", "hide", "toggle", "scrollTo");
    private static final Set<String> RULES = Set.of("required", "email", "phone", "number");

    /** Repère d'un bloc (« b3 ») ou identifiant existant (uuid). */
    public static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SAFE_URL = Pattern.compile("^(https?://|mailto:|tel:|#)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE_URL = Pattern.compile("javascript:|data:|vbscript:|[\\s<>\"']", Pattern.CASE_INSENSITIVE);

    private ActionSanitizer() {
    }

    /** Renvoie la liste nettoyée (éventuellement vide). */
    public static ArrayNode sanitize(JsonNode actions) {
        ArrayNode clean = JsonNodeFactory.instance.arrayNode();
        if (!actions.isArray()) return clean;
        for (JsonNode action : actions) {
            if (clean.size() >= MAX_ACTIONS) break;
            ObjectNode cleanAction = sanitizeAction(action);
            if (cleanAction != null) clean.add(cleanAction);
        }
        return clean;
    }

    private static ObjectNode sanitizeAction(JsonNode action) {
        if (!action.isObject()) return null;
        String kind = action.path("do").asText();
        if (!KINDS.contains(kind)) return null;
        if (action.has("on") && !action.path("on").asText().equals("click")) return null;

        ObjectNode clean = JsonNodeFactory.instance.objectNode();
        clean.put("on", "click");
        clean.put("do", kind);

        if (TARGETED.contains(kind)) {
            String target = action.path("target").asText("");
            if (!SAFE_ID.matcher(target).matches()) return null;
            clean.put("target", target);
        }

        String message = action.path("message").isTextual() ? action.path("message").asText().trim() : "";
        if (message.length() > MAX_MESSAGE) message = message.substring(0, MAX_MESSAGE);

        switch (kind) {
            case "validate" -> {
                String rule = action.path("rule").asText("required");
                clean.put("rule", RULES.contains(rule) ? rule : "required");
                if (!message.isEmpty()) clean.put("message", message);
            }
            case "toast" -> {
                if (message.isEmpty()) return null;
                clean.put("message", message);
            }
            case "link" -> {
                String url = action.path("url").asText("").trim();
                if (!SAFE_URL.matcher(url).find() || UNSAFE_URL.matcher(url).find()) return null;
                clean.put("url", url);
                if (action.path("newTab").asBoolean(false)) clean.put("newTab", true);
            }
            default -> {
            }
        }
        return clean;
    }
}
