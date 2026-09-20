package dev.repair.api.motorcycle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Entirely dynamic motorcycle catalog: every row here was written by
 * pipelines/embeddings/ingest_motorcycle_knowledge.py from the Markdown
 * corpus under knowledge/motorcycles/. There is no hardcoded manufacturer,
 * model, or year anywhere in this class — adding a new manufacturer to the
 * knowledge corpus and re-running ingestion is the only way a new row ever
 * appears here. See docs/repair-v2-architecture.md section 3.
 */
@Repository
public class MotorcycleCatalogRepository {

    private final JdbcClient jdbcClient;

    public MotorcycleCatalogRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ManufacturerDto> listManufacturers() {
        return jdbcClient.sql(
                        """
                        SELECT id, canonical_name, slug FROM motorcycle_manufacturers
                        ORDER BY LOWER(canonical_name)
                        """)
                .query((rs, rowNum) -> new ManufacturerDto(rs.getLong("id"), rs.getString("canonical_name"), rs.getString("slug")))
                .list();
    }

    public List<ModelDto> listModels(long manufacturerId) {
        return jdbcClient.sql(
                        """
                        SELECT id, manufacturer_id, canonical_name, slug FROM motorcycle_models
                        WHERE manufacturer_id = :manufacturerId
                        ORDER BY LOWER(canonical_name)
                        """)
                .param("manufacturerId", manufacturerId)
                .query((rs, rowNum) -> new ModelDto(
                        rs.getLong("id"), rs.getLong("manufacturer_id"), rs.getString("canonical_name"), rs.getString("slug")))
                .list();
    }

    public Optional<ModelDetailDto> findModelDetail(long modelId) {
        return jdbcClient.sql(
                        """
                        SELECT mm.id, mm.manufacturer_id, mf.canonical_name AS manufacturer_name,
                               mm.canonical_name AS model_name, mm.slug
                        FROM motorcycle_models mm
                        JOIN motorcycle_manufacturers mf ON mf.id = mm.manufacturer_id
                        WHERE mm.id = :modelId
                        """)
                .param("modelId", modelId)
                .query((rs, rowNum) -> new ModelDetailDto(
                        rs.getLong("id"), rs.getLong("manufacturer_id"), rs.getString("manufacturer_name"),
                        rs.getString("model_name"), rs.getString("slug")))
                .optional();
    }

    /** Every year actually covered by at least one ingested knowledge
     * document for this model, derived from year_from/year_to ranges —
     * never a stored, separately-maintained list. */
    public List<Integer> listYears(long modelId) {
        return jdbcClient.sql(
                        """
                        SELECT DISTINCT y AS year
                        FROM motorcycle_knowledge_documents d, generate_series(d.year_from, d.year_to) AS y
                        WHERE d.model_id = :modelId
                        ORDER BY year DESC
                        """)
                .param("modelId", modelId)
                .query(Integer.class)
                .list();
    }

    /** True only if at least one ingested document actually covers this
     * exact (model, year) pair — the hard-filter precondition retrieval
     * and garage-vehicle creation both rely on. */
    public boolean hasKnowledgeCoverage(long modelId, int year) {
        return jdbcClient.sql(
                        """
                        SELECT count(*) FROM motorcycle_knowledge_documents
                        WHERE model_id = :modelId AND year_from <= :year AND year_to >= :year
                        """)
                .param("modelId", modelId)
                .param("year", year)
                .query(Long.class)
                .single() > 0;
    }

    /** Deterministic, ingestion-extracted facts for this exact
     * (model, year) — never LLM-inferred (see architecture doc section 4).
     * If more than one document matches (e.g. distinct market editions),
     * the first value seen for a given fact_type wins; duplicates across
     * documents in this corpus are expected to agree. */
    public Map<String, MotorcycleFactDto> findFacts(long modelId, int year) {
        Map<String, MotorcycleFactDto> facts = new LinkedHashMap<>();
        jdbcClient.sql(
                        """
                        SELECT f.fact_type, f.value_numeric, f.value_text, f.unit
                        FROM motorcycle_facts f
                        JOIN motorcycle_knowledge_documents d ON d.id = f.document_id
                        WHERE d.model_id = :modelId AND d.year_from <= :year AND d.year_to >= :year
                        ORDER BY f.fact_type
                        """)
                .param("modelId", modelId)
                .param("year", year)
                .query((rs, rowNum) -> new MotorcycleFactDto(
                        rs.getString("fact_type"),
                        nullableDouble(rs, "value_numeric"),
                        rs.getString("value_text"),
                        rs.getString("unit")))
                .list()
                .forEach(fact -> facts.putIfAbsent(fact.factType(), fact));
        return facts;
    }

    private static Double nullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
