package com.example.basilience.models;

public class FoggingEvent {
    public String id;
    public String event; // "ON" or "OFF"
    public long timestamp;
    public String source;
    public String reason;
    public boolean isManual;

    public FoggingEvent() {
    }

    public FoggingEvent(String event, long timestamp, String source, String reason, boolean isManual) {
        this.event = event;
        this.timestamp = timestamp;
        this.source = source;
        this.reason = reason;
        this.isManual = isManual;
    }
}
