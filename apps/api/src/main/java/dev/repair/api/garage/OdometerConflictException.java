package dev.repair.api.garage;

/** A reading contradicts the vehicle's recorded history. Mapped to HTTP 409 with a machine-readable
 * {@code code} and the odometer value the client may offer to apply or confirm. */
public class OdometerConflictException extends RuntimeException {

    public static final String BELOW_SERVICE_HISTORY = "ODOMETER_BELOW_SERVICE_HISTORY";
    public static final String SERVICE_ABOVE_ODOMETER = "SERVICE_ABOVE_ODOMETER";

    private final String code;
    private final double suggestedOdometerKm;

    public OdometerConflictException(String code, String message, double suggestedOdometerKm) {
        super(message);
        this.code = code;
        this.suggestedOdometerKm = suggestedOdometerKm;
    }

    public String code() {
        return code;
    }

    public double suggestedOdometerKm() {
        return suggestedOdometerKm;
    }
}
