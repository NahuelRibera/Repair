package dev.repair.api.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.repair.api.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real Postgres: when a service above the odometer is saved with
 * updateOdometer=true, the odometer raise and the maintenance write commit
 * together or not at all — a failed insert or a stale event id must leave
 * the stored odometer exactly as it was.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class MaintenanceOdometerRollbackIT {

    private static final String RAISING_BODY =
            "{\"serviceType\":\"ENGINE_OIL_CHANGE\",\"odometerKm\":12000,\"updateOdometer\":true}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private GarageVehicleRepository garageVehicleRepository;
    @MockitoSpyBean
    private MaintenanceRepository maintenanceRepository;

    private String sub;
    private long userId;
    private long vehicle;

    @BeforeEach
    void seed() {
        sub = "rollback-" + UUID.randomUUID();
        userId = jdbcClient.sql(
                        "INSERT INTO app_users (google_sub, email, display_name) VALUES (:sub, :email, 'Rider') RETURNING id")
                .param("sub", sub).param("email", sub + "@example.test")
                .query(Long.class).single();
        long manufacturer = jdbcClient.sql(
                        "INSERT INTO motorcycle_manufacturers (canonical_name, slug) VALUES (:n, :s) RETURNING id")
                .param("n", "Brand-" + UUID.randomUUID()).param("s", UUID.randomUUID().toString())
                .query(Long.class).single();
        long model = jdbcClient.sql(
                        "INSERT INTO motorcycle_models (manufacturer_id, canonical_name, slug) VALUES (:m, :n, :s) RETURNING id")
                .param("m", manufacturer).param("n", "Model-" + UUID.randomUUID()).param("s", UUID.randomUUID().toString())
                .query(Long.class).single();
        vehicle = garageVehicleRepository.create(userId, model, 2021, null, "Rollback bike");
        garageVehicleRepository.updateOdometerIfOwned(userId, vehicle, 10000.0);
        maintenanceRepository.createEvent(vehicle, "ENGINE_OIL_CHANGE", 5000.0, null, null, "manual");
    }

    private OidcLoginRequestPostProcessor asOwner() {
        return oidcLogin().idToken(t -> t.subject(sub));
    }

    private Double storedOdometer() {
        return garageVehicleRepository.find(userId, vehicle).orElseThrow().currentOdometerKm();
    }

    @Test
    void failedCreateRollsBackTheRaisedOdometer() {
        doThrow(new DataIntegrityViolationException("simulated insert failure"))
                .when(maintenanceRepository).createEvent(eq(vehicle), anyString(), any(), any(), any(), anyString());

        assertThatThrownBy(() -> mockMvc.perform(post("/api/garage/vehicles/{id}/maintenance", vehicle)
                        .with(asOwner()).with(csrf()).contentType("application/json").content(RAISING_BODY)))
                .hasRootCauseInstanceOf(DataIntegrityViolationException.class);

        assertThat(storedOdometer()).isEqualTo(10000.0);
        assertThat(maintenanceRepository.listForOwnedVehicle(userId, vehicle)).hasSize(1);
    }

    @Test
    void updateOfAStaleEventIdReturns404AndRollsBackTheRaisedOdometer() throws Exception {
        mockMvc.perform(patch("/api/garage/vehicles/{id}/maintenance/{e}", vehicle, Long.MAX_VALUE)
                        .with(asOwner()).with(csrf()).contentType("application/json").content(RAISING_BODY))
                .andExpect(status().isNotFound());

        assertThat(storedOdometer()).isEqualTo(10000.0);
    }

    @Test
    void failedUpdateRollsBackTheRaisedOdometer() {
        long eventId = maintenanceRepository.listForOwnedVehicle(userId, vehicle).get(0).id();
        doThrow(new DataIntegrityViolationException("simulated update failure"))
                .when(maintenanceRepository).updateOwnedEvent(anyLong(), anyLong(), anyLong(), anyString(), anyDouble(), any(), any());

        assertThatThrownBy(() -> mockMvc.perform(patch("/api/garage/vehicles/{id}/maintenance/{e}", vehicle, eventId)
                        .with(asOwner()).with(csrf()).contentType("application/json").content(RAISING_BODY)))
                .hasRootCauseInstanceOf(DataIntegrityViolationException.class);

        assertThat(storedOdometer()).isEqualTo(10000.0);
        assertThat(maintenanceRepository.listForOwnedVehicle(userId, vehicle).get(0).odometerKm()).isEqualTo(5000.0);
    }

    @Test
    void successfulCreateCommitsBothTheEventAndTheRaisedOdometer() throws Exception {
        mockMvc.perform(post("/api/garage/vehicles/{id}/maintenance", vehicle)
                        .with(asOwner()).with(csrf()).contentType("application/json").content(RAISING_BODY))
                .andExpect(status().isCreated());

        assertThat(storedOdometer()).isEqualTo(12000.0);
        assertThat(maintenanceRepository.listForOwnedVehicle(userId, vehicle)).hasSize(2);
    }
}
