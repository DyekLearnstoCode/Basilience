package com.example.basilience.models;

/**
 * The single authoritative filter state for a Parameter Report. Built once
 * by SystemReportsFragment from the currently selected cycle, parameter, and
 * period, then reused as-is for the graph, the statistics, the farmer
 * summary, the PDF export, and the CSV export - so every output describes
 * exactly the same data subset instead of each recomputing its own range.
 */
public class ParameterReportFilter {
    public final String deviceId;
    public final String cycleId;
    public final String cycleLabel;
    public final String cycleStatus;
    public final long cycleStartMs;
    public final long cycleEndMs;
    public final String canonicalParameter;
    public final String displayParameter;
    public final String periodLabel;
    public final long effectiveStartMs;
    public final long effectiveEndMs;

    public ParameterReportFilter(String deviceId, String cycleId, String cycleLabel, String cycleStatus,
                                  long cycleStartMs, long cycleEndMs, String canonicalParameter,
                                  String displayParameter, String periodLabel,
                                  long effectiveStartMs, long effectiveEndMs) {
        this.deviceId = deviceId;
        this.cycleId = cycleId;
        this.cycleLabel = cycleLabel;
        this.cycleStatus = cycleStatus;
        this.cycleStartMs = cycleStartMs;
        this.cycleEndMs = cycleEndMs;
        this.canonicalParameter = canonicalParameter;
        this.displayParameter = displayParameter;
        this.periodLabel = periodLabel;
        this.effectiveStartMs = effectiveStartMs;
        this.effectiveEndMs = effectiveEndMs;
    }
}
