package com.example.basilience.models;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The aggregate totals for one rendered Fogging Report, frozen at the moment
 * that report finished loading.
 *
 * <p>This exists so the screen and the exported PDF can never disagree. Every
 * value here is copied straight out of the already-processed
 * {@link FoggingReportSummary} (and the session counts derived alongside it),
 * which is what applies the report's effective range, boundary-session
 * clipping, running-session contribution and anomalous-session exclusion.
 *
 * <p>Deliberately NOT derived from summing a list of sessions: raw
 * {@code FoggingSession.getDurationMs()} values are the unclipped
 * start-to-end durations, so re-adding them would undo the processor's
 * clipping and exclusions. The PDF generator therefore receives this object
 * and prints it verbatim rather than recomputing anything.
 *
 * <p>It carries only plain data - no Android or Fragment state - so it is
 * safe to hand to the PDF generator and to a background thread.
 */
public final class FoggingReportTotals {

    /** Sessions in the report, including a currently-running one. */
    public final int totalSessionCount;
    /** Processor-clipped total fogging runtime for the report range. */
    public final long totalRuntimeMs;
    public final long averageSessionDurationMs;
    public final int automaticSessionCount;
    public final int manualSessionCount;
    public final long automaticRuntimeMs;
    public final long manualRuntimeMs;
    /** Persisted per-strategy runtime, already processed. Never inferred. */
    public final Map<String, Long> strategyRuntimeMs;

    public FoggingReportTotals(int totalSessionCount,
                               long totalRuntimeMs,
                               long averageSessionDurationMs,
                               int automaticSessionCount,
                               int manualSessionCount,
                               long automaticRuntimeMs,
                               long manualRuntimeMs,
                               Map<String, Long> strategyRuntimeMs) {
        this.totalSessionCount = totalSessionCount;
        this.totalRuntimeMs = totalRuntimeMs;
        this.averageSessionDurationMs = averageSessionDurationMs;
        this.automaticSessionCount = automaticSessionCount;
        this.manualSessionCount = manualSessionCount;
        this.automaticRuntimeMs = automaticRuntimeMs;
        this.manualRuntimeMs = manualRuntimeMs;
        this.strategyRuntimeMs = Collections.unmodifiableMap(
                strategyRuntimeMs == null ? new HashMap<>() : new HashMap<>(strategyRuntimeMs));
    }

    /**
     * Builds the totals from an already-processed summary.
     *
     * @param totalSessionCount     session count the report is showing
     * @param automaticSessionCount automatic session count already counted
     * @param manualSessionCount    manual session count already counted
     */
    public static FoggingReportTotals from(FoggingReportSummary summary,
                                           int totalSessionCount,
                                           int automaticSessionCount,
                                           int manualSessionCount) {
        long totalRuntimeMs = summary.getTotalDurationMs();
        long averageMs = totalSessionCount > 0 ? totalRuntimeMs / totalSessionCount : 0L;
        return new FoggingReportTotals(
                totalSessionCount,
                totalRuntimeMs,
                averageMs,
                automaticSessionCount,
                manualSessionCount,
                summary.getTotalAutoDurationMs(),
                summary.getTotalManualDurationMs(),
                summary.getAutoStrategyDurationMs());
    }
}
