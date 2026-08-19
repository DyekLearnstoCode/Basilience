package com.example.basilience;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class DeviceConnectionManager {

    private static DeviceConnectionManager instance;
    private final MutableLiveData<Boolean> onlineStatus = new MutableLiveData<>(false);
    private final MutableLiveData<DeviceConnectivityState> connectivityState =
            new MutableLiveData<>(DeviceConnectivityState.RECONNECTING);

    public static final long HEARTBEAT_INTERVAL_MS = 10_000L;
    public static final long FRESH_HEARTBEAT_MAX_AGE_MS = HEARTBEAT_INTERVAL_MS * 2L;
    public static final long OFFLINE_TIMEOUT_MS = 40_000L;
    private static final long STATE_REFRESH_INTERVAL_MS = 2_000L;
    
    private String currentDeviceId;
    private DatabaseReference statusRef;
    private ValueEventListener statusListener;
    private Boolean backendOnline;
    private Long lastServerSeen;
    private boolean provisioning;
    private final Handler stateHandler = new Handler(Looper.getMainLooper());
    private final Runnable stateRefresh = new Runnable() {
        @Override
        public void run() {
            publishResolvedState();
            stateHandler.postDelayed(this, STATE_REFRESH_INTERVAL_MS);
        }
    };

    private DeviceConnectionManager() {
        // Singleton pattern
    }

    public static synchronized DeviceConnectionManager getInstance() {
        if (instance == null) {
            instance = new DeviceConnectionManager();
        }
        return instance;
    }

    public LiveData<Boolean> getOnlineStatus() {
        return onlineStatus;
    }

    public LiveData<DeviceConnectivityState> getConnectivityState() {
        return connectivityState;
    }

    public static DeviceConnectivityState resolveState(Boolean backendOnline,
                                                        Long lastServerSeen,
                                                        long nowMs) {
        return resolveState(backendOnline, lastServerSeen, false, nowMs);
    }

    public static DeviceConnectivityState resolveState(Boolean backendOnline,
                                                        Long lastServerSeen,
                                                        boolean provisioning,
                                                        long nowMs) {
        if (provisioning) return DeviceConnectivityState.RECONNECTING;
        if (Boolean.FALSE.equals(backendOnline)) return DeviceConnectivityState.OFFLINE;
        if (!Boolean.TRUE.equals(backendOnline) || lastServerSeen == null) {
            return DeviceConnectivityState.RECONNECTING;
        }

        long ageMs = Math.max(0L, nowMs - lastServerSeen);
        if (ageMs >= OFFLINE_TIMEOUT_MS) return DeviceConnectivityState.RECONNECTING;
        if (ageMs > FRESH_HEARTBEAT_MAX_AGE_MS) return DeviceConnectivityState.RECONNECTING;
        return DeviceConnectivityState.ONLINE;
    }

    public void monitorDevice(String deviceId) {
        if (deviceId == null || deviceId.equals(currentDeviceId)) {
            return;
        }

        stopMonitoring();
        currentDeviceId = deviceId;
        
        FirebaseDatabase db = FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app");

        backendOnline = null;
        lastServerSeen = null;
        provisioning = false;
        connectivityState.setValue(DeviceConnectivityState.RECONNECTING);
        onlineStatus.setValue(false);

        // Both fields are backend-owned: online is the confirmed classification and
        // lastServerSeen is written with the Cloud Function server clock.
        statusRef = db.getReference("devices").child(deviceId).child("status");
        statusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                backendOnline = snapshot.child("online").getValue(Boolean.class);
                lastServerSeen = snapshot.child("lastServerSeen").getValue(Long.class);
                provisioning = Boolean.TRUE.equals(
                        snapshot.child("provisioning").getValue(Boolean.class));
                publishResolvedState();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                android.util.Log.e("ConnectionManager", "Status listener cancelled: " + error.getMessage());
                // A cancelled listener is not an authoritative status/online=false event.
                // Retain the last backend-reported presence value.
            }
        };
        statusRef.addValueEventListener(statusListener);
        stateHandler.removeCallbacks(stateRefresh);
        stateHandler.post(stateRefresh);
    }

    private void publishResolvedState() {
        DeviceConnectivityState state = resolveState(
                backendOnline, lastServerSeen, provisioning, System.currentTimeMillis());
        if (connectivityState.getValue() != state) connectivityState.setValue(state);
        boolean online = state == DeviceConnectivityState.ONLINE;
        if (!Boolean.valueOf(online).equals(onlineStatus.getValue())) onlineStatus.setValue(online);
    }

    public void stopMonitoring() {
        if (statusRef != null && statusListener != null) {
            statusRef.removeEventListener(statusListener);
        }
        stateHandler.removeCallbacks(stateRefresh);
        statusRef = null;
        statusListener = null;
        backendOnline = null;
        lastServerSeen = null;
        provisioning = false;
        connectivityState.setValue(DeviceConnectivityState.RECONNECTING);
        onlineStatus.setValue(false);
        currentDeviceId = null;
    }
}
