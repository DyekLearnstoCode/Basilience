package com.example.basilience.models;

import java.util.ArrayList;
import java.util.List;

public class FoggingReportSummary {
    private final List<FoggingSession> completedSessions = new ArrayList<>();
    private final List<FoggingEvent> allEvents = new ArrayList<>();
    private FoggingSession currentlyRunningSession = null;

    private long totalDurationMs = 0;
    private long totalAutoDurationMs = 0;
    private long totalManualDurationMs = 0;
    private final java.util.Map<String, Long> autoStrategyDurationMs = new java.util.HashMap<>();
    
    // Aggregated bucket data (e.g. for charts)
    // Key: Bucket start time (ms), Value: Duration in bucket (ms)
    private final java.util.Map<Long, Long> bucketAggregations = new java.util.HashMap<>();

    private int observedDays = 1;

    public void addCompletedSession(FoggingSession session) {
        completedSessions.add(session);
        long duration = session.getDurationMs();
        totalDurationMs += duration;
        if (session.isManual()) {
            totalManualDurationMs += duration;
        } else {
            totalAutoDurationMs += duration;
            String strategy = session.getStrategy();
            if (strategy != null) {
                long current = autoStrategyDurationMs.containsKey(strategy) ? autoStrategyDurationMs.get(strategy) : 0L;
                autoStrategyDurationMs.put(strategy, current + duration);
            }
        }
    }

    public void setCurrentlyRunningSession(FoggingSession session) {
        this.currentlyRunningSession = session;
    }

    public void setAllEvents(List<FoggingEvent> events) {
        this.allEvents.clear();
        this.allEvents.addAll(events);
    }

    public void addBucketDuration(long bucketStartTimeMs, long durationMs) {
        long current = bucketAggregations.containsKey(bucketStartTimeMs) ? bucketAggregations.get(bucketStartTimeMs) : 0L;
        bucketAggregations.put(bucketStartTimeMs, current + durationMs);
    }

    public void setObservedDays(int days) {
        this.observedDays = Math.max(1, days);
    }

    public List<FoggingSession> getCompletedSessions() { return completedSessions; }
    public List<FoggingEvent> getAllEvents() { return allEvents; }
    public FoggingSession getCurrentlyRunningSession() { return currentlyRunningSession; }
    public long getTotalDurationMs() { return totalDurationMs; }
    public long getTotalAutoDurationMs() { return totalAutoDurationMs; }
    public long getTotalManualDurationMs() { return totalManualDurationMs; }
    public java.util.Map<String, Long> getAutoStrategyDurationMs() { return autoStrategyDurationMs; }
    public java.util.Map<Long, Long> getBucketAggregations() { return bucketAggregations; }
    public int getObservedDays() { return observedDays; }
}
