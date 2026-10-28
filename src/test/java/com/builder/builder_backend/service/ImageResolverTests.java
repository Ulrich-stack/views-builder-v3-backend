package com.builder.builder_backend.service;

import com.builder.builder_backend.service.PhotoSearch.Orientation;
import com.builder.builder_backend.service.PhotoSearch.Photo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ImageResolverTests {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Banque factice : renvoie les photos prévues pour chaque requête et note les recherches faites. */
    private static class FakeSource implements PhotoSearch {
        final String name;
        final Function<String, List<Photo>> results;
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        FakeSource(String name, Map<String, List<Photo>> results) {
            this.name = name;
            this.results = q -> results.getOrDefault(q, List.of());
        }

        final List<String> used = Collections.synchronizedList(new ArrayList<>());

        @Override
        public List<Photo> search(String query, Orientation orientation) {
            calls.add(query + "|" + orientation);
            return results.apply(query);
        }

        @Override
        public void markUsed(Photo photo) {
            used.add(photo.url());
        }
    }

    private static Photo photo(String name) {
        return new Photo("https://img.test/" + name + ".jpg", "https://img.test/" + name + "-2x.jpg",
                "Ana " + name + " sur Unsplash", "https://unsplash.com/@" + name, null);
    }

    @Test
    void deduitLaFormeDeLEmplacementDeSonAspectRatio() throws Exception {
        assertEquals(Orientation.PORTRAIT, ImageResolver.orientationOf(mapper.readTree("{ \"aspect-ratio\": \"4 / 5\" }")));
        assertEquals(Orientation.LANDSCAPE, ImageResolver.orientationOf(mapper.readTree("{ \"aspect-ratio\": \"16 / 7\" }")));
        assertEquals(Orientation.SQUARE, ImageResolver.orientationOf(mapper.readTree("{ \"aspect-ratio\": \"1 / 1\" }")));
        assertEquals(Orientation.ANY, ImageResolver.orientationOf(mapper.readTree("{ \"width\": \"100%\" }")));
    }

    @Test
    void retenteAvecLePremierMotQuiPorteLeSujetAvantDePasserALaBanqueSuivante() {
        FakeSource pexels = new FakeSource("pexels", Map.of("tea", List.of(photo("tea"))));
        FakeSource openverse = new FakeSource("openverse", Map.of("tea room", List.of(photo("room"))));
        List<Photo> photos = new ImageResolver(List.of(pexels, openverse)).searchWithFallback("tea room", Orientation.PORTRAIT).photos();

        assertEquals("https://img.test/tea.jpg", photos.get(0).url(), "« tea » et non « room »");
        assertEquals(List.of("tea room|PORTRAIT", "tea|PORTRAIT"), pexels.calls);
        assertTrue(openverse.calls.isEmpty(), "Openverse n'est interrogé que si Pexels ne trouve rien");
    }

    @Test
    void passeALaBanqueDeSecoursQuandLaPremiereNeTrouveRien() {
        FakeSource pexels = new FakeSource("pexels", Map.of());
        FakeSource openverse = new FakeSource("openverse", Map.of("yoga class", List.of(photo("yoga"))));
        List<Photo> photos = new ImageResolver(List.of(pexels, openverse)).searchWithFallback("yoga class", Orientation.ANY).photos();
        assertEquals("https://img.test/yoga.jpg", photos.get(0).url());
        assertEquals(List.of("yoga class|ANY"), openverse.calls);
    }

    @Test
    void choisitLaFormeEtLaTailleSelonLEmplacementSansRepeterUnePhoto() throws Exception {
        FakeSource pexels = new FakeSource("pexels", Map.of(
                "tea", List.of(photo("tea-1"), photo("tea-2")),
                "tea room", List.of(photo("salon"))));
        JsonNode widgets = mapper.readTree("""
                [ { "type": "container", "element": "section", "styles": { "background-image": "linear-gradient(black, transparent)" },
                    "config": { "backgroundQuery": "tea room" }, "children": [
                      { "type": "media", "element": "img", "styles": { "aspect-ratio": "4 / 5" }, "config": { "imageQuery": "tea" } },
                      { "type": "media", "element": "img", "styles": { "aspect-ratio": "4 / 5" }, "config": { "imageQuery": "tea" } }
                ] } ]
                """);
        new ImageResolver(List.of(pexels)).resolveImages(widgets);

        JsonNode section = widgets.path(0);
        assertEquals("linear-gradient(black, transparent), url('https://img.test/salon-2x.jpg')",
                section.path("styles").path("background-image").asText(), "grande taille sous le voile");
        assertFalse(section.path("config").has("backgroundQuery"));
        assertEquals("https://img.test/tea-1.jpg", section.path("children").path(0).path("config").path("src").asText());
        assertEquals("https://img.test/tea-2.jpg", section.path("children").path(1).path("config").path("src").asText(),
                "deux emplacements, deux photos différentes");
        assertTrue(pexels.calls.contains("tea room|LANDSCAPE"), "une photo de fond est cherchée en paysage");
        assertTrue(pexels.calls.contains("tea|PORTRAIT"));

        // Crédits du photographe : sur l'image, et sur le bloc pour une photo de fond
        assertEquals("Ana tea-1 sur Unsplash", section.path("children").path(0).path("config").path("credit").asText());
        assertEquals("https://unsplash.com/@tea-1", section.path("children").path(0).path("config").path("creditUrl").asText());
        assertEquals("Ana salon sur Unsplash", section.path("config").path("credit").asText());
        // Chaque photo placée est signalée à sa banque
        assertEquals(3, pexels.used.size());
    }

    @Test
    void neRefaitPasUneRechercheDejaFaite() {
        FakeSource unsplash = new FakeSource("unsplash", Map.of("pottery", List.of(photo("pot"))));
        ImageResolver resolver = new ImageResolver(List.of(unsplash));
        resolver.searchWithFallback("pottery", Orientation.SQUARE);
        resolver.searchWithFallback("pottery", Orientation.SQUARE);
        assertEquals(1, unsplash.calls.size(), "la seconde recherche vient du cache");
    }

    @Test
    void litLaReponseDUnsplashAvecLeCreditEtLeSignalement() throws Exception {
        List<Photo> photos = UnsplashSearch.parse("""
                { "results": [ {
                    "urls": { "raw": "https://images.unsplash.com/photo-1?ixid=abc", "regular": "https://images.unsplash.com/photo-1?w=1080" },
                    "user": { "name": "Léa Martin", "links": { "html": "https://unsplash.com/@lea" } },
                    "links": { "download_location": "https://api.unsplash.com/photos/1/download?ixid=abc" } } ] }
                """);
        Photo photo = photos.get(0);
        assertEquals("https://images.unsplash.com/photo-1?w=1080", photo.url());
        assertEquals("https://images.unsplash.com/photo-1?ixid=abc&w=1920&q=80&fm=jpg&fit=crop", photo.large());
        assertEquals("Léa Martin sur Unsplash", photo.credit());
        assertEquals("https://unsplash.com/@lea?utm_source=views_builder&utm_medium=referral", photo.creditUrl());
        assertEquals("https://api.unsplash.com/photos/1/download?ixid=abc", photo.trackUrl());
        assertEquals("&orientation=squarish", UnsplashSearch.orientationParam(Orientation.SQUARE));
    }

    @Test
    void litLaReponseDePexels() throws Exception {
        List<Photo> photos = PexelsSearch.parse("""
                { "photos": [
                    { "src": { "large": "https://images.pexels.com/1.jpeg?h=650", "large2x": "https://images.pexels.com/1.jpeg?h=1300" } },
                    { "src": { "large": "http://pas-sur.test/2.jpeg" } }
                ] }
                """);
        assertEquals(1, photos.size(), "les adresses non https sont ignorées");
        assertEquals("https://images.pexels.com/1.jpeg?h=1300", photos.get(0).large());
        assertEquals("&orientation=portrait", PexelsSearch.orientationParam(Orientation.PORTRAIT));
        assertEquals("", PexelsSearch.orientationParam(Orientation.ANY));
    }
}
