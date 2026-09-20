package dev.repair.api.motochat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic, no-extra-AI-call conversation title derived from the
 * rider's first message — replaces the previous behavior of every
 * conversation on the same bike sharing one identical title (just the
 * manufacturer/model), which made the sidebar list unreadable once a
 * rider had more than one or two chats about the same motorcycle.
 *
 * A small keyword -> topic map covers the common cases; anything else
 * falls back to a trimmed excerpt of the message itself. Deliberately not
 * model-specific and not an LLM call — this only needs to be "good
 * enough to tell two sidebar entries apart," not a perfect summary.
 */
final class ChatTitleGenerator {

    private ChatTitleGenerator() {
    }

    private static final int MAX_TITLE_LENGTH = 42;

    // Ordered so a more specific phrase can be checked before a more
    // generic one that might also match the same message (e.g. "oil
    // filter" before "oil").
    private static final Map<String, String> KEYWORD_TOPICS = buildKeywordTopics();

    private static Map<String, String> buildKeywordTopics() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("oil filter", "Oil filter");
        map.put("oil change", "Oil change");
        map.put("engine oil", "Engine oil");
        map.put("oil", "Oil change");
        map.put("chain", "Chain maintenance");
        map.put("spark plug", "Spark plugs");
        map.put("valve", "Valve clearance");
        map.put("coolant", "Cooling system");
        map.put("overheat", "Cooling issue");
        map.put("hot", "Cooling issue");
        map.put("hotter", "Cooling issue");
        map.put("temperature", "Cooling issue");
        map.put("brake fluid", "Brake fluid");
        map.put("brake", "Brakes");
        map.put("suspension", "Suspension setup");
        map.put("fork", "Suspension setup");
        map.put("shock", "Suspension setup");
        map.put("tire pressure", "Tire pressure");
        map.put("tyre pressure", "Tire pressure");
        map.put("tire", "Tires");
        map.put("tyre", "Tires");
        map.put("battery", "Battery");
        map.put("fuse", "Electrical / fuses");
        map.put("won t start", "Won't start"); // "won't" after punctuation normalization
        map.put("wont start", "Won't start");
        map.put("does not start", "Won't start");
        map.put("doesn t start", "Won't start"); // "doesn't" after punctuation normalization
        map.put("odometer", "Odometer update");
        map.put("mileage", "Odometer update");
        map.put("air filter", "Air filter");
        map.put("clutch", "Clutch");
        map.put("noise", "Unusual noise");
        map.put("rattl", "Unusual noise");
        map.put("vibrat", "Vibration");
        return map;
    }

    static String generate(String firstUserMessage) {
        if (firstUserMessage == null || firstUserMessage.isBlank()) {
            return null;
        }
        // Collapse punctuation to spaces first so word-boundary matching
        // below isn't defeated by "...the oil?" or "...the oil,".
        String normalized = firstUserMessage.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ");
        String padded = " " + normalized + " ";
        for (Map.Entry<String, String> entry : KEYWORD_TOPICS.entrySet()) {
            if (padded.contains(" " + entry.getKey() + " ")) {
                return entry.getValue();
            }
        }
        return fallbackExcerpt(firstUserMessage);
    }

    private static String fallbackExcerpt(String text) {
        String trimmed = text.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= MAX_TITLE_LENGTH) {
            return trimmed;
        }
        String cut = trimmed.substring(0, MAX_TITLE_LENGTH);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > 20) {
            cut = cut.substring(0, lastSpace);
        }
        return cut + "…";
    }
}
