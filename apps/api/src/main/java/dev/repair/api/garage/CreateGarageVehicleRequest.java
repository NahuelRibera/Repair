package dev.repair.api.garage;

import jakarta.validation.constraints.NotNull;

public record CreateGarageVehicleRequest(@NotNull Long modelId, @NotNull Integer year, String nickname) {
}
