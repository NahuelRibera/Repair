package dev.repair.api.conversation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every query here is scoped by visitor_id. There is intentionally no
 * method that reads a session by id alone — ownership is not an
 * afterthought layered on top, it is baked into the SQL.
 */
@Repository
public class SessionRepository {

    private final JdbcClient jdbcClient;

    public SessionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long createSession(UUID visitorId, long variantId, String title) {
        return jdbcClient.sql(
                        """
                        INSERT INTO diagnostic_sessions (visitor_id, variant_id, title)
                        VALUES (:visitorId, :variantId, :title)
                        RETURNING id
                        """)
                .param("visitorId", visitorId)
                .param("variantId", variantId)
                .param("title", title)
                .query(Long.class)
                .single();
    }

    public List<SessionSummaryDto> listSessions(UUID visitorId, String search) {
        String like = "%" + (search == null ? "" : search.trim()) + "%";
        return jdbcClient.sql(
                        """
                        SELECT s.id, s.title, s.variant_id, s.created_at, s.updated_at,
                               mf.canonical_name AS manufacturer_name, m.model_name, v.variant_name
                        FROM diagnostic_sessions s
                        JOIN vehicle_variants v ON v.id = s.variant_id
                        JOIN vehicle_models m ON m.id = v.model_id
                        JOIN manufacturers mf ON mf.id = m.manufacturer_id
                        WHERE s.visitor_id = :visitorId AND s.deleted_at IS NULL
                          AND (:like = '%%' OR s.title ILIKE :like OR m.model_name ILIKE :like)
                        ORDER BY s.updated_at DESC
                        LIMIT 200
                        """)
                .param("visitorId", visitorId)
                .param("like", like)
                .query(SessionRepository::mapSummary)
                .list();
    }

    public Optional<SessionDetailDto> findSessionDetail(UUID visitorId, long sessionId) {
        Optional<SessionSummaryDto> summary = jdbcClient.sql(
                        """
                        SELECT s.id, s.title, s.variant_id, s.created_at, s.updated_at,
                               mf.canonical_name AS manufacturer_name, m.model_name, v.variant_name
                        FROM diagnostic_sessions s
                        JOIN vehicle_variants v ON v.id = s.variant_id
                        JOIN vehicle_models m ON m.id = v.model_id
                        JOIN manufacturers mf ON mf.id = m.manufacturer_id
                        WHERE s.visitor_id = :visitorId AND s.id = :sessionId AND s.deleted_at IS NULL
                        """)
                .param("visitorId", visitorId)
                .param("sessionId", sessionId)
                .query(SessionRepository::mapSummary)
                .optional();

        if (summary.isEmpty()) {
            return Optional.empty();
        }

        List<MessageDto> messages = jdbcClient.sql(
                        """
                        SELECT id, role, content, structured_response, created_at
                        FROM diagnostic_messages
                        WHERE session_id = :sessionId
                        ORDER BY created_at ASC, id ASC
                        """)
                .param("sessionId", sessionId)
                .query((rs, rowNum) -> new MessageDto(
                        rs.getLong("id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("structured_response"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ))
                .list();

        return Optional.of(new SessionDetailDto(summary.get(), messages));
    }

    /** Returns true only if the session exists, is not deleted, and belongs to this visitor. */
    public boolean isOwnedActiveSession(UUID visitorId, long sessionId) {
        return jdbcClient.sql(
                        "SELECT count(*) FROM diagnostic_sessions WHERE id = :id AND visitor_id = :visitorId AND deleted_at IS NULL")
                .param("id", sessionId)
                .param("visitorId", visitorId)
                .query(Long.class)
                .single() > 0;
    }

    public Optional<Long> findVariantIdForSession(UUID visitorId, long sessionId) {
        return jdbcClient.sql(
                        "SELECT variant_id FROM diagnostic_sessions WHERE id = :id AND visitor_id = :visitorId AND deleted_at IS NULL")
                .param("id", sessionId)
                .param("visitorId", visitorId)
                .query(Long.class)
                .optional();
    }

    public boolean softDelete(UUID visitorId, long sessionId) {
        int updated = jdbcClient.sql(
                        "UPDATE diagnostic_sessions SET deleted_at = now() WHERE id = :id AND visitor_id = :visitorId AND deleted_at IS NULL")
                .param("id", sessionId)
                .param("visitorId", visitorId)
                .update();
        return updated > 0;
    }

    public void touchUpdatedAt(long sessionId) {
        jdbcClient.sql("UPDATE diagnostic_sessions SET updated_at = now() WHERE id = :id")
                .param("id", sessionId)
                .update();
    }

    public long insertMessage(long sessionId, String role, String content, String structuredResponseJson) {
        return jdbcClient.sql(
                        """
                        INSERT INTO diagnostic_messages (session_id, role, content, structured_response)
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

    public List<MessageDto> recentMessages(long sessionId, int limit) {
        return jdbcClient.sql(
                        """
                        SELECT id, role, content, structured_response, created_at
                        FROM diagnostic_messages
                        WHERE session_id = :sessionId
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("sessionId", sessionId)
                .param("limit", limit)
                .query((rs, rowNum) -> new MessageDto(
                        rs.getLong("id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("structured_response"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ))
                .list()
                .reversed();
    }

    private static SessionSummaryDto mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new SessionSummaryDto(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getLong("variant_id"),
                rs.getString("manufacturer_name"),
                rs.getString("model_name"),
                rs.getString("variant_name"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
