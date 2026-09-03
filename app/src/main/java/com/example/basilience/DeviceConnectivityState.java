package com.example.basilience;

import androidx.annotation.ColorRes;

public enum DeviceConnectivityState {
    ONLINE("Online", R.color.device_status_online),
    WIFI_CONFIGURATION_REQUIRED("Wi-Fi Configuration Required", R.color.device_status_reconnecting),
    RECONNECTING("Reconnecting...", R.color.device_status_reconnecting),
    OFFLINE("Device Unreachable", R.color.device_status_offline),
    // Distinct from RECONNECTING on purpose: this fires when the RTDB status
    // read comes back permission-denied (e.g. an Admin unclaimed this device),
    // not from a transient network gap. Firebase never retries a listener
    // that was cancelled for permission reasons, so without a dedicated
    // terminal state the UI would be stuck showing "Reconnecting..." forever
    // with no further update ever arriving.
    ACCESS_REVOKED("Access Revoked", R.color.device_status_offline);

    private final String label;
    private final int colorRes;

    DeviceConnectivityState(String label, @ColorRes int colorRes) {
        this.label = label;
        this.colorRes = colorRes;
    }

    public String getLabel() {
        return label;
    }

    @ColorRes
    public int getColorRes() {
        return colorRes;
    }
}
