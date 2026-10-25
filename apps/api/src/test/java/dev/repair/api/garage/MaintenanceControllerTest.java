package dev.repair.api.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.repair.api.auth.AppUserDto;
import dev.repair.api.auth.AuthenticatedUserContext;
import dev.repair.api.common.BadRequestException;
import dev.repair.api.common.NotFoundException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class MaintenanceControllerTest {

    private static final long USER_ID = 101L;
    private static final long VEHICLE_ID = 7L;
    private static final long EVENT_ID = 55L;

    @Mock
    private GarageVehicleRepository garageVehicleRepository;
    @Mock
    private MaintenanceRepository maintenanceRepository;
    @Mock
    private VehiclePreferenceRepository preferenceRepository;
    @Mock
    private MaintenanceStatusService statusService;

    @Mock
    private OdometerConsistencyService odometerConsistency;

    private MaintenanceController controller;

    @BeforeEach
    void setUp() {
        AuthenticatedUserContext currentUser = new AuthenticatedUserContext();
        currentUser.setUser(new AppUserDto(USER_ID, "sub", "rider@example.test", "Rider",
                null, OffsetDateTime.now(), OffsetDateTime.now()));
        controller = new MaintenanceController(garageVehicleRepository, maintenanceRepository,
                preferenceRepository, statusService, currentUser, new ObjectMapper(), odometerConsistency);
    }

    private static MaintenanceEventDto dto(double km) {
        return new MaintenanceEventDto(EVENT_ID, VEHICLE_ID, "ENGINE_OIL_CHANGE", km, null, null, "manual", OffsetDateTime.now());
    }

    @Test
    void updateReplacesTheEventAndReturnsIt() {
        when(garageVehicleRepository.isOwned(USER_ID, VEHICLE_ID)).thenReturn(true);
        when(maintenanceRepository.updateOwnedEvent(USER_ID, VEHICLE_ID, EVENT_ID, "ENGINE_OIL_CHANGE", 19000.0, null, "n"))
                .thenReturn(Optional.of(dto(19000.0)));

        var result = controller.updateMaintenance(VEHICLE_ID, EVENT_ID,
                new CreateMaintenanceEventRequest("ENGINE_OIL_CHANGE", 19000.0, null, "n"));

        assertThat(result.odometerKm()).isEqualTo(19000.0);
    }

    @Test
    void creatingAServiceAboveTheOdometerIsRejectedWithoutWriting() {
        when(garageVehicleRepository.isOwned(USER_ID, VEHICLE_ID)).thenReturn(true);
        org.mockito.Mockito.doThrow(new OdometerConflictException(
                OdometerConflictException.SERVICE_ABOVE_ODOMETER, "above", 30000.0))
                .when(odometerConsistency).applyServiceMileage(VEHICLE_ID, 30000.0, false);

        assertThatThrownBy(() -> controller.createMaintenance(VEHICLE_ID,
                new CreateMaintenanceEventRequest("ENGINE_OIL_CHANGE", 30000.0, null, null)))
                .isInstanceOf(OdometerConflictException.class);
        verify(maintenanceRepository, never()).createEvent(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void creatingAServiceWithUpdateOdometerAsksTheServiceToRaiseIt() {
        when(garageVehicleRepository.isOwned(USER_ID, VEHICLE_ID)).thenReturn(true);
        when(maintenanceRepository.createEvent(VEHICLE_ID, "ENGINE_OIL_CHANGE", 30000.0, null, null, "manual"))
                .thenReturn(EVENT_ID);
        when(maintenanceRepository.listForOwnedVehicle(USER_ID, VEHICLE_ID)).thenReturn(java.util.List.of(dto(30000.0)));

        controller.createMaintenance(VEHICLE_ID,
                new CreateMaintenanceEventRequest("ENGINE_OIL_CHANGE", 30000.0, null, null, true));

        verify(odometerConsistency).applyServiceMileage(VEHICLE_ID, 30000.0, true);
    }

    @Test
    void updateRejectsInvalidInputLikeCreationDoes() {
        when(garageVehicleRepository.isOwned(USER_ID, VEHICLE_ID)).thenReturn(true);

        assertThatThrownBy(() -> controller.updateMaintenance(VEHICLE_ID, EVENT_ID,
                new CreateMaintenanceEventRequest("NOPE", 100.0, null, null))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> controller.updateMaintenance(VEHICLE_ID, EVENT_ID,
                new CreateMaintenanceEventRequest("ENGINE_OIL_CHANGE", null, null, null))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> controller.updateMaintenance(VEHICLE_ID, EVENT_ID,
                new CreateMaintenanceEventRequest("ENGINE_OIL_CHANGE", 500_001.0, LocalDate.now(), null)))
                .isInstanceOf(BadRequestException.class);
        verify(maintenanceRepository, never()).updateOwnedEvent(anyLong(), anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void updateOfUnknownEventIs404() {
        when(garageVehicleRepository.isOwned(USER_ID, VEHICLE_ID)).thenReturn(true);
        when(maintenanceRepository.updateOwnedEvent(USER_ID, VEHICLE_ID, EVENT_ID, "ENGINE_OIL_CHANGE", 1.0, null, null))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.updateMaintenance(VEHICLE_ID, EVENT_ID,
                new CreateMaintenanceEventRequest("ENGINE_OIL_CHANGE", 1.0, null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteSucceedsAndMissingEventIs404() {
        when(garageVehicleRepository.isOwned(USER_ID, VEHICLE_ID)).thenReturn(true);
        when(maintenanceRepository.deleteOwnedEvent(USER_ID, VEHICLE_ID, EVENT_ID)).thenReturn(true, false);

        controller.deleteMaintenance(VEHICLE_ID, EVENT_ID);
        assertThatThrownBy(() -> controller.deleteMaintenance(VEHICLE_ID, EVENT_ID)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteOnAnotherUsersVehicleIs404AndDeletesNothing() {
        when(garageVehicleRepository.isOwned(USER_ID, VEHICLE_ID)).thenReturn(false);

        assertThatThrownBy(() -> controller.deleteMaintenance(VEHICLE_ID, EVENT_ID)).isInstanceOf(NotFoundException.class);
        verify(maintenanceRepository, never()).deleteOwnedEvent(anyLong(), anyLong(), anyLong());
    }

    private static Map<String, Object> keys(int n) {
        Map<String, Object> data = new HashMap<>();
        for (int i = 0; i < n; i++) {
            data.put("k" + i, 1);
        }
        return data;
    }

    @Test
    void preferenceWithTwentyKeysIsSavedAndTwentyOneIsRejected() {
        when(garageVehicleRepository.isOwned(USER_ID, VEHICLE_ID)).thenReturn(true);

        controller.savePreference(VEHICLE_ID, new SaveVehiclePreferenceRequest("TIRE_PRESSURE", "ROAD", keys(20)));
        verify(preferenceRepository).upsert(org.mockito.ArgumentMatchers.eq(VEHICLE_ID), any(), any(), any());

        assertThatThrownBy(() -> controller.savePreference(VEHICLE_ID,
                new SaveVehiclePreferenceRequest("TIRE_PRESSURE", "ROAD", keys(21))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("20 keys");
    }

    @Test
    void preferenceSerializedSizeIsBoundedAt2048Bytes() {
        when(garageVehicleRepository.isOwned(USER_ID, VEHICLE_ID)).thenReturn(true);
        // {"v":"<n x a>"} has 8 bytes of JSON overhead around the value.
        controller.savePreference(VEHICLE_ID, new SaveVehiclePreferenceRequest(
                "TIRE_PRESSURE", "ROAD", Map.of("v", "a".repeat(2048 - 8))));
        verify(preferenceRepository).upsert(org.mockito.ArgumentMatchers.eq(VEHICLE_ID), any(), any(), any());

        assertThatThrownBy(() -> controller.savePreference(VEHICLE_ID, new SaveVehiclePreferenceRequest(
                "TIRE_PRESSURE", "ROAD", Map.of("v", "a".repeat(2048 - 7)))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("2048 bytes");
    }

    @Test
    void maintenanceOdometerLimitMatchesTheSharedConstant() {
        when(garageVehicleRepository.isOwned(USER_ID, VEHICLE_ID)).thenReturn(true);
        assertThatThrownBy(() -> controller.createMaintenance(VEHICLE_ID, new CreateMaintenanceEventRequest(
                "ENGINE_OIL_CHANGE", GarageLimits.MAX_ODOMETER_KM + 1, null, null)))
                .isInstanceOf(BadRequestException.class);
    }
}
