package dev.repair.api.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.repair.api.auth.AppUserDto;
import dev.repair.api.auth.AuthenticatedUserContext;
import dev.repair.api.common.ApiExceptionHandler;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class OdometerConsistencyServiceTest {

    private static final long USER_ID = 101L;
    private static final long VEHICLE_ID = 7L;

    @Mock
    private MaintenanceRepository maintenanceRepository;
    @Mock
    private GarageVehicleRepository garageVehicleRepository;

    private OdometerConsistencyService service;

    @BeforeEach
    void setUp() {
        AuthenticatedUserContext currentUser = new AuthenticatedUserContext();
        currentUser.setUser(new AppUserDto(USER_ID, "sub", "rider@example.test", "Rider",
                null, OffsetDateTime.now(), OffsetDateTime.now()));
        service = new OdometerConsistencyService(maintenanceRepository, garageVehicleRepository, currentUser);
    }

    private void currentOdometer(Double km) {
        when(garageVehicleRepository.find(USER_ID, VEHICLE_ID)).thenReturn(Optional.of(
                new GarageVehicleDto(VEHICLE_ID, 1L, "Yamaha", "Tenere 700", 2021, null, null, km,
                        OffsetDateTime.now(), OffsetDateTime.now())));
    }

    @Test
    void lowerThanHighestServiceIsRejectedWithSuggestionAndMessage() {
        when(maintenanceRepository.maxServiceOdometer(VEHICLE_ID)).thenReturn(20000.0);

        assertThatThrownBy(() -> service.checkOdometerUpdate(VEHICLE_ID, 15000.0, false))
                .isInstanceOfSatisfying(OdometerConflictException.class, e -> {
                    assertThat(e.code()).isEqualTo(OdometerConflictException.BELOW_SERVICE_HISTORY);
                    assertThat(e.suggestedOdometerKm()).isEqualTo(20000.0);
                    assertThat(e.getMessage()).contains("20,000 km");
                });
    }

    @Test
    void equalOrHigherOrNoHistoryIsAccepted() {
        when(maintenanceRepository.maxServiceOdometer(VEHICLE_ID)).thenReturn(20000.0, 20000.0, null);

        assertThatCode(() -> service.checkOdometerUpdate(VEHICLE_ID, 20000.0, false)).doesNotThrowAnyException();
        assertThatCode(() -> service.checkOdometerUpdate(VEHICLE_ID, 25000.0, false)).doesNotThrowAnyException();
        assertThatCode(() -> service.checkOdometerUpdate(VEHICLE_ID, 10.0, false)).doesNotThrowAnyException();
    }

    @Test
    void confirmedCorrectionSkipsTheHistoryLookup() {
        assertThatCode(() -> service.checkOdometerUpdate(VEHICLE_ID, 100.0, true)).doesNotThrowAnyException();
        verify(maintenanceRepository, never()).maxServiceOdometer(anyLong());
    }

    @Test
    void serviceAboveOdometerIsRejectedUnlessTheRiderAgreesToRaiseIt() {
        currentOdometer(18000.0);

        assertThatThrownBy(() -> service.applyServiceMileage(VEHICLE_ID, 20000.0, false))
                .isInstanceOfSatisfying(OdometerConflictException.class, e -> {
                    assertThat(e.code()).isEqualTo(OdometerConflictException.SERVICE_ABOVE_ODOMETER);
                    assertThat(e.suggestedOdometerKm()).isEqualTo(20000.0);
                });
        verify(garageVehicleRepository, never()).updateOdometerIfOwned(anyLong(), anyLong(), anyDouble());
    }

    @Test
    void agreeingRaisesTheOdometerToTheServiceMileage() {
        currentOdometer(18000.0);

        service.applyServiceMileage(VEHICLE_ID, 20000.0, true);

        verify(garageVehicleRepository).updateOdometerIfOwned(USER_ID, VEHICLE_ID, 20000.0);
    }

    @Test
    void serviceAtOrBelowOdometerOrWithoutOdometerChangesNothing() {
        currentOdometer(18000.0);
        service.applyServiceMileage(VEHICLE_ID, 18000.0, false);
        service.applyServiceMileage(VEHICLE_ID, null, false);
        assertThat(OdometerConsistencyService.serviceExceedsOdometer(5000.0, null)).isFalse();
        verify(garageVehicleRepository, never()).updateOdometerIfOwned(anyLong(), anyLong(), anyDouble());
    }

    @Test
    void conflictsAreReturnedAs409WithCodeAndSuggestion() {
        var response = new ApiExceptionHandler().handleOdometerConflict(new OdometerConflictException(
                OdometerConflictException.SERVICE_ABOVE_ODOMETER, "msg", 20000.0));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "msg")
                .containsEntry("code", "SERVICE_ABOVE_ODOMETER").containsEntry("suggestedOdometerKm", 20000.0);
    }
}
