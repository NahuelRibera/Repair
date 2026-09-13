package dev.repair.api.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import dev.repair.api.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Real Postgres (Testcontainers), no mocks. Reproduces and verifies the
 * fix for a real bug: the frontend's manufacturer/model dropdowns always
 * requested a small fixed page size, so any manufacturer or model sorting
 * alphabetically past that cutoff (e.g. "BMW 3 Series Sedan," which sorts
 * around position 30 among BMW's ~100 real models) could never be reached
 * by browsing. The fix is a much larger MAX_PAGE_SIZE in
 * CatalogueController plus the frontend requesting a size that comfortably
 * covers the real catalogue's per-manufacturer maximums — this test
 * proves the repository/pagination contract those rely on.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CatalogueRepositoryIT {

    @Autowired
    private CatalogueRepository repository;
    @Autowired
    private JdbcClient jdbcClient;

    private long manufacturerId;

    @BeforeEach
    void seedManyModelsPastASmallPageCutoff() {
        manufacturerId = jdbcClient.sql(
                        "INSERT INTO manufacturers (canonical_name, slug) VALUES (:n, :s) RETURNING id")
                .param("n", "ZTestBrand-" + UUID.randomUUID())
                .param("s", "ztest-brand-" + UUID.randomUUID())
                .query(Long.class).single();

        // 25 models named "Model 01".."Model 25" alphabetically sort with
        // "Model 20" before "Target Model", which sorts after "Model 25"
        // too (T > M) — i.e. the target is the LAST alphabetically, well
        // past a naive size=20 cutoff. This mirrors the real bug shape:
        // "BMW 3 Series Sedan" sorted at position 30 of ~100 BMW models.
        for (int i = 1; i <= 25; i++) {
            String name = String.format("Model %02d", i);
            jdbcClient.sql("INSERT INTO vehicle_models (manufacturer_id, model_name, slug) VALUES (:m, :n, :s)")
                    .param("m", manufacturerId)
                    .param("n", name)
                    .param("s", "model-" + i + "-" + UUID.randomUUID())
                    .update();
        }
        jdbcClient.sql("INSERT INTO vehicle_models (manufacturer_id, model_name, slug) VALUES (:m, :n, :s)")
                .param("m", manufacturerId)
                .param("n", "Target Model")
                .param("s", "target-model-" + UUID.randomUUID())
                .update();
    }

    @Test
    void aSmallPageSizeCannotReachAModelSortedPastTheCutoff_reproducesTheBug() {
        PageResultLike smallPage = search(20);

        assertThat(smallPage.total).isEqualTo(26);
        assertThat(smallPage.names).doesNotContain("Target Model");
    }

    @Test
    void aPageSizeCoveringTheRealCatalogueMaximumReachesEveryModel_provesTheFix() {
        PageResultLike fullPage = search(500); // CatalogueController's new MAX_PAGE_SIZE

        assertThat(fullPage.total).isEqualTo(26);
        assertThat(fullPage.names).contains("Target Model");
        assertThat(fullPage.names).hasSize(26);
    }

    @Test
    void findDemoVariantResolvesOnlyAVariantWithRealKnowledgeCoverage() {
        long mfg = jdbcClient.sql(
                        "INSERT INTO manufacturers (canonical_name, slug) VALUES ('DemoTestBMW', :s) RETURNING id")
                .param("s", "demo-test-bmw-" + UUID.randomUUID())
                .query(Long.class).single();
        long model = jdbcClient.sql(
                        "INSERT INTO vehicle_models (manufacturer_id, model_name, slug) VALUES (:m, 'Demo Test 3 Series', :s) RETURNING id")
                .param("m", mfg)
                .param("s", "demo-test-3-series-" + UUID.randomUUID())
                .query(Long.class).single();
        long coveredVariant = insertVariant(model, "Demo Test (E90) 320d 6MT RWD (177 HP)", 2008);
        long uncoveredVariant = insertVariant(model, "Demo Test (E90) 320d 6MT RWD (177 HP)", 2011);
        // Only the 2008 variant gets a document link — the 2011 one has an
        // identical name pattern but zero coverage, so it must not resolve.
        long documentId = jdbcClient.sql(
                        "INSERT INTO documents (title, slug, provenance, evidence_scope, content_hash) " +
                                "VALUES ('t', :slug, 'synthetic_demo', 'vehicle_specific', 'h') RETURNING id")
                .param("slug", "demo-test-doc-" + UUID.randomUUID())
                .query(Long.class).single();
        jdbcClient.sql("INSERT INTO document_vehicle_links (document_id, variant_id, applicability) VALUES (:d, :v, 'exact')")
                .param("d", documentId)
                .param("v", coveredVariant)
                .update();

        var resolved = repository.findDemoVariant("DemoTestBMW", "Demo Test 3 Series", "(E90) 320d", 2008);
        var notResolvedWrongYear = repository.findDemoVariant("DemoTestBMW", "Demo Test 3 Series", "(E90) 320d", 2011);
        var notResolvedUnknownBrand = repository.findDemoVariant("Nonexistent", "Demo Test 3 Series", "(E90) 320d", 2008);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().variant().id()).isEqualTo(coveredVariant);
        assertThat(resolved.get().hasKnowledgeCoverage()).isTrue();
        assertThat(notResolvedWrongYear).as("the 2011 sibling has no document link and must not resolve").isEmpty();
        assertThat(notResolvedUnknownBrand).isEmpty();
        assertThat(uncoveredVariant).isNotEqualTo(coveredVariant); // sanity: both rows really exist and differ
    }

    private long insertVariant(long modelId, String variantName, int yearStart) {
        return jdbcClient.sql(
                        """
                        INSERT INTO vehicle_variants (model_id, variant_name, slug, year_start, provenance)
                        VALUES (:m, :n, :s, :y, 'synthetic_fixture') RETURNING id
                        """)
                .param("m", modelId)
                .param("n", variantName)
                .param("s", "demo-test-variant-" + UUID.randomUUID())
                .param("y", yearStart)
                .query(Long.class).single();
    }

    @Test
    void modelsAreOrderedCaseInsensitively() {
        long mfg = jdbcClient.sql(
                        "INSERT INTO manufacturers (canonical_name, slug) VALUES (:n, :s) RETURNING id")
                .param("n", "CaseTestBrand-" + UUID.randomUUID())
                .param("s", "case-test-brand-" + UUID.randomUUID())
                .query(Long.class).single();
        for (String name : List.of("zebra", "Apple", "banana", "Aardvark")) {
            jdbcClient.sql("INSERT INTO vehicle_models (manufacturer_id, model_name, slug) VALUES (:m, :n, :s)")
                    .param("m", mfg)
                    .param("n", name)
                    .param("s", name.toLowerCase() + "-" + UUID.randomUUID())
                    .update();
        }

        List<String> names = repository.searchModels(mfg, "", 0, 100).items().stream()
                .map(ModelDto::modelName)
                .toList();

        assertThat(names).containsExactly("Aardvark", "Apple", "banana", "zebra");
    }

    private PageResultLike search(int size) {
        var page = repository.searchModels(manufacturerId, "", 0, size);
        return new PageResultLike(page.total(), page.items().stream().map(ModelDto::modelName).toList());
    }

    private record PageResultLike(long total, List<String> names) {
    }
}
