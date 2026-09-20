package dev.repair.api.motorcycle;

import java.util.Map;

/**
 * Human-readable labels for the internal motorcycle_facts fact_type
 * enum, used anywhere a fact is shown to the rider (chat prompt input,
 * normal answer UI) — the raw enum key (e.g. "ENGINE_OIL_INTERVAL_KM")
 * must only ever appear in Evidence & Debug, never in normal
 * customer-facing text. See docs/repair-v2-architecture.md section on
 * provenance separation.
 *
 * Deliberately centralized instead of duplicated per call site, and
 * deliberately NOT model/value-specific — this maps a fact *type* to a
 * label, never a manufacturer value to a label.
 */
public final class MotorcycleFactLabels {

    private MotorcycleFactLabels() {
    }

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("ENGINE_OIL_CAPACITY_L", "Engine oil capacity"),
            Map.entry("ENGINE_OIL_CAPACITY_WITH_FILTER_L", "Engine oil capacity (with filter)"),
            Map.entry("ENGINE_OIL_INTERVAL_KM", "Engine oil change interval"),
            Map.entry("ENGINE_OIL_INTERVAL_MONTHS", "Engine oil change interval (time-based)"),
            Map.entry("OIL_FILTER_INTERVAL_KM", "Oil filter replacement interval"),
            Map.entry("OIL_FILTER_INTERVAL_MONTHS", "Oil filter replacement interval (time-based)"),
            Map.entry("AIR_FILTER_INTERVAL_KM", "Air filter replacement interval"),
            Map.entry("SPARK_PLUG_MODEL", "Spark plug model"),
            Map.entry("SPARK_PLUG_GAP_MM", "Spark plug gap"),
            Map.entry("SPARK_PLUG_TORQUE_NM", "Spark plug torque"),
            Map.entry("SPARK_PLUG_REPLACE_INTERVAL_KM", "Spark plug replacement interval"),
            Map.entry("SPARK_PLUG_REPLACE_INTERVAL_MONTHS", "Spark plug replacement interval (time-based)"),
            Map.entry("VALVE_CLEARANCE_INTERVAL_KM", "Valve clearance check interval"),
            Map.entry("COOLANT_CAPACITY_L", "Coolant capacity"),
            Map.entry("COOLANT_CHANGE_INTERVAL_MONTHS", "Coolant change interval"),
            Map.entry("BRAKE_FLUID_TYPE", "Brake fluid type"),
            Map.entry("BRAKE_FLUID_INTERVAL_MONTHS", "Brake fluid change interval"),
            Map.entry("CHAIN_SLACK_MM", "Chain slack (specified range)"),
            Map.entry("CHAIN_LUBE_INTERVAL", "Chain lubrication interval"),
            Map.entry("CHAIN_LUBE_INTERVAL_KM", "Chain lubrication interval"),
            Map.entry("FRONT_TIRE_PRESSURE_KPA", "Front tire pressure (road)"),
            Map.entry("REAR_TIRE_PRESSURE_KPA", "Rear tire pressure (road)"),
            Map.entry("BATTERY_MODEL", "Battery model"),
            Map.entry("OIL_FILTER_TORQUE_NM", "Oil filter torque"),
            Map.entry("OIL_DRAIN_BOLT_TORQUE_NM", "Oil drain bolt torque"),
            Map.entry("REAR_AXLE_TORQUE_NM", "Rear axle nut torque"),
            Map.entry("CHAIN_ADJUSTER_LOCKNUT_TORQUE_NM", "Chain-adjuster locknut torque"),
            Map.entry("FRONT_AXLE_TORQUE_NM", "Front axle nut torque")
    );

    /** Falls back to a humanized version of the enum key (never the raw
     * key itself) for any fact type not yet in the explicit map above,
     * so a newly-added extractor never leaks an enum name by omission. */
    public static String label(String factType) {
        String known = LABELS.get(factType);
        if (known != null) {
            return known;
        }
        String withoutUnitSuffix = factType.replaceAll("_(KM|MM|NM|KPA|L|MONTHS)$", "");
        String[] words = withoutUnitSuffix.split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(word.charAt(0)).append(word.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
