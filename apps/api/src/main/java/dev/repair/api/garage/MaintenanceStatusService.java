package dev.repair.api.garage;

import dev.repair.api.motorcycle.MotorcycleCatalogRepository;
import dev.repair.api.motorcycle.MotorcycleFactDto;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Pure, deterministic maintenance-due computation — no LLM involved. Every
 * number here comes from motorcycle_facts (ingestion-extracted, see
 * docs/repair-v2-architecture.md section 4) and maintenance_events
 * (user-confirmed history); nothing is invented when data is missing —
 * see the DUE_STATUS_UNKNOWN cases below, which surface as "Not recorded"
 * rather than a fabricated status.
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
     * service on this bike at all"; INTERVAL_KNOWN_NO_HISTORY means "we
     * know the interval, we just don't have a recorded previous service
     * to measure from" — the dashboard renders these very differently.
     */
    public enum Status { UNKNOWN, INTERVAL_KNOWN_NO_HISTORY, OK, DUE_SOON, DUE, OVERDUE }

    public record StatusCard(
            String serviceType, Status status, Double lastOdometerKm, LocalDate lastPerformedAt,
            Double intervalKm, Double remainingKm, Double intervalMonths, Double remainingMonths, String note
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
            return new StatusCard(serviceType, Status.UNKNOWN, lastEvent == null ? null : lastEvent.odometerKm(),
                    lastEvent == null ? null : lastEvent.performedAt(), null, null, null, null,
                    "No verified interval for this service on this bike");
        }

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
            Double remainingKm = null;
            if (intervalKm != null && vehicle.currentOdometerKm() != null && vehicle.currentOdometerKm() < intervalKm) {
                remainingKm = intervalKm - vehicle.currentOdometerKm();
            }
            return new StatusCard(serviceType, Status.INTERVAL_KNOWN_NO_HISTORY, null, null,
                    intervalKm, remainingKm, intervalMonths, null, "No previous service recorded");
        }

        Dimension byDistance = evaluateDistance(vehicle, lastEvent, intervalKm);
        Dimension byTime = evaluateTime(lastEvent, intervalMonths);
        Dimension worse = worseOf(byDistance, byTime);

        return new StatusCard(
                serviceType, worse.status, lastEvent.odometerKm(), lastEvent.performedAt(),
                intervalKm, byDistance.remaining, intervalMonths, byTime.remaining, null
        );
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

    private Double factNumeric(Map<String, MotorcycleFactDto> facts, String factType) {
        if (factType == null) {
            return null;
        }
        MotorcycleFactDto fact = facts.get(factType);
        return fact == null ? null : fact.valueNumeric();
    }
}
