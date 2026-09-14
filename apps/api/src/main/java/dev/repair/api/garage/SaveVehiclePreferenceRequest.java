package dev.repair.api.garage;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record SaveVehiclePreferenceRequest(@NotBlank String preferenceType, @NotBlank String context, Map<String, Object> data) {
}
