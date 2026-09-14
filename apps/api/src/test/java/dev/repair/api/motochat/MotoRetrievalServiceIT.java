package dev.repair.api.motochat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.repair.api.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Real Postgres (Testcontainers), no mocks — proves the hard vehicle
 * filter in MotoRetrievalService actually excludes an incompatible
 * model/year from the candidate pool rather than merely down-ranking it.
 * This is the automated version of spec section 49's contamination
 * checks: an MT-07 query must never surface an MT-09 chunk, MT-09 SP
 * suspension must not fall back to base MT-09 suspension, and a document
 * outside its own year range must not match.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MotoRetrievalServiceIT {

    @Autowired
    private MotoRetrievalService retrievalService;
    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void chainQueryOnMt07NeverReturnsAnMt09Chunk() {
        long manufacturer = insertManufacturer("Yamaha-" + UUID.randomUUID());
        long mt07 = insertModel(manufacturer, "MT-07-" + UUID.randomUUID());
        long mt09 = insertModel(manufacturer, "MT-09-" + UUID.randomUUID());
        long mt07Doc = insertDocument(mt07, 2025, 2025);
        long mt09Doc = insertDocument(mt09, 2025, 2025);
        insertChunk(mt07Doc, "Periodic maintenance", "Drive chain", "maintenance",
                "MT-07 drive chain slack should be 51.0-56.0 mm, lubricate every 1000 km.");
        insertChunk(mt09Doc, "Periodic maintenance", "Drive chain", "maintenance",
                "MT-09 drive chain slack should be 36.0-41.0 mm, lubricate every 1000 km.");

        List<MotoRetrievedChunk> results = retrievalService.hybridSearch(
                mt07, 2025, "drive chain slack lubrication", zeroVector(), 10);

        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(c -> c.content().contains("MT-07"));
        assertThat(results).noneMatch(c -> c.content().contains("MT-09 drive"));
    }

    @Test
    void mt09SpSuspensionIsNotReplacedByStandardMt09Suspension() {
        long manufacturer = insertManufacturer("Yamaha-" + UUID.randomUUID());
        long mt09 = insertModel(manufacturer, "MT-09-" + UUID.randomUUID());
        long mt09sp = insertModel(manufacturer, "MT-09 SP-" + UUID.randomUUID());
        long mt09Doc = insertDocument(mt09, 2025, 2025);
        long mt09spDoc = insertDocument(mt09sp, 2025, 2025);
        insertChunk(mt09Doc, "Suspension baseline", "Rear suspension", "other",
                "Standard MT-09 rear suspension shock: manually adjustable preload and rebound damping.");
        insertChunk(mt09spDoc, "Suspension baseline", "Rear suspension", "other",
                "MT-09 SP rear suspension shock: fully adjustable Ohlins unit with electronic support.");

        List<MotoRetrievedChunk> results = retrievalService.hybridSearch(
                mt09sp, 2025, "rear suspension shock adjustment", zeroVector(), 10);

        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(c -> c.content().contains("SP rear suspension shock"));
        assertThat(results).noneMatch(c -> c.content().contains("Standard MT-09"));
    }

    @Test
    void documentOutsideItsYearRangeDoesNotMatch() {
        long manufacturer = insertManufacturer("Yamaha-" + UUID.randomUUID());
        long model = insertModel(manufacturer, "Tenere700-" + UUID.randomUUID());
        long doc = insertDocument(model, 2021, 2023);
        insertChunk(doc, "Periodic maintenance", "Engine oil", "maintenance",
                "Engine oil change interval is every 10000 km for this generation.");

        List<MotoRetrievedChunk> withinRange = retrievalService.hybridSearch(model, 2022, "engine oil change interval", zeroVector(), 10);
        List<MotoRetrievedChunk> outsideRange = retrievalService.hybridSearch(model, 2024, "engine oil change interval", zeroVector(), 10);

        assertThat(withinRange).isNotEmpty();
        assertThat(outsideRange).isEmpty();
    }

    private long insertManufacturer(String name) {
        return jdbcClient.sql("INSERT INTO motorcycle_manufacturers (canonical_name, slug) VALUES (:n, :s) RETURNING id")
                .param("n", name).param("s", name.toLowerCase())
                .query(Long.class).single();
    }

    private long insertModel(long manufacturerId, String name) {
        return jdbcClient.sql(
                        "INSERT INTO motorcycle_models (manufacturer_id, canonical_name, slug) VALUES (:m, :n, :s) RETURNING id")
                .param("m", manufacturerId).param("n", name).param("s", name.toLowerCase())
                .query(Long.class).single();
    }

    private long insertDocument(long modelId, int yearFrom, int yearTo) {
        String path = "test/" + UUID.randomUUID() + ".md";
        return jdbcClient.sql(
                        """
                        INSERT INTO motorcycle_knowledge_documents
                            (model_id, year_from, year_to, source_relative_path, content_hash)
                        VALUES (:m, :yf, :yt, :p, :h) RETURNING id
                        """)
                .param("m", modelId).param("yf", yearFrom).param("yt", yearTo)
                .param("p", path).param("h", UUID.randomUUID().toString())
                .query(Long.class).single();
    }

    private void insertChunk(long documentId, String section, String subsection, String category, String content) {
        jdbcClient.sql(
                        """
                        INSERT INTO motorcycle_knowledge_chunks
                            (document_id, section, subsection, category, heading, section_path, content, content_hash)
                        VALUES (:d, :sec, :sub, :cat, :sub, :sec || ' > ' || :sub, :content, :hash)
                        """)
                .param("d", documentId).param("sec", section).param("sub", subsection).param("cat", category)
                .param("content", content).param("hash", UUID.randomUUID().toString())
                .update();
    }

    private float[] zeroVector() {
        float[] v = new float[1536];
        return v;
    }
}
