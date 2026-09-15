package dev.repair.api.motochat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.repair.api.garage.MaintenanceEventDto;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaintenanceContextRelevanceTest {

    private MaintenanceEventDto event(String serviceType) {
        return new MaintenanceEventDto(1L, 1L, serviceType, 1000.0, null, null, "manual", OffsetDateTime.now());
    }

    @Test
    void unrelatedHistoryIsExcludedFromAFocusedQuestion() {
        // Reproduces the reported bug: an overheating question must not
        // drag in unrelated chain-lubrication history.
        List<MaintenanceEventDto> history = List.of(event("CHAIN_LUBE"), event("COOLANT_CHANGE"));

        List<MaintenanceEventDto> filtered = MaintenanceContextRelevance.filter(history, "I feel the bike is hotter than usual");

        assertThat(filtered).extracting(MaintenanceEventDto::serviceType).containsExactly("COOLANT_CHANGE");
    }

    @Test
    void genericMaintenanceQuestionKeepsFullHistory() {
        List<MaintenanceEventDto> history = List.of(event("CHAIN_LUBE"), event("COOLANT_CHANGE"), event("ENGINE_OIL_CHANGE"));

        List<MaintenanceEventDto> filtered = MaintenanceContextRelevance.filter(history, "What maintenance is due soon?");

        assertThat(filtered).hasSize(3);
    }

    @Test
    void matchingKeywordKeepsOnlyTheRelevantEvent() {
        List<MaintenanceEventDto> history = List.of(event("CHAIN_LUBE"), event("ENGINE_OIL_CHANGE"));

        List<MaintenanceEventDto> filtered = MaintenanceContextRelevance.filter(history, "When is my next oil change?");

        assertThat(filtered).extracting(MaintenanceEventDto::serviceType).containsExactly("ENGINE_OIL_CHANGE");
    }
}
