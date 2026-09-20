package dev.repair.api.garage;

import java.util.Set;

/** The controlled maintenance service-type taxonomy — must match the
 * CHECK constraint on maintenance_events.service_type in
 * V7__garage_and_maintenance.sql exactly. Kept deliberately small per
 * docs/repair-v2-architecture.md section 1; anything not in this set is
 * OTHER, never a free-text invented category. */
public final class ServiceType {

    private ServiceType() {
    }

    public static final Set<String> VALID = Set.of(
            "ENGINE_OIL_CHANGE", "OIL_FILTER_CHANGE", "SPARK_PLUG_CHANGE", "AIR_FILTER_CHANGE",
            "VALVE_CLEARANCE_CHECK", "CHAIN_LUBE", "CHAIN_ADJUSTMENT", "BRAKE_FLUID_CHANGE",
            "COOLANT_CHANGE", "BATTERY_REPLACEMENT", "TIRE_REPLACEMENT", "OTHER"
    );

    public static boolean isValid(String value) {
        return value != null && VALID.contains(value);
    }
}
