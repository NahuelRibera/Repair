package dev.repair.api.garage;

import java.time.OffsetDateTime;

public record VehiclePreferenceDto(
        long id, long garageVehicleId, String preferenceType, String context, String dataJson,
        OffsetDateTime createdAt, OffsetDateTime updatedAt
) {
}
