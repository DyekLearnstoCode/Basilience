package com.example.basilience.models;

public class FoggingSession {
    private final FoggingEvent startEvent;
    private FoggingEvent endEvent;

    public FoggingSession(FoggingEvent startEvent) {
        this.startEvent = startEvent;
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

        String strategy = startEvent.strategy.trim().toLowerCase();
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

        return "Automatic \u00b7 " + strategy.substring(0, 1).toUpperCase() + strategy.substring(1);
    }
}
