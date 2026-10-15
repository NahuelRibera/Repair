package dev.repair.api.garage;

/** {@code confirmCorrection} lets the rider lower the odometer below recorded service mileage. */
public record UpdateGarageVehicleRequest(String nickname, Double currentOdometerKm, Boolean confirmCorrection) {

    public UpdateGarageVehicleRequest(String nickname, Double currentOdometerKm) {
        this(nickname, currentOdometerKm, null);
    }
}
