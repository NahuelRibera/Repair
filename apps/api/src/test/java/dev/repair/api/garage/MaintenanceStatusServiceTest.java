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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void missingLastEventWithKnownIntervalIsIntervalKnownNoHistory_notUnknown() {
        // "We know the interval but not the rider's history" must be a
        // distinct state from "we don't know the interval at all" — see
        // docs/maintenance-tracking.md. 23,500 km current, 6,000 km
        // interval, no recorded event: assuming no prior service, the
        // next scheduled point is the interval itself (6,000 km) and
        // 23,500 already exceeds it — remaining is only shown when the
        // rider hasn't yet reached one full interval, so here it should
        // stay unset rather than claim a fabricated overdue amount.
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.of(
                "ENGINE_OIL_INTERVAL_KM", new MotorcycleFactDto("ENGINE_OIL_INTERVAL_KM", 6000.0, null, "km")
        ));
        var vehicle = vehicle(23500.0);

        var card = cardFor("ENGINE_OIL_CHANGE", service.buildDashboard(vehicle, Map.of()));

        assertThat(card.status()).isEqualTo(MaintenanceStatusService.Status.INTERVAL_KNOWN_NO_HISTORY);
        assertThat(card.intervalKm()).isEqualTo(6000.0);
        assertThat(card.lastOdometerKm()).isNull();
        assertThat(card.note()).isEqualTo("No previous service recorded");
    }

    @Test
    void missingLastEventButOdometerWithinFirstInterval_showsNextScheduled() {
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.of(
                "VALVE_CLEARANCE_INTERVAL_KM", new MotorcycleFactDto("VALVE_CLEARANCE_INTERVAL_KM", 42000.0, null, "km")
        ));
        var vehicle = vehicle(18500.0);

        var card = cardFor("VALVE_CLEARANCE_CHECK", service.buildDashboard(vehicle, Map.of()));

        assertThat(card.status()).isEqualTo(MaintenanceStatusService.Status.INTERVAL_KNOWN_NO_HISTORY);
        assertThat(card.intervalKm()).isEqualTo(42000.0);
        assertThat(card.remainingKm()).isEqualTo(23500.0); // 42,000 - 18,500, matching the spec's own worked example
        assertThat(card.note()).isEqualTo("No previous service recorded");
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

    @Test
    void serviceRecordedAboveCurrentOdometer_isFlaggedInconsistentNotSilentlyCalculated() {
        // Section 2.6 of the productization pass: a historical event at
        // 25,000 km with a current odometer of 18,500 km is impossible —
        // never compute a "remaining" figure from it.
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.of(
                "ENGINE_OIL_INTERVAL_KM", new MotorcycleFactDto("ENGINE_OIL_INTERVAL_KM", 6000.0, null, "km")
        ));
        var vehicle = vehicle(18500.0);
        var latest = Map.of("ENGINE_OIL_CHANGE", oilEvent(25000.0, null));

        var card = cardFor("ENGINE_OIL_CHANGE", service.buildDashboard(vehicle, latest));

        assertThat(card.status()).isEqualTo(MaintenanceStatusService.Status.DATA_INCONSISTENT);
        assertThat(card.remainingKm()).isNull();
        assertThat(card.note()).contains("25000").contains("18500").contains("Check one of these values");
    }

    @Test
    void serviceRecordedAtOrBelowCurrentOdometer_isNotFlaggedInconsistent() {
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.of(
                "ENGINE_OIL_INTERVAL_KM", new MotorcycleFactDto("ENGINE_OIL_INTERVAL_KM", 6000.0, null, "km")
        ));
        var vehicle = vehicle(25000.0);
        var latest = Map.of("ENGINE_OIL_CHANGE", oilEvent(19000.0, null));

        var card = cardFor("ENGINE_OIL_CHANGE", service.buildDashboard(vehicle, latest));

        assertThat(card.status()).isNotEqualTo(MaintenanceStatusService.Status.DATA_INCONSISTENT);
    }

    @Test
    void factMappingMatrix_recognizesEveryExtractedIntervalType() {
        // The dashboard must map every fact type ingestion can actually
        // extract into its corresponding service — see
        // docs/knowledge-ingestion.md and section 33 of the QA pass.
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.ofEntries(
                Map.entry("ENGINE_OIL_INTERVAL_KM", new MotorcycleFactDto("ENGINE_OIL_INTERVAL_KM", 6000.0, null, "km")),
                Map.entry("ENGINE_OIL_INTERVAL_MONTHS", new MotorcycleFactDto("ENGINE_OIL_INTERVAL_MONTHS", 6.0, null, "months")),
                Map.entry("OIL_FILTER_INTERVAL_KM", new MotorcycleFactDto("OIL_FILTER_INTERVAL_KM", 12000.0, null, "km")),
                Map.entry("OIL_FILTER_INTERVAL_MONTHS", new MotorcycleFactDto("OIL_FILTER_INTERVAL_MONTHS", 12.0, null, "months")),
                Map.entry("AIR_FILTER_INTERVAL_KM", new MotorcycleFactDto("AIR_FILTER_INTERVAL_KM", 37000.0, null, "km")),
                Map.entry("CHAIN_LUBE_INTERVAL_KM", new MotorcycleFactDto("CHAIN_LUBE_INTERVAL_KM", 1000.0, null, "km")),
                Map.entry("SPARK_PLUG_REPLACE_INTERVAL_KM", new MotorcycleFactDto("SPARK_PLUG_REPLACE_INTERVAL_KM", 19000.0, null, "km")),
                Map.entry("SPARK_PLUG_REPLACE_INTERVAL_MONTHS", new MotorcycleFactDto("SPARK_PLUG_REPLACE_INTERVAL_MONTHS", 18.0, null, "months")),
                Map.entry("VALVE_CLEARANCE_INTERVAL_KM", new MotorcycleFactDto("VALVE_CLEARANCE_INTERVAL_KM", 42000.0, null, "km")),
                Map.entry("BRAKE_FLUID_INTERVAL_MONTHS", new MotorcycleFactDto("BRAKE_FLUID_INTERVAL_MONTHS", 24.0, null, "months")),
                Map.entry("COOLANT_CHANGE_INTERVAL_MONTHS", new MotorcycleFactDto("COOLANT_CHANGE_INTERVAL_MONTHS", 36.0, null, "months"))
        ));
        var vehicle = vehicle(500.0); // low odometer so every km interval is still "first interval, no history"

        var cards = service.buildDashboard(vehicle, Map.of());

        assertThat(cardFor("ENGINE_OIL_CHANGE", cards).intervalKm()).isEqualTo(6000.0);
        assertThat(cardFor("OIL_FILTER_CHANGE", cards).intervalKm()).isEqualTo(12000.0);
        assertThat(cardFor("AIR_FILTER_CHANGE", cards).intervalKm()).isEqualTo(37000.0);
        assertThat(cardFor("CHAIN_LUBE", cards).intervalKm()).isEqualTo(1000.0);
        assertThat(cardFor("SPARK_PLUG_CHANGE", cards).intervalKm()).isEqualTo(19000.0);
        assertThat(cardFor("SPARK_PLUG_CHANGE", cards).intervalMonths()).isEqualTo(18.0);
        assertThat(cardFor("VALVE_CLEARANCE_CHECK", cards).intervalKm()).isEqualTo(42000.0);
        assertThat(cardFor("BRAKE_FLUID_CHANGE", cards).intervalMonths()).isEqualTo(24.0);
        assertThat(cardFor("COOLANT_CHANGE", cards).intervalMonths()).isEqualTo(36.0);
        // Every mapped card is at least INTERVAL_KNOWN_NO_HISTORY, never a
        // bare UNKNOWN, now that a real interval fact exists for it.
        for (String type : List.of("ENGINE_OIL_CHANGE", "OIL_FILTER_CHANGE", "AIR_FILTER_CHANGE", "CHAIN_LUBE",
                "SPARK_PLUG_CHANGE", "VALVE_CLEARANCE_CHECK", "BRAKE_FLUID_CHANGE", "COOLANT_CHANGE")) {
            assertThat(cardFor(type, cards).status()).isNotEqualTo(MaintenanceStatusService.Status.UNKNOWN);
        }
    }

    @Test
    void conditionBasedServiceTypes_areConditionBasedNotUnknown() {
        // CHAIN_ADJUSTMENT, TIRE_REPLACEMENT, and BATTERY_REPLACEMENT are
        // condition/wear-based in the real knowledge base, not scheduled
        // intervals — they must never be assigned a fabricated interval,
        // but they aren't "unknown" either.
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.of(
                "ENGINE_OIL_INTERVAL_KM", new MotorcycleFactDto("ENGINE_OIL_INTERVAL_KM", 6000.0, null, "km")
        ));
        var vehicle = vehicle(23500.0);

        var cards = service.buildDashboard(vehicle, Map.of());

        for (String type : List.of("CHAIN_ADJUSTMENT", "TIRE_REPLACEMENT", "BATTERY_REPLACEMENT")) {
            var card = cardFor(type, cards);
            assertThat(card.status()).isEqualTo(MaintenanceStatusService.Status.CONDITION_BASED);
            assertThat(card.intervalKm()).isNull();
            assertThat(card.intervalMonths()).isNull();
            assertThat(card.remainingKm()).isNull();
        }
    }

    @Test
    void conditionBasedServiceType_keepsRecordedHistory() {
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.of());
        var vehicle = vehicle(25000.0);
        var tires = new MaintenanceEventDto(3L, 1L, "TIRE_REPLACEMENT", 23300.0, null, "Rear tire", "manual", OffsetDateTime.now());

        var card = cardFor("TIRE_REPLACEMENT", service.buildDashboard(vehicle, Map.of("TIRE_REPLACEMENT", tires)));

        assertThat(card.status()).isEqualTo(MaintenanceStatusService.Status.CONDITION_BASED);
        assertThat(card.lastOdometerKm()).isEqualTo(23300.0);
    }

    @Test
    void noIntervalButRecordedHistory_isTrackedNotUnknown() {
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.of());
        var vehicle = vehicle(23500.0);
        var latest = Map.of("SPARK_PLUG_CHANGE",
                new MaintenanceEventDto(4L, 1L, "SPARK_PLUG_CHANGE", 13000.0, null, null, "manual", OffsetDateTime.now()));

        var card = cardFor("SPARK_PLUG_CHANGE", service.buildDashboard(vehicle, latest));

        assertThat(card.status()).isEqualTo(MaintenanceStatusService.Status.TRACKED);
        assertThat(card.lastOdometerKm()).isEqualTo(13000.0);
        assertThat(card.intervalKm()).isNull();
        assertThat(card.remainingKm()).isNull();
    }

    @Test
    void oilFilterScheduleIsCalculatedFromTheLastFilterChangeNotTheLastOilChange() {
        // MT-07 2021: filter replacement points 13,000 km / 12 months and
        // 25,000 km / 24 months -> 12,000 km / 12 months (extracted by
        // pipelines/embeddings/motorcycle_facts.py). Oil and filter are
        // separate events; only the filter event drives this card.
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(Map.of(
                "OIL_FILTER_INTERVAL_KM", new MotorcycleFactDto("OIL_FILTER_INTERVAL_KM", 12000.0, null, "km"),
                "OIL_FILTER_INTERVAL_MONTHS", new MotorcycleFactDto("OIL_FILTER_INTERVAL_MONTHS", 12.0, null, "months")
        ));
        var vehicle = vehicle(24000.0);
        var latest = Map.of(
                "ENGINE_OIL_CHANGE", oilEvent(23000.0, LocalDate.now().minusMonths(1)),
                "OIL_FILTER_CHANGE", new MaintenanceEventDto(
                        5L, 1L, "OIL_FILTER_CHANGE", 13000.0, LocalDate.now().minusMonths(3), null, "manual", OffsetDateTime.now()));

        var card = cardFor("OIL_FILTER_CHANGE", service.buildDashboard(vehicle, latest));

        assertThat(card.intervalKm()).isEqualTo(12000.0);
        assertThat(card.remainingKm()).isEqualTo(1000.0);
        assertThat(card.status()).isEqualTo(MaintenanceStatusService.Status.DUE_SOON);
    }

    // --- Manufacturer schedule points (MT-07 2021 oil filter / spark plugs) ---
    //
    // MT-07 2021 lists fixed points: oil filter at 1,000, 13,000, 25,000 km
    // and on; spark-plug replacement at 13,000, 25,000 km and on. The
    // status and "remaining" stay based on recorded service (last event +
    // 12,000 km); scheduledNextKm is the separate manufacturer-schedule
    // figure. No tolerance is applied for early or late services.

    private static final double SCHEDULE_STEP_KM = 12000.0;

    private Map<String, MotorcycleFactDto> mt07_2021ScheduleFacts() {
        return Map.ofEntries(
                Map.entry("ENGINE_OIL_INTERVAL_KM", new MotorcycleFactDto("ENGINE_OIL_INTERVAL_KM", 6000.0, null, "km")),
                Map.entry("OIL_FILTER_INTERVAL_KM", new MotorcycleFactDto("OIL_FILTER_INTERVAL_KM", SCHEDULE_STEP_KM, null, "km")),
                Map.entry("OIL_FILTER_INITIAL_POINT_KM", new MotorcycleFactDto("OIL_FILTER_INITIAL_POINT_KM", 1000.0, null, "km")),
                Map.entry("OIL_FILTER_SCHEDULE_START_KM", new MotorcycleFactDto("OIL_FILTER_SCHEDULE_START_KM", 13000.0, null, "km")),
                Map.entry("SPARK_PLUG_REPLACE_INTERVAL_KM",
                        new MotorcycleFactDto("SPARK_PLUG_REPLACE_INTERVAL_KM", SCHEDULE_STEP_KM, null, "km")),
                Map.entry("SPARK_PLUG_SCHEDULE_START_KM", new MotorcycleFactDto("SPARK_PLUG_SCHEDULE_START_KM", 13000.0, null, "km"))
        );
    }

    private MaintenanceStatusService.StatusCard scheduledCard(String serviceType, double odometerKm, Double lastServiceKm) {
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(mt07_2021ScheduleFacts());
        Map<String, MaintenanceEventDto> latest = lastServiceKm == null ? Map.of() : Map.of(serviceType,
                new MaintenanceEventDto(9L, 1L, serviceType, lastServiceKm, null, null, "manual", OffsetDateTime.now()));
        return cardFor(serviceType, service.buildDashboard(vehicle(odometerKm), latest));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OIL_FILTER_CHANGE", "SPARK_PLUG_CHANGE"})
    void schedule_noHistoryBeforeFirstRegularPoint_usesTheFixedPointNotAZeroBasedInterval(String serviceType) {
        var card = scheduledCard(serviceType, 5000.0, null);

        assertThat(card.status()).isEqualTo(MaintenanceStatusService.Status.INTERVAL_KNOWN_NO_HISTORY);
        assertThat(card.scheduledNextKm()).isEqualTo(13000.0); // not the 12,000 km "interval mark"
        assertThat(card.scheduledRemainingKm()).isEqualTo(8000.0);
        assertThat(card.remainingKm()).isNull();
    }

    @Test
    void schedule_oilFilterNoHistoryBeforeInitialService_usesTheInitialPoint() {
        var card = scheduledCard("OIL_FILTER_CHANGE", 600.0, null);

        assertThat(card.scheduledNextKm()).isEqualTo(1000.0);
        assertThat(card.scheduledRemainingKm()).isEqualTo(400.0);
    }

    @Test
    void schedule_sparkPlugsHaveNoInitialPoint_soNoHistoryBelow1000KmStillPointsAt13000() {
        var card = scheduledCard("SPARK_PLUG_CHANGE", 600.0, null);

        assertThat(card.scheduledNextKm()).isEqualTo(13000.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OIL_FILTER_CHANGE", "SPARK_PLUG_CHANGE"})
    void schedule_servicePerformedEarly_keepsRecordedCalculationAndExposesTheManufacturerPoint(String serviceType) {
        var card = scheduledCard(serviceType, 12400.0, 8000.0);

        // Based on recorded service: 8,000 + 12,000 = 20,000 km.
        assertThat(card.remainingKm()).isEqualTo(7600.0);
        assertThat(card.status()).isEqualTo(MaintenanceStatusService.Status.OK);
        // Manufacturer schedule: the next fixed point after 8,000 km is 13,000 km — not moved.
        assertThat(card.scheduledNextKm()).isEqualTo(13000.0);
        assertThat(card.scheduledRemainingKm()).isEqualTo(600.0);
        assertThat(card.note()).contains("8000 km").contains("not on a manufacturer schedule point");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OIL_FILTER_CHANGE", "SPARK_PLUG_CHANGE"})
    void schedule_servicePerformedExactlyOnAPoint_bothFiguresAgree(String serviceType) {
        var card = scheduledCard(serviceType, 14000.0, 13000.0);

        assertThat(card.remainingKm()).isEqualTo(11000.0); // 25,000 - 14,000
        assertThat(card.scheduledNextKm()).isEqualTo(25000.0);
        assertThat(card.scheduledRemainingKm()).isEqualTo(11000.0);
        assertThat(card.note()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OIL_FILTER_CHANGE", "SPARK_PLUG_CHANGE"})
    void schedule_servicePerformedLate_doesNotShiftTheManufacturerPoint(String serviceType) {
        var card = scheduledCard(serviceType, 15000.0, 14000.0);

        assertThat(card.remainingKm()).isEqualTo(11000.0); // recorded: 14,000 + 12,000 = 26,000
        assertThat(card.scheduledNextKm()).isEqualTo(25000.0);
        assertThat(card.scheduledRemainingKm()).isEqualTo(10000.0);
        assertThat(card.note()).contains("not on a manufacturer schedule point");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OIL_FILTER_CHANGE", "SPARK_PLUG_CHANGE"})
    void schedule_subsequentCycles_continueEveryStepFromTheStartPoint(String serviceType) {
        // On-point service in a later cycle: 37,000 -> next 49,000, aligned.
        var onPoint = scheduledCard(serviceType, 38000.0, 37000.0);
        assertThat(onPoint.scheduledNextKm()).isEqualTo(49000.0);
        assertThat(onPoint.note()).isNull();

        // Off-point service in a later cycle: 50,000 -> next point 61,000, recorded 62,000.
        var offPoint = scheduledCard(serviceType, 51000.0, 50000.0);
        assertThat(offPoint.scheduledNextKm()).isEqualTo(61000.0);
        assertThat(offPoint.remainingKm()).isEqualTo(11000.0);
        assertThat(offPoint.note()).isNotNull();

        // No history past the first cycle: next point at or after 30,000 is 37,000.
        var noHistory = scheduledCard(serviceType, 30000.0, null);
        assertThat(noHistory.scheduledNextKm()).isEqualTo(37000.0);

        // Exactly on a point with no history: that point is the next one, 0 km away.
        var atPoint = scheduledCard(serviceType, 25000.0, null);
        assertThat(atPoint.scheduledNextKm()).isEqualTo(25000.0);
        assertThat(atPoint.scheduledRemainingKm()).isEqualTo(0.0);
    }

    @Test
    void schedule_engineOilStaysAResettableInterval() {
        when(catalogRepository.findFacts(MODEL_ID, YEAR)).thenReturn(mt07_2021ScheduleFacts());
        var withHistory = cardFor("ENGINE_OIL_CHANGE",
                service.buildDashboard(vehicle(12400.0), Map.of("ENGINE_OIL_CHANGE", oilEvent(8000.0, null))));
        assertThat(withHistory.remainingKm()).isEqualTo(1600.0); // 8,000 + 6,000 - 12,400
        assertThat(withHistory.scheduledNextKm()).isNull();
        assertThat(withHistory.note()).isNull();

        var noHistory = cardFor("ENGINE_OIL_CHANGE", service.buildDashboard(vehicle(5000.0), Map.of()));
        assertThat(noHistory.remainingKm()).isEqualTo(1000.0); // unchanged interval-mark behaviour
        assertThat(noHistory.scheduledNextKm()).isNull();
    }

    private MaintenanceStatusService.StatusCard cardFor(String serviceType, List<MaintenanceStatusService.StatusCard> cards) {
        return cards.stream().filter(c -> c.serviceType().equals(serviceType)).findFirst()
                .orElseThrow(() -> new AssertionError("no card for " + serviceType));
    }
}
