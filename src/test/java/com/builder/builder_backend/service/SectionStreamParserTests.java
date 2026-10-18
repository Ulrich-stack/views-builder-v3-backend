package com.builder.builder_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SectionStreamParserTests {

    private static final String RESPONSE = """
            { "mode": "persuader", "sections": [
              { "template": "nav", "content": { "brand": "Maison {Thé}", "links": ["A", "B"] } },
              { "template": "hero-split", "content": { "title": "Un \\"vrai\\" titre ] } avec accolades" } }
            ] }
            """;

    @Test
    void rendChaqueSectionDesQuElleEstComplete() {
        SectionStreamParser parser = new SectionStreamParser();
        List<JsonNode> sections = new ArrayList<>();
        // Découpage arbitraire, comme les morceaux envoyés par l'IA
        for (int i = 0; i < RESPONSE.length(); i += 7) {
            sections.addAll(parser.feed(RESPONSE.substring(i, Math.min(RESPONSE.length(), i + 7))));
        }
        assertEquals(2, sections.size());
        assertEquals("persuader", parser.mode());
        assertEquals("Maison {Thé}", sections.get(0).path("content").path("brand").asText());
        assertEquals("Un \"vrai\" titre ] } avec accolades", sections.get(1).path("content").path("title").asText());
    }

    @Test
    void laPremiereSectionArriveAvantLaFinDeLaReponse() {
        SectionStreamParser parser = new SectionStreamParser();
        int firstSectionEnd = RESPONSE.indexOf("} },") + 3;
        List<JsonNode> early = parser.feed(RESPONSE.substring(0, firstSectionEnd));
        assertEquals(1, early.size());
        assertEquals("nav", early.get(0).path("template").asText());
    }

    @Test
    void ignoreUneReponseSansSections() {
        SectionStreamParser parser = new SectionStreamParser();
        assertTrue(parser.feed("{ \"mode\": \"lire\", \"widgets\": [ { \"type\": \"text\" } ] }").isEmpty());
        assertFalse(parser.sawSections());
    }

    @Test
    void litLaPaletteAnnonceeAvantLesSections() {
        SectionStreamParser parser = new SectionStreamParser();
        String response = """
                { "mode": "persuader", "palette": { "primary": "#7A8B6F", "mode": "light" }, "sections": [ { "template": "nav" } ] }
                """;
        for (int i = 0; i < response.length(); i += 5) parser.feed(response.substring(i, Math.min(response.length(), i + 5)));
        GeminiService.PaletteEvent palette = GeminiService.PaletteEvent.from(parser.palette());
        assertNotNull(palette);
        assertEquals("#7a8b6f", palette.primary());
        assertEquals("light", palette.mode());
    }

    @Test
    void refuseUnePaletteInutilisable() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertNull(GeminiService.PaletteEvent.from(null));
        assertNull(GeminiService.PaletteEvent.from(mapper.readTree("{ \"primary\": \"vert\" }")));
        assertNull(GeminiService.PaletteEvent.from(mapper.readTree("{ \"primary\": \"#abc\" }")));
        assertEquals("light", GeminiService.PaletteEvent.from(mapper.readTree("{ \"primary\": \"#112233\", \"mode\": \"néon\" }")).mode());
        assertEquals("dark", GeminiService.PaletteEvent.from(mapper.readTree("{ \"primary\": \"#112233\", \"mode\": \"dark\" }")).mode());
    }
}
