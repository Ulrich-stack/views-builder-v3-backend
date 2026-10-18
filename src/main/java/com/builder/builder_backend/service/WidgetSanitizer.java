package com.builder.builder_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Nettoie l'arbre de blocs renvoyé par l'IA pour qu'il corresponde exactement
 * à ce que l'éditeur sait afficher, et qu'il reste fidèle au thème.
 */
public final class WidgetSanitizer {

    private static final int MAX_DEPTH = 12;
    private static final String SPACING_UNIT = "8px";

    // Éléments que le rendu Angular sait afficher, par type de bloc (le premier sert de valeur par défaut)
    private static final Map<String, Set<String>> ELEMENTS = Map.of(
            "container", Set.of("section", "div", "nav", "grid"),
            "text", Set.of("p", "h1", "h2", "h3", "blockquote", "span", "a"),
            "action", Set.of("button"),
            "input", Set.of("input", "textarea", "checkbox"),
            "media", Set.of("img", "video", "icon")
    );
    private static final Map<String, String> DEFAULT_ELEMENT = Map.of(
            "container", "div", "text", "p", "action", "button", "input", "input", "media", "img"
    );

    // Une couleur en dur casserait le changement de thème : on retire la propriété
    private static final Pattern RAW_COLOR = Pattern.compile(
            "#[0-9a-f]{3,8}\\b|\\b(rgba?|hsla?|oklch|lab|lch)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE_VALUE = Pattern.compile("expression\\s*\\(|javascript:", Pattern.CASE_INSENSITIVE);
    // Images de fond : seules les adresses https sont acceptées, sans guillemet ni parenthèse dans l'adresse
    private static final Pattern URL = Pattern.compile("url\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_URL = Pattern.compile("url\\(\\s*(['\"]?)https://[^'\"()\\s\\\\]+\\1\\s*\\)", Pattern.CASE_INSENSITIVE);
    // Nom d'icône Lucide (« leaf », « map-pin »...)
    private static final Pattern ICON_NAME = Pattern.compile("[a-z0-9-]{1,40}");

    /**
     * @param normalizeSpacing remplace var(--spacing-base) par une unité fixe (pages générées)
     * @param allowDataImages  garde les images importées par l'utilisateur (data:image/...) lors d'une modification
     */
    public record Options(boolean normalizeSpacing, boolean allowDataImages) {
        public static final Options GENERATION = new Options(true, false);
        public static final Options EDIT = new Options(false, true);
    }

    private WidgetSanitizer() {
    }

    /** Renvoie { "mode": ..., "widgets": [...] } nettoyé, ou lève une exception si la réponse est inutilisable. */
    public static ObjectNode sanitize(JsonNode root) {
        return sanitize(root, Options.GENERATION);
    }

    public static ObjectNode sanitize(JsonNode root, Options options) {
        JsonNode widgets = root.isArray() ? root : root.path("widgets");
        if (!widgets.isArray()) {
            throw new IllegalStateException("La réponse de l'IA ne contient pas de liste de blocs");
        }

        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("mode", root.path("mode").asText(""));
        ArrayNode cleaned = result.putArray("widgets");
        for (JsonNode widget : widgets) {
            ObjectNode clean = sanitizeWidget(widget, 0, options);
            if (clean != null) cleaned.add(clean);
        }
        if (cleaned.isEmpty()) {
            throw new IllegalStateException("L'IA n'a renvoyé aucun bloc utilisable");
        }
        return result;
    }

    private static ObjectNode sanitizeWidget(JsonNode node, int depth, Options options) {
        if (!node.isObject() || depth > MAX_DEPTH) return null;

        String type = node.path("type").asText();
        if (node.path("children").isArray()) type = "container";
        if (!ELEMENTS.containsKey(type)) return null;

        String element = node.path("element").asText();
        if (!ELEMENTS.get(type).contains(element)) element = DEFAULT_ELEMENT.get(type);

        ObjectNode clean = JsonNodeFactory.instance.objectNode();
        // Repère court envoyé par l'éditeur pour retrouver le bloc et viser ses actions
        String ref = node.path("ref").asText("");
        if (ActionSanitizer.SAFE_ID.matcher(ref).matches()) clean.put("ref", ref);
        clean.put("type", type);
        clean.put("element", element);
        clean.put("name", node.path("name").asText(""));
        clean.put("content", node.path("content").asText(""));
        clean.set("styles", sanitizeStyles(node.path("styles"), options));
        clean.set("config", sanitizeConfig(node.path("config"), options));

        if (type.equals("container")) {
            ArrayNode children = clean.putArray("children");
            for (JsonNode child : node.path("children")) {
                ObjectNode cleanChild = sanitizeWidget(child, depth + 1, options);
                if (cleanChild != null) children.add(cleanChild);
            }
        }
        return clean;
    }

    private static ObjectNode sanitizeStyles(JsonNode styles, Options options) {
        ObjectNode clean = JsonNodeFactory.instance.objectNode();
        if (!styles.isObject()) return clean;
        for (Iterator<Map.Entry<String, JsonNode>> it = styles.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode value = entry.getValue();
            if (!value.isValueNode()) continue;
            String css = value.asText().trim();
            if (css.isEmpty() || RAW_COLOR.matcher(css).find() || UNSAFE_VALUE.matcher(css).find() || hasUnsafeUrl(css)) continue;
            // --spacing-base est le padding réglé par l'utilisateur (jusqu'à 24px et plus) : multiplié dans
            // une mise en page, il produit des marges énormes. On le ramène à une unité fixe.
            if (options.normalizeSpacing()) css = css.replace("var(--spacing-base)", SPACING_UNIT);
            clean.put(entry.getKey().trim(), css);
        }
        return clean;
    }

    private static ObjectNode sanitizeConfig(JsonNode config, Options options) {
        ObjectNode clean = JsonNodeFactory.instance.objectNode();
        if (!config.isObject()) return clean;
        for (Iterator<Map.Entry<String, JsonNode>> it = config.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            // Interactions : liste blanche d'actions, et visibilité au départ (booléen)
            if (entry.getKey().equals("actions")) {
                ArrayNode actions = ActionSanitizer.sanitize(entry.getValue());
                if (!actions.isEmpty()) clean.set("actions", actions);
                continue;
            }
            if (entry.getKey().equals("hidden")) {
                if (entry.getValue().asBoolean(false)) clean.put("hidden", true);
                continue;
            }
            if (!entry.getValue().isValueNode()) continue;
            String value = entry.getValue().asText();
            // Seules les images en https sont acceptées comme source
            if (entry.getKey().equals("src") && !value.startsWith("https://")
                    && !(options.allowDataImages() && value.startsWith("data:image/"))) continue;
            if (entry.getKey().equals("href") && (UNSAFE_VALUE.matcher(value).find() || URL.matcher(value).find())) continue;
            if (entry.getKey().equals("icon") && !ICON_NAME.matcher(value).matches()) continue;
            clean.put(entry.getKey(), value);
        }
        return clean;
    }

    /** Vrai si la valeur contient une url() qui ne pointe pas vers une adresse https simple. */
    private static boolean hasUnsafeUrl(String css) {
        return URL.matcher(SAFE_URL.matcher(css).replaceAll("")).find();
    }
}
