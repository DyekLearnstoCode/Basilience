package com.example.basilience;

public class Device {
    private String deviceId;
    private String deviceName;
    private String deviceToken;
    private String ownerUid;
    private String status;
    private String firmwareVersion;
    private String activeCycleId;
    private boolean isOnline;
    private long lastOnline;
    private long createdAt;
    // deviceId of the Basilience Harvest Scale (BasilienceHarvestScale, a
    // standalone ESP8266 unit) paired with this device - one scale per
    // device when reproduced across multiple units, not a shared/global
    // scale. Null/absent when unpaired.
    private String harvestScaleId;

    public Device() {
        // Required for Firebase
    }

    public Device(String deviceId, String deviceName, String deviceToken, String ownerUid, String status) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.deviceToken = deviceToken;
        this.ownerUid = ownerUid;
        this.status = status;
        this.createdAt = System.currentTimeMillis();
    }

    // Getters and Setters
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public String getDeviceToken() { return deviceToken; }
    public void setDeviceToken(String deviceToken) { this.deviceToken = deviceToken; }

    public String getOwnerUid() { return ownerUid; }
    public void setOwnerUid(String ownerUid) { this.ownerUid = ownerUid; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getFirmwareVersion() { return firmwareVersion; }
    public void setFirmwareVersion(String firmwareVersion) { this.firmwareVersion = firmwareVersion; }

    public String getActiveCycleId() { return activeCycleId; }
    public void setActiveCycleId(String activeCycleId) { this.activeCycleId = activeCycleId; }

    public boolean isOnline() { return isOnline; }
    public void setOnline(boolean online) { isOnline = online; }

    public long getLastOnline() { return lastOnline; }
    public void setLastOnline(long lastOnline) { this.lastOnline = lastOnline; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public String getHarvestScaleId() { return harvestScaleId; }
    public void setHarvestScaleId(String harvestScaleId) { this.harvestScaleId = harvestScaleId; }
}
