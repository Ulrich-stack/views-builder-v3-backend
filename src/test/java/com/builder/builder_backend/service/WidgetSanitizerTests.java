package com.builder.builder_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WidgetSanitizerTests {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode sanitize(String json) throws Exception {
        return WidgetSanitizer.sanitize(mapper.readTree(json));
    }

    @Test
    void retireLesCouleursEnDurEtGardeLesVariables() throws Exception {
        JsonNode result = sanitize("""
                { "mode": "persuader", "widgets": [ { "type": "container", "element": "section",
                  "styles": { "background": "#ff0000", "color": "var(--text)", "border": "1px solid rgb(0,0,0)",
                              "box-shadow": "0 8px 24px color-mix(in srgb, var(--text) 20%, transparent)" },
                  "children": [] } ] }
                """);
        JsonNode styles = result.path("widgets").path(0).path("styles");
        assertFalse(styles.has("background"));
        assertFalse(styles.has("border"));
        assertEquals("var(--text)", styles.path("color").asText());
        assertTrue(styles.has("box-shadow"));
        assertEquals("persuader", result.path("mode").asText());
    }

    @Test
    void corrigeLesElementsInconnusEtSupprimeLesTypesInconnus() throws Exception {
        JsonNode result = sanitize("""
                { "widgets": [ { "type": "container", "element": "article", "children": [
                    { "type": "text", "element": "h4", "content": "Titre" },
                    { "type": "carousel", "element": "div" },
                    { "type": "media", "element": "img", "config": { "src": "javascript:alert(1)", "alt": "x" } }
                ] } ] }
                """);
        JsonNode section = result.path("widgets").path(0);
        assertEquals("div", section.path("element").asText());
        assertEquals(2, section.path("children").size());
        assertEquals("p", section.path("children").path(0).path("element").asText());
        assertFalse(section.path("children").path(1).path("config").has("src"));
    }

    @Test
    void enModificationGardeLesReglagesEtImagesDeLUtilisateur() throws Exception {
        String json = """
                { "widgets": [ { "type": "media", "element": "img",
                  "styles": { "padding": "var(--spacing-base)" }, "config": { "src": "data:image/png;base64,AAAA" } } ] }
                """;
        JsonNode edit = WidgetSanitizer.sanitize(mapper.readTree(json), WidgetSanitizer.Options.EDIT).path("widgets").path(0);
        assertEquals("var(--spacing-base)", edit.path("styles").path("padding").asText());
        assertEquals("data:image/png;base64,AAAA", edit.path("config").path("src").asText());

        JsonNode generated = sanitize(json).path("widgets").path(0);
        assertEquals("8px", generated.path("styles").path("padding").asText());
        assertFalse(generated.path("config").has("src"));
    }

    @Test
    void gardeLesImagesDeFondEnHttpsSeulement() throws Exception {
        JsonNode result = sanitize("""
                { "widgets": [ { "type": "container", "element": "section", "children": [], "styles": {
                    "background-image": "linear-gradient(to top, color-mix(in srgb, black 60%, transparent), transparent), url('https://cdn.example.com/a.jpg')",
                    "background": "url(http://example.com/a.jpg)",
                    "border-image": "url(javascript:alert(1))",
                    "mask-image": "url(data:image/svg+xml,abc)",
                    "list-style-image": "url('https://ok.example/a.png') , url( 'https://x.example/b' )",
                    "cursor": "url('https://a.example/x.png?q=1') url(/local.cur), auto" } } ] }
                """);
        JsonNode styles = result.path("widgets").path(0).path("styles");
        assertTrue(styles.path("background-image").asText().contains("url('https://cdn.example.com/a.jpg')"));
        assertTrue(styles.has("list-style-image"));
        assertFalse(styles.has("background"), "http refusé");
        assertFalse(styles.has("border-image"), "javascript: refusé");
        assertFalse(styles.has("mask-image"), "data: refusé à la génération");
        assertFalse(styles.has("cursor"), "une seule url non https suffit à refuser la valeur");
    }

    @Test
    void accepteLesIntertitresCitationsEtIcones() throws Exception {
        JsonNode result = sanitize("""
                { "widgets": [ { "type": "container", "element": "section", "children": [
                    { "type": "text", "element": "h3", "content": "Intertitre" },
                    { "type": "text", "element": "blockquote", "content": "Citation" },
                    { "type": "media", "element": "icon", "config": { "icon": "map-pin" } },
                    { "type": "media", "element": "icon", "config": { "icon": "<svg onload=x>" } },
                    { "type": "text", "element": "a", "content": "Lien", "config": { "href": "url(https://x)" } }
                ] } ] }
                """);
        JsonNode children = result.path("widgets").path(0).path("children");
        assertEquals("h3", children.path(0).path("element").asText());
        assertEquals("blockquote", children.path(1).path("element").asText());
        assertEquals("icon", children.path(2).path("element").asText());
        assertEquals("map-pin", children.path(2).path("config").path("icon").asText());
        assertFalse(children.path(3).path("config").has("icon"), "nom d'icône invalide retiré");
        assertFalse(children.path(4).path("config").has("href"));
    }

    @Test
    void refuseUneReponseSansBlocs() {
        assertThrows(IllegalStateException.class, () -> sanitize("{ \"message\": \"désolé\" }"));
        assertThrows(IllegalStateException.class, () -> sanitize("{ \"widgets\": [] }"));
    }
}
