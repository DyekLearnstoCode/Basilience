package com.example.basilience;

/**
 * One physical, stability-confirmed weighing captured by a paired
 * BasilienceHarvestScale device, read from
 * devices/{scaleDeviceId}/harvestScale/harvests/{measurementId} in
 * Realtime Database (see Database_Helper.findUnconsumedHarvestScaleReading()).
 *
 * Replaces a bare Double grams value - that discarded the measurement's
 * identity (the RTDB push key) and its timestamps, making it impossible to
 * tell a genuinely new scale reading apart from one already used for an
 * earlier Harvest, or from a stale reading left over from days ago. Every
 * field here is required for that: measurementId is what
 * harvestScaleConsumptions/{deterministicId} is keyed on for idempotency
 * (see Database_Helper.addHarvestTransaction(String, Harvest,
 * HarvestScaleReading)), and capturedAt/syncedAt are what
 * HARVEST_SCALE_MAX_READING_AGE_MS staleness checking uses.
 */
public class HarvestScaleReading {

    private final String measurementId;   // RTDB push key under harvests/ - the canonical measurement ID
    private final String scaleDeviceId;   // Which physical scale this came from
    private final double grams;
    // Epoch seconds - 0 means "not available" (the scale's clock wasn't
    // synced yet when this field was set), never a fabricated value. See
    // BasilienceHarvestScale.ino's capturedAt/syncedAt handling.
    private final long capturedAt;
    private final long syncedAt;

    public HarvestScaleReading(String measurementId, String scaleDeviceId, double grams,
                                long capturedAt, long syncedAt) {
        this.measurementId = measurementId;
        this.scaleDeviceId = scaleDeviceId;
        this.grams = grams;
        this.capturedAt = capturedAt;
        this.syncedAt = syncedAt;
    }

    public String getMeasurementId() { return measurementId; }
    public String getScaleDeviceId() { return scaleDeviceId; }
    public double getGrams() { return grams; }
    public long getCapturedAt() { return capturedAt; }
    public long getSyncedAt() { return syncedAt; }

    /**
     * The timestamp to judge this reading's age by: capturedAt ONLY - the
     * scale's own clock at the moment of physical measurement, never
     * syncedAt (when the write merely landed on the server). An offline
     * measurement can sit staged on the scale for hours, across a reboot,
     * before finally syncing - at that point syncedAt is "now" regardless
     * of how old the actual weighing was, so using it as a freshness
     * fallback would let a stale reading masquerade as one just taken.
     * capturedAt is 0 only when the scale genuinely could not establish
     * (or reconstruct, within the same boot - see
     * BasilienceHarvestScale.ino/FirebaseManager.cpp) when the measurement
     * was actually taken; callers must treat that as "unverifiable," never
     * as "just captured," and must not fall back to syncedAt themselves.
     */
    public long effectiveTimestampEpochSec() {
        return capturedAt;
    }
}
