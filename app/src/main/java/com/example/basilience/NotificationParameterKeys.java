package com.example.basilience;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Canonical parameter grouping for notifications (pH / EC / Water Temp / Air
 * Temp / Humidity / Water Level), and the fallback used to recover it for
 * documents written before functions/index.js started persisting a
 * parameterKey field directly.
 *
 * The alert-key table and the alertKey -&gt; canonical mapping here must stay
 * identical to ALERT_KEY_TO_PARAMETER_KEY in functions/index.js - both sides
 * derive from the same underlying alert-definition table, so a change to one
 * without the other would make old and new documents disagree on grouping.
 */
public final class NotificationParameterKeys {

    private NotificationParameterKeys() {}

    public static final String PH = "ph";
    public static final String EC = "ec";
    public static final String WATER_TEMP = "waterTemp";
    public static final String AIR_TEMP = "airTemp";
    public static final String HUMIDITY = "humidity";
    public static final String WATER_LEVEL = "waterLevel";

    private static final Map<String, String> ALERT_KEY_TO_PARAMETER_KEY = new HashMap<>();
    static {
        ALERT_KEY_TO_PARAMETER_KEY.put("phLow", PH);
        ALERT_KEY_TO_PARAMETER_KEY.put("phHigh", PH);
        ALERT_KEY_TO_PARAMETER_KEY.put("ecLow", EC);
        ALERT_KEY_TO_PARAMETER_KEY.put("ecHigh", EC);
        ALERT_KEY_TO_PARAMETER_KEY.put("waterTempOutOfRange", WATER_TEMP);
        ALERT_KEY_TO_PARAMETER_KEY.put("waterTempLow", WATER_TEMP);
        ALERT_KEY_TO_PARAMETER_KEY.put("lowAirTemperature", AIR_TEMP);
        ALERT_KEY_TO_PARAMETER_KEY.put("highTemperature", AIR_TEMP);
        ALERT_KEY_TO_PARAMETER_KEY.put("humidityLow", HUMIDITY);
        ALERT_KEY_TO_PARAMETER_KEY.put("humidityHigh", HUMIDITY);
        ALERT_KEY_TO_PARAMETER_KEY.put("waterLevelLow", WATER_LEVEL);
        ALERT_KEY_TO_PARAMETER_KEY.put("waterLevelHigh", WATER_LEVEL);
        ALERT_KEY_TO_PARAMETER_KEY.put("lowWater", WATER_LEVEL);
        ALERT_KEY_TO_PARAMETER_KEY.put("criticalLowWater", WATER_LEVEL);
        // sensorFault intentionally absent - it is type "hardware", not "parameter".
    }

    /**
     * Resolves the canonical parameter key for a notification. Prefers the
     * stored field (every document written after this feature shipped has
     * one). For an older document that predates it, derives the key from the
     * document id's trailing alert-key suffix - safe because every
     * "parameter"-type id is {@code <64-char sha256 hex>_<alertKey>} (see
     * functions/index.js's safeEventId/onAlertUpdated) and the hash half is
     * pure hex, so splitting on the LAST underscore and exact-matching the
     * remainder against the known alert-key table can never misfire on a
     * hash digit or collide with a non-parameter document (those either
     * carry a different type, or - for the rare offline-queue replay with no
     * derivable suffix - simply won't match and fall through to null).
     *
     * @return one of the PH/EC/WATER_TEMP/AIR_TEMP/HUMIDITY/WATER_LEVEL
     *         constants, or null if type isn't "parameter" or no key could
     *         be resolved (caller should bucket null under "Unspecified").
     */
    @Nullable
    public static String resolve(@Nullable String type, @Nullable String storedParameterKey,
                                  @Nullable String notificationId) {
        if (storedParameterKey != null && ALERT_KEY_TO_PARAMETER_KEY.containsValue(storedParameterKey)) {
            return storedParameterKey;
        }
        if (!NotificationAdapter.NotificationItem.TYPE_PARAMETER.equals(type) || notificationId == null) {
            return null;
        }
        int lastUnderscore = notificationId.lastIndexOf('_');
        if (lastUnderscore < 0 || lastUnderscore == notificationId.length() - 1) return null;
        String alertKey = notificationId.substring(lastUnderscore + 1);
        return ALERT_KEY_TO_PARAMETER_KEY.get(alertKey);
    }

    /** Display label for a canonical parameter key, for chips and headers. */
    public static String displayName(@Nullable String parameterKey) {
        if (parameterKey == null) return "Unspecified";
        switch (parameterKey) {
            case PH: return "pH";
            case EC: return "EC";
            case WATER_TEMP: return "Water Temp";
            case AIR_TEMP: return "Air Temp";
            case HUMIDITY: return "Humidity";
            case WATER_LEVEL: return "Water Level";
            default: return "Unspecified";
        }
    }
}
