package dev.repair.api.motochat;

import java.time.OffsetDateTime;

public record MotoSessionSummaryDto(
        long id, String title, long garageVehicleId, String manufacturerName, String modelName, int year,
        OffsetDateTime createdAt, OffsetDateTime updatedAt
) {
}
