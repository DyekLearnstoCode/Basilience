package com.example.basilience;

import androidx.annotation.Nullable;

/**
 * Canonical hardware/component vocabulary for the Hardware Guide, and the
 * fallback used to recover which component a TYPE_HARDWARE notification is
 * about from its document id's trailing subsystem-key suffix.
 *
 * <p>Peristaltic Pumps is kept as ONE component here (matching the single
 * "Peristaltic Pumps" guide section, which internally distinguishes pH Up
 * Pump / pH Down Pump / Nutrient Pump) rather than three separate keys,
 * since neither RTDB subsystem-lock flag below can tell which specific pump
 * within that pair actually needs attention - only that the pH or EC dosing
 * subsystem as a whole is stuck.
 */
public enum HardwareComponentKey {
    MAIN_CONTROLLER,
    PH_SENSOR,
    EC_SENSOR,
    WATER_TEMPERATURE_SENSOR,
    AIR_TEMPERATURE_HUMIDITY_SENSOR,
    WATER_LEVEL_SENSOR,
    FOGGER,
    BLOWER,
    PERISTALTIC_PUMPS,
    TEMPERATURE_CONTROL,
    GROW_LIGHT,
    RESERVOIR,
    HARVEST_SCALE,
    /**
     * Not a physical component - reuses this same per-device
     * {@code devices/{deviceId}/hardwareGuide/{componentKey}} override
     * document/editor architecture purely so the Hardware Video Tutorial's
     * title/description/URL/thumbnail can be remotely edited without a new
     * Firestore collection or a second content system. See
     * HardwareGuideRepository#saveVideoOverride and
     * HardwareGuideFragment#openEditor.
     */
    VIDEO_TUTORIAL;

    /**
     * Resolves which hardware component a TYPE_HARDWARE notification concerns,
     * the same way NotificationParameterKeys.resolve() recovers a parameter
     * key: every subsystem-lock notification's id is
     * {@code <64-char sha256 hex>_<subsystemKey>} (see functions/index.js's
     * safeEventId + the statusKeys table), so splitting on the LAST
     * underscore and exact-matching the remainder is safe for the same
     * reason documented there (the hash half is pure hex and can never
     * collide with a named suffix).
     *
     * <p>Deliberately returns null for "sensorFault" and "safetyLock":
     * both are genuinely generic today (a sensor fault names no specific
     * sensor, a safety lock names no specific subsystem), and the backend
     * notification schema does not carry a more specific identifier for
     * either - see the audit note in the Hardware Guide design work. This
     * method never guesses a target from message/title text.
     *
     * @return the affected component, or null if this notification (by type,
     *         or by an unresolvable/offline-queue id with no derivable
     *         suffix) has no reliably-identifiable single component.
     */
    @Nullable
    public static HardwareComponentKey resolve(@Nullable String type, @Nullable String notificationId) {
        if (!NotificationEntity.TYPE_HARDWARE.equals(type) || notificationId == null) return null;
        int lastUnderscore = notificationId.lastIndexOf('_');
        if (lastUnderscore < 0 || lastUnderscore == notificationId.length() - 1) return null;
        String subsystemKey = notificationId.substring(lastUnderscore + 1);
        switch (subsystemKey) {
            case "phSubsystemLocked":
            case "ecSubsystemLocked":
                return PERISTALTIC_PUMPS;
            case "refillSubsystemLocked":
                return RESERVOIR;
            case "coolingSubsystemLocked":
                return TEMPERATURE_CONTROL;
            default:
                // Includes "sensorFault", "safetyLock", and any id with no
                // known suffix (e.g. an offline-queue-mirrored notification).
                return null;
        }
    }
}
