package dev.repair.api.garage;

import dev.repair.api.motorcycle.MotorcycleCatalogRepository;
import dev.repair.api.motorcycle.MotorcycleFactDto;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Pure, deterministic maintenance-due computation — no LLM involved. Every
 * number here comes from motorcycle_facts (ingestion-extracted, see
 * docs/repair-v2-architecture.md section 4) and maintenance_events
 * (user-confirmed history); nothing is invented when data is missing —
 * see the UNKNOWN / CONDITION_BASED / TRACKED cases below, which surface
 * as plain labels rather than a fabricated status.
 */
@Service
public class MaintenanceStatusService {

    /** Distance-based interval fact per service type, or null if this
     * service type has no reliably-extracted distance interval. Extend
     * this map (and MONTH_INTERVAL_FACT below) as ingestion learns to
     * extract more fact types — never hardcode a value here, only a
     * fact_type key to look up. */
    private static final Map<String, String> KM_INTERVAL_FACT = Map.of(
            "ENGINE_OIL_CHANGE", "ENGINE_OIL_INTERVAL_KM",
            "VALVE_CLEARANCE_CHECK", "VALVE_CLEARANCE_INTERVAL_KM",
            "OIL_FILTER_CHANGE", "OIL_FILTER_INTERVAL_KM",
            "AIR_FILTER_CHANGE", "AIR_FILTER_INTERVAL_KM",
            "CHAIN_LUBE", "CHAIN_LUBE_INTERVAL_KM",
            "SPARK_PLUG_CHANGE", "SPARK_PLUG_REPLACE_INTERVAL_KM"
    );

    /** Time-based interval fact (in months) per service type, or null. */
    private static final Map<String, String> MONTH_INTERVAL_FACT = Map.of(
            "ENGINE_OIL_CHANGE", "ENGINE_OIL_INTERVAL_MONTHS",
            "COOLANT_CHANGE", "COOLANT_CHANGE_INTERVAL_MONTHS",
            "BRAKE_FLUID_CHANGE", "BRAKE_FLUID_INTERVAL_MONTHS",
            "OIL_FILTER_CHANGE", "OIL_FILTER_INTERVAL_MONTHS",
            "SPARK_PLUG_CHANGE", "SPARK_PLUG_REPLACE_INTERVAL_MONTHS"
    );

    /** First regular manufacturer schedule point (km) per service type, for
     * services whose knowledge file lists fixed points (e.g. MT-07 oil
     * filter at 13,000 / 25,000 km) rather than a plain "every X km". The
     * points repeat every KM_INTERVAL_FACT km from here. Engine oil is
     * deliberately absent: its source wording is a resettable interval. */
    private static final Map<String, String> SCHEDULE_START_FACT = Map.of(
            "OIL_FILTER_CHANGE", "OIL_FILTER_SCHEDULE_START_KM",
            "SPARK_PLUG_CHANGE", "SPARK_PLUG_SCHEDULE_START_KM"
    );

    /** Optional one-off initial-service point (km) before SCHEDULE_START_FACT. */
    private static final Map<String, String> INITIAL_POINT_FACT = Map.of(
            "OIL_FILTER_CHANGE", "OIL_FILTER_INITIAL_POINT_KM"
    );

    /** Service types the knowledge base treats as inspection/condition-driven
     * (tread depth or damage, chain slack out of spec, a battery that no
     * longer holds charge) rather than scheduled. Only consulted when no
     * interval fact exists for the service, so a verified interval still
     * wins if ingestion ever maps one. */
    private static final Set<String> CONDITION_BASED = Set.of(
            "TIRE_REPLACEMENT", "CHAIN_ADJUSTMENT", "BATTERY_REPLACEMENT"
    );

    /** Below this fraction of the interval remaining, a service is "due
     * soon"; at/below zero-minus-this-fraction it's "overdue" rather than
     * merely "due". A simple, single, explainable threshold rather than a
     * multi-factor score (see spec section 27's "avoid arbitrary overly
     * complicated scoring"). */
    private static final double DUE_SOON_FRACTION = 0.2;

    /**
     * UNKNOWN and INTERVAL_KNOWN_NO_HISTORY are deliberately distinct —
     * see docs/maintenance-tracking.md "interval known vs. history
     * unknown". UNKNOWN means "we have no verified interval for this
     * service on this bike and nothing recorded"; INTERVAL_KNOWN_NO_HISTORY
     * means "we know the interval, we just don't have a recorded previous
     * service to measure from" — the dashboard renders these very
     * differently. Without an interval, CONDITION_BASED marks services
     * maintained on inspection rather than a schedule, and TRACKED marks
     * any other service with recorded history but no next-due calculation.
     */
    public enum Status { UNKNOWN, CONDITION_BASED, TRACKED, INTERVAL_KNOWN_NO_HISTORY, OK, DUE_SOON, DUE, OVERDUE, DATA_INCONSISTENT }

    /**
     * remainingKm/remainingMonths and the status are always "based on
     * recorded service" (last event + interval). scheduledNextKm is the
     * separate "manufacturer schedule" figure for services with fixed
     * schedule points: the first point after the last recorded service, or,
     * with no history, the first point at or after the current odometer.
     * Both are exposed side by side — neither silently overrides the other,
     * and no tolerance is assumed for an early or late service.
     */
    public record StatusCard(
            String serviceType, Status status, Double lastOdometerKm, LocalDate lastPerformedAt,
            Double intervalKm, Double remainingKm, Double intervalMonths, Double remainingMonths, String note,
            Double scheduledNextKm, Double scheduledRemainingKm
    ) {
    }

    private final MotorcycleCatalogRepository catalogRepository;

    public MaintenanceStatusService(MotorcycleCatalogRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    public List<StatusCard> buildDashboard(GarageVehicleDto vehicle, Map<String, MaintenanceEventDto> latestByType) {
        Map<String, MotorcycleFactDto> facts = catalogRepository.findFacts(vehicle.modelId(), vehicle.year());
        List<StatusCard> cards = new ArrayList<>();
        for (String serviceType : ServiceType.VALID) {
            if ("OTHER".equals(serviceType)) {
                continue;
            }
            cards.add(buildCard(serviceType, vehicle, latestByType.get(serviceType), facts));
        }
        return cards;
    }

    private StatusCard buildCard(
            String serviceType, GarageVehicleDto vehicle, MaintenanceEventDto lastEvent, Map<String, MotorcycleFactDto> facts
    ) {
        Double intervalKm = factNumeric(facts, KM_INTERVAL_FACT.get(serviceType));
        Double intervalMonths = factNumeric(facts, MONTH_INTERVAL_FACT.get(serviceType));

        if (intervalKm == null && intervalMonths == null) {
            Double lastOdometerKm = lastEvent == null ? null : lastEvent.odometerKm();
            LocalDate lastPerformedAt = lastEvent == null ? null : lastEvent.performedAt();
            if (CONDITION_BASED.contains(serviceType)) {
                return new StatusCard(serviceType, Status.CONDITION_BASED, lastOdometerKm, lastPerformedAt,
                        null, null, null, null, "Serviced based on inspection and condition, not a fixed interval",
                        null, null);
            }
            if (lastEvent != null) {
                return new StatusCard(serviceType, Status.TRACKED, lastOdometerKm, lastPerformedAt,
                        null, null, null, null, "No verified interval to calculate the next service", null, null);
            }
            return new StatusCard(serviceType, Status.UNKNOWN, null, null, null, null, null, null,
                    "No verified interval for this service on this bike", null, null);
        }

        if (lastEvent != null && lastEvent.odometerKm() != null && vehicle.currentOdometerKm() != null
                && lastEvent.odometerKm() > vehicle.currentOdometerKm()) {
            // Impossible history: a recorded service at a higher mileage than
            // the bike's current odometer — almost always stale/incorrect
            // data (an odometer correction after the fact, a typo, or dirty
            // data from an earlier, buggier version of the app). Never
            // silently compute a "remaining" figure from this — that would
            // produce a confidently wrong answer. Surface it plainly instead
            // and let the rider fix whichever value is wrong.
            return new StatusCard(serviceType, Status.DATA_INCONSISTENT, lastEvent.odometerKm(), lastEvent.performedAt(),
                    intervalKm, null, intervalMonths, null,
                    "Recorded service at " + formatKm(lastEvent.odometerKm()) + " km is higher than the current "
                            + "odometer (" + formatKm(vehicle.currentOdometerKm()) + " km). Check one of these values.",
                    null, null);
        }

        Double scheduleStartKm = factNumeric(facts, SCHEDULE_START_FACT.get(serviceType));
        Double initialPointKm = factNumeric(facts, INITIAL_POINT_FACT.get(serviceType));
        boolean scheduleAnchored = scheduleStartKm != null && intervalKm != null;
        Double currentKm = vehicle.currentOdometerKm();

        if (lastEvent == null) {
            // We know the interval but have no recorded previous service to
            // measure from — this is NOT the same as "unknown interval"
            // (see the Status enum javadoc). Where the current odometer is
            // known and hasn't yet reached one full interval, show a
            // best-effort "next scheduled at the interval mark" figure —
            // clearly caveated as assuming no prior service, never as a
            // confirmed due date. Never claim overdue from this assumption
            // alone; an unverified guess of "overdue" is worse than an
            // honest "we don't know."
            //
            // Schedule-anchored services never use that interval-mark guess:
            // their points don't start at zero (MT-07 oil filter: 1,000 then
            // 13,000 km, not 12,000), so the next real manufacturer point at
            // or after the current odometer is shown instead.
            if (scheduleAnchored) {
                Double scheduledNextKm = currentKm == null ? null
                        : nextSchedulePoint(currentKm, true, initialPointKm, scheduleStartKm, intervalKm);
                return new StatusCard(serviceType, Status.INTERVAL_KNOWN_NO_HISTORY, null, null,
                        intervalKm, null, intervalMonths, null, "No previous service recorded",
                        scheduledNextKm, scheduledNextKm == null ? null : scheduledNextKm - currentKm);
            }
            Double remainingKm = null;
            if (intervalKm != null && currentKm != null && currentKm < intervalKm) {
                remainingKm = intervalKm - currentKm;
            }
            return new StatusCard(serviceType, Status.INTERVAL_KNOWN_NO_HISTORY, null, null,
                    intervalKm, remainingKm, intervalMonths, null, "No previous service recorded", null, null);
        }

        Dimension byDistance = evaluateDistance(vehicle, lastEvent, intervalKm);
        Dimension byTime = evaluateTime(lastEvent, intervalMonths);
        Dimension worse = worseOf(byDistance, byTime);

        Double scheduledNextKm = null;
        Double scheduledRemainingKm = null;
        String note = null;
        if (scheduleAnchored && lastEvent.odometerKm() != null) {
            scheduledNextKm = nextSchedulePoint(lastEvent.odometerKm(), false, initialPointKm, scheduleStartKm, intervalKm);
            scheduledRemainingKm = currentKm == null ? null : scheduledNextKm - currentKm;
            if (scheduledNextKm != lastEvent.odometerKm() + intervalKm) {
                note = "Last service at " + formatKm(lastEvent.odometerKm()) + " km was not on a manufacturer "
                        + "schedule point, so the recorded-service and manufacturer-schedule figures differ.";
            }
        }

        return new StatusCard(
                serviceType, worse.status, lastEvent.odometerKm(), lastEvent.performedAt(),
                intervalKm, byDistance.remaining, intervalMonths, byTime.remaining, note,
                scheduledNextKm, scheduledRemainingKm
        );
    }

    /**
     * The first manufacturer schedule point after {@code km} (or at it, when
     * {@code inclusive}): the optional initial point, then startKm, then
     * every stepKm after that. Pure arithmetic over the extracted points —
     * no tolerance for "close enough" early or late services.
     */
    static double nextSchedulePoint(double km, boolean inclusive, Double initialPointKm, double startKm, double stepKm) {
        if (initialPointKm != null && (inclusive ? km <= initialPointKm : km < initialPointKm)) {
            return initialPointKm;
        }
        if (inclusive ? km <= startKm : km < startKm) {
            return startKm;
        }
        double lastPointAtOrBefore = startKm + Math.floor((km - startKm) / stepKm) * stepKm;
        return inclusive && lastPointAtOrBefore == km ? lastPointAtOrBefore : lastPointAtOrBefore + stepKm;
    }

    private record Dimension(Status status, Double remaining) {
        static final Dimension UNKNOWN = new Dimension(Status.UNKNOWN, null);
    }

    private Dimension evaluateDistance(GarageVehicleDto vehicle, MaintenanceEventDto lastEvent, Double intervalKm) {
        if (intervalKm == null || vehicle.currentOdometerKm() == null || lastEvent == null || lastEvent.odometerKm() == null) {
            return Dimension.UNKNOWN;
        }
        double distanceSince = vehicle.currentOdometerKm() - lastEvent.odometerKm();
        double remaining = intervalKm - distanceSince;
        return new Dimension(statusFor(remaining, intervalKm), remaining);
    }

    private Dimension evaluateTime(MaintenanceEventDto lastEvent, Double intervalMonths) {
        if (intervalMonths == null || lastEvent == null || lastEvent.performedAt() == null) {
            return Dimension.UNKNOWN;
        }
        int monthsSince = Period.between(lastEvent.performedAt(), LocalDate.now()).getYears() * 12
                + Period.between(lastEvent.performedAt(), LocalDate.now()).getMonths();
        double remaining = intervalMonths - monthsSince;
        return new Dimension(statusFor(remaining, intervalMonths), remaining);
    }

    private Status statusFor(double remaining, double interval) {
        double threshold = interval * DUE_SOON_FRACTION;
        if (remaining > threshold) {
            return Status.OK;
        }
        if (remaining > 0) {
            return Status.DUE_SOON;
        }
        if (remaining > -threshold) {
            return Status.DUE;
        }
        return Status.OVERDUE;
    }

    private Dimension worseOf(Dimension a, Dimension b) {
        if (a.status == Status.UNKNOWN) return b;
        if (b.status == Status.UNKNOWN) return a;
        return a.status.ordinal() >= b.status.ordinal() ? a : b;
    }

    private static String formatKm(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private Double factNumeric(Map<String, MotorcycleFactDto> facts, String factType) {
        if (factType == null) {
            return null;
        }
        MotorcycleFactDto fact = facts.get(factType);
        return fact == null ? null : fact.valueNumeric();
    }
}
