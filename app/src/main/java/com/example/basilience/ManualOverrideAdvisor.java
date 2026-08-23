package com.example.basilience;

import androidx.annotation.Nullable;

import com.example.basilience.models.SensorData;

/**
 * Decides whether a manual actuator activation needs a confirmation prompt.
 *
 * Manual override stays an override: nothing here blocks an action. The only
 * question this class answers is whether the user should be asked to confirm
 * first, because the parameter the actuator controls is already where it
 * should be, or because Basilience cannot tell.
 *
 * <p><b>Where the truth comes from.</b> No threshold is invented here. The
 * directional decisions read the firmware-published alert flags under the
 * device's {@code alerts} node ({@code phLow}, {@code phHigh}, {@code ecLow},
 * {@code lowWater}) - the same flags the Monitoring screen already uses to
 * colour each reading. Cooling is the one case those flags cannot answer,
 * because {@code waterTempOutOfRange} carries no direction; it uses the
 * configured {@code settings/highWaterTemp} value, which is the same
 * configured ceiling the System Reports screen already reads. When a value
 * needed for the decision is missing, the answer is {@link Advice#CONFIRM_UNKNOWN}
 * rather than an assumption that the action is safe.
 */
public final class ManualOverrideAdvisor {

    /** What the caller should do before sending the manual ON request. */
    public enum Advice {
        /** The reading says this action is called for - send it without asking. */
        PROCEED,
        /** The parameter is already where it should be - ask first. */
        CONFIRM_UNNECESSARY,
        /** The reading needed to judge this is missing - ask first. */
        CONFIRM_UNKNOWN
    }

    /**
     * The controlling condition behind a manual actuator action.
     *
     * {@link #NONE} covers actuators with no verified single controlling
     * condition, including the Grow Light, which is exempt from
     * threshold-based confirmation by design.
     */
    public enum Condition {
        /** Dosing that raises pH (pH Up). Called for when pH is low. */
        PH_RAISE,
        /** Dosing that lowers pH (pH Down). Called for when pH is high. */
        PH_LOWER,
        /** Nutrient dosing, which raises EC. Called for when EC is low. */
        EC_RAISE,
        /** Active reservoir cooling. Called for when water temperature is at or above the configured ceiling. */
        WATER_COOL,
        /** Adding water to the reservoir. Called for when the water level is low. */
        WATER_LEVEL_FILL,
        /** No verified controlling condition - never prompts. */
        NONE
    }

    /**
     * The firmware-published directional alert flags this advisor depends on.
     * Held as a small value object so the decision logic stays free of any
     * Firebase or Android types.
     */
    public static final class AlertFlags {
        public boolean phLow;
        public boolean phHigh;
        public boolean ecLow;
        public boolean lowWater;
    }

    // Validity bounds match the ones the Monitoring screen already applies when
    // it decides whether a reading renders as a number or as "--", so a value
    // shown as No Data is never treated here as a usable reading.
    private static final double PH_MIN = 0.0, PH_MAX = 14.0;
    private static final double WATER_TEMP_MIN = -55.0, WATER_TEMP_MAX = 125.0;
    private static final double WATER_TEMP_SENTINEL = -127.0;
    private static final double WATER_LEVEL_MIN = 0.0, WATER_LEVEL_MAX = 100.0;

    private ManualOverrideAdvisor() {}

    /**
     * @param condition        what the actuator controls
     * @param data             the latest sensor snapshot, may be null
     * @param flags            firmware alert flags, may be null if never loaded
     * @param configuredHighWaterTemp configured cooling ceiling, may be null if not configured
     */
    public static Advice evaluate(Condition condition,
                                  @Nullable SensorData data,
                                  @Nullable AlertFlags flags,
                                  @Nullable Double configuredHighWaterTemp) {
        if (condition == null || condition == Condition.NONE) return Advice.PROCEED;

        switch (condition) {
            case PH_RAISE:
                if (!isUsable(data == null ? null : data.ph, PH_MIN, PH_MAX, null)) return Advice.CONFIRM_UNKNOWN;
                if (flags == null) return Advice.CONFIRM_UNKNOWN;
                return flags.phLow ? Advice.PROCEED : Advice.CONFIRM_UNNECESSARY;

            case PH_LOWER:
                if (!isUsable(data == null ? null : data.ph, PH_MIN, PH_MAX, null)) return Advice.CONFIRM_UNKNOWN;
                if (flags == null) return Advice.CONFIRM_UNKNOWN;
                return flags.phHigh ? Advice.PROCEED : Advice.CONFIRM_UNNECESSARY;

            case EC_RAISE:
                if (!isUsable(data == null ? null : data.ec, 0.0, Double.MAX_VALUE, null)) return Advice.CONFIRM_UNKNOWN;
                if (flags == null) return Advice.CONFIRM_UNKNOWN;
                return flags.ecLow ? Advice.PROCEED : Advice.CONFIRM_UNNECESSARY;

            case WATER_COOL:
                Double waterTemp = data == null ? null : data.waterTemperature;
                if (!isUsable(waterTemp, WATER_TEMP_MIN, WATER_TEMP_MAX, WATER_TEMP_SENTINEL)) {
                    return Advice.CONFIRM_UNKNOWN;
                }
                // No ceiling configured means there is nothing authoritative to
                // compare against - ask rather than guess a target temperature.
                if (configuredHighWaterTemp == null) return Advice.CONFIRM_UNKNOWN;
                return waterTemp >= configuredHighWaterTemp ? Advice.PROCEED : Advice.CONFIRM_UNNECESSARY;

            case WATER_LEVEL_FILL:
                if (!isUsable(data == null ? null : data.waterLevel, WATER_LEVEL_MIN, WATER_LEVEL_MAX, null)) {
                    return Advice.CONFIRM_UNKNOWN;
                }
                if (flags == null) return Advice.CONFIRM_UNKNOWN;
                return flags.lowWater ? Advice.PROCEED : Advice.CONFIRM_UNNECESSARY;

            default:
                return Advice.PROCEED;
        }
    }

    /** Title for every manual-override confirmation. */
    public static final String CONFIRM_TITLE = "Confirm Manual Override";

    /**
     * The prompt text for a condition that needs confirming. Written in the
     * same friendly vocabulary the Monitoring screen uses - no actuator keys.
     */
    public static String messageFor(Condition condition, Advice advice) {
        if (advice == Advice.CONFIRM_UNKNOWN) {
            switch (condition) {
                case PH_RAISE:
                case PH_LOWER:
                    return "Current pH data is unavailable, so Basilience cannot verify whether pH dosing is "
                            + "needed. Continue with the manual action?";
                case EC_RAISE:
                    return "Current EC data is unavailable, so Basilience cannot verify whether nutrient dosing "
                            + "is needed. Continue with the manual action?";
                case WATER_COOL:
                    return "Current water temperature data is unavailable, so Basilience cannot verify whether "
                            + "cooling is needed. Continue with the manual action?";
                case WATER_LEVEL_FILL:
                    return "Current water-level data is unavailable, so Basilience cannot verify whether a "
                            + "refill is needed. Continue anyway?";
                default:
                    return "Basilience cannot verify whether this action is needed right now. Continue with "
                            + "the manual action?";
            }
        }

        switch (condition) {
            case PH_RAISE:
                return "pH is currently within the desired range. Running pH Up may raise pH beyond the "
                        + "recommended level. Do you want to continue?";
            case PH_LOWER:
                return "pH is currently within the desired range. Running pH Down may lower pH below the "
                        + "recommended level. Do you want to continue?";
            case EC_RAISE:
                return "EC is currently within the desired range. Adding nutrients may raise EC beyond the "
                        + "recommended level. Do you want to continue?";
            case WATER_COOL:
                return "Water temperature is currently within the desired range. Running cooling may lower it "
                        + "further than needed. Do you want to continue?";
            case WATER_LEVEL_FILL:
                return "Water level is currently within the desired range. Starting a refill may add water "
                        + "unnecessarily. Do you want to continue?";
            default:
                return "This action may not be needed right now. Do you want to continue?";
        }
    }

    /**
     * Mirrors the Monitoring screen's own reading-validity test: null, NaN,
     * infinite, out-of-range and sentinel values are all "no reading".
     */
    private static boolean isUsable(@Nullable Double value, double min, double max,
                                    @Nullable Double invalidSentinel) {
        if (value == null || value.isNaN() || value.isInfinite()) return false;
        if (invalidSentinel != null && Math.abs(value - invalidSentinel) < 0.0001) return false;
        return !(value < min) && !(value > max);
    }
}
