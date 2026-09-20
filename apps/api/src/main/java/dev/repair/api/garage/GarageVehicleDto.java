package dev.repair.api.garage;

import java.time.OffsetDateTime;

public record GarageVehicleDto(
        long id, long modelId, String manufacturerName, String modelName, int year, String market,
        String nickname, Double currentOdometerKm, OffsetDateTime createdAt, OffsetDateTime updatedAt
) {
}
