package dev.repair.api.motorcycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MotorcycleFactLabelsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "ENGINE_OIL_CAPACITY_L, Engine oil capacity",
            "ENGINE_OIL_INTERVAL_MONTHS, Engine oil change interval (time-based)",
            "SPARK_PLUG_MODEL, Spark plug model",
            "SPARK_PLUG_GAP_MM, Spark plug gap",
            "FRONT_TIRE_PRESSURE_KPA, Front tire pressure (road)",
            "REAR_AXLE_TORQUE_NM, Rear axle nut torque",
            "CHAIN_LUBE_INTERVAL, Chain lubrication interval",
            "CHAIN_LUBE_INTERVAL_KM, Chain lubrication interval"
    })
    void knownFactTypesMapToExplicitLabels(String factType, String expected) {
        assertThat(MotorcycleFactLabels.label(factType)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "STEERING_HEAD_TORQUE_NM, Steering Head Torque",
            "FORK_OIL_LEVEL_MM, Fork Oil Level",
            "TANK_RESERVE_CAPACITY_L, Tank Reserve Capacity",
            "TIRE_REPLACE_INTERVAL_KM, Tire Replace Interval",
            "FUEL_FILTER_INTERVAL_MONTHS, Fuel Filter Interval",
            "REAR_TIRE_PRESSURE_PASSENGER_KPA, Rear Tire Pressure Passenger",
            "HEADLIGHT_BULB_TYPE, Headlight Bulb Type",
            "KM_MARKER_VALUE, Km Marker Value",
            "SPARK, Spark"
    })
    void unknownFactTypesUseHumanizedFallback(String factType, String expected) {
        assertThat(MotorcycleFactLabels.label(factType)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "STEERING_HEAD_TORQUE_NM",
            "HEADLIGHT_BULB_TYPE",
            "REAR_TIRE_PRESSURE_PASSENGER_KPA",
            "FUEL_FILTER_INTERVAL_MONTHS"
    })
    void fallbackNeverReturnsRawEnumKey(String unknownMultiWordFactType) {
        String label = MotorcycleFactLabels.label(unknownMultiWordFactType);

        assertThat(label).doesNotContain("_");
        assertThat(label).isNotEqualTo(unknownMultiWordFactType);
    }

    @Test
    void knownLabelsAreNotRunThroughFallback() {
        assertThat(MotorcycleFactLabels.label("OIL_DRAIN_BOLT_TORQUE_NM")).isEqualTo("Oil drain bolt torque");
    }
}
