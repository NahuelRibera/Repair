package dev.repair.api.motochat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MotoRagRunRepository {

    private final JdbcClient jdbcClient;

    public MotoRagRunRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long start(UUID requestId, long sessionId, long garageVehicleId, String retrievalFiltersJson) {
        return jdbcClient.sql(
                        """
                        INSERT INTO moto_rag_runs (request_id, session_id, garage_vehicle_id, retrieval_filters,
                                                     retrieval_started_at, provider_status)
                        VALUES (:requestId, :sessionId, :garageVehicleId, CAST(:filters AS jsonb), now(), 'ok')
                        RETURNING id
                        """)
                .param("requestId", requestId)
                .param("sessionId", sessionId)
                .param("garageVehicleId", garageVehicleId)
                .param("filters", retrievalFiltersJson)
                .query(Long.class)
                .single();
    }

    public void complete(
            long ragRunId, Long messageId, OffsetDateTime retrievalFinished, OffsetDateTime generationStarted,
            OffsetDateTime generationFinished, String embeddingModel, String generationModel,
            Integer promptTokens, Integer completionTokens, String providerStatus, String errorDetail,
            String actionsTakenJson
    ) {
        jdbcClient.sql(
                        """
                        UPDATE moto_rag_runs SET
                            message_id = :messageId,
                            retrieval_finished_at = :retrievalFinished,
                            generation_started_at = :generationStarted,
                            generation_finished_at = :generationFinished,
                            embedding_model = :embeddingModel,
                            generation_model = :generationModel,
                            prompt_tokens = :promptTokens,
                            completion_tokens = :completionTokens,
                            provider_status = :providerStatus,
                            error_detail = :errorDetail,
                            actions_taken = CAST(:actionsTaken AS jsonb)
                        WHERE id = :id
                        """)
                .param("id", ragRunId)
                .param("messageId", messageId)
                .param("retrievalFinished", retrievalFinished)
                .param("generationStarted", generationStarted)
                .param("generationFinished", generationFinished)
                .param("embeddingModel", embeddingModel)
                .param("generationModel", generationModel)
                .param("promptTokens", promptTokens)
                .param("completionTokens", completionTokens)
                .param("providerStatus", providerStatus)
                .param("errorDetail", errorDetail)
                .param("actionsTaken", actionsTakenJson == null ? "[]" : actionsTakenJson)
                .update();
    }

    public void recordEvidence(long ragRunId, List<MotoRetrievedChunk> chunks) {
        int rank = 1;
        for (MotoRetrievedChunk chunk : chunks) {
            jdbcClient.sql(
                            """
                            INSERT INTO moto_retrieved_evidence (rag_run_id, chunk_id, rank, vector_score, text_score, fused_score)
                            VALUES (:ragRunId, :chunkId, :rank, :vectorScore, :textScore, :fusedScore)
                            ON CONFLICT (rag_run_id, chunk_id) DO NOTHING
                            """)
                    .param("ragRunId", ragRunId)
                    .param("chunkId", chunk.chunkId())
                    .param("rank", rank)
                    .param("vectorScore", chunk.vectorScore())
                    .param("textScore", chunk.textScore())
                    .param("fusedScore", chunk.fusedScore())
                    .update();
            rank++;
        }
    }

    /** Package-private: used only right after a run this same request created. */
    MotoRagRunDebugDto findDebugByRequestId(UUID requestId) {
        return jdbcClient.sql(
                        """
                        SELECT r.request_id, r.garage_vehicle_id, mf.canonical_name AS manufacturer_name,
                               mm.canonical_name AS model_name, g.year, r.embedding_model, r.generation_model,
                               r.prompt_tokens, r.completion_tokens, r.provider_status, r.error_detail,
                               r.actions_taken::text AS actions_taken_json,
                               EXTRACT(EPOCH FROM (r.retrieval_finished_at - r.retrieval_started_at)) * 1000 AS retrieval_ms,
                               EXTRACT(EPOCH FROM (r.generation_finished_at - r.generation_started_at)) * 1000 AS generation_ms
                        FROM moto_rag_runs r
                        JOIN garage_vehicles g ON g.id = r.garage_vehicle_id
                        JOIN motorcycle_models mm ON mm.id = g.model_id
                        JOIN motorcycle_manufacturers mf ON mf.id = mm.manufacturer_id
                        WHERE r.request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query(MotoRagRunRepository::mapDebug)
                .single();
    }

    public Optional<MotoRagRunDebugDto> findDebugForVisitor(UUID requestId, UUID visitorId) {
        return jdbcClient.sql(
                        """
                        SELECT r.request_id, r.garage_vehicle_id, mf.canonical_name AS manufacturer_name,
                               mm.canonical_name AS model_name, g.year, r.embedding_model, r.generation_model,
                               r.prompt_tokens, r.completion_tokens, r.provider_status, r.error_detail,
                               r.actions_taken::text AS actions_taken_json,
                               EXTRACT(EPOCH FROM (r.retrieval_finished_at - r.retrieval_started_at)) * 1000 AS retrieval_ms,
                               EXTRACT(EPOCH FROM (r.generation_finished_at - r.generation_started_at)) * 1000 AS generation_ms
                        FROM moto_rag_runs r
                        JOIN moto_chat_sessions s ON s.id = r.session_id
                        JOIN garage_vehicles g ON g.id = r.garage_vehicle_id
                        JOIN motorcycle_models mm ON mm.id = g.model_id
                        JOIN motorcycle_manufacturers mf ON mf.id = mm.manufacturer_id
                        WHERE r.request_id = :requestId AND s.visitor_id = :visitorId
                        """)
                .param("requestId", requestId)
                .param("visitorId", visitorId)
                .query(MotoRagRunRepository::mapDebug)
                .optional();
    }

    public List<MotoEvidenceCardDto> findEvidenceForVisitor(UUID requestId, UUID visitorId) {
        return jdbcClient.sql(
                        """
                        SELECT c.id AS chunk_id, c.document_id, c.section, c.subsection, c.category,
                               c.heading, c.section_path, c.content,
                               re.vector_score, re.text_score, re.fused_score
                        FROM moto_rag_runs r
                        JOIN moto_chat_sessions s ON s.id = r.session_id
                        JOIN moto_retrieved_evidence re ON re.rag_run_id = r.id
                        JOIN motorcycle_knowledge_chunks c ON c.id = re.chunk_id
                        WHERE r.request_id = :requestId AND s.visitor_id = :visitorId
                        ORDER BY re.rank
                        """)
                .param("requestId", requestId)
                .param("visitorId", visitorId)
                .query((rs, rowNum) -> new MotoEvidenceCardDto(
                        rs.getLong("chunk_id"), rs.getLong("document_id"), rs.getString("section"),
                        rs.getString("subsection"), rs.getString("category"), rs.getString("heading"),
                        rs.getString("section_path"), rs.getString("content"),
                        nullableDouble(rs, "vector_score"), nullableDouble(rs, "text_score"),
                        rs.getDouble("fused_score")
                ))
                .list();
    }

    private static MotoRagRunDebugDto mapDebug(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new MotoRagRunDebugDto(
                rs.getString("request_id"), rs.getLong("garage_vehicle_id"), rs.getString("manufacturer_name"),
                rs.getString("model_name"), rs.getInt("year"), null,
                rs.getString("embedding_model"), rs.getString("generation_model"),
                (Integer) rs.getObject("prompt_tokens"), (Integer) rs.getObject("completion_tokens"),
                rs.getObject("retrieval_ms") == null ? null : rs.getLong("retrieval_ms"),
                rs.getObject("generation_ms") == null ? null : rs.getLong("generation_ms"),
                rs.getString("provider_status"), rs.getString("error_detail"), rs.getString("actions_taken_json")
        );
    }

    private static Double nullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
