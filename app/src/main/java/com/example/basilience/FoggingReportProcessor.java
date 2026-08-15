package com.example.basilience;

import com.example.basilience.models.FoggingEvent;
import com.example.basilience.models.FoggingReportSummary;
import com.example.basilience.models.FoggingSession;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class FoggingReportProcessor {

    /**
     * Processes raw fogging events to reconstruct sessions and aggregate data into buckets.
     *
     * @param rawEvents The raw fogging logs from Firestore.
     * @param reportStartTimeMs The start time of the selected report window.
     * @param reportEndTimeMs The end time of the selected report window.
     * @param bucketSizeMs The size of each aggregation bucket (e.g., 1 day or 1 hour).
     * @param isRunning True if the actuator status currently reports fogger as running.
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
                    summary.addCompletedSession(currentSession);
                    currentSession = null;
                }
                // If OFF without ON, it is discarded.
            }
        }

        // 3. Handle Running Session
        if (currentSession != null) {
            if (isRunning) {
                summary.setCurrentlyRunningSession(currentSession);
            }
            // If it's not actually running, the session was interrupted/lost, we discard it.
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
            long sessionStart = session.getStartEvent().timestamp;
            long sessionEnd = session.getEndEvent().timestamp;

            bucketStart = reportStartTimeMs;
            while (bucketStart < reportEndTimeMs) {
                long bucketEnd = bucketStart + bucketSizeMs;
                
                // Calculate exact overlap
                long overlap = Math.max(0, Math.min(sessionEnd, bucketEnd) - Math.max(sessionStart, bucketStart));
                if (overlap > 0) {
                    summary.addBucketDuration(bucketStart, overlap);
                }
                
                bucketStart += bucketSizeMs;
            }
        }

        return summary;
    }
}
