package dev.repair.api.garage;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record MaintenanceEventDto(
        long id, long garageVehicleId, String serviceType, Double odometerKm, LocalDate performedAt,
        String notes, String createdVia, OffsetDateTime createdAt
) {
}
