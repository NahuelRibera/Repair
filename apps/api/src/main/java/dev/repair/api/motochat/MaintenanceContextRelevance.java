package dev.repair.api.motochat;

import dev.repair.api.garage.MaintenanceEventDto;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Query-relevance filter for how much stored maintenance history gets
 * injected into a chat turn's prompt — fixes the "irrelevant context
 * injection" class of bug (e.g. a temperature/overheating question
 * dragging in unrelated chain-lubrication and tire-preference history).
 * Deterministic and keyword-based, not a second model call: this only
 * needs to catch the obvious case (a question about one system shouldn't
 * surface history about an unrelated one), not perform real intent
 * understanding — genuinely ambiguous relevance is fine to leave to the
 * model's own judgement over whatever a broader, generic query includes.
 */
final class MaintenanceContextRelevance {

    private MaintenanceContextRelevance() {
    }

    private static final Map<String, List<String>> SERVICE_TYPE_KEYWORDS = Map.ofEntries(
            Map.entry("ENGINE_OIL_CHANGE", List.of("oil")),
            Map.entry("OIL_FILTER_CHANGE", List.of("oil filter")),
            Map.entry("SPARK_PLUG_CHANGE", List.of("spark plug", "plug")),
            Map.entry("AIR_FILTER_CHANGE", List.of("air filter")),
            Map.entry("VALVE_CLEARANCE_CHECK", List.of("valve")),
            Map.entry("CHAIN_LUBE", List.of("chain")),
            Map.entry("CHAIN_ADJUSTMENT", List.of("chain")),
            Map.entry("BRAKE_FLUID_CHANGE", List.of("brake")),
            Map.entry("COOLANT_CHANGE", List.of("coolant", "cooling", "overheat", "hot", "temperature", "radiator")),
            Map.entry("BATTERY_REPLACEMENT", List.of("battery", "electrical", "won't start", "wont start")),
            Map.entry("TIRE_REPLACEMENT", List.of("tire", "tyre", "pressure")),
            Map.entry("OTHER", List.of())
    );

    /** Broad, dashboard-style questions legitimately want the whole
     * history regardless of keyword overlap. */
    private static final Set<String> GENERIC_MARKERS = Set.of(
            "due", "maintenance", "service schedule", "overview", "status", "overdue",
            "what's due", "whats due", "next service", "upcoming", "history", "garage"
    );

    static List<MaintenanceEventDto> filter(List<MaintenanceEventDto> recentMaintenance, String query) {
        if (recentMaintenance.isEmpty()) {
            return recentMaintenance;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        if (GENERIC_MARKERS.stream().anyMatch(lower::contains)) {
            return recentMaintenance;
        }
        return recentMaintenance.stream()
                .filter(event -> SERVICE_TYPE_KEYWORDS.getOrDefault(event.serviceType(), List.of()).stream().anyMatch(lower::contains))
                .toList();
    }
}
