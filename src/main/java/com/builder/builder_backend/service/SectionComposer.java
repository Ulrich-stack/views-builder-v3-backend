package com.builder.builder_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assemble une page à partir des sections conçues à la main (ai/section-templates.json).
 * L'IA choisit les sections et écrit leur contenu ; le design vient des modèles de section.
 *
 * Syntaxe des modèles : {{slot}} insère un contenu, « if » retire un bloc dont le contenu est absent,
 * « repeat » répète le premier enfant pour chaque élément d'une liste, « liste.0.champ » vise un élément précis.
 * Chaque section appartient à des familles de styles (« families ») que les thèmes privilégient.
 */
@Service
public class SectionComposer {

    public static final String CUSTOM = "custom";

    private static final Pattern SLOT = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");
    private static final int MAX_REPEAT = 12;

    private final Map<String, ObjectNode> templates = new LinkedHashMap<>();
    private final List<String> icons = new ArrayList<>();

    public SectionComposer() throws IOException {
        try (InputStream in = new ClassPathResource("ai/section-templates.json").getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            for (JsonNode template : root.path("templates")) {
                templates.put(template.path("id").asText(), (ObjectNode) template);
            }
            root.path("icons").forEach(icon -> icons.add(icon.asText()));
        }
    }

    /** Liste des sections pour le prompt : identifiant, usage et contenu attendu (« ? » = facultatif). */
    public String catalog() {
        StringBuilder sb = new StringBuilder();
        templates.forEach((id, template) -> {
            sb.append("- \"").append(id).append("\" : ").append(template.path("description").asText());
            List<String> notes = new ArrayList<>();
            List<String> families = families(template);
            if (!families.isEmpty()) notes.add("style " + String.join(", ", families));
            if (!template.path("tone").asBoolean(true)) notes.add("ton fixe");
            if (!notes.isEmpty()) sb.append(" (").append(String.join(", ", notes)).append(")");
            sb.append("\n  content : ").append(template.path("slots").toString()).append("\n");
        });
        if (!icons.isEmpty()) sb.append("\nIcônes disponibles (champ « icon ») : ").append(String.join(", ", icons)).append("\n");
        return sb.toString();
    }

    /** Identifiants des sections d'une famille de styles, dans l'ordre du catalogue. */
    public List<String> sectionsOf(String family) {
        List<String> ids = new ArrayList<>();
        templates.forEach((id, template) -> {
            if (families(template).contains(family)) ids.add(id);
        });
        return ids;
    }

    private static List<String> families(JsonNode template) {
        List<String> families = new ArrayList<>();
        template.path("families").forEach(f -> families.add(f.asText()));
        return families;
    }

    public List<String> icons() {
        return List.copyOf(icons);
    }

    public boolean hasTemplate(String id) {
        return templates.containsKey(id);
    }

    /**
     * Transforme la réponse de l'IA ({ "sections": [ { "template", "tone", "content" } ] }) en blocs de l'éditeur.
     * Une section « custom » apporte directement son bloc dans « widget ». Les sections inconnues sont ignorées.
     */
    public ArrayNode compose(JsonNode sections) {
        ArrayNode widgets = JsonNodeFactory.instance.arrayNode();
        for (JsonNode section : sections) {
            String id = section.path("template").asText();
            if (CUSTOM.equals(id)) {
                if (section.path("widget").isObject()) widgets.add(section.get("widget"));
                continue;
            }
            ObjectNode template = templates.get(id);
            if (template == null) continue;

            ObjectNode content = section.path("content").isObject()
                    ? ((ObjectNode) section.get("content")).deepCopy()
                    : JsonNodeFactory.instance.objectNode();
            boolean alt = "alt".equals(section.path("tone").asText()) && template.path("tone").asBoolean(true);
            content.put("_bg", alt ? "var(--surface)" : "var(--bg-page)");
            content.put("_card", alt ? "var(--bg-page)" : "var(--surface)");
            content.put("_name", sectionName(template));

            JsonNode widget = expand(template.get("widget"), new Context(content, null));
            if (widget != null) widgets.add(widget);
        }
        return widgets;
    }

    private static String sectionName(ObjectNode template) {
        return template.path("label").asText(template.path("id").asText());
    }

    /** Copie un nœud du modèle en remplaçant les {{slots}} ; renvoie null si le bloc doit disparaître. */
    private JsonNode expand(JsonNode node, Context ctx) {
        if (node.isTextual()) return JsonNodeFactory.instance.textNode(fill(node.asText(), ctx));
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : node) {
                JsonNode expanded = expand(child, ctx);
                if (expanded != null) copy.add(expanded);
            }
            return copy;
        }
        if (!node.isObject()) return node.deepCopy();

        if (node.has("if") && isEmpty(ctx.lookup(node.get("if").asText()))) return null;

        ObjectNode copy = JsonNodeFactory.instance.objectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            String key = field.getKey();
            if (key.equals("if") || key.equals("repeat")) continue;
            if (key.equals("children") && node.has("repeat")) {
                ArrayNode children = repeat(field.getValue().path(0), ctx.lookup(node.get("repeat").asText()), ctx);
                if (children.isEmpty()) return null;
                copy.set("children", children);
                continue;
            }
            JsonNode expanded = expand(field.getValue(), ctx);
            if (expanded != null) copy.set(key, expanded);
        }
        return copy;
    }

    private ArrayNode repeat(JsonNode itemTemplate, JsonNode items, Context ctx) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        if (!items.isArray() || itemTemplate.isMissingNode()) return result;
        int index = 0;
        for (JsonNode item : items) {
            if (index == MAX_REPEAT) break;
            ObjectNode itemCtx = item.isObject()
                    ? ((ObjectNode) item).deepCopy()
                    : JsonNodeFactory.instance.objectNode().put("value", item.asText());
            itemCtx.put("_index", String.valueOf(++index));
            JsonNode expanded = expand(itemTemplate, new Context(itemCtx, ctx));
            if (expanded != null) result.add(expanded);
        }
        return result;
    }

    private static String fill(String text, Context ctx) {
        Matcher m = SLOT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            JsonNode value = ctx.lookup(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value.isValueNode() ? value.asText() : ""));
        }
        m.appendTail(sb);
        return sb.toString().trim();
    }

    private static boolean isEmpty(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return true;
        if (value.isTextual()) return value.asText().isBlank();
        return value.isContainerNode() && value.isEmpty();
    }

    /** Contenu disponible pour un bloc : celui de l'élément répété, puis celui de la section. */
    private record Context(JsonNode values, Context parent) {
        JsonNode lookup(String path) {
            JsonNode node = values;
            for (String part : path.split("\\.")) {
                // « images.0.imageQuery » : un nombre désigne un élément de liste
                node = node.isArray() && part.chars().allMatch(Character::isDigit) ? node.path(Integer.parseInt(part)) : node.path(part);
            }
            if (!node.isMissingNode() || parent == null) return node;
            return parent.lookup(path);
        }
    }
}
