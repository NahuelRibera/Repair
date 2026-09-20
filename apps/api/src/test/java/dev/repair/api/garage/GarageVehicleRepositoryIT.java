package dev.repair.api.garage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.repair.api.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Real Postgres (Testcontainers) — proves garage vehicles, maintenance
 * history, and preferences are isolated per user and per garage
 * vehicle, exactly like dev.repair.api.conversation.SessionRepositoryIT
 * does for car chat sessions. Ownership is baked into every query, not
 * layered on afterward.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GarageVehicleRepositoryIT {

    @Autowired
    private GarageVehicleRepository garageVehicleRepository;
    @Autowired
    private MaintenanceRepository maintenanceRepository;
    @Autowired
    private VehiclePreferenceRepository preferenceRepository;
    @Autowired
    private JdbcClient jdbcClient;

    private long seedModel() {
        long manufacturer = jdbcClient.sql(
                        "INSERT INTO motorcycle_manufacturers (canonical_name, slug) VALUES (:n, :s) RETURNING id")
                .param("n", "Brand-" + UUID.randomUUID()).param("s", UUID.randomUUID().toString())
                .query(Long.class).single();
        return jdbcClient.sql(
                        "INSERT INTO motorcycle_models (manufacturer_id, canonical_name, slug) VALUES (:m, :n, :s) RETURNING id")
                .param("m", manufacturer).param("n", "Model-" + UUID.randomUUID()).param("s", UUID.randomUUID().toString())
                .query(Long.class).single();
    }

    private long seedUser() {
        String sub = "test-sub-" + UUID.randomUUID();
        return jdbcClient.sql(
                        """
                        INSERT INTO app_users (google_sub, email, display_name)
                        VALUES (:sub, :email, :name) RETURNING id
                        """)
                .param("sub", sub).param("email", sub + "@example.test").param("name", "Test Rider")
                .query(Long.class).single();
    }

    @Test
    void oneVisitorCannotSeeAnotherVisitorsGarageVehicle() {
        long modelId = seedModel();
        long visitorA = seedUser();
        long visitorB = seedUser();
        long vehicleId = garageVehicleRepository.create(visitorA, modelId, 2025, null, "A's bike");

        assertThat(garageVehicleRepository.find(visitorA, vehicleId)).isPresent();
        assertThat(garageVehicleRepository.find(visitorB, vehicleId)).isEmpty();
        assertThat(garageVehicleRepository.list(visitorB)).isEmpty();
        assertThat(garageVehicleRepository.isOwned(visitorB, vehicleId)).isFalse();
    }

    @Test
    void findExistingReusesTheSameCanonicalBikeForOneVisitor() {
        long modelId = seedModel();
        long visitor = seedUser();
        long firstId = garageVehicleRepository.create(visitor, modelId, 2021, null, null);

        var found = garageVehicleRepository.findExisting(visitor, modelId, 2021);

        assertThat(found).contains(firstId);
    }

    @Test
    void findExistingDoesNotMatchADifferentYearOrAnotherVisitor() {
        long modelId = seedModel();
        long visitor = seedUser();
        long stranger = seedUser();
        garageVehicleRepository.create(visitor, modelId, 2021, null, null);

        assertThat(garageVehicleRepository.findExisting(visitor, modelId, 2022)).isEmpty();
        assertThat(garageVehicleRepository.findExisting(stranger, modelId, 2021)).isEmpty();
    }

    @Test
    void findExistingIgnoresADeletedVehicle() {
        long modelId = seedModel();
        long visitor = seedUser();
        long id = garageVehicleRepository.create(visitor, modelId, 2021, null, null);
        garageVehicleRepository.deleteVehicleAndAllData(visitor, id);

        assertThat(garageVehicleRepository.findExisting(visitor, modelId, 2021)).isEmpty();
    }

    @Test
    void deletedVehicleNoLongerAppearsForItsOwner() {
        long modelId = seedModel();
        long visitor = seedUser();
        long vehicleId = garageVehicleRepository.create(visitor, modelId, 2025, null, null);

        boolean deleted = garageVehicleRepository.deleteVehicleAndAllData(visitor, vehicleId);

        assertThat(deleted).isTrue();
        assertThat(garageVehicleRepository.find(visitor, vehicleId)).isEmpty();
        assertThat(garageVehicleRepository.list(visitor)).isEmpty();
    }

    @Test
    void maintenanceHistoryDoesNotLeakBetweenTwoVehiclesOfTheSameVisitor() {
        long modelId = seedModel();
        long visitor = seedUser();
        long bikeOne = garageVehicleRepository.create(visitor, modelId, 2025, null, "Bike 1");
        long bikeTwo = garageVehicleRepository.create(visitor, modelId, 2024, null, "Bike 2");

        maintenanceRepository.createEvent(bikeOne, "ENGINE_OIL_CHANGE", 19000.0, null, null, "manual");
        maintenanceRepository.createEvent(bikeTwo, "CHAIN_LUBE", 5000.0, null, null, "manual");

        assertThat(maintenanceRepository.listForOwnedVehicle(visitor, bikeOne)).hasSize(1);
        assertThat(maintenanceRepository.listForOwnedVehicle(visitor, bikeOne).get(0).serviceType()).isEqualTo("ENGINE_OIL_CHANGE");
        assertThat(maintenanceRepository.listForOwnedVehicle(visitor, bikeTwo)).hasSize(1);
        assertThat(maintenanceRepository.listForOwnedVehicle(visitor, bikeTwo).get(0).serviceType()).isEqualTo("CHAIN_LUBE");
    }

    @Test
    void anotherVisitorCannotReadMaintenanceHistoryOrPreferencesForAVehicleTheyDoNotOwn() {
        long modelId = seedModel();
        long owner = seedUser();
        long stranger = seedUser();
        long vehicleId = garageVehicleRepository.create(owner, modelId, 2025, null, null);
        maintenanceRepository.createEvent(vehicleId, "ENGINE_OIL_CHANGE", 19000.0, null, null, "manual");
        preferenceRepository.upsert(vehicleId, "TIRE_PRESSURE", "OFF_ROAD", "{\"frontKpa\":190,\"rearKpa\":200}");

        assertThat(maintenanceRepository.listForOwnedVehicle(stranger, vehicleId)).isEmpty();
        assertThat(preferenceRepository.listForOwnedVehicle(stranger, vehicleId)).isEmpty();
        assertThat(maintenanceRepository.listForOwnedVehicle(owner, vehicleId)).hasSize(1);
        assertThat(preferenceRepository.listForOwnedVehicle(owner, vehicleId)).hasSize(1);
    }

    @Test
    void correctLatestEventUpdatesTheMostRecentlyInsertedEventInPlaceRatherThanAddingASecondOne() {
        // Reproduces the reported bug exactly: "I changed the oil at 20,000
        // km" followed by "I actually changed it at 19,000 km" must leave
        // exactly ONE persisted engine-oil event, at the corrected value —
        // never a stale first row plus a conflicting second one.
        long modelId = seedModel();
        long visitor = seedUser();
        long vehicleId = garageVehicleRepository.create(visitor, modelId, 2025, null, null);
        maintenanceRepository.createEvent(vehicleId, "ENGINE_OIL_CHANGE", 20000.0, null, null, "chat");

        var corrected = maintenanceRepository.correctLatestEvent(vehicleId, "ENGINE_OIL_CHANGE", 19000.0, null, null);

        assertThat(corrected).isPresent();
        assertThat(corrected.get().odometerKm()).isEqualTo(19000.0);
        var history = maintenanceRepository.listForOwnedVehicle(visitor, vehicleId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).odometerKm()).isEqualTo(19000.0);
        assertThat(maintenanceRepository.latestEventByType(vehicleId).get("ENGINE_OIL_CHANGE").odometerKm()).isEqualTo(19000.0);
    }

    @Test
    void correctLatestEventReturnsEmptyWhenNoPriorEventOfThatTypeExists() {
        long modelId = seedModel();
        long visitor = seedUser();
        long vehicleId = garageVehicleRepository.create(visitor, modelId, 2025, null, null);

        var corrected = maintenanceRepository.correctLatestEvent(vehicleId, "SPARK_PLUG_CHANGE", 25000.0, null, null);

        assertThat(corrected).isEmpty();
        assertThat(maintenanceRepository.listForOwnedVehicle(visitor, vehicleId)).isEmpty();
    }

    @Test
    void correctLatestEventNeverTouchesADifferentServiceType() {
        // Guards against the OIL_FILTER/AIR_FILTER/ENGINE_OIL cross-mapping
        // concern: correcting one service type's history must never alter
        // a different service type's most recent event.
        long modelId = seedModel();
        long visitor = seedUser();
        long vehicleId = garageVehicleRepository.create(visitor, modelId, 2025, null, null);
        maintenanceRepository.createEvent(vehicleId, "ENGINE_OIL_CHANGE", 20000.0, null, null, "chat");
        maintenanceRepository.createEvent(vehicleId, "AIR_FILTER_CHANGE", 20000.0, null, null, "chat");

        maintenanceRepository.correctLatestEvent(vehicleId, "ENGINE_OIL_CHANGE", 19000.0, null, null);

        var latestByType = maintenanceRepository.latestEventByType(vehicleId);
        assertThat(latestByType.get("ENGINE_OIL_CHANGE").odometerKm()).isEqualTo(19000.0);
        assertThat(latestByType.get("AIR_FILTER_CHANGE").odometerKm()).isEqualTo(20000.0);
    }

    @Test
    void correctEventAtMileageTargetsTheEventHoldingThatValueNotTheMostRecentlyInsertedOne() {
        // Reproduces the real production bug this method exists to fix:
        // engine oil already exists at 20,000 km (inserted first) and
        // 22,000 km (inserted second, a genuinely separate later event).
        // correctLatestEvent (id-DESC "most recent insertion") would
        // wrongly hit the 22,000 km row here — correctEventAtMileage must
        // instead update the 20,000 km row specifically, because that's
        // the value actually named as being corrected.
        long modelId = seedModel();
        long visitor = seedUser();
        long vehicleId = garageVehicleRepository.create(visitor, modelId, 2025, null, null);
        maintenanceRepository.createEvent(vehicleId, "ENGINE_OIL_CHANGE", 20000.0, null, null, "chat");
        maintenanceRepository.createEvent(vehicleId, "ENGINE_OIL_CHANGE", 22000.0, null, null, "chat");

        var corrected = maintenanceRepository.correctEventAtMileage(vehicleId, "ENGINE_OIL_CHANGE", 20000.0, 19000.0, null, null);

        assertThat(corrected).isPresent();
        assertThat(corrected.get().odometerKm()).isEqualTo(19000.0);
        var history = maintenanceRepository.listForOwnedVehicle(visitor, vehicleId).stream()
                .map(MaintenanceEventDto::odometerKm).toList();
        assertThat(history).containsExactlyInAnyOrder(19000.0, 22000.0); // the 22,000 km event survives untouched
    }

    @Test
    void correctEventAtMileageReturnsEmptyWhenNoEventSitsAtTheNamedOldValue() {
        long modelId = seedModel();
        long visitor = seedUser();
        long vehicleId = garageVehicleRepository.create(visitor, modelId, 2025, null, null);
        maintenanceRepository.createEvent(vehicleId, "ENGINE_OIL_CHANGE", 20000.0, null, null, "chat");

        var corrected = maintenanceRepository.correctEventAtMileage(vehicleId, "ENGINE_OIL_CHANGE", 21000.0, 19000.0, null, null);

        assertThat(corrected).isEmpty();
        assertThat(maintenanceRepository.latestEventByType(vehicleId).get("ENGINE_OIL_CHANGE").odometerKm()).isEqualTo(20000.0);
    }

    @Test
    void correctLatestEventOnlyOverwritesFieldsTheCorrectionActuallySupplies() {
        // Correcting just the mileage must never blank out a previously
        // recorded date (COALESCE against the existing stored value).
        long modelId = seedModel();
        long visitor = seedUser();
        long vehicleId = garageVehicleRepository.create(visitor, modelId, 2025, null, null);
        maintenanceRepository.createEvent(
                vehicleId, "ENGINE_OIL_CHANGE", 20000.0, java.time.LocalDate.of(2026, 1, 5), "first note", "chat");

        var corrected = maintenanceRepository.correctLatestEvent(vehicleId, "ENGINE_OIL_CHANGE", 19000.0, null, null);

        assertThat(corrected).isPresent();
        assertThat(corrected.get().odometerKm()).isEqualTo(19000.0);
        assertThat(corrected.get().performedAt()).isEqualTo(java.time.LocalDate.of(2026, 1, 5));
        assertThat(corrected.get().notes()).isEqualTo("first note");
    }

    @Test
    void latestEventByTypeUsesTheHighestOdometerReadingNotTheNewestRow() {
        // Reproduces the reported bug exactly: a rider entering history
        // out of chronological order ("20,000" then "24,000" then
        // "22,000") must never have the last-inserted row (22,000) treated
        // as "latest" — the bike's own timeline says 24,000 is latest.
        long modelId = seedModel();
        long visitor = seedUser();
        long vehicleId = garageVehicleRepository.create(visitor, modelId, 2025, null, null);
        maintenanceRepository.createEvent(vehicleId, "ENGINE_OIL_CHANGE", 20000.0, null, null, "chat");
        maintenanceRepository.createEvent(vehicleId, "ENGINE_OIL_CHANGE", 24000.0, null, null, "chat");
        maintenanceRepository.createEvent(vehicleId, "ENGINE_OIL_CHANGE", 22000.0, null, null, "chat");

        assertThat(maintenanceRepository.latestEventByType(vehicleId).get("ENGINE_OIL_CHANGE").odometerKm())
                .isEqualTo(24000.0);
    }

    @Test
    void listForOwnedVehicleOrdersHistoryByOdometerDescendingAndRetainsAllEvents() {
        long modelId = seedModel();
        long visitor = seedUser();
        long vehicleId = garageVehicleRepository.create(visitor, modelId, 2025, null, null);
        maintenanceRepository.createEvent(vehicleId, "ENGINE_OIL_CHANGE", 12000.0, null, null, "chat");
        maintenanceRepository.createEvent(vehicleId, "ENGINE_OIL_CHANGE", 24000.0, null, null, "chat");
        maintenanceRepository.createEvent(vehicleId, "ENGINE_OIL_CHANGE", 20000.0, null, null, "chat");

        var history = maintenanceRepository.listForOwnedVehicle(visitor, vehicleId);

        assertThat(history).hasSize(3); // all three real historical events survive — none is deleted/merged
        assertThat(history.stream().map(e -> e.odometerKm()).toList())
                .containsExactly(24000.0, 20000.0, 12000.0);
    }

    @Test
    void deleteVehicleAndAllDataRemovesMaintenanceHistoryPreferencesAndChatData() {
        long modelId = seedModel();
        long visitor = seedUser();
        long vehicleId = garageVehicleRepository.create(visitor, modelId, 2025, null, null);
        maintenanceRepository.createEvent(vehicleId, "ENGINE_OIL_CHANGE", 20000.0, null, null, "chat");
        preferenceRepository.upsert(vehicleId, "TIRE_PRESSURE", "OFF_ROAD", "{\"frontKpa\":190,\"rearKpa\":200}");
        long sessionId = jdbcClient.sql(
                        "INSERT INTO moto_chat_sessions (garage_vehicle_id, title) VALUES (:v, 'Test session') RETURNING id")
                .param("v", vehicleId).query(Long.class).single();
        jdbcClient.sql("INSERT INTO moto_chat_messages (session_id, role, content) VALUES (:s, 'user', 'hello')")
                .param("s", sessionId).update();
        long ragRunId = jdbcClient.sql(
                        """
                        INSERT INTO moto_rag_runs (request_id, session_id, garage_vehicle_id, provider_status)
                        VALUES (:r, :s, :v, 'ok') RETURNING id
                        """)
                .param("r", UUID.randomUUID()).param("s", sessionId).param("v", vehicleId).query(Long.class).single();

        boolean deleted = garageVehicleRepository.deleteVehicleAndAllData(visitor, vehicleId);

        assertThat(deleted).isTrue();
        assertThat(garageVehicleRepository.find(visitor, vehicleId)).isEmpty();
        assertThat(maintenanceRepository.listForOwnedVehicle(visitor, vehicleId)).isEmpty();
        assertThat(preferenceRepository.listForOwnedVehicle(visitor, vehicleId)).isEmpty();
        assertThat(jdbcClient.sql("SELECT count(*) FROM moto_chat_sessions WHERE id = :s")
                .param("s", sessionId).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT count(*) FROM moto_chat_messages WHERE session_id = :s")
                .param("s", sessionId).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT count(*) FROM moto_rag_runs WHERE id = :r")
                .param("r", ragRunId).query(Long.class).single()).isZero();
    }

    @Test
    void deleteVehicleAndAllDataNeverTouchesAnotherUsersVehicleOrData() {
        long modelId = seedModel();
        long ownerA = seedUser();
        long ownerB = seedUser();
        long vehicleA = garageVehicleRepository.create(ownerA, modelId, 2025, null, "A's bike");
        long vehicleB = garageVehicleRepository.create(ownerB, modelId, 2024, null, "B's bike");
        maintenanceRepository.createEvent(vehicleB, "ENGINE_OIL_CHANGE", 15000.0, null, null, "chat");

        boolean deleted = garageVehicleRepository.deleteVehicleAndAllData(ownerA, vehicleA);

        assertThat(deleted).isTrue();
        assertThat(garageVehicleRepository.find(ownerB, vehicleB)).isPresent();
        assertThat(maintenanceRepository.listForOwnedVehicle(ownerB, vehicleB)).hasSize(1);
    }

    @Test
    void deleteVehicleAndAllDataReturnsFalseAndDeletesNothingForANonOwnedOrUnknownVehicle() {
        long modelId = seedModel();
        long owner = seedUser();
        long stranger = seedUser();
        long vehicleId = garageVehicleRepository.create(owner, modelId, 2025, null, null);
        maintenanceRepository.createEvent(vehicleId, "ENGINE_OIL_CHANGE", 15000.0, null, null, "chat");

        assertThat(garageVehicleRepository.deleteVehicleAndAllData(stranger, vehicleId)).isFalse();
        assertThat(garageVehicleRepository.deleteVehicleAndAllData(owner, vehicleId + 999_999L)).isFalse();

        assertThat(garageVehicleRepository.find(owner, vehicleId)).isPresent();
        assertThat(maintenanceRepository.listForOwnedVehicle(owner, vehicleId)).hasSize(1);
    }

    @Test
    void preferenceUpsertNeverTouchesTheManufacturerFactsTable() {
        long modelId = seedModel();
        long visitor = seedUser();
        long vehicleId = garageVehicleRepository.create(visitor, modelId, 2025, null, null);

        preferenceRepository.upsert(vehicleId, "TIRE_PRESSURE", "OFF_ROAD", "{\"frontKpa\":190,\"rearKpa\":200}");
        preferenceRepository.upsert(vehicleId, "TIRE_PRESSURE", "OFF_ROAD", "{\"frontKpa\":195,\"rearKpa\":205}");

        long factCount = jdbcClient.sql("SELECT count(*) FROM motorcycle_facts").query(Long.class).single();
        var prefs = preferenceRepository.listForOwnedVehicle(visitor, vehicleId);

        assertThat(factCount).isZero();
        assertThat(prefs).hasSize(1); // updated in place, not duplicated
        assertThat(prefs.get(0).dataJson()).contains("195");
    }
}
