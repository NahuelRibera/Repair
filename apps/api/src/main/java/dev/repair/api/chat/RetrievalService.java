package dev.repair.api.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Hybrid retrieval: pgvector cosine similarity plus PostgreSQL full-text
 * search, combined with reciprocal rank fusion (RRF). Both legs are always
 * scoped to documents applicable to the given vehicle (exact variant,
 * model-wide, or explicitly generic) — a chunk from an incompatible
 * vehicle is excluded before ranking, not filtered afterward.
 */
@Service
public class RetrievalService {

    private static final int RRF_K = 60;
    private static final int CANDIDATE_POOL_SIZE = 20;

    private final JdbcClient jdbcClient;

    public RetrievalService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<RetrievedChunk> hybridSearch(long variantId, long modelId, String queryText, float[] queryEmbedding, int limit) {
        String vectorLiteral = toVectorLiteral(queryEmbedding);

        List<ScoredRow> vectorRows = jdbcClient.sql(
                        """
                        SELECT c.id, c.document_id, d.title, d.provenance, d.source_url,
                               c.heading, c.section_path, c.page_number, c.content,
                               1 - (c.embedding <=> CAST(:embedding AS vector)) AS score
                        FROM document_chunks c
                        JOIN documents d ON d.id = c.document_id
                        WHERE c.embedding IS NOT NULL AND EXISTS (
                            SELECT 1 FROM document_vehicle_links l
                            WHERE l.document_id = d.id
                              AND (l.variant_id = :variantId OR l.model_id = :modelId OR l.applicability = 'generic')
                        )
                        ORDER BY c.embedding <=> CAST(:embedding AS vector)
                        LIMIT :limit
                        """)
                .param("embedding", vectorLiteral)
                .param("variantId", variantId)
                .param("modelId", modelId)
                .param("limit", CANDIDATE_POOL_SIZE)
                .query(ScoredRow::fromResultSet)
                .list();

        List<ScoredRow> textRows = jdbcClient.sql(
                        """
                        SELECT c.id, c.document_id, d.title, d.provenance, d.source_url,
                               c.heading, c.section_path, c.page_number, c.content,
                               ts_rank(to_tsvector('english', c.content), plainto_tsquery('english', :query)) AS score
                        FROM document_chunks c
                        JOIN documents d ON d.id = c.document_id
                        WHERE plainto_tsquery('english', :query) @@ to_tsvector('english', c.content)
                          AND EXISTS (
                            SELECT 1 FROM document_vehicle_links l
                            WHERE l.document_id = d.id
                              AND (l.variant_id = :variantId OR l.model_id = :modelId OR l.applicability = 'generic')
                          )
                        ORDER BY score DESC
                        LIMIT :limit
                        """)
                .param("query", queryText)
                .param("variantId", variantId)
                .param("modelId", modelId)
                .param("limit", CANDIDATE_POOL_SIZE)
                .query(ScoredRow::fromResultSet)
                .list();

        return fuse(vectorRows, textRows, limit);
    }

    private List<RetrievedChunk> fuse(List<ScoredRow> vectorRows, List<ScoredRow> textRows, int limit) {
        Map<Long, ScoredRow> byId = new LinkedHashMap<>();
        Map<Long, Double> rrfScore = new LinkedHashMap<>();
        Map<Long, Double> vectorScoreById = new LinkedHashMap<>();
        Map<Long, Double> textScoreById = new LinkedHashMap<>();

        for (int rank = 0; rank < vectorRows.size(); rank++) {
            ScoredRow row = vectorRows.get(rank);
            byId.put(row.id(), row);
            vectorScoreById.put(row.id(), row.score());
            rrfScore.merge(row.id(), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        for (int rank = 0; rank < textRows.size(); rank++) {
            ScoredRow row = textRows.get(rank);
            byId.putIfAbsent(row.id(), row);
            textScoreById.put(row.id(), row.score());
            rrfScore.merge(row.id(), 1.0 / (RRF_K + rank + 1), Double::sum);
        }

        List<RetrievedChunk> results = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            long id = entry.getKey();
            ScoredRow row = entry.getValue();
            results.add(new RetrievedChunk(
                    row.id(), row.documentId(), row.title(), row.provenance(), row.sourceUrl(),
                    row.heading(), row.sectionPath(), row.pageNumber(), row.content(),
                    vectorScoreById.get(id), textScoreById.get(id), rrfScore.get(id)
            ));
        }
        results.sort((a, b) -> Double.compare(b.fusedScore(), a.fusedScore()));
        return results.size() > limit ? results.subList(0, limit) : results;
    }

    private static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }

    private record ScoredRow(
            long id, long documentId, String title, String provenance, String sourceUrl,
            String heading, String sectionPath, Integer pageNumber, String content, double score
    ) {
        static ScoredRow fromResultSet(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            return new ScoredRow(
                    rs.getLong("id"), rs.getLong("document_id"), rs.getString("title"),
                    rs.getString("provenance"), rs.getString("source_url"),
                    rs.getString("heading"), rs.getString("section_path"),
                    (Integer) rs.getObject("page_number"), rs.getString("content"), rs.getDouble("score")
            );
        }
    }
}
