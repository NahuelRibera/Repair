package dev.repair.api.garage;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MaintenanceRepository {

    private final JdbcClient jdbcClient;

    public MaintenanceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Caller (controller or chat orchestration) must have already
     * verified garageVehicleId belongs to the current authenticated user — this
     * mirrors dev.repair.api.chat.RetrievalService trusting an
     * already-ownership-checked variantId, rather than re-deriving
     * ownership from a value the caller already resolved. */
    public long createEvent(
            long garageVehicleId, String serviceType, Double odometerKm, LocalDate performedAt, String notes, String createdVia
    ) {
        return jdbcClient.sql(
                        """
                        INSERT INTO maintenance_events (garage_vehicle_id, service_type, odometer_km, performed_at, notes, created_via)
                        VALUES (:garageVehicleId, :serviceType, :odometerKm, :performedAt, :notes, :createdVia)
                        RETURNING id
                        """)
                .param("garageVehicleId", garageVehicleId)
                .param("serviceType", serviceType)
                .param("odometerKm", odometerKm)
                .param("performedAt", performedAt)
                .param("notes", notes)
                .param("createdVia", createdVia)
                .query(Long.class)
                .single();
    }

    /** Replaces the editable fields of one event, scoped by owner: the row
     * is touched only when its vehicle belongs to userId and is not
     * deleted. Empty when no such event exists for this user and vehicle. */
    public Optional<MaintenanceEventDto> updateOwnedEvent(
            long userId, long garageVehicleId, long eventId, String serviceType, Double odometerKm, LocalDate performedAt, String notes
    ) {
        return jdbcClient.sql(
                        """
                        UPDATE maintenance_events e
                        SET service_type = :serviceType, odometer_km = :odometerKm, performed_at = :performedAt, notes = :notes
                        FROM garage_vehicles g
                        WHERE e.id = :eventId AND e.garage_vehicle_id = :garageVehicleId
                          AND g.id = e.garage_vehicle_id AND g.user_id = :userId AND g.deleted_at IS NULL
                        RETURNING e.id, e.garage_vehicle_id, e.service_type, e.odometer_km, e.performed_at,
                                  e.notes, e.created_via, e.created_at
                        """)
                .param("serviceType", serviceType)
                .param("odometerKm", odometerKm)
                .param("performedAt", performedAt)
                .param("notes", notes)
                .param("eventId", eventId)
                .param("garageVehicleId", garageVehicleId)
                .param("userId", userId)
                .query(MaintenanceRepository::map)
                .optional();
    }

    /** Deletes one event, scoped by owner. Returns false when no such event exists for this user and vehicle. */
    public boolean deleteOwnedEvent(long userId, long garageVehicleId, long eventId) {
        return jdbcClient.sql(
                        """
                        DELETE FROM maintenance_events e
                        USING garage_vehicles g
                        WHERE e.id = :eventId AND e.garage_vehicle_id = :garageVehicleId
                          AND g.id = e.garage_vehicle_id AND g.user_id = :userId AND g.deleted_at IS NULL
                        """)
                .param("eventId", eventId)
                .param("garageVehicleId", garageVehicleId)
                .param("userId", userId)
                .update() > 0;
    }

    /**
     * Used when the rider explicitly names the OLD value being corrected
     * — "the oil change at 20,000 km was at 19,000 km", "it wasn't
     * 20,000, it was 19,000" — so the correction can target the exact
     * row that actually holds 20,000, rather than whichever row of this
     * service type merely happens to be the most recently inserted (see
     * {@link #correctLatestEvent}, which is the wrong tool once more than
     * one event of the same service type exists: a real rider once
     * corrected "20,000 -> 19,000" while a genuinely separate 22,000 km
     * event of the same type had been logged afterward, and
     * correctLatestEvent silently overwrote THAT 22,000 km event instead
     * — destroying real history while leaving the actual 20,000 km
     * mistake uncorrected). Falls back to {@link Optional#empty()}
     * (never a fabricated match) when no event of this type currently
     * sits at oldOdometerKm, so the caller can fall back to
     * correctLatestEvent's best-effort behavior instead.
     */
    public Optional<MaintenanceEventDto> correctEventAtMileage(
            long garageVehicleId, String serviceType, double oldOdometerKm, Double newOdometerKm, LocalDate performedAt, String notes
    ) {
        return jdbcClient.sql(
                        """
                        UPDATE maintenance_events
                        SET odometer_km = COALESCE(:newOdometerKm, odometer_km),
                            performed_at = COALESCE(:performedAt, performed_at),
                            notes = COALESCE(:notes, notes)
                        WHERE id = (
                            SELECT id FROM maintenance_events
                            WHERE garage_vehicle_id = :garageVehicleId AND service_type = :serviceType
                              AND odometer_km = :oldOdometerKm
                            ORDER BY id DESC
                            LIMIT 1
                        )
                        RETURNING id, garage_vehicle_id, service_type, odometer_km, performed_at, notes, created_via, created_at
                        """)
                .param("newOdometerKm", newOdometerKm)
                .param("performedAt", performedAt)
                .param("notes", notes)
                .param("garageVehicleId", garageVehicleId)
                .param("serviceType", serviceType)
                .param("oldOdometerKm", oldOdometerKm)
                .query(MaintenanceRepository::map)
                .optional();
    }

    /** Used when the rider corrects a value they already stated for the
     * SAME service type earlier in this conversation (see
     * MotoDiagnosticAnswer.ProposedMaintenanceEvent.isCorrection and
     * MotoChatOrchestrationService.validateAndApplyProposedActions) —
     * updates the most recently INSERTED event of this type in place
     * (true insertion order via {@code id DESC}, deliberately never
     * {@code performed_at}/{@code created_at}-based: the model has no
     * reliable anchor for "today" and a wrong guessed date must never be
     * allowed to make an older, superseded event look newer than the
     * correction). Only overwrites the fields this correction actually
     * supplies ({@code COALESCE} against the stored value), so correcting
     * just the mileage never blanks out a previously recorded date or
     * note. Returns the persisted, corrected row, or {@link
     * Optional#empty()} if there is no prior event of this type for this
     * vehicle to correct — the caller must then fall back to creating a
     * new event instead of silently doing nothing.
     *
     * This is a best-effort fallback for when the rider's message names
     * no specific old value to target (see {@link #correctEventAtMileage}
     * for the precise, preferred path) — "most recently inserted" is
     * only a safe proxy for "the thing the rider is talking about" when
     * at most one event of this service type has been logged since the
     * value actually being corrected. */
    public Optional<MaintenanceEventDto> correctLatestEvent(
            long garageVehicleId, String serviceType, Double odometerKm, LocalDate performedAt, String notes
    ) {
        return jdbcClient.sql(
                        """
                        UPDATE maintenance_events
                        SET odometer_km = COALESCE(:odometerKm, odometer_km),
                            performed_at = COALESCE(:performedAt, performed_at),
                            notes = COALESCE(:notes, notes)
                        WHERE id = (
                            SELECT id FROM maintenance_events
                            WHERE garage_vehicle_id = :garageVehicleId AND service_type = :serviceType
                            ORDER BY id DESC
                            LIMIT 1
                        )
                        RETURNING id, garage_vehicle_id, service_type, odometer_km, performed_at, notes, created_via, created_at
                        """)
                .param("odometerKm", odometerKm)
                .param("performedAt", performedAt)
                .param("notes", notes)
                .param("garageVehicleId", garageVehicleId)
                .param("serviceType", serviceType)
                .query(MaintenanceRepository::map)
                .optional();
    }

    /** Ownership-scoped maintenance history, for the My Garage history
     * list. Ordered by event mileage descending where mileage exists —
     * "when in the conversation the rider happened to mention it" is
     * NOT the same as "when it happened on the bike's own timeline", and
     * this list must reflect the latter. NULLS LAST falls back to date
     * (then true insertion order) only for the rare date-only event with
     * no odometer reading at all. See docs/maintenance-tracking.md
     * "latest service by mileage, not by mention order". */
    public List<MaintenanceEventDto> listForOwnedVehicle(long userId, long garageVehicleId) {
        return jdbcClient.sql(
                        """
                        SELECT e.id, e.garage_vehicle_id, e.service_type, e.odometer_km, e.performed_at,
                               e.notes, e.created_via, e.created_at
                        FROM maintenance_events e
                        JOIN garage_vehicles g ON g.id = e.garage_vehicle_id
                        WHERE e.garage_vehicle_id = :garageVehicleId AND g.user_id = :userId AND g.deleted_at IS NULL
                        ORDER BY e.odometer_km DESC NULLS LAST, COALESCE(e.performed_at, e.created_at::date) DESC, e.id DESC
                        """)
                .param("garageVehicleId", garageVehicleId)
                .param("userId", userId)
                .query(MaintenanceRepository::map)
                .list();
    }

    /** Same mileage-first ordering as {@link #listForOwnedVehicle} — this
     * feeds the chat prompt's "Rider's own stored data" block, which
     * must present history in real bike-timeline order too, not
     * conversation-mention order. */
    public List<MaintenanceEventDto> recentEvents(long garageVehicleId, int limit) {
        return jdbcClient.sql(
                        """
                        SELECT id, garage_vehicle_id, service_type, odometer_km, performed_at, notes, created_via, created_at
                        FROM maintenance_events
                        WHERE garage_vehicle_id = :garageVehicleId
                        ORDER BY odometer_km DESC NULLS LAST, COALESCE(performed_at, created_at::date) DESC, id DESC
                        LIMIT :limit
                        """)
                .param("garageVehicleId", garageVehicleId)
                .param("limit", limit)
                .query(MaintenanceRepository::map)
                .list();
    }

    /** Highest odometer reading among the vehicle's service records, or null when none has one. */
    public Double maxServiceOdometer(long garageVehicleId) {
        return jdbcClient.sql("SELECT max(odometer_km) FROM maintenance_events WHERE garage_vehicle_id = :garageVehicleId")
                .param("garageVehicleId", garageVehicleId)
                .query(Double.class)
                .optional()
                .orElse(null);
    }

    /** Latest event per service_type — the input the maintenance
     * dashboard's status calculation needs (see MaintenanceStatusService).
     * "Latest" here means "at the highest odometer reading" (how far
     * into the bike's own life the service happened), NEVER "most
     * recently created" or "most recently mentioned in chat" — a rider
     * can enter real history out of chronological order (e.g. recall a
     * 24,000 km oil change, then later recall an earlier one at 20,000),
     * and the newest DB row is not necessarily the newest service.
     * NULLS LAST falls back to date/insertion order only when an event
     * genuinely has no odometer reading at all. */
    public Map<String, MaintenanceEventDto> latestEventByType(long garageVehicleId) {
        return jdbcClient.sql(
                        """
                        SELECT DISTINCT ON (service_type)
                               id, garage_vehicle_id, service_type, odometer_km, performed_at, notes, created_via, created_at
                        FROM maintenance_events
                        WHERE garage_vehicle_id = :garageVehicleId
                        ORDER BY service_type, odometer_km DESC NULLS LAST, COALESCE(performed_at, created_at::date) DESC, id DESC
                        """)
                .param("garageVehicleId", garageVehicleId)
                .query(MaintenanceRepository::map)
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(MaintenanceEventDto::serviceType, e -> e));
    }

    private static MaintenanceEventDto map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Object odometer = rs.getObject("odometer_km");
        java.sql.Date performedAt = rs.getDate("performed_at");
        return new MaintenanceEventDto(
                rs.getLong("id"), rs.getLong("garage_vehicle_id"), rs.getString("service_type"),
                odometer instanceof Number number ? number.doubleValue() : null,
                performedAt == null ? null : performedAt.toLocalDate(),
                rs.getString("notes"), rs.getString("created_via"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }
}
