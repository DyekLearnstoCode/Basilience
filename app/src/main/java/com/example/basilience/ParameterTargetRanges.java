package com.example.basilience;

/**
 * The canonical target (acceptable) range definition for one monitored
 * parameter: its RTDB field names, its compiled defaults, and the physical
 * bounds a value must sit inside.
 *
 * <p>Defaults and physical bounds mirror the firmware exactly (Config.h and
 * FirebaseManager::applyTargetRange), so the app can never offer a value the
 * device would silently reject, and a field missing from Firebase shows the
 * same number the device is actually using rather than a misleading 0.
 *
 * <p>These are target ranges only. Actuator control/hysteresis settings
 * (airTempRelease, humidityRelease, coolerOffTemp, refillStartLevel,
 * refillStopLevel) are deliberately absent - they answer when equipment
 * switches, not what counts as an acceptable reading.
 */
public enum ParameterTargetRanges {

    PH("pH", "minPH", "maxPH", 5.5f, 6.5f, 0f, 14f, 2, ""),
    EC("EC", "minEC", "maxEC", 1.2f, 2.0f, 0f, 10f, 2, " mS/cm"),
    AIR_TEMPERATURE("Air Temperature", "minAirTemp", "maxAirTemp", 20f, 32f, -40f, 80f, 1, "°C"),
    HUMIDITY("Humidity", "minHumidity", "maxHumidity", 60f, 80f, 0f, 100f, 1, "%"),
    WATER_TEMPERATURE("Water Temperature", "minWaterTemp", "maxWaterTemp", 18f, 28f, 0f, 100f, 1, "°C"),
    // Water-level management spec alignment (firmware Config.h): 100% now
    // means the reservoir's ~20cm full depth (was a 6cm "working capacity"),
    // so these defaults were recomputed from the actual refill control
    // thresholds (REFILL_START_CM=2.0cm, REFILL_STOP_CM=5.0cm) against the
    // new basis - 2.0/20*100, 5.0/20*100 - matching Config.h's
    // TARGET_MIN_WATER_LEVEL/TARGET_MAX_WATER_LEVEL exactly (was 20f/75f,
    // itself a stale leftover from the retired legacy percentage refill
    // model that never matched the real cm-based control band either).
    WATER_LEVEL("Water Level", "minWaterLevel", "maxWaterLevel", 10f, 25f, 0f, 100f, 1, "%");

    public final String displayName;
    public final String minKey;
    public final String maxKey;
    public final float defaultMin;
    public final float defaultMax;
    public final float physicalMin;
    public final float physicalMax;
    public final int decimals;
    public final String unit;

    ParameterTargetRanges(String displayName, String minKey, String maxKey,
                          float defaultMin, float defaultMax,
                          float physicalMin, float physicalMax,
                          int decimals, String unit) {
        this.displayName = displayName;
        this.minKey = minKey;
        this.maxKey = maxKey;
        this.defaultMin = defaultMin;
        this.defaultMax = defaultMax;
        this.physicalMin = physicalMin;
        this.physicalMax = physicalMax;
        this.decimals = decimals;
        this.unit = unit;
    }
}
