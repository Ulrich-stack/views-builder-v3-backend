package com.builder.builder_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActionSanitizerTests {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode sanitize(String json) throws Exception {
        return ActionSanitizer.sanitize(mapper.readTree(json));
    }

    @Test
    void gardeLesActionsDeLaListeBlancheAvecLeursSeulsChampsUtiles() throws Exception {
        JsonNode result = sanitize("""
                [ { "on": "click", "do": "validate", "target": "b3", "rule": "email", "message": " E-mail invalide ", "url": "x" },
                  { "do": "hide", "target": "b2", "message": "ignoré" },
                  { "on": "click", "do": "show", "target": "n1" },
                  { "on": "click", "do": "toast", "message": "Merci !" },
                  { "on": "click", "do": "link", "url": "https://exemple.fr", "newTab": true } ]
                """);
        assertEquals(5, result.size());
        JsonNode validate = result.path(0);
        assertEquals("email", validate.path("rule").asText());
        assertEquals("E-mail invalide", validate.path("message").asText());
        assertFalse(validate.has("url"));
        assertEquals("click", result.path(1).path("on").asText());
        assertFalse(result.path(1).has("message"));
        assertTrue(result.path(4).path("newTab").asBoolean());
    }

    @Test
    void rejetteLesActionsInconnuesEtLeCodeLibre() throws Exception {
        JsonNode result = sanitize("""
                [ { "on": "click", "do": "eval", "code": "alert(1)" },
                  { "on": "hover", "do": "show", "target": "b1" },
                  { "on": "click", "do": "show" },
                  { "on": "click", "do": "show", "target": "b1\\"><script>" },
                  { "on": "click", "do": "toast" },
                  { "on": "click", "do": "link", "url": "javascript:alert(1)" },
                  { "on": "click", "do": "link", "url": "data:text/html,x" },
                  { "on": "click", "do": "validate", "target": "b2", "rule": "regex" },
                  "texte" ]
                """);
        assertEquals(1, result.size());
        assertEquals("required", result.path(0).path("rule").asText());
    }

    @Test
    void limiteLeNombreDActions() throws Exception {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 30; i++) json.append(i == 0 ? "" : ",").append("{ \"do\": \"toast\", \"message\": \"m\" }");
        assertEquals(ActionSanitizer.MAX_ACTIONS, sanitize(json.append("]").toString()).size());
        assertEquals(0, sanitize("{ \"do\": \"toast\" }").size());
    }

    @Test
    void leNettoyageDesBlocsGardeLesRepereslesActionsEtLaVisibilite() throws Exception {
        JsonNode result = WidgetSanitizer.sanitize(mapper.readTree("""
                { "widgets": [ { "ref": "b1", "type": "container", "element": "div", "children": [
                    { "ref": "b2", "type": "action", "element": "button", "content": "Envoyer",
                      "config": { "actions": [ { "on": "click", "do": "show", "target": "n1" }, { "do": "run", "js": "x" } ] } },
                    { "ref": "n1", "type": "text", "element": "p", "content": "Merci", "config": { "hidden": "true" } },
                    { "ref": "<b>", "type": "text", "element": "p", "config": { "hidden": false, "actions": "oups" } }
                ] } ] }
                """), WidgetSanitizer.Options.EDIT).path("widgets").path(0);
        assertEquals("b1", result.path("ref").asText());
        JsonNode button = result.path("children").path(0);
        assertEquals(1, button.path("config").path("actions").size());
        JsonNode message = result.path("children").path(1);
        assertTrue(message.path("config").path("hidden").isBoolean());
        assertTrue(message.path("config").path("hidden").asBoolean());
        JsonNode other = result.path("children").path(2);
        assertFalse(other.has("ref"));
        assertFalse(other.path("config").has("hidden"));
        assertFalse(other.path("config").has("actions"));
    }
}
