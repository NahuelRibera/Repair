package dev.repair.api.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.repair.api.TestcontainersConfiguration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end ownership isolation (real security filter chain, real Postgres):
 * rider B must get 404 (never 403, which would reveal that the vehicle exists)
 * on every maintenance, dashboard and preferences endpoint of rider A's
 * garage vehicle, and rider B's attempts must leave A's data untouched.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class CrossUserIsolationIT {

    private static final String EVENT_BODY = "{\"serviceType\":\"ENGINE_OIL_CHANGE\",\"odometerKm\":12000}";
    private static final String PREFERENCE_BODY =
            "{\"preferenceType\":\"TIRE_PRESSURE\",\"context\":\"ROAD\",\"data\":{\"frontBar\":2.5}}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private GarageVehicleRepository garageVehicleRepository;
    @Autowired
    private MaintenanceRepository maintenanceRepository;

    private String subA;
    private String subB;
    private long vehicleA;
    private long vehicleB;

    @BeforeEach
    void seed() {
        subA = "isolation-a-" + UUID.randomUUID();
        subB = "isolation-b-" + UUID.randomUUID();
        long userA = seedUser(subA);
        long userB = seedUser(subB);
        long model = seedModel();
        vehicleA = garageVehicleRepository.create(userA, model, 2021, null, "A's bike");
        vehicleB = garageVehicleRepository.create(userB, model, 2021, null, "B's bike");
        maintenanceRepository.createEvent(vehicleA, "ENGINE_OIL_CHANGE", 5000.0, null, "A's secret note", "manual");
    }

    private OidcLoginRequestPostProcessor asA() {
        return oidcLogin().idToken(t -> t.subject(subA));
    }

    private OidcLoginRequestPostProcessor asB() {
        return oidcLogin().idToken(t -> t.subject(subB));
    }

    @Test
    void otherUserGets404WhenListingMaintenance() throws Exception {
        mockMvc.perform(get("/api/garage/vehicles/{id}/maintenance", vehicleA).with(asB()))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherUserGets404WhenCreatingMaintenanceAndNothingIsWritten() throws Exception {
        mockMvc.perform(post("/api/garage/vehicles/{id}/maintenance", vehicleA).with(asB()).with(csrf())
                        .contentType("application/json").content(EVENT_BODY))
                .andExpect(status().isNotFound());

        assertThat(maintenanceRepository.listForOwnedVehicle(userIdOf(subA), vehicleA)).hasSize(1);
    }

    @Test
    void otherUserGets404WhenEditingOrDeletingMaintenanceAndNothingChanges() throws Exception {
        long eventId = maintenanceRepository.listForOwnedVehicle(userIdOf(subA), vehicleA).get(0).id();

        mockMvc.perform(patch("/api/garage/vehicles/{id}/maintenance/{e}", vehicleA, eventId).with(asB()).with(csrf())
                        .contentType("application/json").content(EVENT_BODY))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/garage/vehicles/{id}/maintenance/{e}", vehicleA, eventId).with(asB()).with(csrf()))
                .andExpect(status().isNotFound());
        // B's own vehicle id combined with A's event id must not reach A's row either.
        mockMvc.perform(delete("/api/garage/vehicles/{id}/maintenance/{e}", vehicleB, eventId).with(asB()).with(csrf()))
                .andExpect(status().isNotFound());

        var events = maintenanceRepository.listForOwnedVehicle(userIdOf(subA), vehicleA);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).odometerKm()).isEqualTo(5000.0);
    }

    @Test
    void otherUserGets404WhenEditingAnotherUsersEventThroughTheirOwnVehicle() throws Exception {
        var original = maintenanceRepository.listForOwnedVehicle(userIdOf(subA), vehicleA).get(0);

        // B owns vehicleB, so the vehicle ownership check passes; only the update's own scoping protects A's row.
        mockMvc.perform(patch("/api/garage/vehicles/{id}/maintenance/{e}", vehicleB, original.id()).with(asB()).with(csrf())
                        .contentType("application/json").content(EVENT_BODY))
                .andExpect(status().isNotFound());

        var events = maintenanceRepository.listForOwnedVehicle(userIdOf(subA), vehicleA);
        assertThat(events).containsExactly(original);
        assertThat(maintenanceRepository.listForOwnedVehicle(userIdOf(subB), vehicleB)).isEmpty();
    }

    @Test
    void ownerEditsAndDeletesOwnEventAndDashboardFollows() throws Exception {
        long eventId = maintenanceRepository.listForOwnedVehicle(userIdOf(subA), vehicleA).get(0).id();
        // The seeded model has no interval facts, so a recorded service shows as TRACKED and an unrecorded one as UNKNOWN.
        mockMvc.perform(get("/api/garage/vehicles/{id}/dashboard", vehicleA).with(asA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.serviceType == 'ENGINE_OIL_CHANGE')].status").value("TRACKED"))
                .andExpect(jsonPath("$[?(@.serviceType == 'ENGINE_OIL_CHANGE')].lastOdometerKm").value(5000.0))
                .andExpect(jsonPath("$[?(@.serviceType == 'CHAIN_LUBE')].status").value("UNKNOWN"));

        mockMvc.perform(patch("/api/garage/vehicles/{id}/maintenance/{e}", vehicleA, eventId).with(asA()).with(csrf())
                        .contentType("application/json")
                        .content("{\"serviceType\":\"CHAIN_LUBE\",\"odometerKm\":7300,"
                                + "\"performedAt\":\"2026-03-01\",\"notes\":\"Edited note\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId))
                .andExpect(jsonPath("$.serviceType").value("CHAIN_LUBE"))
                .andExpect(jsonPath("$.odometerKm").value(7300.0))
                .andExpect(jsonPath("$.performedAt").value("2026-03-01"))
                .andExpect(jsonPath("$.notes").value("Edited note"))
                .andExpect(jsonPath("$.createdVia").value("manual"));
        var edited = maintenanceRepository.listForOwnedVehicle(userIdOf(subA), vehicleA);
        assertThat(edited).hasSize(1);
        assertThat(edited.get(0).serviceType()).isEqualTo("CHAIN_LUBE");
        assertThat(edited.get(0).odometerKm()).isEqualTo(7300.0);
        assertThat(edited.get(0).performedAt()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(edited.get(0).notes()).isEqualTo("Edited note");
        assertThat(edited.get(0).createdVia()).isEqualTo("manual");

        mockMvc.perform(get("/api/garage/vehicles/{id}/dashboard", vehicleA).with(asA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.serviceType == 'ENGINE_OIL_CHANGE')].status").value("UNKNOWN"))
                .andExpect(jsonPath("$[?(@.serviceType == 'CHAIN_LUBE')].status").value("TRACKED"))
                .andExpect(jsonPath("$[?(@.serviceType == 'CHAIN_LUBE')].lastOdometerKm").value(7300.0))
                .andExpect(jsonPath("$[?(@.serviceType == 'CHAIN_LUBE')].lastPerformedAt").value("2026-03-01"));

        mockMvc.perform(patch("/api/garage/vehicles/{id}/maintenance/{e}", vehicleA, eventId).with(asA()).with(csrf())
                        .contentType("application/json").content("{\"serviceType\":\"BOGUS\",\"odometerKm\":1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/garage/vehicles/{id}/maintenance/{e}", vehicleA, eventId).with(asA()).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/garage/vehicles/{id}/maintenance/{e}", vehicleA, eventId).with(asA()).with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(maintenanceRepository.listForOwnedVehicle(userIdOf(subA), vehicleA)).isEmpty();
        assertThat(maintenanceRepository.latestEventByType(vehicleA)).isEmpty();
        mockMvc.perform(get("/api/garage/vehicles/{id}/dashboard", vehicleA).with(asA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.serviceType == 'ENGINE_OIL_CHANGE')].status").value("UNKNOWN"))
                .andExpect(jsonPath("$[?(@.serviceType == 'CHAIN_LUBE')].status").value("UNKNOWN"))
                .andExpect(jsonPath("$[?(@.serviceType == 'CHAIN_LUBE')].lastOdometerKm").value(contains(nullValue())));
    }

    @Test
    void ownerEditWithNegativeOdometerIsRejectedAndNothingChanges() throws Exception {
        var original = maintenanceRepository.listForOwnedVehicle(userIdOf(subA), vehicleA).get(0);

        mockMvc.perform(patch("/api/garage/vehicles/{id}/maintenance/{e}", vehicleA, original.id()).with(asA()).with(csrf())
                        .contentType("application/json").content("{\"serviceType\":\"ENGINE_OIL_CHANGE\",\"odometerKm\":-1}"))
                .andExpect(status().isBadRequest());

        assertThat(maintenanceRepository.listForOwnedVehicle(userIdOf(subA), vehicleA)).containsExactly(original);
    }

    @Test
    void otherUserGets404ForTheDashboard() throws Exception {
        mockMvc.perform(get("/api/garage/vehicles/{id}/dashboard", vehicleA).with(asB()))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherUserGets404WhenListingPreferences() throws Exception {
        mockMvc.perform(get("/api/garage/vehicles/{id}/preferences", vehicleA).with(asB()))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherUserGets404WhenSavingPreferencesAndNothingIsWritten() throws Exception {
        mockMvc.perform(post("/api/garage/vehicles/{id}/preferences", vehicleA).with(asB()).with(csrf())
                        .contentType("application/json").content(PREFERENCE_BODY))
                .andExpect(status().isNotFound());

        Long rows = jdbcClient.sql("SELECT count(*) FROM vehicle_preferences WHERE garage_vehicle_id = :v")
                .param("v", vehicleA).query(Long.class).single();
        assertThat(rows).isZero();
    }

    @Test
    void nonexistentVehicleIsIndistinguishableFromAnotherUsersVehicle() throws Exception {
        mockMvc.perform(get("/api/garage/vehicles/{id}/maintenance", Long.MAX_VALUE - 1).with(asB()))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerStillReadsAndWritesOwnData() throws Exception {
        mockMvc.perform(get("/api/garage/vehicles/{id}/maintenance", vehicleA).with(asA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/garage/vehicles/{id}/dashboard", vehicleA).with(asA()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/garage/vehicles/{id}/preferences", vehicleA).with(asA()).with(csrf())
                        .contentType("application/json").content(PREFERENCE_BODY))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/garage/vehicles/{id}/maintenance", vehicleA).with(asA()).with(csrf())
                        .contentType("application/json").content(EVENT_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void riderBSeesOnlyAnEmptyHistoryForTheirOwnVehicle() throws Exception {
        mockMvc.perform(get("/api/garage/vehicles/{id}/maintenance", vehicleB).with(asB()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private long userIdOf(String sub) {
        return jdbcClient.sql("SELECT id FROM app_users WHERE google_sub = :s").param("s", sub)
                .query(Long.class).single();
    }

    private long seedUser(String sub) {
        return jdbcClient.sql(
                        "INSERT INTO app_users (google_sub, email, display_name) VALUES (:sub, :email, :name) RETURNING id")
                .param("sub", sub).param("email", sub + "@example.test").param("name", "Rider")
                .query(Long.class).single();
    }

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
}
