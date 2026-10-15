package dev.repair.api.garage;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/** {@code updateOdometer} lets the rider raise the odometer when the service mileage is above it. */
public record CreateMaintenanceEventRequest(
        @NotBlank String serviceType, Double odometerKm, LocalDate performedAt, String notes, Boolean updateOdometer
) {

    public CreateMaintenanceEventRequest(String serviceType, Double odometerKm, LocalDate performedAt, String notes) {
        this(serviceType, odometerKm, performedAt, notes, null);
    }
}
