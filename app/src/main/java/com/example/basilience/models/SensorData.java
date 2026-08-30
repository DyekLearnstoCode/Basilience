package com.example.basilience.models;

public class SensorData {

    // Boxed values preserve Firebase null/missing fields instead of fabricating 0.0.
    public Double airTemperature;
    public Double humidity;
    public Double waterTemperature;

    // Water-depth model (see firmware Config.h's "Water Reservoir
    // Geometry"). waterLevel is the derived 0-100 working percentage - kept
    // as-is, same meaning as before, just recalibrated at the source to the
    // reservoir's actual 6cm working depth instead of the old empty/full
    // sensor-distance span. waterLevelCm/waterVolumeLiters are the new
    // authoritative depth/volume fields; both are null on records published
    // before the firmware update, or if the sensor was invalid - never
    // fabricate one from waterLevel.
    public Double waterLevel;
    public Double waterLevelCm;
    public Double waterVolumeLiters;
    public Double waterLevelDistanceCm;

    public Double ec;
    public Double tds;
    public Double ph;

    // True while the pH temporal step filter is still confirming a newly
    // observed level (baseline establishment or a jump beyond
    // PH_STEP_ACCEPT_DELTA) - see firmware SensorManager's pH step filter.
    // ph itself is held at the last CONFIRMED value throughout, never
    // blanked or replaced by the unconfirmed candidate - phConfirming is
    // display-only metadata, it never gates automation.
    public Boolean phConfirming;

    public Long timestamp;

    // Coherent initial sensor snapshot metadata (see the real-time sensor
    // presentation task report) - published by firmware as nested fields of
    // this SAME /sensors node, in the same atomic write as every value
    // above, so ready/stabilizing can never be observed out of sync with a
    // partially-written sensor set. null on records from before this field
    // existed - callers must treat that the same as "not ready" rather than
    // assuming readiness.
    public static class SensorState {
        public Boolean stabilizing;
        public Boolean ready;
        public Long updatedAt;

        public SensorState() {}
    }

    public SensorState sensorState;

    public SensorData() {}
}
