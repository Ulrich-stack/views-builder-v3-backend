package com.builder.builder_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lit au fil de l'eau la réponse JSON de l'IA ({ "mode": ..., "sections": [ {...}, {...} ] })
 * et rend chaque section dès que son objet est complet, sans attendre la fin de la réponse.
 */
public class SectionStreamParser {

    private static final Pattern SECTIONS_START = Pattern.compile("\"sections\"\\s*:\\s*\\[");
    private static final Pattern MODE = Pattern.compile("\"mode\"\\s*:\\s*\"(\\w+)\"");
    // La palette est un petit objet plat placé avant les sections : { "primary": "#...", "mode": "light" }
    private static final Pattern PALETTE = Pattern.compile("\"palette\"\\s*:\\s*(\\{[^{}]*})");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StringBuilder buffer = new StringBuilder();

    private int position = -1; // -1 : le tableau "sections" n'a pas encore commencé
    private int depth;
    private int objectStart;
    private boolean inString;
    private boolean escaped;
    private boolean finished;
    private String mode;
    private JsonNode palette;

    /** Ajoute un morceau de texte et renvoie les sections devenues complètes. */
    public List<JsonNode> feed(String chunk) {
        buffer.append(chunk);
        List<JsonNode> sections = new ArrayList<>();
        if (mode == null) {
            Matcher m = MODE.matcher(buffer);
            if (m.find()) mode = m.group(1);
        }
        if (palette == null && position < 0) {
            Matcher m = PALETTE.matcher(buffer);
            if (m.find()) palette = parse(m.group(1));
        }
        if (position < 0) {
            Matcher m = SECTIONS_START.matcher(buffer);
            if (!m.find()) return sections;
            position = m.end();
        }
        while (!finished && position < buffer.length()) {
            char c = buffer.charAt(position);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) objectStart = position;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) sections.add(parse(buffer.substring(objectStart, position + 1)));
            } else if (c == ']' && depth == 0) {
                finished = true;
            }
            position++;
        }
        sections.removeIf(s -> s == null);
        return sections;
    }

    /** Type de page annoncé par l'IA, dès qu'il est connu. */
    public String mode() {
        return mode;
    }

    /** Palette proposée par l'IA (objet brut, à valider), si elle l'a écrite avant les sections. */
    public JsonNode palette() {
        return palette;
    }

    /** Tout le texte reçu, pour exploiter une réponse qui n'aurait pas le format attendu. */
    public String fullText() {
        return buffer.toString();
    }

    /** Vrai quand le tableau "sections" a été entièrement reçu (crochet fermant compris). */
    public boolean isComplete() {
        return finished;
    }

    public boolean sawSections() {
        return position >= 0;
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null; // Section mal formée : on l'ignore, les suivantes restent utilisables
        }
    }
}
