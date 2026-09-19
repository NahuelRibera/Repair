package dev.repair.api.garage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every query here is scoped by user_id — same ownership-baked-into-SQL
 * pattern as dev.repair.api.conversation.SessionRepository (which stays
 * on the legacy anonymous visitor_id for the preserved car prototype).
 * There is no method that reads or writes a garage vehicle by id alone.
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

    public long create(long userId, long modelId, int year, String market, String nickname) {
        return jdbcClient.sql(
                        """
                        INSERT INTO garage_vehicles (user_id, model_id, year, market, nickname)
                        VALUES (:userId, :modelId, :year, :market, :nickname)
                        RETURNING id
                        """)
                .param("userId", userId)
                .param("modelId", modelId)
                .param("year", year)
                .param("market", market)
                .param("nickname", nickname)
                .query(Long.class)
                .single();
    }

    /** The same (user, model, year) canonical bike, if this user
     * already has one — used by the normal "choose your bike" flow to
     * reuse an existing garage vehicle instead of silently creating a
     * duplicate physical motorcycle every time the same bike is selected
     * again. The explicit "+ Add another bike" flow bypasses this (see
     * GarageVehicleController) since a rider may genuinely own two
     * identical bikes. Oldest match wins, so a rider's existing
     * conversations/history stay attached to the bike they've been using. */
    public Optional<Long> findExisting(long userId, long modelId, int year) {
        return jdbcClient.sql(
                        """
                        SELECT id FROM garage_vehicles
                        WHERE user_id = :userId AND model_id = :modelId AND year = :year AND deleted_at IS NULL
                        ORDER BY created_at
                        LIMIT 1
                        """)
                .param("userId", userId)
                .param("modelId", modelId)
                .param("year", year)
                .query(Long.class)
                .optional();
    }

    public List<GarageVehicleDto> list(long userId) {
        return jdbcClient.sql(SELECT + " WHERE g.user_id = :userId AND g.deleted_at IS NULL ORDER BY g.created_at")
                .param("userId", userId)
                .query(GarageVehicleRepository::map)
                .list();
    }

    public Optional<GarageVehicleDto> find(long userId, long id) {
        return jdbcClient.sql(SELECT + " WHERE g.id = :id AND g.user_id = :userId AND g.deleted_at IS NULL")
                .param("id", id)
                .param("userId", userId)
                .query(GarageVehicleRepository::map)
                .optional();
    }

    public boolean isOwned(long userId, long id) {
        return jdbcClient.sql(
                        "SELECT count(*) FROM garage_vehicles WHERE id = :id AND user_id = :userId AND deleted_at IS NULL")
                .param("id", id)
                .param("userId", userId)
                .query(Long.class)
                .single() > 0;
    }

    public boolean update(long userId, long id, String nickname, Double currentOdometerKm) {
        int updated = jdbcClient.sql(
                        """
                        UPDATE garage_vehicles
                        SET nickname = COALESCE(:nickname, nickname),
                            current_odometer_km = COALESCE(:odometer, current_odometer_km),
                            updated_at = now()
                        WHERE id = :id AND user_id = :userId AND deleted_at IS NULL
                        """)
                .param("nickname", nickname)
                .param("odometer", currentOdometerKm)
                .param("id", id)
                .param("userId", userId)
                .update();
        return updated > 0;
    }

    /** Used by the chat controlled-action path (see MotoChatOrchestrationService)
     * after the proposed odometer value has already been validated — a
     * plain, parameterized write, never model-generated SQL.
     *
     * Scoped by user_id (defense in depth — the caller has already
     * resolved this vehicle as owned, but a write must never rely on that
     * alone) and excludes soft-deleted vehicles. Returns the value actually
     * persisted via {@code RETURNING}, or {@link Optional#empty()} if the
     * update affected zero rows (wrong id, wrong owner, or the vehicle was
     * deleted between the read and this write) — the caller must treat an
     * empty result as a real failure, never as a successful write. See
     * "never claim a write succeeded until it really succeeded" in
     * docs/maintenance-tracking.md. */
    public Optional<Double> updateOdometerIfOwned(long userId, long id, double odometerKm) {
        return jdbcClient.sql(
                        """
                        UPDATE garage_vehicles
                        SET current_odometer_km = :odometer, updated_at = now()
                        WHERE id = :id AND user_id = :userId AND deleted_at IS NULL
                        RETURNING current_odometer_km
                        """)
                .param("odometer", odometerKm)
                .param("id", id)
                .param("userId", userId)
                .query(Double.class)
                .optional();
    }

    /**
     * Permanently deletes this physical garage vehicle AND every row
     * that belongs specifically to it, in one atomic transaction — never
     * a soft/orphaning delete. Ownership-scoped: the final DELETE on
     * garage_vehicles is the authoritative check (WHERE id AND user_id),
     * so a non-owned or unknown id simply deletes nothing and this
     * returns false; the caller must treat that as "not found", never
     * attempt the child deletes for a vehicle that turned out not to be
     * this user's.
     *
     * Deletion order follows the FK dependency graph in
     * V7__garage_and_maintenance.sql exactly (every FK here is a plain
     * REFERENCES with no ON DELETE CASCADE, by design — see that
     * migration's header comment — so this is the one place that graph
     * has to be walked child-first by hand):
     * moto_retrieved_evidence -> moto_rag_runs -> moto_chat_messages ->
     * moto_chat_sessions -> {maintenance_events, vehicle_preferences} ->
     * garage_vehicles. Never touches app_users, the motorcycle catalog,
     * the knowledge base, or any other user's rows (every DELETE below
     * is itself scoped to this one garage_vehicle_id, and the whole
     * operation is a no-op unless the final ownership-scoped delete
     * would have matched).
     */
    @Transactional
    public boolean deleteVehicleAndAllData(long userId, long id) {
        if (!isOwned(userId, id)) {
            return false;
        }
        jdbcClient.sql(
                        """
                        DELETE FROM moto_retrieved_evidence
                        WHERE rag_run_id IN (SELECT id FROM moto_rag_runs WHERE garage_vehicle_id = :id)
                        """)
                .param("id", id).update();
        jdbcClient.sql("DELETE FROM moto_rag_runs WHERE garage_vehicle_id = :id").param("id", id).update();
        jdbcClient.sql(
                        """
                        DELETE FROM moto_chat_messages
                        WHERE session_id IN (SELECT id FROM moto_chat_sessions WHERE garage_vehicle_id = :id)
                        """)
                .param("id", id).update();
        jdbcClient.sql("DELETE FROM moto_chat_sessions WHERE garage_vehicle_id = :id").param("id", id).update();
        jdbcClient.sql("DELETE FROM maintenance_events WHERE garage_vehicle_id = :id").param("id", id).update();
        jdbcClient.sql("DELETE FROM vehicle_preferences WHERE garage_vehicle_id = :id").param("id", id).update();
        int deleted = jdbcClient.sql("DELETE FROM garage_vehicles WHERE id = :id AND user_id = :userId")
                .param("id", id)
                .param("userId", userId)
                .update();
        return deleted > 0;
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
