package com.example.basilience;

import android.util.Log;

import com.example.basilience.models.FoggingEvent;
import com.example.basilience.models.FoggingReportSummary;
import com.example.basilience.models.FoggingSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class FoggingReportProcessor {

    private static final String TAG = "FoggingReportProcessor";

    // Ceiling on how long a single reconstructed ON->OFF (or still-running)
    // fogging session may plausibly last before it is treated as a
    // reboot/offline-corrupted pair rather than real continuous fogging.
    // Normal fogger duty-cycling runs sessions of a few minutes at a time
    // (short bursts, long gaps between them). Two hours is well beyond any
    // real session length, so it cannot misclassify genuine Basilience fog
    // cycles - including long manual/startup overrides - as stale, while
    // still reliably catching an ON event whose matching OFF only arrives
    // after a multi-hour/multi-day connectivity gap.
    private static final long MAX_PLAUSIBLE_SESSION_DURATION_MS = 2L * 60 * 60 * 1000;

    /**
     * Processes raw fogging events to reconstruct sessions and aggregate data into buckets.
     *
     * @param rawEvents The raw fogging logs from Firestore. May include one event with a
     *                  timestamp before reportStartTimeMs (an ON that started before the
     *                  window) so that a session straddling the window's start boundary can
     *                  be reconstructed and clipped, instead of appearing as an orphan OFF.
     * @param reportStartTimeMs The start time of the selected report window.
     * @param reportEndTimeMs The end time of the selected report window.
     * @param bucketSizeMs The size of each aggregation bucket (e.g., 1 day or 1 hour).
     * @param isRunning True only when the fogger is confirmed to be running
     *                  RIGHT NOW - i.e. the actuator flag says running AND the
     *                  device's live presence is trustworthy. Callers must not
     *                  pass the raw actuator flag on its own: that value
     *                  persists in RTDB after a device drops offline, and
     *                  treating a stale "true" as live made an unmatched ON
     *                  session grow forever ("Running now - 1d 5h"). When this
     *                  is false, a trailing unmatched ON is recorded as an
     *                  incomplete session contributing zero duration.
     * @return A summarized report containing completed sessions, aggregations, and statistics.
     */
    public static FoggingReportSummary process(
            List<FoggingEvent> rawEvents,
            long reportStartTimeMs,
            long reportEndTimeMs,
            long bucketSizeMs,
            boolean isRunning) {

        FoggingReportSummary summary = new FoggingReportSummary();
        if (rawEvents == null || rawEvents.isEmpty()) {
            return summary;
        }

        // 1. Sort events chronologically
        List<FoggingEvent> sortedEvents = new ArrayList<>(rawEvents);
        Collections.sort(sortedEvents, new Comparator<FoggingEvent>() {
            @Override
            public int compare(FoggingEvent e1, FoggingEvent e2) {
                return Long.compare(e1.timestamp, e2.timestamp);
            }
        });
        summary.setAllEvents(sortedEvents);

        // 2. Reconstruct Sessions
        FoggingSession currentSession = null;
        long earliestValidEventTime = Long.MAX_VALUE;

        for (FoggingEvent event : sortedEvents) {
            // Track earliest event for observed days calculation
            if (event.timestamp >= reportStartTimeMs && event.timestamp <= reportEndTimeMs) {
                if (event.timestamp < earliestValidEventTime) {
                    earliestValidEventTime = event.timestamp;
                }
            }

            if ("ON".equalsIgnoreCase(event.event)) {
                // If ON -> ON, we discard the first ON because an OFF was missed.
                // Starting a fresh session prevents corrupting statistics with massive invalid durations.
                currentSession = new FoggingSession(event);
            } else if ("OFF".equalsIgnoreCase(event.event)) {
                if (currentSession != null) {
                    currentSession.setEndEvent(event);

                    long rawDurationMs = currentSession.getDurationMs();
                    long effectiveDurationMs;
                    if (rawDurationMs > MAX_PLAUSIBLE_SESSION_DURATION_MS) {
                        currentSession.setAnomalous(true);
                        effectiveDurationMs = 0L;
                        Log.w(TAG, "Excluding anomalous fogging session from report aggregates: start="
                                + currentSession.getStartEvent().timestamp + " end=" + event.timestamp
                                + " rawDurationMs=" + rawDurationMs + " exceeds MAX_PLAUSIBLE_SESSION_DURATION_MS="
                                + MAX_PLAUSIBLE_SESSION_DURATION_MS + " (likely a reboot/offline gap between ON and OFF)");
                    } else {
                        effectiveDurationMs = clippedDurationMs(currentSession, reportStartTimeMs, reportEndTimeMs);
                    }

                    summary.addCompletedSession(currentSession, effectiveDurationMs);
                    currentSession = null;
                }
                // If OFF without ON, it is discarded.
            }
        }

        // 3. Handle Running Session
        long nowMs = System.currentTimeMillis();
        if (currentSession != null) {
            if (isRunning) {
                summary.setCurrentlyRunningSession(currentSession);

                long rawRunningDurationMs = Math.max(0, nowMs - currentSession.getStartEvent().timestamp);
                if (rawRunningDurationMs > MAX_PLAUSIBLE_SESSION_DURATION_MS) {
                    Log.w(TAG, "Currently-running fogging session exceeds MAX_PLAUSIBLE_SESSION_DURATION_MS="
                            + MAX_PLAUSIBLE_SESSION_DURATION_MS + "; excluding from aggregates but still showing as running. start="
                            + currentSession.getStartEvent().timestamp);
                } else {
                    long effectiveEnd = Math.min(nowMs, reportEndTimeMs);
                    long effectiveStart = Math.max(currentSession.getStartEvent().timestamp, reportStartTimeMs);
                    long runningDurationMs = Math.max(0, effectiveEnd - effectiveStart);
                    summary.addRunningSessionDuration(currentSession, runningDurationMs);
                }
            } else {
                // No trustworthy confirmation that the fogger is running right
                // now, so this trailing ON has no known OFF: the device may
                // have gone offline mid-session, or stopped without the OFF
                // event reaching Firestore. Its real end time is unknown, so
                // it is surfaced as an incomplete record - the same treatment
                // an anomalous ON->OFF pair already gets - contributing zero
                // to every aggregate rather than being counted as
                // now-minus-start of fabricated runtime.
                //
                // Note this deliberately no longer discards the session
                // silently: a recorded fogging start really did happen, and
                // hiding it made an offline stall look like nothing at all.
                currentSession.setAnomalous(true);
                summary.addCompletedSession(currentSession, 0L);
                Log.w(TAG, "Unmatched fogging ON with no confirmed live-running state; recording as an"
                        + " incomplete session excluded from aggregates. start="
                        + currentSession.getStartEvent().timestamp);
            }
        }

        // 4. Calculate observed days based on the earliest valid event within the report window
        if (earliestValidEventTime != Long.MAX_VALUE) {
            long durationMs = reportEndTimeMs - earliestValidEventTime;
            int days = (int) Math.ceil(durationMs / (double) (1000 * 60 * 60 * 24));
            summary.setObservedDays(Math.max(1, days));
        } else {
            // Fallback if no events fall inside the window
            long durationMs = reportEndTimeMs - reportStartTimeMs;
            int days = (int) Math.ceil(durationMs / (double) (1000 * 60 * 60 * 24));
            summary.setObservedDays(Math.max(1, days));
        }

        // 5. Cross-Bucket Aggregation
        // Pre-fill buckets with 0 to ensure the chart renders empty buckets
        long bucketStart = reportStartTimeMs;
        while (bucketStart < reportEndTimeMs) {
            summary.getBucketAggregations().put(bucketStart, 0L);
            bucketStart += bucketSizeMs;
        }

        for (FoggingSession session : summary.getCompletedSessions()) {
            if (session.isAnomalous()) continue;

            addOverlapToBuckets(summary, session.getStartEvent().timestamp, session.getEndEvent().timestamp,
                    reportStartTimeMs, reportEndTimeMs, bucketSizeMs);
        }

        // Currently-running session also contributes to whichever buckets its
        // elapsed-so-far runtime overlaps, clipped to now/report end the same
        // way its total-duration contribution was above.
        FoggingSession runningSession = summary.getCurrentlyRunningSession();
        if (runningSession != null
                && Math.max(0, nowMs - runningSession.getStartEvent().timestamp) <= MAX_PLAUSIBLE_SESSION_DURATION_MS) {
            addOverlapToBuckets(summary, runningSession.getStartEvent().timestamp, Math.min(nowMs, reportEndTimeMs),
                    reportStartTimeMs, reportEndTimeMs, bucketSizeMs);
        }

        return summary;
    }

    /**
     * Adds a session's overlap duration to every bucket it actually touches.
     * A session is almost always far shorter than a bucket, so only the
     * handful of buckets it can possibly overlap are visited directly (via
     * the bucket-index math below) instead of scanning every bucket in the
     * whole report window per session - the previous version was O(sessions
     * x total buckets), which for "Entire Cycle" on a long-running,
     * frequently-fogged cycle meant hundreds of thousands of iterations on
     * the main thread (Firestore/RTDB listeners always deliver there).
     */
    private static void addOverlapToBuckets(FoggingReportSummary summary, long sessionStart, long sessionEnd,
                                             long reportStartTimeMs, long reportEndTimeMs, long bucketSizeMs) {
        long effectiveStart = Math.max(sessionStart, reportStartTimeMs);
        long effectiveEnd = Math.min(sessionEnd, reportEndTimeMs);
        if (effectiveEnd <= effectiveStart) return;

        long firstBucketIndex = (effectiveStart - reportStartTimeMs) / bucketSizeMs;
        long lastBucketIndex = (effectiveEnd - 1 - reportStartTimeMs) / bucketSizeMs;

        for (long index = firstBucketIndex; index <= lastBucketIndex; index++) {
            long bucketStart = reportStartTimeMs + index * bucketSizeMs;
            if (bucketStart >= reportEndTimeMs) break;
            long bucketEnd = bucketStart + bucketSizeMs;
            // Same overlap formula as before, just no longer computed for
            // every bucket in the window - only the ones this loop reaches.
            long overlap = Math.max(0, Math.min(sessionEnd, bucketEnd) - Math.max(sessionStart, bucketStart));
            if (overlap > 0) {
                summary.addBucketDuration(bucketStart, overlap);
            }
        }
    }

    // Clips a completed session's duration to the report window so that only
    // the portion of a session that actually falls within [windowStart, windowEnd]
    // is counted toward aggregates - e.g. a session that started before the
    // window and ended inside it only contributes windowStart..end, not its
    // full real-world duration.
    private static long clippedDurationMs(FoggingSession session, long windowStart, long windowEnd) {
        if (!session.isCompleted()) return 0L;
        long start = Math.max(session.getStartEvent().timestamp, windowStart);
        long end = Math.min(session.getEndEvent().timestamp, windowEnd);
        return Math.max(0, end - start);
    }
}
