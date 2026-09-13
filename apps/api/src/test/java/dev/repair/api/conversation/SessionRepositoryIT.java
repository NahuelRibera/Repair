package dev.repair.api.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.repair.api.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Real Postgres (Testcontainers, pgvector image, Flyway-migrated — see
 * TestcontainersConfiguration), no mocks. Exercises the property the
 * master spec calls out explicitly: "Anonymous visitors cannot access
 * each other's sessions by guessing/changing IDs." Every query in
 * SessionRepository is scoped by visitor_id in the SQL itself, not just
 * checked in application code afterward — this test proves that holds at
 * the database level.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SessionRepositoryIT {

    @Autowired
    private SessionRepository sessionRepository;
    @Autowired
    private JdbcClient jdbcClient;

    private long variantId;
    private final UUID visitorA = UUID.randomUUID();
    private final UUID visitorB = UUID.randomUUID();

    @BeforeEach
    void seedCatalogue() {
        long manufacturerId = jdbcClient.sql(
                        "INSERT INTO manufacturers (canonical_name, slug) VALUES (:n, :s) RETURNING id")
                .param("n", "TestBrand-" + UUID.randomUUID())
                .param("s", "test-brand-" + UUID.randomUUID())
                .query(Long.class).single();
        long modelId = jdbcClient.sql(
                        "INSERT INTO vehicle_models (manufacturer_id, model_name, slug) VALUES (:m, 'Test Model', :s) RETURNING id")
                .param("m", manufacturerId)
                .param("s", "test-model-" + UUID.randomUUID())
                .query(Long.class).single();
        variantId = jdbcClient.sql(
                        """
                        INSERT INTO vehicle_variants (model_id, variant_name, slug, provenance)
                        VALUES (:m, 'Test Variant', :s, 'synthetic_fixture') RETURNING id
                        """)
                .param("m", modelId)
                .param("s", "test-variant-" + UUID.randomUUID())
                .query(Long.class).single();
    }

    @Test
    void visitorCannotReadAnotherVisitorsSession() {
        long sessionId = sessionRepository.createSession(visitorA, variantId, "A's session");

        var ownerView = sessionRepository.findSessionDetail(visitorA, sessionId);
        var strangerView = sessionRepository.findSessionDetail(visitorB, sessionId);

        assertThat(ownerView).isPresent();
        assertThat(strangerView).isEmpty();
    }

    @Test
    void visitorCannotDeleteAnotherVisitorsSession() {
        long sessionId = sessionRepository.createSession(visitorA, variantId, "A's session");

        boolean strangerDeleted = sessionRepository.softDelete(visitorB, sessionId);
        boolean stillOwnedByA = sessionRepository.isOwnedActiveSession(visitorA, sessionId);

        assertThat(strangerDeleted).isFalse();
        assertThat(stillOwnedByA).isTrue();
    }

    @Test
    void listSessionsOnlyReturnsTheCallingVisitorsOwnSessions() {
        sessionRepository.createSession(visitorA, variantId, "A's session");
        sessionRepository.createSession(visitorB, variantId, "B's session");

        var aSessions = sessionRepository.listSessions(visitorA, null);

        assertThat(aSessions).hasSize(1);
        assertThat(aSessions.get(0).title()).isEqualTo("A's session");
    }

    @Test
    void softDeletedSessionIsExcludedFromListingsAndLookups() {
        long sessionId = sessionRepository.createSession(visitorA, variantId, "To be deleted");

        boolean deleted = sessionRepository.softDelete(visitorA, sessionId);

        assertThat(deleted).isTrue();
        assertThat(sessionRepository.findSessionDetail(visitorA, sessionId)).isEmpty();
        assertThat(sessionRepository.listSessions(visitorA, null)).isEmpty();
    }

    @Test
    void recentMessagesAreReturnedOldestFirstAndBoundedByLimit() {
        long sessionId = sessionRepository.createSession(visitorA, variantId, "Chat");
        for (int i = 0; i < 5; i++) {
            sessionRepository.insertMessage(sessionId, "user", "message " + i, null);
        }

        var lastThree = sessionRepository.recentMessages(sessionId, 3);

        assertThat(lastThree).hasSize(3);
        assertThat(lastThree.get(0).content()).isEqualTo("message 2");
        assertThat(lastThree.get(2).content()).isEqualTo("message 4");
    }
}
