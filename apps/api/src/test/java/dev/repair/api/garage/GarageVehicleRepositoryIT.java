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
 * history, and preferences are isolated per visitor and per garage
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

    @Test
    void oneVisitorCannotSeeAnotherVisitorsGarageVehicle() {
        long modelId = seedModel();
        UUID visitorA = UUID.randomUUID();
        UUID visitorB = UUID.randomUUID();
        long vehicleId = garageVehicleRepository.create(visitorA, modelId, 2025, null, "A's bike");

        assertThat(garageVehicleRepository.find(visitorA, vehicleId)).isPresent();
        assertThat(garageVehicleRepository.find(visitorB, vehicleId)).isEmpty();
        assertThat(garageVehicleRepository.list(visitorB)).isEmpty();
        assertThat(garageVehicleRepository.isOwned(visitorB, vehicleId)).isFalse();
    }

    @Test
    void findExistingReusesTheSameCanonicalBikeForOneVisitor() {
        long modelId = seedModel();
        UUID visitor = UUID.randomUUID();
        long firstId = garageVehicleRepository.create(visitor, modelId, 2021, null, null);

        var found = garageVehicleRepository.findExisting(visitor, modelId, 2021);

        assertThat(found).contains(firstId);
    }

    @Test
    void findExistingDoesNotMatchADifferentYearOrAnotherVisitor() {
        long modelId = seedModel();
        UUID visitor = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        garageVehicleRepository.create(visitor, modelId, 2021, null, null);

        assertThat(garageVehicleRepository.findExisting(visitor, modelId, 2022)).isEmpty();
        assertThat(garageVehicleRepository.findExisting(stranger, modelId, 2021)).isEmpty();
    }

    @Test
    void findExistingIgnoresASoftDeletedVehicle() {
        long modelId = seedModel();
        UUID visitor = UUID.randomUUID();
        long id = garageVehicleRepository.create(visitor, modelId, 2021, null, null);
        garageVehicleRepository.softDelete(visitor, id);

        assertThat(garageVehicleRepository.findExisting(visitor, modelId, 2021)).isEmpty();
    }

    @Test
    void deletedVehicleNoLongerAppearsForItsOwner() {
        long modelId = seedModel();
        UUID visitor = UUID.randomUUID();
        long vehicleId = garageVehicleRepository.create(visitor, modelId, 2025, null, null);

        boolean deleted = garageVehicleRepository.softDelete(visitor, vehicleId);

        assertThat(deleted).isTrue();
        assertThat(garageVehicleRepository.find(visitor, vehicleId)).isEmpty();
        assertThat(garageVehicleRepository.list(visitor)).isEmpty();
    }

    @Test
    void maintenanceHistoryDoesNotLeakBetweenTwoVehiclesOfTheSameVisitor() {
        long modelId = seedModel();
        UUID visitor = UUID.randomUUID();
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
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        long vehicleId = garageVehicleRepository.create(owner, modelId, 2025, null, null);
        maintenanceRepository.createEvent(vehicleId, "ENGINE_OIL_CHANGE", 19000.0, null, null, "manual");
        preferenceRepository.upsert(vehicleId, "TIRE_PRESSURE", "OFF_ROAD", "{\"frontKpa\":190,\"rearKpa\":200}");

        assertThat(maintenanceRepository.listForOwnedVehicle(stranger, vehicleId)).isEmpty();
        assertThat(preferenceRepository.listForOwnedVehicle(stranger, vehicleId)).isEmpty();
        assertThat(maintenanceRepository.listForOwnedVehicle(owner, vehicleId)).hasSize(1);
        assertThat(preferenceRepository.listForOwnedVehicle(owner, vehicleId)).hasSize(1);
    }

    @Test
    void preferenceUpsertNeverTouchesTheManufacturerFactsTable() {
        long modelId = seedModel();
        UUID visitor = UUID.randomUUID();
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
