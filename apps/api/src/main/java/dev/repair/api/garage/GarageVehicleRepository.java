package dev.repair.api.garage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every query here is scoped by visitor_id — same ownership-baked-into-SQL
 * pattern as dev.repair.api.conversation.SessionRepository. There is no
 * method that reads or writes a garage vehicle by id alone.
 */
@Repository
public class GarageVehicleRepository {

    private static final String SELECT = """
            SELECT g.id, g.model_id, mf.canonical_name AS manufacturer_name, mm.canonical_name AS model_name,
                   g.year, g.market, g.nickname, g.current_odometer_km, g.created_at, g.updated_at
            FROM garage_vehicles g
            JOIN motorcycle_models mm ON mm.id = g.model_id
            JOIN motorcycle_manufacturers mf ON mf.id = mm.manufacturer_id
            """;

    private final JdbcClient jdbcClient;

    public GarageVehicleRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long create(UUID visitorId, long modelId, int year, String market, String nickname) {
        return jdbcClient.sql(
                        """
                        INSERT INTO garage_vehicles (visitor_id, model_id, year, market, nickname)
                        VALUES (:visitorId, :modelId, :year, :market, :nickname)
                        RETURNING id
                        """)
                .param("visitorId", visitorId)
                .param("modelId", modelId)
                .param("year", year)
                .param("market", market)
                .param("nickname", nickname)
                .query(Long.class)
                .single();
    }

    public List<GarageVehicleDto> list(UUID visitorId) {
        return jdbcClient.sql(SELECT + " WHERE g.visitor_id = :visitorId AND g.deleted_at IS NULL ORDER BY g.created_at")
                .param("visitorId", visitorId)
                .query(GarageVehicleRepository::map)
                .list();
    }

    public Optional<GarageVehicleDto> find(UUID visitorId, long id) {
        return jdbcClient.sql(SELECT + " WHERE g.id = :id AND g.visitor_id = :visitorId AND g.deleted_at IS NULL")
                .param("id", id)
                .param("visitorId", visitorId)
                .query(GarageVehicleRepository::map)
                .optional();
    }

    public boolean isOwned(UUID visitorId, long id) {
        return jdbcClient.sql(
                        "SELECT count(*) FROM garage_vehicles WHERE id = :id AND visitor_id = :visitorId AND deleted_at IS NULL")
                .param("id", id)
                .param("visitorId", visitorId)
                .query(Long.class)
                .single() > 0;
    }

    public boolean update(UUID visitorId, long id, String nickname, Double currentOdometerKm) {
        int updated = jdbcClient.sql(
                        """
                        UPDATE garage_vehicles
                        SET nickname = COALESCE(:nickname, nickname),
                            current_odometer_km = COALESCE(:odometer, current_odometer_km),
                            updated_at = now()
                        WHERE id = :id AND visitor_id = :visitorId AND deleted_at IS NULL
                        """)
                .param("nickname", nickname)
                .param("odometer", currentOdometerKm)
                .param("id", id)
                .param("visitorId", visitorId)
                .update();
        return updated > 0;
    }

    /** Used by the chat controlled-action path (see MotoChatOrchestrationService)
     * after the proposed odometer value has already been validated — a
     * plain, parameterized write, never model-generated SQL. */
    public void updateOdometer(long id, double odometerKm) {
        jdbcClient.sql("UPDATE garage_vehicles SET current_odometer_km = :odometer, updated_at = now() WHERE id = :id")
                .param("odometer", odometerKm)
                .param("id", id)
                .update();
    }

    public boolean softDelete(UUID visitorId, long id) {
        int updated = jdbcClient.sql(
                        "UPDATE garage_vehicles SET deleted_at = now() WHERE id = :id AND visitor_id = :visitorId AND deleted_at IS NULL")
                .param("id", id)
                .param("visitorId", visitorId)
                .update();
        return updated > 0;
    }

    private static GarageVehicleDto map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Object odometer = rs.getObject("current_odometer_km");
        return new GarageVehicleDto(
                rs.getLong("id"), rs.getLong("model_id"), rs.getString("manufacturer_name"), rs.getString("model_name"),
                rs.getInt("year"), rs.getString("market"), rs.getString("nickname"),
                odometer instanceof Number number ? number.doubleValue() : null,
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
