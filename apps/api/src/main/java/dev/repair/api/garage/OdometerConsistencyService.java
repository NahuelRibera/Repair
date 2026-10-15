package dev.repair.api.garage;

import dev.repair.api.auth.AuthenticatedUserContext;
import org.springframework.stereotype.Service;

/** The single place that decides whether an odometer reading is consistent with the service history. */
@Service
public class OdometerConsistencyService {

    private final MaintenanceRepository maintenanceRepository;
    private final GarageVehicleRepository garageVehicleRepository;
    private final AuthenticatedUserContext currentUser;

    public OdometerConsistencyService(
            MaintenanceRepository maintenanceRepository, GarageVehicleRepository garageVehicleRepository,
            AuthenticatedUserContext currentUser
    ) {
        this.maintenanceRepository = maintenanceRepository;
        this.garageVehicleRepository = garageVehicleRepository;
        this.currentUser = currentUser;
    }

    /** Rejects lowering the odometer below the highest recorded service mileage unless confirmed. */
    public void checkOdometerUpdate(long garageVehicleId, Double newOdometerKm, boolean confirmCorrection) {
        if (newOdometerKm == null || confirmCorrection) {
            return;
        }
        checkOdometerUpdate(newOdometerKm, maintenanceRepository.maxServiceOdometer(garageVehicleId));
    }

    /** Applies the service-mileage rule to a recorded service; when the service is beyond the current
     * odometer and the rider agreed, the odometer is raised to the service mileage. */
    public void applyServiceMileage(long garageVehicleId, Double serviceOdometerKm, boolean updateOdometer) {
        if (serviceOdometerKm == null) {
            return;
        }
        Double current = garageVehicleRepository.find(currentUser.getUserId(), garageVehicleId)
                .map(GarageVehicleDto::currentOdometerKm)
                .orElse(null);
        if (!serviceExceedsOdometer(serviceOdometerKm, current)) {
            return;
        }
        if (!updateOdometer) {
            throw serviceAboveOdometer(serviceOdometerKm, current);
        }
        garageVehicleRepository.updateOdometerIfOwned(currentUser.getUserId(), garageVehicleId, serviceOdometerKm);
    }

    /** Pure rule: an unrecorded history or an equal reading is always consistent. */
    static void checkOdometerUpdate(double newOdometerKm, Double highestServiceKm) {
        if (highestServiceKm != null && newOdometerKm < highestServiceKm) {
            throw new OdometerConflictException(OdometerConflictException.BELOW_SERVICE_HISTORY,
                    "The odometer cannot be lower than your highest recorded service (" + format(highestServiceKm)
                            + " km). Confirm if you are correcting a wrong reading.",
                    highestServiceKm);
        }
    }

    /** Pure rule: with no odometer recorded there is nothing to contradict. */
    static boolean serviceExceedsOdometer(double serviceKm, Double currentOdometerKm) {
        return currentOdometerKm != null && serviceKm > currentOdometerKm;
    }

    private static OdometerConflictException serviceAboveOdometer(double serviceKm, Double current) {
        return new OdometerConflictException(OdometerConflictException.SERVICE_ABOVE_ODOMETER,
                "This service (" + format(serviceKm) + " km) is above your current odometer (" + format(current)
                        + " km). Update the odometer to " + format(serviceKm) + " km?",
                serviceKm);
    }

    private static String format(double km) {
        return String.format(java.util.Locale.ROOT, "%,.0f", km);
    }
}
