package dev.repair.api.motochat;

import jakarta.validation.constraints.NotNull;

public record CreateMotoSessionRequest(@NotNull Long garageVehicleId) {
}
