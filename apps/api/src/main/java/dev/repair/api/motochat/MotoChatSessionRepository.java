package dev.repair.api.motochat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Ownership baked into SQL, same pattern as
 * dev.repair.api.conversation.SessionRepository — every method takes
 * userId as a real filter, never trusted from a path/body id alone. */
@Repository
public class MotoChatSessionRepository {

    private static final String SELECT_SUMMARY = """
            SELECT s.id, s.title, s.garage_vehicle_id, s.created_at, s.updated_at,
                   mf.canonical_name AS manufacturer_name, mm.canonical_name AS model_name, g.year
            FROM moto_chat_sessions s
            JOIN garage_vehicles g ON g.id = s.garage_vehicle_id
            JOIN motorcycle_models mm ON mm.id = g.model_id
            JOIN motorcycle_manufacturers mf ON mf.id = mm.manufacturer_id
            """;

    private final JdbcClient jdbcClient;

    public MotoChatSessionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long createSession(long userId, long garageVehicleId, String title) {
        return jdbcClient.sql(
                        """
                        INSERT INTO moto_chat_sessions (user_id, garage_vehicle_id, title)
                        VALUES (:userId, :garageVehicleId, :title)
                        RETURNING id
                        """)
                .param("userId", userId)
                .param("garageVehicleId", garageVehicleId)
                .param("title", title)
                .query(Long.class)
                .single();
    }

    public List<MotoSessionSummaryDto> listSessions(long userId) {
        return jdbcClient.sql(SELECT_SUMMARY + " WHERE s.user_id = :userId AND s.deleted_at IS NULL ORDER BY s.updated_at DESC LIMIT 200")
                .param("userId", userId)
                .query(MotoChatSessionRepository::mapSummary)
                .list();
    }

    public Optional<MotoSessionDetailDto> findSessionDetail(long userId, long sessionId) {
        Optional<MotoSessionSummaryDto> summary = jdbcClient.sql(
                        SELECT_SUMMARY + " WHERE s.user_id = :userId AND s.id = :sessionId AND s.deleted_at IS NULL")
                .param("userId", userId)
                .param("sessionId", sessionId)
                .query(MotoChatSessionRepository::mapSummary)
                .optional();

        if (summary.isEmpty()) {
            return Optional.empty();
        }

        List<MotoMessageDto> messages = jdbcClient.sql(
                        """
                        SELECT id, role, content, structured_response, created_at
                        FROM moto_chat_messages
                        WHERE session_id = :sessionId
                        ORDER BY created_at ASC, id ASC
                        """)
                .param("sessionId", sessionId)
                .query(MotoChatSessionRepository::mapMessage)
                .list();

        return Optional.of(new MotoSessionDetailDto(summary.get(), messages));
    }

    public Optional<Long> findGarageVehicleIdForSession(long userId, long sessionId) {
        return jdbcClient.sql(
                        "SELECT garage_vehicle_id FROM moto_chat_sessions WHERE id = :id AND user_id = :userId AND deleted_at IS NULL")
                .param("id", sessionId)
                .param("userId", userId)
                .query(Long.class)
                .optional();
    }

    public boolean softDelete(long userId, long sessionId) {
        int updated = jdbcClient.sql(
                        "UPDATE moto_chat_sessions SET deleted_at = now() WHERE id = :id AND user_id = :userId AND deleted_at IS NULL")
                .param("id", sessionId)
                .param("userId", userId)
                .update();
        return updated > 0;
    }

    public void touchUpdatedAt(long sessionId) {
        jdbcClient.sql("UPDATE moto_chat_sessions SET updated_at = now() WHERE id = :id")
                .param("id", sessionId)
                .update();
    }

    public long countMessages(long sessionId) {
        return jdbcClient.sql("SELECT count(*) FROM moto_chat_messages WHERE session_id = :id")
                .param("id", sessionId)
                .query(Long.class)
                .single();
    }

    /** Used once, right after the rider's first message, to replace the
     * generic bike-name title with a deterministic topic title — see
     * ChatTitleGenerator. Not user-scoped: the caller already holds an
     * ownership-checked sessionId from the same request that just wrote
     * to this session. */
    public void updateTitle(long sessionId, String title) {
        jdbcClient.sql("UPDATE moto_chat_sessions SET title = :title WHERE id = :id")
                .param("title", title)
                .param("id", sessionId)
                .update();
    }

    public long insertMessage(long sessionId, String role, String content, String structuredResponseJson) {
        return jdbcClient.sql(
                        """
                        INSERT INTO moto_chat_messages (session_id, role, content, structured_response)
                        VALUES (:sessionId, :role, :content, CAST(:structuredResponse AS jsonb))
                        RETURNING id
                        """)
                .param("sessionId", sessionId)
                .param("role", role)
                .param("content", content)
                .param("structuredResponse", structuredResponseJson)
                .query(Long.class)
                .single();
    }

    public List<MotoMessageDto> recentMessages(long sessionId, int limit) {
        return jdbcClient.sql(
                        """
                        SELECT id, role, content, structured_response, created_at
                        FROM moto_chat_messages
                        WHERE session_id = :sessionId
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("sessionId", sessionId)
                .param("limit", limit)
                .query(MotoChatSessionRepository::mapMessage)
                .list()
                .reversed();
    }

    private static MotoSessionSummaryDto mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new MotoSessionSummaryDto(
                rs.getLong("id"), rs.getString("title"), rs.getLong("garage_vehicle_id"),
                rs.getString("manufacturer_name"), rs.getString("model_name"), rs.getInt("year"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private static MotoMessageDto mapMessage(ResultSet rs, int rowNum) throws SQLException {
        return new MotoMessageDto(
                rs.getLong("id"), rs.getString("role"), rs.getString("content"),
                rs.getString("structured_response"), rs.getObject("created_at", OffsetDateTime.class)
        );
    }
}
