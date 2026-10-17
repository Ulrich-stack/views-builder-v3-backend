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
                    { "type": "text", "element": "h3", "content": "Titre" },
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
    void refuseUneReponseSansBlocs() {
        assertThrows(IllegalStateException.class, () -> sanitize("{ \"message\": \"désolé\" }"));
        assertThrows(IllegalStateException.class, () -> sanitize("{ \"widgets\": [] }"));
    }
}
