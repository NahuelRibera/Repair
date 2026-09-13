package dev.repair.api.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RagRunRepository {

    private final JdbcClient jdbcClient;

    public RagRunRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long start(UUID requestId, long sessionId, long variantId, String retrievalFiltersJson) {
        return jdbcClient.sql(
                        """
                        INSERT INTO rag_runs (request_id, session_id, variant_id, retrieval_filters,
                                               retrieval_started_at, provider_status)
                        VALUES (:requestId, :sessionId, :variantId, CAST(:filters AS jsonb), now(), 'ok')
                        RETURNING id
                        """)
                .param("requestId", requestId)
                .param("sessionId", sessionId)
                .param("variantId", variantId)
                .param("filters", retrievalFiltersJson)
                .query(Long.class)
                .single();
    }

    public void complete(
            long ragRunId, Long messageId, OffsetDateTime retrievalFinished, OffsetDateTime generationStarted,
            OffsetDateTime generationFinished, String embeddingModel, String generationModel,
            Integer promptTokens, Integer completionTokens, String providerStatus, String errorDetail
    ) {
        jdbcClient.sql(
                        """
                        UPDATE rag_runs SET
                            message_id = :messageId,
                            retrieval_finished_at = :retrievalFinished,
                            generation_started_at = :generationStarted,
                            generation_finished_at = :generationFinished,
                            embedding_model = :embeddingModel,
                            generation_model = :generationModel,
                            prompt_tokens = :promptTokens,
                            completion_tokens = :completionTokens,
                            provider_status = :providerStatus,
                            error_detail = :errorDetail
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
                .update();
    }

    public void recordEvidence(long ragRunId, List<RetrievedChunk> chunks) {
        int rank = 1;
        for (RetrievedChunk chunk : chunks) {
            jdbcClient.sql(
                            """
                            INSERT INTO retrieved_evidence (rag_run_id, chunk_id, rank, vector_score, text_score, fused_score)
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

    /** Package-private: used only right after a run this same request created, so no visitor check needed. */
    RagRunDebugDto findDebugByRequestId(UUID requestId) {
        return jdbcClient.sql(
                        """
                        SELECT request_id, variant_id, embedding_model, generation_model,
                               prompt_tokens, completion_tokens, provider_status, error_detail,
                               EXTRACT(EPOCH FROM (retrieval_finished_at - retrieval_started_at)) * 1000 AS retrieval_ms,
                               EXTRACT(EPOCH FROM (generation_finished_at - generation_started_at)) * 1000 AS generation_ms
                        FROM rag_runs WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((rs, rowNum) -> new RagRunDebugDto(
                        rs.getString("request_id"),
                        rs.getLong("variant_id"),
                        rs.getString("embedding_model"),
                        rs.getString("generation_model"),
                        (Integer) rs.getObject("prompt_tokens"),
                        (Integer) rs.getObject("completion_tokens"),
                        rs.getObject("retrieval_ms") == null ? null : rs.getLong("retrieval_ms"),
                        rs.getObject("generation_ms") == null ? null : rs.getLong("generation_ms"),
                        rs.getString("provider_status"),
                        rs.getString("error_detail")
                ))
                .single();
    }

    /** Visitor-scoped: returns empty unless this rag_run's session belongs to the given visitor. */
    public java.util.Optional<RagRunDebugDto> findDebugForVisitor(UUID requestId, UUID visitorId) {
        return jdbcClient.sql(
                        """
                        SELECT r.request_id, r.variant_id, r.embedding_model, r.generation_model,
                               r.prompt_tokens, r.completion_tokens, r.provider_status, r.error_detail,
                               EXTRACT(EPOCH FROM (r.retrieval_finished_at - r.retrieval_started_at)) * 1000 AS retrieval_ms,
                               EXTRACT(EPOCH FROM (r.generation_finished_at - r.generation_started_at)) * 1000 AS generation_ms
                        FROM rag_runs r
                        JOIN diagnostic_sessions s ON s.id = r.session_id
                        WHERE r.request_id = :requestId AND s.visitor_id = :visitorId
                        """)
                .param("requestId", requestId)
                .param("visitorId", visitorId)
                .query((rs, rowNum) -> new RagRunDebugDto(
                        rs.getString("request_id"),
                        rs.getLong("variant_id"),
                        rs.getString("embedding_model"),
                        rs.getString("generation_model"),
                        (Integer) rs.getObject("prompt_tokens"),
                        (Integer) rs.getObject("completion_tokens"),
                        rs.getObject("retrieval_ms") == null ? null : rs.getLong("retrieval_ms"),
                        rs.getObject("generation_ms") == null ? null : rs.getLong("generation_ms"),
                        rs.getString("provider_status"),
                        rs.getString("error_detail")
                ))
                .optional();
    }

    public List<EvidenceCardDto> findEvidenceForVisitor(UUID requestId, UUID visitorId) {
        return jdbcClient.sql(
                        """
                        SELECT c.id AS chunk_id, c.document_id, d.title, d.provenance, d.source_url,
                               c.heading, c.section_path, c.page_number, c.content,
                               re.vector_score, re.text_score, re.fused_score
                        FROM rag_runs r
                        JOIN diagnostic_sessions s ON s.id = r.session_id
                        JOIN retrieved_evidence re ON re.rag_run_id = r.id
                        JOIN document_chunks c ON c.id = re.chunk_id
                        JOIN documents d ON d.id = c.document_id
                        WHERE r.request_id = :requestId AND s.visitor_id = :visitorId
                        ORDER BY re.rank
                        """)
                .param("requestId", requestId)
                .param("visitorId", visitorId)
                .query((rs, rowNum) -> new EvidenceCardDto(
                        rs.getLong("chunk_id"), rs.getLong("document_id"), rs.getString("title"),
                        rs.getString("provenance"), rs.getString("source_url"),
                        rs.getString("heading"), rs.getString("section_path"),
                        (Integer) rs.getObject("page_number"), rs.getString("content"),
                        nullableDouble(rs, "vector_score"), nullableDouble(rs, "text_score"),
                        rs.getDouble("fused_score")
                ))
                .list();
    }

    private static Double nullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.doubleValue() : null;
    }

}
