package com.example.basilience.models;

/**
 * The single authoritative filter state for a Fogging Report, mirroring
 * ParameterReportFilter's role for Parameter Reports. Built once by
 * FoggingReportsFragment from the currently selected cycle and period, then
 * reused as-is for the fogging-log query, session reconstruction,
 * aggregate statistics, the chart, Recent Activity, and the PDF export - so
 * every output describes exactly the same data subset.
 */
public class FoggingReportFilter {
    public final String deviceId;
    public final String cycleId;
    public final String cycleLabel;
    public final String cycleStatus;
    public final long cycleStartMs;
    public final long cycleEndMs;
    public final String periodLabel;
    public final long effectiveStartMs;
    public final long effectiveEndMs;

    public FoggingReportFilter(String deviceId, String cycleId, String cycleLabel, String cycleStatus,
                                long cycleStartMs, long cycleEndMs, String periodLabel,
                                long effectiveStartMs, long effectiveEndMs) {
        this.deviceId = deviceId;
        this.cycleId = cycleId;
        this.cycleLabel = cycleLabel;
        this.cycleStatus = cycleStatus;
        this.cycleStartMs = cycleStartMs;
        this.cycleEndMs = cycleEndMs;
        this.periodLabel = periodLabel;
        this.effectiveStartMs = effectiveStartMs;
        this.effectiveEndMs = effectiveEndMs;
    }
}
