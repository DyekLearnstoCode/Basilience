package com.example.basilience.models;

public class FoggingSession {
    private final FoggingEvent startEvent;
    private FoggingEvent endEvent;
    // Set by FoggingReportProcessor when the raw ON->OFF gap is too large to
    // be a real fog cycle (e.g. a reboot/offline gap between events). Kept
    // visible in Recent Activity/PDF so the anomaly isn't hidden, but its
    // duration is excluded from report aggregates so it can't skew totals.
    private boolean anomalous;
    private long countedStartMs;
    private long countedEndMs;
    private boolean hasCountedInterval;
    private boolean clippedToReport;

    public FoggingSession(FoggingEvent startEvent) {
        this.startEvent = startEvent;
    }

    public boolean isAnomalous() {
        return anomalous;
    }

    public void setAnomalous(boolean anomalous) {
        this.anomalous = anomalous;
    }

    public void setEndEvent(FoggingEvent endEvent) {
        this.endEvent = endEvent;
    }

    public FoggingEvent getStartEvent() {
        return startEvent;
    }

    public FoggingEvent getEndEvent() {
        return endEvent;
    }

    public boolean isCompleted() {
        return endEvent != null;
    }

    public long getDurationMs() {
        if (!isCompleted()) return 0;
        return Math.max(0, endEvent.timestamp - startEvent.timestamp);
    }

    /**
     * Sets the part of this session that counts inside the report window.
     * Set once by FoggingReportProcessor; totals, chart buckets, the session
     * table and the PDF all read this same interval. The raw start/end events
     * are never changed. An empty interval (start == end) means the session
     * contributes nothing (anomalous/incomplete, or entirely outside).
     */
    public void setCountedInterval(long countedStartMs, long countedEndMs) {
        this.countedStartMs = countedStartMs;
        this.countedEndMs = Math.max(countedStartMs, countedEndMs);
        this.hasCountedInterval = true;
    }

    public long getCountedDurationMs() {
        return hasCountedInterval ? countedEndMs - countedStartMs : getDurationMs();
    }

    public long getCountedStartMs() {
        return hasCountedInterval ? countedStartMs : startEvent.timestamp;
    }

    public long getCountedEndMs() {
        return hasCountedInterval ? countedEndMs : (endEvent != null ? endEvent.timestamp : startEvent.timestamp);
    }

    /** True when part of this session falls outside the report window, so its counted duration is shorter than its real one. */
    public boolean isClippedToReport() {
        return clippedToReport;
    }

    public void setClippedToReport(boolean clippedToReport) {
        this.clippedToReport = clippedToReport;
    }

    public boolean isManual() {
        // According to the new schema, we check isManual or source
        if (startEvent != null) {
            if (startEvent.isManual) return true;
            if ("manual".equalsIgnoreCase(startEvent.source)) return true;
        }
        return false;
    }

    public String getStrategy() {
        if (isManual() || startEvent == null || startEvent.strategy == null) {
            return null;
        }

        String strategy = startEvent.strategy.trim().toLowerCase(java.util.Locale.ROOT);
        switch (strategy) {
            case "startup":
            case "normal":
            case "hot":
            case "cold":
                return strategy;
            default:
                return null;
        }
    }

    public String getDisplayType() {
        if (isManual()) {
            return "Manual";
        }

        String strategy = getStrategy();
        if (strategy == null) {
            return "Automatic";
        }

        return "Automatic \u00b7 " + strategy.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + strategy.substring(1);
    }
}
