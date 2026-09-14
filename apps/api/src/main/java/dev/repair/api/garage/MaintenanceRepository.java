package dev.repair.api.garage;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MaintenanceRepository {

    private final JdbcClient jdbcClient;

    public MaintenanceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Caller (controller or chat orchestration) must have already
     * verified garageVehicleId belongs to the current visitor — this
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

    public List<MaintenanceEventDto> listForOwnedVehicle(UUID visitorId, long garageVehicleId) {
        return jdbcClient.sql(
                        """
                        SELECT e.id, e.garage_vehicle_id, e.service_type, e.odometer_km, e.performed_at,
                               e.notes, e.created_via, e.created_at
                        FROM maintenance_events e
                        JOIN garage_vehicles g ON g.id = e.garage_vehicle_id
                        WHERE e.garage_vehicle_id = :garageVehicleId AND g.visitor_id = :visitorId AND g.deleted_at IS NULL
                        ORDER BY COALESCE(e.performed_at, e.created_at::date) DESC, e.id DESC
                        """)
                .param("garageVehicleId", garageVehicleId)
                .param("visitorId", visitorId)
                .query(MaintenanceRepository::map)
                .list();
    }

    public List<MaintenanceEventDto> recentEvents(long garageVehicleId, int limit) {
        return jdbcClient.sql(
                        """
                        SELECT id, garage_vehicle_id, service_type, odometer_km, performed_at, notes, created_via, created_at
                        FROM maintenance_events
                        WHERE garage_vehicle_id = :garageVehicleId
                        ORDER BY COALESCE(performed_at, created_at::date) DESC, id DESC
                        LIMIT :limit
                        """)
                .param("garageVehicleId", garageVehicleId)
                .param("limit", limit)
                .query(MaintenanceRepository::map)
                .list();
    }

    /** Latest event per service_type — the input the maintenance
     * dashboard's status calculation needs (see MaintenanceStatusService). */
    public Map<String, MaintenanceEventDto> latestEventByType(long garageVehicleId) {
        return jdbcClient.sql(
                        """
                        SELECT DISTINCT ON (service_type)
                               id, garage_vehicle_id, service_type, odometer_km, performed_at, notes, created_via, created_at
                        FROM maintenance_events
                        WHERE garage_vehicle_id = :garageVehicleId
                        ORDER BY service_type, COALESCE(performed_at, created_at::date) DESC, id DESC
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
