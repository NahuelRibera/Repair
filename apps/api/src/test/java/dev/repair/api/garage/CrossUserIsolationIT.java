package dev.repair.api.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.repair.api.TestcontainersConfiguration;
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
