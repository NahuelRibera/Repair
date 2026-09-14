package dev.repair.api.garage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** User-owned setup preferences (e.g. off-road tire pressure), kept in a
 * separate table from motorcycle_facts on purpose — a personal preference
 * must never be presented as, or overwrite, manufacturer reference data.
 * See docs/repair-v2-architecture.md section 5 ("manufacturer facts vs
 * personal preferences"). */
@Repository
public class VehiclePreferenceRepository {

    private final JdbcClient jdbcClient;

    public VehiclePreferenceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void upsert(long garageVehicleId, String preferenceType, String context, String dataJson) {
        jdbcClient.sql(
                        """
                        INSERT INTO vehicle_preferences (garage_vehicle_id, preference_type, context, data)
                        VALUES (:garageVehicleId, :preferenceType, :context, CAST(:data AS jsonb))
                        ON CONFLICT (garage_vehicle_id, preference_type, context) DO UPDATE SET
                            data = EXCLUDED.data, updated_at = now()
                        """)
                .param("garageVehicleId", garageVehicleId)
                .param("preferenceType", preferenceType)
                .param("context", context)
                .param("data", dataJson)
                .update();
    }

    public List<VehiclePreferenceDto> listForOwnedVehicle(UUID visitorId, long garageVehicleId) {
        return jdbcClient.sql(
                        """
                        SELECT p.id, p.garage_vehicle_id, p.preference_type, p.context, p.data::text AS data_json,
                               p.created_at, p.updated_at
                        FROM vehicle_preferences p
                        JOIN garage_vehicles g ON g.id = p.garage_vehicle_id
                        WHERE p.garage_vehicle_id = :garageVehicleId AND g.visitor_id = :visitorId AND g.deleted_at IS NULL
                        ORDER BY p.preference_type, p.context
                        """)
                .param("garageVehicleId", garageVehicleId)
                .param("visitorId", visitorId)
                .query((rs, rowNum) -> new VehiclePreferenceDto(
                        rs.getLong("id"), rs.getLong("garage_vehicle_id"), rs.getString("preference_type"),
                        rs.getString("context"), rs.getString("data_json"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }

    public List<VehiclePreferenceDto> listForVehicle(long garageVehicleId) {
        return jdbcClient.sql(
                        """
                        SELECT id, garage_vehicle_id, preference_type, context, data::text AS data_json, created_at, updated_at
                        FROM vehicle_preferences
                        WHERE garage_vehicle_id = :garageVehicleId
                        """)
                .param("garageVehicleId", garageVehicleId)
                .query((rs, rowNum) -> new VehiclePreferenceDto(
                        rs.getLong("id"), rs.getLong("garage_vehicle_id"), rs.getString("preference_type"),
                        rs.getString("context"), rs.getString("data_json"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }
}
