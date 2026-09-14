package dev.repair.api.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.repair.api.motorcycle.MotorcycleCatalogRepository;
import dev.repair.api.motorcycle.MotorcycleFactDto;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure computation tests against the worked examples in the task spec
 * (sections 18/19/51): no LLM, no database — MotorcycleCatalogRepository
 * is mocked to supply exactly the facts each scenario needs.
 */
@ExtendWith(MockitoExtension.class)
class MaintenanceStatusServiceTest {

    private static final long MODEL_ID = 1L;
    private static final int YEAR = 2025;

    @Mock
    private MotorcycleCatalogRepository catalogRepository;

    private MaintenanceStatusService service;

    @BeforeEach
    void setUp() {
        service = new MaintenanceStatusService(catalogRepository);
    }

    private GarageVehicleDto vehicle(Double odometerKm) {
        return new GarageVehicleDto(1L, MODEL_ID, "Yamaha", "MT-07", YEAR, null, null, odometerKm,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private MaintenanceEventDto oilEvent(Double odometerKm, LocalDate performedAt) {
        return new MaintenanceEventDto(1L, 1L, "ENGINE_OIL_CHANGE", odometerKm, performedAt, null, "manual", OffsetDateTime.now());
    }

    @Test
    void notYetDue_matchesWorkedExample() {
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.of(
                "ENGINE_OIL_INTERVAL_KM", new MotorcycleFactDto("ENGINE_OIL_INTERVAL_KM", 6000.0, null, "km")
        ));
        var vehicle = vehicle(23500.0);
        var latest = Map.of("ENGINE_OIL_CHANGE", oilEvent(19000.0, null));

        var card = cardFor("ENGINE_OIL_CHANGE", service.buildDashboard(vehicle, latest));

        assertThat(card.remainingKm()).isEqualTo(1500.0);
        assertThat(card.status()).isIn(MaintenanceStatusService.Status.OK, MaintenanceStatusService.Status.DUE_SOON);
    }

    @Test
    void slightlyOverdue_matchesWorkedExample() {
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.of(
                "ENGINE_OIL_INTERVAL_KM", new MotorcycleFactDto("ENGINE_OIL_INTERVAL_KM", 6000.0, null, "km")
        ));
        var vehicle = vehicle(25400.0);
        var latest = Map.of("ENGINE_OIL_CHANGE", oilEvent(19000.0, null));

        var card = cardFor("ENGINE_OIL_CHANGE", service.buildDashboard(vehicle, latest));

        assertThat(card.remainingKm()).isEqualTo(-400.0);
        assertThat(card.status()).isIn(MaintenanceStatusService.Status.DUE, MaintenanceStatusService.Status.OVERDUE);
    }

    @Test
    void farOverdue_isOverdueNotJustDue() {
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.of(
                "ENGINE_OIL_INTERVAL_KM", new MotorcycleFactDto("ENGINE_OIL_INTERVAL_KM", 6000.0, null, "km")
        ));
        var vehicle = vehicle(40000.0); // 15,000 km past a 19,000 km service on a 6,000 km interval
        var latest = Map.of("ENGINE_OIL_CHANGE", oilEvent(19000.0, null));

        var card = cardFor("ENGINE_OIL_CHANGE", service.buildDashboard(vehicle, latest));

        assertThat(card.status()).isEqualTo(MaintenanceStatusService.Status.OVERDUE);
    }

    @Test
    void missingOdometerIsUnknownNotFabricated() {
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.of(
                "ENGINE_OIL_INTERVAL_KM", new MotorcycleFactDto("ENGINE_OIL_INTERVAL_KM", 6000.0, null, "km")
        ));
        var vehicle = vehicle(null); // current odometer never recorded
        var latest = Map.of("ENGINE_OIL_CHANGE", oilEvent(19000.0, null));

        var card = cardFor("ENGINE_OIL_CHANGE", service.buildDashboard(vehicle, latest));

        assertThat(card.status()).isEqualTo(MaintenanceStatusService.Status.UNKNOWN);
    }

    @Test
    void missingLastEventIsUnknownWithNotRecordedNote() {
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.of(
                "ENGINE_OIL_INTERVAL_KM", new MotorcycleFactDto("ENGINE_OIL_INTERVAL_KM", 6000.0, null, "km")
        ));
        var vehicle = vehicle(23500.0);

        var card = cardFor("ENGINE_OIL_CHANGE", service.buildDashboard(vehicle, Map.of()));

        assertThat(card.status()).isEqualTo(MaintenanceStatusService.Status.UNKNOWN);
        assertThat(card.note()).isEqualTo("Not recorded");
    }

    @Test
    void noIntervalFactAtAllIsUnknownWithExplanation() {
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.of());
        var vehicle = vehicle(23500.0);

        var card = cardFor("ENGINE_OIL_CHANGE", service.buildDashboard(vehicle, Map.of()));

        assertThat(card.status()).isEqualTo(MaintenanceStatusService.Status.UNKNOWN);
        assertThat(card.note()).contains("No verified interval");
    }

    @Test
    void dateBasedIntervalIsEvaluatedWhenOnlyATimeFactExists() {
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.of(
                "BRAKE_FLUID_INTERVAL_MONTHS", new MotorcycleFactDto("BRAKE_FLUID_INTERVAL_MONTHS", 24.0, null, "months")
        ));
        var vehicle = vehicle(23500.0);
        var lastBrakeFluid = new MaintenanceEventDto(
                2L, 1L, "BRAKE_FLUID_CHANGE", null, LocalDate.now().minusMonths(30), null, "manual", OffsetDateTime.now());

        var card = cardFor("BRAKE_FLUID_CHANGE", service.buildDashboard(vehicle, Map.of("BRAKE_FLUID_CHANGE", lastBrakeFluid)));

        assertThat(card.status()).isIn(MaintenanceStatusService.Status.DUE, MaintenanceStatusService.Status.OVERDUE);
    }

    @Test
    void distanceAndTimeCombine_worseDimensionWins() {
        // 6,000 km / 6 month oil interval: distance says comfortably OK,
        // but the bike hasn't been ridden in 10 months — time must win.
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.of(
                "ENGINE_OIL_INTERVAL_KM", new MotorcycleFactDto("ENGINE_OIL_INTERVAL_KM", 6000.0, null, "km"),
                "ENGINE_OIL_INTERVAL_MONTHS", new MotorcycleFactDto("ENGINE_OIL_INTERVAL_MONTHS", 6.0, null, "months")
        ));
        var vehicle = vehicle(19500.0); // only 500 km since last service
        var latest = Map.of("ENGINE_OIL_CHANGE",
                new MaintenanceEventDto(1L, 1L, "ENGINE_OIL_CHANGE", 19000.0, LocalDate.now().minusMonths(10), null, "manual", OffsetDateTime.now()));

        var card = cardFor("ENGINE_OIL_CHANGE", service.buildDashboard(vehicle, latest));

        assertThat(card.remainingKm()).isEqualTo(5500.0); // distance alone looks fine
        assertThat(card.status()).isEqualTo(MaintenanceStatusService.Status.OVERDUE); // but time has lapsed
    }

    private MaintenanceStatusService.StatusCard cardFor(String serviceType, List<MaintenanceStatusService.StatusCard> cards) {
        return cards.stream().filter(c -> c.serviceType().equals(serviceType)).findFirst()
                .orElseThrow(() -> new AssertionError("no card for " + serviceType));
    }
}
