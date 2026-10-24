package dev.repair.api.motochat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Hybrid retrieval hard-filtered to one exact (model, year) pair before
 * any ranking happens — the motorcycle-specific counterpart of
 * dev.repair.api.chat.RetrievalService. A chunk from a document whose
 * year range doesn't cover the selected year, or whose model doesn't
 * match, is excluded from both candidate pools outright: it can
 * structurally never be ranked, not merely down-weighted. See
 * docs/repair-v2-architecture.md section 3 ("hard vehicle filtering").
 */
@Service
public class MotoRetrievalService {

    private static final int RRF_K = 60;
    private static final int CANDIDATE_POOL_SIZE = 20;

    private static final int MAX_QUERY_TERMS = 24;
    private static final Pattern TERM = Pattern.compile("[\\p{L}\\p{N}]+");

    private final JdbcClient jdbcClient;

    public MotoRetrievalService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<MotoRetrievedChunk> hybridSearch(long modelId, int year, String queryText, float[] queryEmbedding, int limit) {
        String vectorLiteral = toVectorLiteral(queryEmbedding);

        List<ScoredRow> vectorRows = jdbcClient.sql(
                        """
                        SELECT c.id, c.document_id, c.section, c.subsection, c.category, c.heading, c.section_path, c.content,
                               1 - (c.embedding <=> CAST(:embedding AS vector)) AS score
                        FROM motorcycle_knowledge_chunks c
                        JOIN motorcycle_knowledge_documents d ON d.id = c.document_id
                        WHERE c.embedding IS NOT NULL
                          AND d.model_id = :modelId AND d.year_from <= :year AND d.year_to >= :year
                        ORDER BY c.embedding <=> CAST(:embedding AS vector)
                        LIMIT :limit
                        """)
                .param("embedding", vectorLiteral)
                .param("modelId", modelId)
                .param("year", year)
                .param("limit", CANDIDATE_POOL_SIZE)
                .query(ScoredRow::fromResultSet)
                .list();

        List<ScoredRow> textRows = textSearch(modelId, year, queryText);

        return fuse(vectorRows, textRows, limit);
    }

    /**
     * Full-text candidates for any of the question's terms, ranked by how many
     * (and how well) they match. plainto_tsquery would AND every word, so a long
     * natural question rarely matched a single chunk. The model and year filter
     * is identical to the vector pool, and the tsvector expression is unchanged
     * so the GIN index stays usable.
     */
    private List<ScoredRow> textSearch(long modelId, int year, String queryText) {
        String tsQuery = toOrTsQuery(queryText);
        if (tsQuery.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql(
                        """
                        SELECT c.id, c.document_id, c.section, c.subsection, c.category, c.heading, c.section_path, c.content,
                               ts_rank(to_tsvector('english', c.content), to_tsquery('english', :query)) AS score
                        FROM motorcycle_knowledge_chunks c
                        JOIN motorcycle_knowledge_documents d ON d.id = c.document_id
                        WHERE to_tsquery('english', :query) @@ to_tsvector('english', c.content)
                          AND d.model_id = :modelId AND d.year_from <= :year AND d.year_to >= :year
                        ORDER BY score DESC
                        LIMIT :limit
                        """)
                .param("query", tsQuery)
                .param("modelId", modelId)
                .param("year", year)
                .param("limit", CANDIDATE_POOL_SIZE)
                .query(ScoredRow::fromResultSet)
                .list();
    }

    /**
     * Builds an OR tsquery from letter/digit runs only, so user text can never
     * inject tsquery operators. Stop words are dropped by the 'english'
     * configuration. Returns an empty string when no usable term remains.
     */
    static String toOrTsQuery(String queryText) {
        if (queryText == null) {
            return "";
        }
        Set<String> terms = new LinkedHashSet<>();
        Matcher m = TERM.matcher(queryText.toLowerCase(Locale.ROOT));
        while (m.find() && terms.size() < MAX_QUERY_TERMS) {
            if (m.group().length() >= 2) {
                terms.add(m.group());
            }
        }
        return String.join(" | ", terms);
    }

    private List<MotoRetrievedChunk> fuse(List<ScoredRow> vectorRows, List<ScoredRow> textRows, int limit) {
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

        List<MotoRetrievedChunk> results = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            long id = entry.getKey();
            ScoredRow row = entry.getValue();
            results.add(new MotoRetrievedChunk(
                    row.id(), row.documentId(), row.section(), row.subsection(), row.category(),
                    row.heading(), row.sectionPath(), row.content(),
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
            long id, long documentId, String section, String subsection, String category,
            String heading, String sectionPath, String content, double score
    ) {
        static ScoredRow fromResultSet(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            return new ScoredRow(
                    rs.getLong("id"), rs.getLong("document_id"), rs.getString("section"), rs.getString("subsection"),
                    rs.getString("category"), rs.getString("heading"), rs.getString("section_path"),
                    rs.getString("content"), rs.getDouble("score")
            );
        }
    }
}
