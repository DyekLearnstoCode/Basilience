package com.example.basilience;

import androidx.annotation.Nullable;

import com.example.basilience.models.SensorData;

import java.util.ArrayList;
import java.util.List;

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

    // =========================================================================
    // OPERATION-AWARE VALIDATION
    //
    // The advisor above only ever asks "is the parameter already where it
    // should be" - it has no idea whether Basilience is currently correcting
    // or stabilizing that same parameter, whether some other actuator already
    // owns the one being requested, or whether firmware has a hard lock up
    // that will refuse the command outright. evaluateCommand() is the single
    // entry point that folds all of that - plus the CONDITION-based advice
    // above - into one SAFE / SOFT_CONFLICT / HARD_BLOCK verdict per actuator,
    // for both ON and OFF requests. See the task's operation-aware validation
    // plan for the full per-actuator policy matrix this implements.
    // =========================================================================

    /** Final verdict for a manual command, folding in every input this class knows about. */
    public enum Decision { SAFE, SOFT_CONFLICT, HARD_BLOCK }

    /** One of the 10 independently-toggleable actuator cards (Bloom Pump has no card of its own - it is paired under NUTRIENTS). */
    public enum ActuatorKey {
        PH_UP, PH_DOWN, NUTRIENTS, SOLENOID, PELTIER,
        CIRCULATION_PUMP, FOGGER, BLOWER, CANOPY_FAN, GROW_LIGHT
    }

    /** status/currentMode ordinals - mirrors firmware's SystemMode enum (Types.h). */
    private static final int MODE_REFILLING = 3;
    private static final int MODE_DOSING_PH = 4;
    private static final int MODE_STABILIZING_PH = 5;
    private static final int MODE_DOSING_EC = 6;
    private static final int MODE_STABILIZING_EC = 7;

    /**
     * The system-wide operation state a command decision needs, all sourced
     * from devices/{deviceId}/status - a node the Monitoring screen already
     * listens to in full. No field here is derived or reimplemented from
     * firmware logic; each is published by firmware as-is.
     */
    public static final class OperationContext {
        /** Raw SystemMode ordinal from status/currentMode, or -1 if not yet loaded. */
        public int currentMode = -1;
        /** "up" / "down" / "none", or null if the firmware hasn't published it yet. */
        @Nullable public String phDirection;
        /** "raise" / "dilute" / "none", or null if the firmware hasn't published it yet. */
        @Nullable public String ecDirection;
        public boolean safetyLock;
        public boolean phSubsystemLocked;
        public boolean ecSubsystemLocked;
        public boolean refillSubsystemLocked;
        public boolean coolingSubsystemLocked;
        public boolean reservoirLocked;
    }

    /** The actuatorStatus fields for one actuator, exactly as published - no derived state. */
    public static final class ActuatorSnapshot {
        public boolean physicalRunning;
        @Nullable public String physicalSource;
        @Nullable public String reason;
    }

    /** actuatorStatus snapshots for every actuator a rule might need to cross-check against. */
    public static final class ActuatorSnapshots {
        public final ActuatorSnapshot phUp = new ActuatorSnapshot();
        public final ActuatorSnapshot phDown = new ActuatorSnapshot();
        public final ActuatorSnapshot nutrients = new ActuatorSnapshot();
        public final ActuatorSnapshot solenoid = new ActuatorSnapshot();
        public final ActuatorSnapshot peltier = new ActuatorSnapshot();
        public final ActuatorSnapshot circulationPump = new ActuatorSnapshot();
        public final ActuatorSnapshot fogger = new ActuatorSnapshot();
        public final ActuatorSnapshot blower = new ActuatorSnapshot();
        public final ActuatorSnapshot canopyFan = new ActuatorSnapshot();
        public final ActuatorSnapshot growLight = new ActuatorSnapshot();

        ActuatorSnapshot forKey(ActuatorKey key) {
            switch (key) {
                case PH_UP: return phUp;
                case PH_DOWN: return phDown;
                case NUTRIENTS: return nutrients;
                case SOLENOID: return solenoid;
                case PELTIER: return peltier;
                case CIRCULATION_PUMP: return circulationPump;
                case FOGGER: return fogger;
                case BLOWER: return blower;
                case CANOPY_FAN: return canopyFan;
                case GROW_LIGHT: return growLight;
                default: return new ActuatorSnapshot();
            }
        }
    }

    /** The outcome of evaluateCommand(): what to do, and - for SOFT_CONFLICT/HARD_BLOCK - what to say. */
    public static final class Result {
        public final Decision decision;
        @Nullable public final String title;
        @Nullable public final String message;

        private Result(Decision decision, @Nullable String title, @Nullable String message) {
            this.decision = decision;
            this.title = title;
            this.message = message;
        }

        static Result safe() { return new Result(Decision.SAFE, null, null); }
        static Result soft(String message) { return new Result(Decision.SOFT_CONFLICT, "System Operation in Progress", message); }
        static Result hard(String message) { return new Result(Decision.HARD_BLOCK, "Action Unavailable", message); }
    }

    private static final String SAFETY_LOCK_MESSAGE =
            "Basilience is currently in a safety-lock state. Manual actuator control is unavailable until the safety condition is cleared.";
    private static final String PH_SUBSYSTEM_LOCK_MESSAGE =
            "Basilience has temporarily locked the pH correction subsystem because a safety or correction limit was reached. pH dosing is unavailable until the lock is cleared.";
    private static final String EC_SUBSYSTEM_LOCK_MESSAGE =
            "Basilience has temporarily locked the EC correction subsystem because a safety or correction limit was reached. Nutrient dosing is unavailable until the lock is cleared.";
    private static final String REFILL_SUBSYSTEM_LOCK_MESSAGE =
            "Basilience has temporarily locked the refill subsystem because a safety or correction limit was reached. Refill/dilution is unavailable until the lock is cleared.";
    private static final String COOLING_SUBSYSTEM_LOCK_MESSAGE =
            "Basilience has temporarily locked the cooling subsystem because a safety or correction limit was reached. Cooling is unavailable until the lock is cleared.";
    private static final String RESERVOIR_LOCKED_MESSAGE =
            "Basilience currently has the reservoir locked for another automatic operation. This action is unavailable until that operation finishes.";
    private static final String DOSING_BLOCKED_BY_REFILL_MESSAGE =
            "The reservoir is currently being refilled. Dosing is unavailable until the refill finishes.";
    private static final String REFILL_BLOCKED_BY_DOSING_MESSAGE =
            "A dosing pump is currently active. The reservoir cannot be refilled until dosing finishes.";

    /**
     * The single entry point every actuator toggle - ON or OFF - is routed
     * through. Priority order (first match wins, per the operation-aware
     * validation plan):
     *   1. known authoritative hard locks/interlocks
     *   2. current-operation hard dependency (none exist beyond (1) today -
     *      Peltier's wait for Circulation is deliberately left to firmware)
     *   3. automation ownership (this actuator is already running under
     *      source=="automatic")
     *   4. blanket pH/EC dosing-or-stabilization interference
     *   5. existing normal-condition (Condition/Advice) rule
     *   6. SAFE
     * Steps 3 and 4 are combined into a single message when both apply -
     * never two dialogs for one command.
     */
    public static Result evaluateCommand(ActuatorKey key, boolean requestedOn,
                                          @Nullable SensorData data, @Nullable AlertFlags flags,
                                          @Nullable Double configuredHighWaterTemp,
                                          OperationContext ops, ActuatorSnapshots all) {
        ActuatorSnapshot self = all.forKey(key);

        if (requestedOn) {
            Result hard = hardLockForOn(key, ops, all);
            if (hard != null) return hard;
        } else if (key == ActuatorKey.CIRCULATION_PUMP) {
            // Firmware already refuses this OFF outright while required
            // (ActuatorManager::requestCommand) - actuatorStatus/circulationPump/reason
            // is non-empty exactly when that refusal would happen, so the app
            // can warn before sending rather than let the command silently no-op.
            if (self.reason != null && !self.reason.trim().isEmpty()) {
                return Result.hard(circulationHardBlockMessage(self.reason));
            }
        }

        boolean ownedAutomatically = isAutomaticallyRunning(self);
        String ownershipClause = ownershipClauseFor(key, requestedOn, ownedAutomatically, all);
        String phEcClause = requestedOn ? phEcClauseFor(key, ops) : null;

        if (ownershipClause != null || phEcClause != null) {
            return Result.soft(combineSoftClauses(key, ops, ownershipClause, phEcClause));
        }

        if (requestedOn) {
            Condition condition = legacyConditionFor(key);
            if (condition != null && condition != Condition.NONE) {
                Advice advice = evaluate(condition, data, flags, configuredHighWaterTemp);
                if (advice != Advice.PROCEED) {
                    return Result.soft(messageFor(condition, advice));
                }
            }
        }

        return Result.safe();
    }

    private static boolean isAutomaticallyRunning(ActuatorSnapshot s) {
        return s.physicalRunning && "automatic".equalsIgnoreCase(s.physicalSource);
    }

    private static boolean isPhActive(OperationContext ops) {
        return ops.currentMode == MODE_DOSING_PH || ops.currentMode == MODE_STABILIZING_PH;
    }

    private static boolean isEcActive(OperationContext ops) {
        return ops.currentMode == MODE_DOSING_EC || ops.currentMode == MODE_STABILIZING_EC;
    }

    /** Maps the 5 actuators with an existing sensor-threshold rule to their Condition; everyone else has none. */
    private static Condition legacyConditionFor(ActuatorKey key) {
        switch (key) {
            case PH_UP: return Condition.PH_RAISE;
            case PH_DOWN: return Condition.PH_LOWER;
            case NUTRIENTS: return Condition.EC_RAISE;
            case PELTIER: return Condition.WATER_COOL;
            case SOLENOID: return Condition.WATER_LEVEL_FILL;
            default: return Condition.NONE;
        }
    }

    // ---- 1. Hard locks/interlocks (ON requests only - firmware never refuses an OFF) ----

    private static Result hardLockForOn(ActuatorKey key, OperationContext ops, ActuatorSnapshots all) {
        if (ops.safetyLock) return Result.hard(SAFETY_LOCK_MESSAGE);

        switch (key) {
            case PH_UP:
            case PH_DOWN: {
                if (ops.phSubsystemLocked) return Result.hard(PH_SUBSYSTEM_LOCK_MESSAGE);
                boolean ownsReservoir = isPhActive(ops);
                if (ops.reservoirLocked && !ownsReservoir) return Result.hard(RESERVOIR_LOCKED_MESSAGE);
                if (all.solenoid.physicalRunning) return Result.hard(DOSING_BLOCKED_BY_REFILL_MESSAGE);
                boolean isUp = key == ActuatorKey.PH_UP;
                ActuatorSnapshot other = isUp ? all.phDown : all.phUp;
                if (other.physicalRunning) {
                    String otherName = isUp ? "pH Down" : "pH Up";
                    String selfName = isUp ? "pH Up" : "pH Down";
                    return Result.hard(otherName + " is currently running. " + selfName + " cannot be activated at the same time.");
                }
                return null;
            }
            case NUTRIENTS: {
                if (ops.ecSubsystemLocked) return Result.hard(EC_SUBSYSTEM_LOCK_MESSAGE);
                boolean ownsReservoir = isEcActive(ops);
                if (ops.reservoirLocked && !ownsReservoir) return Result.hard(RESERVOIR_LOCKED_MESSAGE);
                if (all.solenoid.physicalRunning) return Result.hard(DOSING_BLOCKED_BY_REFILL_MESSAGE);
                return null;
            }
            case SOLENOID: {
                if (ops.refillSubsystemLocked) return Result.hard(REFILL_SUBSYSTEM_LOCK_MESSAGE);
                boolean dilutionOwnsReservoir = ops.currentMode == MODE_DOSING_EC && "dilute".equalsIgnoreCase(ops.ecDirection);
                boolean refilling = ops.currentMode == MODE_REFILLING;
                if (ops.reservoirLocked && !refilling && !dilutionOwnsReservoir) return Result.hard(RESERVOIR_LOCKED_MESSAGE);
                if (all.phUp.physicalRunning || all.phDown.physicalRunning || all.nutrients.physicalRunning) {
                    return Result.hard(REFILL_BLOCKED_BY_DOSING_MESSAGE);
                }
                return null;
            }
            case PELTIER:
                if (ops.coolingSubsystemLocked) return Result.hard(COOLING_SUBSYSTEM_LOCK_MESSAGE);
                return null;
            default:
                // Circulation Pump ON, Fogger, Blower, Canopy Fan, Grow Light: no
                // subsystem lock or hard dependency exists beyond safetyLock above
                // (confirmed by reading every case in ActuatorManager::validateCommand).
                return null;
        }
    }

    /** Parses actuatorStatus/circulationPump/reason (AutomationManager::getCirculationReason) into a user-facing HARD_BLOCK message. */
    private static String circulationHardBlockMessage(String reason) {
        boolean cooling = reason.contains("temperature_circulation");
        boolean ph = reason.contains("ph_stabilization");
        boolean ec = reason.contains("ec_stabilization");

        List<String> clauses = new ArrayList<>();
        if (cooling) clauses.add("cooling the nutrient solution");
        if (ph) clauses.add("stabilizing the pH level");
        if (ec) clauses.add("stabilizing the EC level");

        if (clauses.isEmpty()) {
            return "Basilience currently requires the Circulation Pump for an active automatic operation and it cannot be turned off at this time.";
        }

        String activity = joinWithAnd(clauses);
        if (clauses.size() == 1 && cooling) {
            return "Basilience is currently " + activity + ". The Circulation Pump is required while cooling is active and cannot be turned off at this time.";
        }
        String plural = clauses.size() > 1 ? "these processes" : "this process";
        return "Basilience is currently " + activity + ". The Circulation Pump is required during " + plural + " and cannot be turned off at this time.";
    }

    private static String joinWithAnd(List<String> parts) {
        if (parts.size() == 1) return parts.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(i == parts.size() - 1 ? " and " : ", ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    // ---- 3. Automation ownership (same-actuator ON overlap / OFF interruption) ----

    private static String ownershipClauseFor(ActuatorKey key, boolean requestedOn, boolean ownedAutomatically, ActuatorSnapshots all) {
        switch (key) {
            case PH_UP:
            case PH_DOWN:
            case NUTRIENTS:
                // ON-while-owned for these three is already covered by the
                // pH/EC blanket clause below - automatic pH/EC dosing always
                // means currentMode reflects it, so the blanket rule already
                // fires. Only the OFF-interruption case is distinct here.
                if (!requestedOn && ownedAutomatically) {
                    return "Basilience is currently running this action automatically. Turning it off will interrupt the operation in progress.";
                }
                return null;
            case SOLENOID:
                // Unlike the three above, Solenoid can be automatically
                // running for a plain reservoir refill (currentMode==REFILLING),
                // which is outside the pH/EC blanket set entirely - so its ON
                // case needs its own ownership clause, not just OFF. When the
                // ownership instead comes from an EC-dilution pulse
                // (DOSING_EC + ecDirection=="dilute"), phEcClauseFor also
                // fires and the two combine into one message, same as Fogger.
                if (!ownedAutomatically) return null;
                return requestedOn
                        ? "Basilience is already running an automatic refill. Activating the Solenoid manually would overlap the current refill operation."
                        : "Basilience is currently running an automatic refill. Turning off the Solenoid will interrupt the refill in progress.";
            case PELTIER:
                if (!ownedAutomatically) return null;
                return requestedOn
                        ? "Basilience is already cooling the nutrient solution automatically. Activating the Peltier manually would overlap the current cooling operation."
                        : "Basilience is currently cooling the nutrient solution automatically. Turning off the Peltier will interrupt the active cooling process.";
            case FOGGER:
                if (!isAutomaticallyRunning(all.fogger)) return null;
                return requestedOn
                        ? "The Fogger is already being controlled automatically."
                        : "Basilience is currently running the Fogger automatically. Turning it off will interrupt the automatic fogging cycle.";
            case BLOWER:
                if (!isAutomaticallyRunning(all.blower)) return null;
                return requestedOn
                        ? "Basilience is already running the Blower automatically as part of the current fogging cycle."
                        : "Basilience is currently running the Blower automatically as part of the current fogging cycle. Turning it off will interrupt that cycle.";
            case CANOPY_FAN:
                if (requestedOn || !isAutomaticallyRunning(all.canopyFan)) return null;
                return "Basilience is currently running the Canopy Fan automatically for humidity control. Turning it off will interrupt that correction.";
            case GROW_LIGHT:
                if (requestedOn || !isAutomaticallyRunning(all.growLight)) return null;
                return "Basilience is currently running the automatic grow-light schedule. Turning off the Grow Light will interrupt the scheduled lighting.";
            case CIRCULATION_PUMP:
            default:
                return null;
        }
    }

    // ---- 4. Blanket pH/EC dosing-or-stabilization interference (ON requests only) ----

    /**
     * pH Up, pH Down, Grow/Bloom Pump (NUTRIENTS), Solenoid, Fogger and
     * Peltier can all materially affect the nutrient solution, reservoir
     * state, or the active correction reading - so each gets a SOFT_CONFLICT
     * while Basilience is dosing or stabilizing pH or EC, even when the
     * requested actuator is not itself automation-owned. Grow Light, Canopy
     * Fan and Blower are deliberately excluded from this blanket rule; they
     * are only ever conflicted by their own automation-ownership case above.
     */
    private static String phEcClauseFor(ActuatorKey key, OperationContext ops) {
        boolean phActive = isPhActive(ops);
        boolean ecActive = isEcActive(ops);
        if (!phActive && !ecActive) return null;

        switch (key) {
            case PH_UP: return phPumpClause(true, ops, phActive);
            case PH_DOWN: return phPumpClause(false, ops, phActive);
            case NUTRIENTS: return ecPumpClause(ops, ecActive);
            case SOLENOID: return solenoidStabilizationClause(ops, phActive, ecActive);
            case FOGGER: return stabilizationIntro(ops, phActive) + " Activating the Fogger may affect the ongoing "
                    + (correctingNow(ops, phActive) ? "correction process." : "stabilization process.");
            case PELTIER: return stabilizationIntro(ops, phActive) + " Changing the Peltier may affect the ongoing "
                    + (correctingNow(ops, phActive) ? "correction reading." : "stabilization reading.");
            default: return null; // Grow Light, Canopy Fan, Blower, Circulation Pump: not in the blanket set
        }
    }

    /** "Basilience is currently correcting/stabilizing the pH/EC level[, after a pH Up/Down correction]." */
    private static String stabilizationIntro(OperationContext ops, boolean phActive) {
        String verb = correctingNow(ops, phActive) ? "correcting" : "stabilizing";
        if (phActive) {
            if ("up".equalsIgnoreCase(ops.phDirection)) {
                return "Basilience is currently " + verb + " the pH level after a pH Up correction.";
            }
            if ("down".equalsIgnoreCase(ops.phDirection)) {
                return "Basilience is currently " + verb + " the pH level after a pH Down correction.";
            }
            return "Basilience is currently " + verb + " the pH level.";
        }
        return "Basilience is currently " + verb + " the EC level.";
    }

    private static boolean correctingNow(OperationContext ops, boolean phActive) {
        return phActive ? ops.currentMode == MODE_DOSING_PH : ops.currentMode == MODE_DOSING_EC;
    }

    private static String phPumpClause(boolean isUp, OperationContext ops, boolean phActive) {
        if (!phActive) {
            // An EC operation is active but not a pH one - generic wording, no direction.
            return "Activating the " + (isUp ? "pH Up" : "pH Down") + " Pump may affect the ongoing EC correction.";
        }
        boolean directionUp = "up".equalsIgnoreCase(ops.phDirection);
        boolean directionDown = "down".equalsIgnoreCase(ops.phDirection);
        String verb = ops.currentMode == MODE_DOSING_PH ? "correcting" : "stabilizing";

        if (isUp && directionUp) {
            return "Basilience is currently " + verb + " the pH level after a pH Up correction. Activating the pH Up Pump again may affect the "
                    + (ops.currentMode == MODE_DOSING_PH ? "correction" : "stabilization reading") + " and cause the pH level to overshoot the target range.";
        }
        if (!isUp && directionDown) {
            return "Basilience is currently " + verb + " the pH level after a pH Down correction. Activating the pH Down Pump again may affect the "
                    + (ops.currentMode == MODE_DOSING_PH ? "correction" : "stabilization reading") + " and cause the pH level to overshoot the target range.";
        }
        if (isUp && directionDown) {
            return "Basilience is currently " + verb + " the pH level after lowering it. The current pH is still above the target range. "
                    + "Activating the pH Up Pump would counteract the ongoing correction.";
        }
        if (!isUp && directionUp) {
            return "Basilience is currently " + verb + " the pH level after increasing it. The current pH is still below the target range. "
                    + "Activating the pH Down Pump would counteract the ongoing correction.";
        }
        // Direction unknown (old firmware, or phDirection=="none")
        return "Basilience is currently " + verb + " the pH level. Activating the " + (isUp ? "pH Up" : "pH Down")
                + " Pump may affect the ongoing correction.";
    }

    private static String ecPumpClause(OperationContext ops, boolean ecActive) {
        if (!ecActive) {
            return "Activating the Nutrient Pump may affect the ongoing pH correction.";
        }
        boolean raising = "raise".equalsIgnoreCase(ops.ecDirection);
        boolean diluting = "dilute".equalsIgnoreCase(ops.ecDirection);
        String verb = ops.currentMode == MODE_DOSING_EC ? "correcting" : "stabilizing";

        if (raising) {
            return "Basilience is currently " + verb + " the EC level by adding nutrients. Activating the Nutrient Pump again may affect the "
                    + (ops.currentMode == MODE_DOSING_EC ? "correction" : "stabilization reading") + " and cause the EC level to overshoot the target range.";
        }
        if (diluting) {
            return "Basilience is currently " + verb + " the EC level by diluting the reservoir. Activating the Nutrient Pump would counteract the ongoing dilution.";
        }
        return "Basilience is currently " + verb + " the EC level. Activating the Nutrient Pump may affect the ongoing correction.";
    }

    private static String solenoidStabilizationClause(OperationContext ops, boolean phActive, boolean ecActive) {
        if (ecActive) {
            boolean diluting = "dilute".equalsIgnoreCase(ops.ecDirection);
            String verb = ops.currentMode == MODE_DOSING_EC ? "correcting" : "stabilizing";
            if (diluting) {
                return "Basilience is currently " + verb + " the EC level by diluting the reservoir. Opening the Solenoid again may affect the ongoing dilution.";
            }
            return "Basilience is currently " + verb + " the EC level by adding nutrients. Opening the Solenoid may dilute the reservoir and counteract the ongoing correction.";
        }
        // pH-only active - generic wording, no EC direction concept.
        String verb = ops.currentMode == MODE_DOSING_PH ? "correcting" : "stabilizing";
        return "Basilience is currently " + verb + " the pH level. Opening the Solenoid may affect the ongoing correction.";
    }

    private static String displayName(ActuatorKey key) {
        switch (key) {
            case PH_UP: return "pH Up Pump";
            case PH_DOWN: return "pH Down Pump";
            case NUTRIENTS: return "Nutrient Pump";
            case SOLENOID: return "Solenoid";
            case PELTIER: return "Peltier";
            case CIRCULATION_PUMP: return "Circulation Pump";
            case FOGGER: return "Fogger";
            case BLOWER: return "Blower";
            case CANOPY_FAN: return "Canopy Fan";
            case GROW_LIGHT: return "Grow Light";
            default: return "actuator";
        }
    }

    private static String combineSoftClauses(ActuatorKey key, OperationContext ops,
                                              @Nullable String ownershipClause, @Nullable String phEcClause) {
        StringBuilder sb = new StringBuilder();
        if (ownershipClause != null && phEcClause != null) {
            // Both an active pH/EC operation and this actuator's own
            // automation ownership apply - one combined, shorter sentence,
            // not two dialogs and not the two individually-composed clauses
            // stitched together (see the plan's Fogger-during-stabilization
            // example): "Basilience is currently stabilizing the pH level
            // and the Fogger is already being controlled automatically.
            // Manually changing the Fogger may interfere with the ongoing
            // process."
            boolean phActive = isPhActive(ops);
            String verb = correctingNow(ops, phActive) ? "correcting" : "stabilizing";
            String parameter = phActive ? "pH" : "EC";
            String name = displayName(key);
            sb.append("Basilience is currently ").append(verb).append(" the ").append(parameter)
              .append(" level and the ").append(name).append(" is already being controlled automatically. ")
              .append("Manually changing the ").append(name).append(" may interfere with the ongoing process.");
        } else if (phEcClause != null) {
            sb.append(phEcClause);
        } else {
            sb.append(ownershipClause);
        }
        sb.append(" Are you sure you want to continue?");
        return sb.toString();
    }
}
