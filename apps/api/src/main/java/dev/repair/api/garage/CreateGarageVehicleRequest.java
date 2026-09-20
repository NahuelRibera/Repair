package dev.repair.api.garage;

import jakarta.validation.constraints.NotNull;

/**
 * @param allowDuplicate false (default, including when omitted from the
 *                        request body) reuses an existing garage vehicle
 *                        with the same manufacturer/model/year for this
 *                        visitor if one exists — the normal "choose your
 *                        bike" behavior. true always creates a new row,
 *                        for the explicit "+ Add another bike" flow,
 *                        where owning two identical motorcycles is a
 *                        legitimate, intentional case.
 */
public record CreateGarageVehicleRequest(@NotNull Long modelId, @NotNull Integer year, String nickname, Boolean allowDuplicate) {
}
