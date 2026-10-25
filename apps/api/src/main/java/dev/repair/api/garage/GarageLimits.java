package dev.repair.api.garage;

import dev.repair.api.common.BadRequestException;

/** Input bounds for garage data that is stored and later sent to the assistant context. */
public final class GarageLimits {

    public static final double MAX_ODOMETER_KM = 500_000;
    public static final int MAX_NICKNAME_LENGTH = 60;
    public static final int MAX_PREFERENCE_KEYS = 20;
    public static final int MAX_PREFERENCE_JSON_BYTES = 2048;

    private GarageLimits() {
    }

    public static void checkNickname(String nickname) {
        if (nickname != null && nickname.length() > MAX_NICKNAME_LENGTH) {
            throw new BadRequestException("Nickname must be at most " + MAX_NICKNAME_LENGTH + " characters");
        }
    }

    public static void checkOdometer(Double odometerKm) {
        if (odometerKm != null && (odometerKm < 0 || odometerKm > MAX_ODOMETER_KM)) {
            throw new BadRequestException("Odometer reading is out of range");
        }
    }

    public static void checkPreferenceKeyCount(int keys) {
        if (keys > MAX_PREFERENCE_KEYS) {
            throw new BadRequestException("Preference data may have at most " + MAX_PREFERENCE_KEYS + " keys");
        }
    }

    public static void checkPreferenceJson(String json) {
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PREFERENCE_JSON_BYTES) {
            throw new BadRequestException("Preference data must be at most " + MAX_PREFERENCE_JSON_BYTES + " bytes");
        }
    }
}
