package com.example.basilience;

import com.google.firebase.database.IgnoreExtraProperties;

@IgnoreExtraProperties
public class OperationRequest {
    public int requestId;
    public String operation;
    public String action;
    public long requestTimestamp;
    public int protocolVersion;

    public OperationRequest() {
        // Default constructor required for calls to DataSnapshot.getValue(OperationRequest.class)
    }

    public OperationRequest(int requestId, String operation, String action, long requestTimestamp, int protocolVersion) {
        this.requestId = requestId;
        this.operation = operation;
        this.action = action;
        this.requestTimestamp = requestTimestamp;
        this.protocolVersion = protocolVersion;
    }
}
