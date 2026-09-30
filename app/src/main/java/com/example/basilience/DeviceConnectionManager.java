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

    // Single source of truth for device reachability, used by every screen.
    // It is derived ONLY from the age of the last successful device heartbeat
    // (status/lastServerSeen, written by trackDeviceHeartbeat in
    // functions/index.js with the Cloud Function's server clock):
    //   age <  30s        -> ONLINE
    //   30s <= age < 120s -> RECONNECTING
    //   age >= 120s       -> OFFLINE ("Device Unreachable")
    // status/online is deliberately NOT part of the classification: it means
    // "cloud session state", and a short Firebase/TLS outage with Wi-Fi up and
    // local automation still running must never read as unreachable.
    // UNREACHABLE_MIN_AGE_MS is kept equal to OFFLINE_TIMEOUT_MS in
    // functions/index.js so the app label and the backend's "Device
    // Unreachable" push flip at the same moment.
    public static final long ONLINE_MAX_AGE_MS = 30_000L;
    public static final long UNREACHABLE_MIN_AGE_MS = 120_000L;
    // A lastServerSeen ahead of the (offset-corrected) server clock by more than
    // this cannot be a genuine fresh heartbeat; it is treated as unverifiable
    // (RECONNECTING) rather than trusted as ONLINE forever. Anything smaller is
    // ordinary clock skew and counts as age 0.
    private static final long FUTURE_SKEW_TOLERANCE_MS = ONLINE_MAX_AGE_MS;
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
    private boolean accessRevoked;
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
    private static volatile long serverTimeOffsetMs = 0L;
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

    // The database server's current time as best this phone can tell:
    // System.currentTimeMillis() corrected by .info/serverTimeOffset. Every
    // caller of resolveState() passes this, not the raw local clock.
    public static long serverNowMs() {
        getInstance(); // makes sure the .info/serverTimeOffset listener is attached
        return System.currentTimeMillis() + serverTimeOffsetMs;
    }

    public static DeviceConnectivityState resolveState(Long lastServerSeen, long nowMs) {
        return resolveState(lastServerSeen, false, nowMs);
    }

    public static DeviceConnectivityState resolveState(Long lastServerSeen,
                                                        boolean provisioning,
                                                        long nowMs) {
        if (provisioning) return DeviceConnectivityState.RECONNECTING;

        // Missing or malformed (readLongValue() yields null for a non-number)
        // is the startup / not-yet-loaded case: the most conservative existing
        // state, never a hard "Device Unreachable".
        if (lastServerSeen == null || lastServerSeen <= 0L) {
            return DeviceConnectivityState.RECONNECTING;
        }

        long ageMs = nowMs - lastServerSeen;
        if (ageMs < 0L) {
            // Future timestamp: small skew counts as age 0, a large one is
            // unverifiable.
            return -ageMs > FUTURE_SKEW_TOLERANCE_MS
                    ? DeviceConnectivityState.RECONNECTING
                    : DeviceConnectivityState.ONLINE;
        }
        if (ageMs < ONLINE_MAX_AGE_MS) return DeviceConnectivityState.ONLINE;
        if (ageMs < UNREACHABLE_MIN_AGE_MS) return DeviceConnectivityState.RECONNECTING;
        return DeviceConnectivityState.OFFLINE;
    }

    public void monitorDevice(String deviceId) {
        // The accessRevoked check matters: once a listener is marked
        // revoked it's permanently dead (Firebase never retries a
        // PERMISSION_DENIED listener), and the current-device check below
        // would otherwise treat re-requesting the SAME device as a no-op
        // forever. Without this, unclaiming then re-claiming the same
        // device (with no other device viewed in between) left the screen
        // stuck on "Access Revoked" even though access was genuinely
        // restored - nothing ever re-established a live listener.
        if (deviceId == null || (deviceId.equals(currentDeviceId) && !accessRevoked)) {
            return;
        }

        stopMonitoring();
        currentDeviceId = deviceId;

        FirebaseDatabase db = FirebaseDatabase.getInstance(RTDB_URL);

        backendOnline = null;
        lastServerSeen = null;
        provisioning = false;
        accessRevoked = false;
        connectivityState.setValue(DeviceConnectivityState.RECONNECTING);
        onlineStatus.setValue(false);

        // Both fields are backend-owned. lastServerSeen (Cloud Function server
        // clock) alone drives the state; online is only kept for logging.
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
                if (error.getCode() == DatabaseError.PERMISSION_DENIED) {
                    // The RTDB rules denied this read outright (e.g. an Admin
                    // unclaimed this device). Firebase does not retry a
                    // permission-denied listener - onDataChange will never
                    // fire again on this ref - so without this the UI would
                    // be stuck showing "Reconnecting..." forever. Stop the
                    // periodic refresh too, since it would just keep
                    // re-deriving the same stale RECONNECTING result from
                    // data that can no longer change.
                    accessRevoked = true;
                    stateHandler.removeCallbacks(stateRefresh);
                    connectivityState.setValue(DeviceConnectivityState.ACCESS_REVOKED);
                    onlineStatus.setValue(false);
                    return;
                }
                // Any other cancellation is not evidence the device is unreachable.
                // Retain the last heartbeat timestamp; its age keeps advancing.
            }
        };
        statusRef.addValueEventListener(statusListener);
        stateHandler.removeCallbacks(stateRefresh);
        stateHandler.post(stateRefresh);
    }

    private void publishResolvedState() {
        // Once access has been revoked there is no data left to re-derive a
        // state from - and the periodic refresh runnable is stopped in
        // onCancelled anyway - but this guards against the one already-queued
        // tick that could still be in flight when that happens.
        if (accessRevoked) return;

        // System.currentTimeMillis() alone assumes this device's clock agrees
        // with the Cloud Function's server clock that authored lastServerSeen -
        // see the field comment on serverTimeOffsetMs for why that assumption
        // isn't safe to make. serverNowMs() converts local time to the
        // database server's time, the same correction Firebase's own docs
        // recommend for comparing against a ServerValue.TIMESTAMP-derived value.
        long nowMs = serverNowMs();
        DeviceConnectivityState state = resolveState(lastServerSeen, provisioning, nowMs);

        if (state != lastLoggedState
                || !java.util.Objects.equals(lastServerSeen, lastLoggedServerSeen)
                || !java.util.Objects.equals(backendOnline, lastLoggedBackendOnline)) {
            Long ageMs = lastServerSeen != null ? Math.max(0L, nowMs - lastServerSeen) : null;
            android.util.Log.d(TAG, "[CONNECTIVITY] online(hint only)=" + backendOnline
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
        accessRevoked = false;
        connectivityState.setValue(DeviceConnectivityState.RECONNECTING);
        onlineStatus.setValue(false);
        currentDeviceId = null;
    }
}
