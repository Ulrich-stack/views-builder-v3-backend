package com.builder.builder_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SectionComposerTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SectionComposer composer = new SectionComposer();

    SectionComposerTests() throws Exception {
    }

    private ArrayNode compose(String sectionsJson) throws Exception {
        return composer.compose(mapper.readTree(sectionsJson));
    }

    @Test
    void chaqueSectionDuCatalogueSAssembleAvecUnContenuComplet() throws Exception {
        JsonNode templates;
        try (InputStream in = new ClassPathResource("ai/section-templates.json").getInputStream()) {
            templates = mapper.readTree(in).path("templates");
        }
        for (JsonNode template : templates) {
            String id = template.path("id").asText();
            ObjectNode section = mapper.createObjectNode().put("template", id).put("tone", "alt");
            section.set("content", sampleContent(template.path("slots")));

            ArrayNode widgets = composer.compose(mapper.createArrayNode().add(section));
            assertEquals(1, widgets.size(), id);
            String json = widgets.toString();
            assertFalse(json.contains("{{"), id + " : contenu non remplacé dans " + json);
            assertFalse(json.contains("\"repeat\"") || json.contains("\"if\""), id + " : directive de modèle restée");

            ObjectNode root = mapper.createObjectNode();
            root.set("widgets", widgets);
            JsonNode cleaned = WidgetSanitizer.sanitize(root).path("widgets").path(0);
            assertEquals("section", cleaned.path("element").asText(), id);
            assertTrue(cleaned.path("children").size() > 0, id);
        }
    }

    @Test
    void retireLesBlocsFacultatifsAbsentsEtNumeroteLesRepetitions() throws Exception {
        JsonNode section = compose("""
                [ { "template": "steps", "content": { "title": "Comment réserver",
                    "items": [ { "title": "Choisir", "text": "Un créneau." }, { "title": "Payer", "text": "En ligne." } ] } } ]
                """).path(0);
        String json = section.toString();
        assertFalse(json.contains("Introduction"), "l'intro absente doit disparaître");
        assertTrue(json.contains("\"content\":\"1\"") && json.contains("\"content\":\"2\""));
        assertTrue(json.contains("Comment réserver"));
    }

    @Test
    void appliqueLeTonEtGardeLesSectionsLibres() throws Exception {
        ArrayNode widgets = compose("""
                [ { "template": "features", "tone": "alt", "content": { "title": "T", "items": [ { "title": "a", "text": "b" } ] } },
                  { "template": "inconnu", "content": {} },
                  { "template": "custom", "widget": { "type": "container", "element": "section", "children": [] } } ]
                """);
        assertEquals(2, widgets.size(), "la section inconnue est ignorée");
        assertEquals("var(--surface)", widgets.path(0).path("styles").path("background-color").asText());
        assertEquals("section", widgets.path(1).path("element").asText());
    }

    @Test
    void leCatalogueDecritToutesLesSections() {
        String catalog = composer.catalog();
        for (String id : new String[]{"nav", "hero-split", "form", "footer"}) {
            assertTrue(catalog.contains("\"" + id + "\""), id);
        }
    }

    @Test
    void chaqueFamilleProposeUnBandeauDesPointsFortsUneGalerieEtUnAppelALAction() throws Exception {
        JsonNode briefs;
        try (InputStream in = new ClassPathResource("ai/theme-briefs.json").getInputStream()) {
            briefs = mapper.readTree(in);
        }
        for (Iterator<String> it = briefs.path("_families").fieldNames(); it.hasNext(); ) {
            String family = it.next();
            var ids = composer.sectionsOf(family);
            for (String kind : new String[]{"hero", "features", "gallery", "cta"}) {
                assertTrue(ids.stream().anyMatch(id -> id.equals(kind) || id.startsWith(kind + "-")), family + " : pas de " + kind);
            }
        }
        // Chaque thème prêt à l'emploi pointe vers une famille décrite
        for (Iterator<Map.Entry<String, JsonNode>> it = briefs.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> theme = it.next();
            if (theme.getKey().startsWith("_")) continue;
            String family = theme.getValue().path("family").asText();
            assertTrue(briefs.path("_families").has(family), theme.getKey() + " : famille inconnue « " + family + " »");
        }
    }

    @Test
    void leCatalogueIndiqueLesFamillesEtLesIcones() {
        String catalog = composer.catalog();
        assertTrue(catalog.contains("\"hero-cover\"") && catalog.contains("style chaleureux, editorial"), catalog);
        assertTrue(catalog.contains("Icônes disponibles") && catalog.contains("map-pin"));
        assertTrue(composer.icons().contains("leaf"));
    }

    @Test
    void viseUnElementPrecisDUneListeEtRetireLesPhotosAbsentes() throws Exception {
        JsonNode section = compose("""
                [ { "template": "gallery-mosaic", "content": { "title": "L'atelier",
                    "images": [ { "imageQuery": "tea leaves", "alt": "Feuilles" }, { "imageQuery": "teapot", "alt": "Théière" } ] } } ]
                """).path(0);
        String json = section.toString();
        assertTrue(json.contains("\"imageQuery\":\"tea leaves\"") && json.contains("\"imageQuery\":\"teapot\""), json);
        assertFalse(json.contains("Photo en avant") || json.contains("Photo de côté"), "les photos 3 et 4 absentes disparaissent");
    }

    @Test
    void unBandeauPhotoDemandeSaPhotoDeFondSousUnVoile() throws Exception {
        JsonNode section = compose("""
                [ { "template": "hero-cover", "tone": "alt", "content": { "title": "Thés de récolte", "text": "Infusés à la bonne température.",
                    "cta": "Réserver une table", "imageQuery": "tea room" } } ]
                """).path(0);
        assertEquals("tea room", section.path("config").path("backgroundQuery").asText());
        assertTrue(section.path("styles").path("background-image").asText().startsWith("linear-gradient("));
        assertFalse(section.toString().contains("Repères"), "les repères absents disparaissent");

        ObjectNode root = mapper.createObjectNode();
        root.set("widgets", mapper.createArrayNode().add(section));
        JsonNode cleaned = WidgetSanitizer.sanitize(root).path("widgets").path(0);
        assertEquals("tea room", cleaned.path("config").path("backgroundQuery").asText(), "la requête survit au nettoyage");
        assertTrue(cleaned.path("styles").has("background-image"), "le voile survit au nettoyage");
    }

    @Test
    void lesIconesDesArgumentsDeviennentDesBlocsIcone() throws Exception {
        JsonNode section = compose("""
                [ { "template": "features-icons", "content": { "title": "Pourquoi nous", "imageQuery": "yoga", "imageAlt": "Cours",
                    "items": [ { "icon": "leaf", "title": "Bio", "text": "Tout est bio." } ] } } ]
                """).path(0);
        String json = section.toString();
        assertTrue(json.contains("\"element\":\"icon\"") && json.contains("\"icon\":\"leaf\""), json);
        assertTrue(json.contains("\"element\":\"h3\""), "les titres d'argument sont des h3");
    }

    /** Remplit chaque emplacement décrit dans « slots » avec un contenu factice, listes comprises. */
    private JsonNode sampleContent(JsonNode slots) {
        if (slots.isTextual()) return JsonNodeFactory.instance.textNode("Exemple");
        if (slots.isArray()) {
            ArrayNode list = mapper.createArrayNode();
            for (int i = 0; i < 3; i++) list.add(sampleContent(slots.path(0)));
            return list;
        }
        ObjectNode content = mapper.createObjectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = slots.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> slot = it.next();
            content.set(slot.getKey().replace("?", ""), sampleContent(slot.getValue()));
        }
        return content;
    }
}
