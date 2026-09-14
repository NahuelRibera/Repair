package dev.repair.api.garage;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public record CreateMaintenanceEventRequest(
        @NotBlank String serviceType, Double odometerKm, LocalDate performedAt, String notes
) {
}
