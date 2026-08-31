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
    private static final String RTDB_URL =
            "https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app";
    private static final String TAG = "ConnectionManager";

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

    // lastServerSeen is written with the Cloud Function's server clock (see
    // trackDeviceHeartbeat in functions/index.js), specifically so presence
    // never depends on trusting firmware's local millis(). Comparing that
    // server-authored value against this device's own System.currentTimeMillis()
    // would silently reintroduce exactly the clock-trust problem that was
    // avoided on the write side - a phone/emulator with any meaningful clock
    // skew computes a bogus age and can show RECONNECTING (or mask a real
    // outage) regardless of how fresh the heartbeat actually is server-side.
    // .info/serverTimeOffset is the SDK's own answer to this - serverTimeMs =
    // System.currentTimeMillis() + serverTimeOffsetMs - and is kept live here,
    // not read once, since the offset can itself change (e.g. NTP resync).
    private volatile long serverTimeOffsetMs = 0L;
    private DatabaseReference serverTimeOffsetRef;
    private ValueEventListener serverTimeOffsetListener;

    // Avoids re-logging the same decision every STATE_REFRESH_INTERVAL_MS tick
    // - only a changed input or a changed result is worth a log line.
    private DeviceConnectivityState lastLoggedState;
    private Long lastLoggedServerSeen;
    private Boolean lastLoggedBackendOnline;

    private DeviceConnectionManager() {
        // .info/serverTimeOffset is global to the database instance, not
        // per-device, so this is attached once for the singleton's lifetime
        // rather than per monitorDevice() call.
        serverTimeOffsetRef = FirebaseDatabase.getInstance(RTDB_URL)
                .getReference(".info/serverTimeOffset");
        serverTimeOffsetListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Long offset = readLongValue(snapshot);
                serverTimeOffsetMs = offset != null ? offset : 0L;
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                android.util.Log.e(TAG, "serverTimeOffset listener cancelled: " + error.getMessage());
            }
        };
        serverTimeOffsetRef.addValueEventListener(serverTimeOffsetListener);
    }

    // Firebase Realtime Database's Android SDK only supports getValue(Class)
    // for a fixed set of concrete types (String/Boolean/Long/Double/Map/List,
    // or a POJO) - the abstract Number.class is NOT one of them and throws
    // "Deserializing values to Number is not supported" at runtime (confirmed
    // by the crash this fixes). getValue() with no class returns the raw
    // deserialized Object - a Long or a Double depending on how the value was
    // written - and instanceof/longValue() safely accepts either.
    static Long readLongValue(DataSnapshot snapshot) {
        Object raw = snapshot.getValue();

        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }

        return null;
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

        FirebaseDatabase db = FirebaseDatabase.getInstance(RTDB_URL);

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
                // getValue(Long.class) throws if RTDB happens to return this
                // value typed as Double rather than Long; readLongValue()
                // accepts either via the raw-Object path (see its own comment -
                // getValue(Number.class) is NOT valid here and was the actual
                // crash: "Deserializing values to Number is not supported").
                lastServerSeen = readLongValue(snapshot.child("lastServerSeen"));
                provisioning = Boolean.TRUE.equals(
                        snapshot.child("provisioning").getValue(Boolean.class));
                publishResolvedState();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                android.util.Log.e(TAG, "Status listener cancelled: " + error.getMessage());
                // A cancelled listener is not an authoritative status/online=false event.
                // Retain the last backend-reported presence value.
            }
        };
        statusRef.addValueEventListener(statusListener);
        stateHandler.removeCallbacks(stateRefresh);
        stateHandler.post(stateRefresh);
    }

    private void publishResolvedState() {
        // System.currentTimeMillis() alone assumes this device's clock agrees
        // with the Cloud Function's server clock that authored lastServerSeen -
        // see the field comment on serverTimeOffsetMs for why that assumption
        // isn't safe to make. + serverTimeOffsetMs converts local time to the
        // database server's time, the same correction Firebase's own docs
        // recommend for comparing against a ServerValue.TIMESTAMP-derived value.
        long nowMs = System.currentTimeMillis() + serverTimeOffsetMs;
        DeviceConnectivityState state = resolveState(
                backendOnline, lastServerSeen, provisioning, nowMs);

        if (state != lastLoggedState
                || !java.util.Objects.equals(lastServerSeen, lastLoggedServerSeen)
                || !java.util.Objects.equals(backendOnline, lastLoggedBackendOnline)) {
            Long ageMs = lastServerSeen != null ? Math.max(0L, nowMs - lastServerSeen) : null;
            android.util.Log.d(TAG, "[CONNECTIVITY] online=" + backendOnline
                    + " provisioning=" + provisioning);
            android.util.Log.d(TAG, "[CONNECTIVITY] lastServerSeen=" + lastServerSeen
                    + " now=" + nowMs + " offsetMs=" + serverTimeOffsetMs + " ageMs=" + ageMs);
            android.util.Log.d(TAG, "[CONNECTIVITY] result=" + state);
            lastLoggedState = state;
            lastLoggedServerSeen = lastServerSeen;
            lastLoggedBackendOnline = backendOnline;
        }

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
