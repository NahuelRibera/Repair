package dev.repair.api.motochat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import dev.repair.api.TestcontainersConfiguration;
import dev.repair.api.garage.GarageVehicleRepository;
import dev.repair.api.garage.MaintenanceRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Real Postgres: a staged odometer proposal can only be resolved by its
 * owner, in its own session, exactly once; confirming it re-checks the
 * stored data in one transaction; and it never blocks deleting the vehicle.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MotoPendingActionIT {

    @Autowired
    private MotoPendingActionRepository pendingActionRepository;
    @Autowired
    private MotoChatSessionRepository sessionRepository;
    @Autowired
    private MotoChatOrchestrationService orchestrationService;
    @Autowired
    private MaintenanceRepository maintenanceRepository;
    @Autowired
    private JdbcClient jdbcClient;
    @MockitoSpyBean
    private GarageVehicleRepository garageVehicleRepository;

    private long owner;
    private long vehicle;
    private long session;

    private long seedUser() {
        String sub = "pending-" + UUID.randomUUID();
        return jdbcClient.sql(
                        "INSERT INTO app_users (google_sub, email, display_name) VALUES (:sub, :email, 'Rider') RETURNING id")
                .param("sub", sub).param("email", sub + "@example.test")
                .query(Long.class).single();
    }

    @BeforeEach
    void seed() {
        owner = seedUser();
        long manufacturer = jdbcClient.sql(
                        "INSERT INTO motorcycle_manufacturers (canonical_name, slug) VALUES (:n, :s) RETURNING id")
                .param("n", "Brand-" + UUID.randomUUID()).param("s", UUID.randomUUID().toString())
                .query(Long.class).single();
        long model = jdbcClient.sql(
                        "INSERT INTO motorcycle_models (manufacturer_id, canonical_name, slug) VALUES (:m, :n, :s) RETURNING id")
                .param("m", manufacturer).param("n", "Model-" + UUID.randomUUID()).param("s", UUID.randomUUID().toString())
                .query(Long.class).single();
        vehicle = garageVehicleRepository.create(owner, model, 2021, null, "Pending bike");
        session = sessionRepository.createSession(owner, vehicle, "Oil");
    }

    private String status(long actionId) {
        return jdbcClient.sql("SELECT status FROM moto_pending_actions WHERE id = :id")
                .param("id", actionId).query(String.class).single();
    }

    private Double storedOdometer() {
        return garageVehicleRepository.find(owner, vehicle).orElseThrow().currentOdometerKm();
    }

    @Test
    void anotherUserOrAnotherSessionCannotResolveTheAction() {
        long stranger = seedUser();
        long otherSession = sessionRepository.createSession(owner, vehicle, "Other");
        long actionId = pendingActionRepository.createOdometerUpdate(owner, session, vehicle, 18500.0, null);

        assertThat(pendingActionRepository.resolve(stranger, session, actionId, "confirmed")).isEmpty();
        assertThat(pendingActionRepository.resolve(owner, otherSession, actionId, "confirmed")).isEmpty();
        assertThat(status(actionId)).isEqualTo("pending");
    }

    @Test
    void anActionResolvesExactlyOnce() {
        long actionId = pendingActionRepository.createOdometerUpdate(owner, session, vehicle, 18500.0, null);

        assertThat(pendingActionRepository.resolve(owner, session, actionId, "confirmed")).isPresent();
        assertThat(pendingActionRepository.resolve(owner, session, actionId, "confirmed")).isEmpty();
        assertThat(pendingActionRepository.resolve(owner, session, actionId, "dismissed")).isEmpty();
        assertThat(status(actionId)).isEqualTo("confirmed");
    }

    @Test
    void confirmingWritesTheOdometerWithAFreshTimestamp() {
        long actionId = pendingActionRepository.createOdometerUpdate(owner, session, vehicle, 18500.0, null);

        orchestrationService.confirmPendingAction(owner, session, actionId);

        assertThat(storedOdometer()).isEqualTo(18500.0);
        assertThat(garageVehicleRepository.findOdometerUpdatedAt(owner, vehicle)).isPresent();
        assertThat(status(actionId)).isEqualTo("confirmed");
    }

    private void backdate(long actionId, int minutes) {
        jdbcClient.sql("UPDATE moto_pending_actions SET created_at = now() - make_interval(mins => :m) WHERE id = :id")
                .param("m", minutes).param("id", actionId).update();
    }

    @Test
    void confirmingAStaleProposalMarksItExpiredAndWritesNothing() {
        long actionId = pendingActionRepository.createOdometerUpdate(owner, session, vehicle, 18500.0, null);
        backdate(actionId, 31);

        assertThatThrownBy(() -> orchestrationService.confirmPendingAction(owner, session, actionId))
                .isInstanceOf(PendingActionExpiredException.class);

        assertThat(status(actionId)).isEqualTo("expired");
        assertThat(storedOdometer()).isNull();
        assertThat(pendingActionRepository.resolve(owner, session, actionId, "confirmed")).isEmpty();
    }

    @Test
    void aRecentProposalStillConfirmsAndAStaleOneCanNoLongerBeDismissed() {
        long recent = pendingActionRepository.createOdometerUpdate(owner, session, vehicle, 18500.0, null);
        backdate(recent, 29);
        orchestrationService.confirmPendingAction(owner, session, recent);
        assertThat(status(recent)).isEqualTo("confirmed");

        long stale = pendingActionRepository.createOdometerUpdate(owner, session, vehicle, 19000.0, null);
        backdate(stale, 31);
        assertThatThrownBy(() -> orchestrationService.confirmPendingAction(owner, session, stale))
                .isInstanceOf(PendingActionExpiredException.class);
        assertThatThrownBy(() -> orchestrationService.dismissPendingAction(owner, session, stale))
                .isInstanceOf(dev.repair.api.common.NotFoundException.class);
    }

    @Test
    void confirmingBelowTheServiceHistoryMarksTheActionFailedAndWritesNothing() {
        maintenanceRepository.createEvent(vehicle, "ENGINE_OIL_CHANGE", 15000.0, null, null, "manual");
        long actionId = pendingActionRepository.createOdometerUpdate(owner, session, vehicle, 1500.0, null);

        orchestrationService.confirmPendingAction(owner, session, actionId);

        assertThat(storedOdometer()).isNull();
        assertThat(status(actionId)).isEqualTo("failed");
    }

    @Test
    void aWriteThatThrowsRollsBackSoTheActionStaysPending() {
        long actionId = pendingActionRepository.createOdometerUpdate(owner, session, vehicle, 18500.0, null);
        doThrow(new DataAccessResourceFailureException("simulated write failure"))
                .when(garageVehicleRepository).updateOdometerIfOwned(anyLong(), anyLong(), anyDouble());

        assertThatThrownBy(() -> orchestrationService.confirmPendingAction(owner, session, actionId))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(status(actionId)).isEqualTo("pending");
        assertThat(storedOdometer()).isNull();
    }

    @Test
    void deletingTheVehicleRemovesItsPendingActions() {
        long actionId = pendingActionRepository.createOdometerUpdate(owner, session, vehicle, 18500.0, null);

        garageVehicleRepository.deleteVehicleAndAllData(owner, vehicle);

        assertThat(jdbcClient.sql("SELECT count(*) FROM moto_pending_actions WHERE id = :id")
                .param("id", actionId).query(Long.class).single()).isZero();
    }

    @Test
    void appVersionsThatPredateTheTableCanStillDeleteTheVehicle() {
        long actionId = pendingActionRepository.createOdometerUpdate(owner, session, vehicle, 18500.0, null);

        // The pre-V12 delete order, which never mentions moto_pending_actions.
        jdbcClient.sql("DELETE FROM moto_chat_sessions WHERE garage_vehicle_id = :id").param("id", vehicle).update();
        jdbcClient.sql("DELETE FROM garage_vehicles WHERE id = :id").param("id", vehicle).update();

        assertThat(jdbcClient.sql("SELECT count(*) FROM moto_pending_actions WHERE id = :id")
                .param("id", actionId).query(Long.class).single()).isZero();
    }

    @Test
    void aNicknameOnlyEditLeavesTheOdometerTimestampAlone() {
        assertThat(garageVehicleRepository.update(owner, vehicle, "Renamed", null)).isTrue();
        assertThat(garageVehicleRepository.findOdometerUpdatedAt(owner, vehicle)).isEmpty();

        assertThat(garageVehicleRepository.update(owner, vehicle, null, 12000.0)).isTrue();
        assertThat(garageVehicleRepository.findOdometerUpdatedAt(owner, vehicle)).isPresent();
    }
}
