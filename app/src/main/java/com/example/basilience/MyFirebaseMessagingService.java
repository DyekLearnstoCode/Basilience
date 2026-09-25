package com.example.basilience;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "FCMService";
    private static final String RTDB_URL = "https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app";
    private static final Map<String, Long> latestConnectivityEventByDevice = new ConcurrentHashMap<>();

    // Single authoritative dedupe gate for every notification type (parameter
    // alerts, automation lifecycle START/SUCCESS, connectivity, critical
    // failures, harvest, etc.). FCM does not guarantee exactly-once delivery,
    // and each downstream path (connectivity/parameter validation in
    // particular) does its own async RTDB round-trip before showing a popup;
    // without an early, synchronous claim here, two deliveries of the same
    // backend event can both reach a popup call before either one's
    // path-local dedup check has recorded it. Keyed by the backend's own
    // stable "notificationId", never by title/message text.
    private static final Map<String, Boolean> processedNotificationIds = new ConcurrentHashMap<>();

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.d(TAG, "FCM_TOKEN_REFRESH uidAvailable="
                + (FirebaseAuth.getInstance().getUid() != null));
        sendRegistrationToServer(token);
    }

    private void sendRegistrationToServer(String token) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid != null) {
            Map<String, Object> update = new HashMap<>();
            update.put("fcmToken", token);

            FirebaseFirestore.getInstance().collection("users")
                    .document(uid)
                    .set(update, com.google.firebase.firestore.SetOptions.merge())
                    .addOnSuccessListener(aVoid -> Log.d(TAG, "FCM Token updated successfully."))
                    .addOnFailureListener(e -> Log.e(TAG, "Failed to update FCM token", e));
        } else {
            Log.w(TAG, "FCM_TOKEN_SAVE_SKIPPED no_authenticated_user");
        }
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        Log.d(TAG, "FCM_RECEIVED from=" + remoteMessage.getFrom());

        Map<String, String> data = remoteMessage.getData();
        String eventId = data.get("notificationId");
        String notificationType = data.get("type");
        String deviceId = data.get("deviceId");
        Log.d(TAG, "FCM_TYPE type=" + notificationType + " deviceId=" + deviceId
                + " eventId=" + eventId);

        // Title/body come from the notification payload, then the data map,
        // then wording derived from the type; a missing field no longer
        // drops the message. Only a message with nothing to show or route
        // on is dropped, and it is dropped before its id is claimed below so
        // a later, complete delivery of the same event is not suppressed.
        RemoteMessage.Notification payload = remoteMessage.getNotification();
        NotificationContent content = NotificationContent.resolve(
                payload != null ? payload.getTitle() : null,
                payload != null ? payload.getBody() : null,
                data);
        if (content == null) {
            Log.w(TAG, "DROP_REASON empty_payload from=" + remoteMessage.getFrom());
            return;
        }
        final String title = content.title;
        final String body = content.body;

        if (eventId != null && !eventId.isEmpty()
                && processedNotificationIds.putIfAbsent(eventId, Boolean.TRUE) != null) {
            Log.d(TAG, "DUPLICATE_EVENT_SUPPRESSED eventId=" + eventId);
            return;
        }

        if (isConnectivityType(notificationType)) {
            validateAndShowConnectivity(
                    title,
                    body,
                    eventId,
                    notificationType,
                    deviceId,
                    data);
            return;
        }

        if (isParameterAlert(notificationType)) {
            validateAndShowParameterAlert(
                    title,
                    body,
                    eventId,
                    notificationType,
                    deviceId);
            return;
        }

        if (isAutomationLifecycleEvent(notificationType)) {
            showNotification(title, body, eventId, null, deviceId, false);
            Log.d(TAG, "POPUP_ATTEMPT type=" + notificationType);
            boolean shown = MainActivity.showForegroundAutomationLifecycle(
                    title,
                    body,
                    eventId,
                    automationLifecycleKind(notificationType));
            Log.d(TAG, "POPUP_RESULT type=" + notificationType + " accepted=" + shown);
            return;
        }

        // Notification payloads are automatically posted by FCM only while the
        // app is backgrounded. Foreground delivery always posts exactly one tray
        // card here; only explicitly critical types also receive one popup.
        showNotification(title, body, eventId, null, deviceId, false);
        if (isCriticalAlert(notificationType)) {
            Log.d(TAG, "POPUP_ATTEMPT type=" + notificationType);
            boolean shown = MainActivity.showForegroundAlert(title, body, eventId);
            Log.d(TAG, "POPUP_RESULT type=" + notificationType + " accepted=" + shown);
        }
    }

    private boolean isConnectivityType(String type) {
        return "OFFLINE_ALERT".equalsIgnoreCase(type)
                || "ONLINE_RECOVERY".equalsIgnoreCase(type);
    }

    private void validateAndShowConnectivity(
            String title,
            String body,
            String eventId,
            String type,
            String deviceId,
            Map<String, String> data) {
        final long generatedAt = parseLong(data.get("generatedAt"));
        final long eventLastServerSeen = parseLong(data.get("lastServerSeen"));
        final String presenceState = data.get("presenceState");
        final boolean isOffline = "OFFLINE_ALERT".equalsIgnoreCase(type);

        if (deviceId == null || deviceId.isEmpty()
                || generatedAt <= 0 || eventLastServerSeen <= 0
                || (isOffline && !"offline".equalsIgnoreCase(presenceState))
                || (!isOffline && !"online".equalsIgnoreCase(presenceState))) {
            Log.w(TAG, "Discarded connectivity message with incomplete metadata");
            Log.w(TAG, "DROP_REASON incomplete_connectivity_metadata type=" + type);
            return;
        }

        String selectedDeviceId = getSharedPreferences("basilience_prefs", MODE_PRIVATE)
                .getString("selected_device_id", null);
        if (!deviceId.equals(selectedDeviceId)) {
            Log.i(TAG, "Discarded connectivity message for non-selected device " + deviceId);
            Log.i(TAG, "DROP_REASON selected_device_mismatch eventDevice=" + deviceId
                    + " selectedDevice=" + selectedDeviceId);
            return;
        }
        Log.d(TAG, "DEVICE_MATCH deviceId=" + deviceId);

        Long previousEvent = latestConnectivityEventByDevice.get(deviceId);
        if (previousEvent == null || generatedAt > previousEvent) {
            latestConnectivityEventByDevice.put(deviceId, generatedAt);
        }
        readOnceWithTimeout(
                FirebaseDatabase.getInstance(RTDB_URL)
                        .getReference("devices")
                        .child(deviceId)
                        .child("status"),
                new BoundedRead() {
                    @Override
                    public void onValue(@NonNull DataSnapshot snapshot) {
                        Long newestEvent = latestConnectivityEventByDevice.get(deviceId);
                        // A newer delivery can invalidate an offline callback that
                        // was already waiting on RTDB. Recovery is never rejected
                        // for this reason: a later heartbeat is confirming evidence.
                        if (isOffline && newestEvent != null && newestEvent > generatedAt) {
                            Log.i(TAG, "Discarded superseded connectivity event for " + deviceId);
                            Log.i(TAG, "DROP_REASON newer_connectivity_event deviceId=" + deviceId);
                            return;
                        }

                        // A wrong-typed field reads as null (never throws inside this callback).
                        Boolean online = FirebaseSafeRead.bool(snapshot.child("online"));
                        long currentLastServerSeen = numericValue(snapshot.child("lastServerSeen").getValue());
                        boolean valid = isOffline
                                ? Boolean.FALSE.equals(online)
                                    && currentLastServerSeen > 0
                                    && currentLastServerSeen <= eventLastServerSeen
                                : Boolean.TRUE.equals(online)
                                    && currentLastServerSeen >= eventLastServerSeen;

                        if (!valid) {
                            Log.i(TAG, "Discarded stale " + type + " for " + deviceId
                                    + "; online=" + online
                                    + ", currentLastServerSeen=" + currentLastServerSeen
                                    + ", eventLastServerSeen=" + eventLastServerSeen);
                            Log.i(TAG, "DROP_REASON stale_connectivity_state type=" + type);
                            return;
                        }
                        Log.d(TAG, "VALIDATION_PASS type=" + type + " deviceId=" + deviceId);
                        presentConnectivity(title, body, eventId, type, deviceId, isOffline);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        // A failed presence read is not evidence that the device is offline.
                        Log.w(TAG, "Connectivity validation cancelled; notification discarded", error.toException());
                    }

                    @Override
                    public void onTimeout() {
                        // The read never answered (typically the phone's RTDB link is
                        // down while FCM still works). The event already passed the
                        // metadata, selected-device and dedupe checks above, so show
                        // it rather than delay or lose it; only a newer event that
                        // this process already knows about can still supersede it.
                        Long newestEvent = latestConnectivityEventByDevice.get(deviceId);
                        if (isOffline && newestEvent != null && newestEvent > generatedAt) {
                            Log.i(TAG, "DROP_REASON newer_connectivity_event deviceId=" + deviceId);
                            return;
                        }
                        Log.w(TAG, "VALIDATION_TIMEOUT type=" + type + " deviceId=" + deviceId
                                + "; showing unverified");
                        presentConnectivity(title, body, eventId, type, deviceId, isOffline);
                    }
                });
    }

    private void presentConnectivity(String title, String body, String eventId, String type,
                                     String deviceId, boolean isOffline) {
        if (!isOffline) {
            NotificationHelper.clearWifiConfigurationRequiredNotification(
                    MyFirebaseMessagingService.this, deviceId);
        }

        NotificationHelper.recordCloudConnectivityPresentation(
                MyFirebaseMessagingService.this, deviceId, isOffline);

        // isOffline only, not recovery - tapping "device came back
        // online" has nothing to reconfigure. deviceId here is
        // guaranteed to already be the selected device: the
        // mismatch check above already discarded this
        // message otherwise, so there is no risk of routing to the
        // wrong device's Wi-Fi Configuration screen.
        showNotification(title, body, eventId, deviceId, deviceId, isOffline);
        Log.d(TAG, "POPUP_ATTEMPT type=" + type);
        boolean shown;
        if (isOffline) {
            shown = MainActivity.showForegroundAlert(title, body, eventId);
        } else {
            shown = MainActivity.showForegroundRecovery(title, body, eventId);
        }
        Log.d(TAG, "POPUP_RESULT type=" + type + " accepted=" + shown);
    }

    private void validateAndShowParameterAlert(
            String title,
            String body,
            String eventId,
            String alertKey,
            String deviceId) {
        if (deviceId == null || deviceId.isEmpty()
                || alertKey == null || alertKey.isEmpty()) {
            Log.w(TAG, "Discarded parameter alert with incomplete metadata");
            Log.w(TAG, "DROP_REASON incomplete_parameter_metadata type=" + alertKey);
            return;
        }

        // Same selected-device guard validateAndShowConnectivity() already
        // applies (see its own comment above). Without this, a popup for a
        // DIFFERENT device than the one currently on screen could be tapped
        // while Parameters_Monitoring_Fragment was already showing another
        // device - MainActivity.openParametersFromAlert() only reloads that
        // screen when navigating to a NEW destination, so tapping "View" for
        // an off-screen device's alert while already on Parameters left the
        // previous device's live listeners running and any command sent
        // from that screen still targeted the previous device, not the one
        // the alert/notification named. See the task report's cross-device
        // audit for the full repro.
        String selectedDeviceId = getSharedPreferences("basilience_prefs", MODE_PRIVATE)
                .getString("selected_device_id", null);
        if (!deviceId.equals(selectedDeviceId)) {
            Log.i(TAG, "Discarded parameter alert for non-selected device " + deviceId);
            Log.i(TAG, "DROP_REASON selected_device_mismatch eventDevice=" + deviceId
                    + " selectedDevice=" + selectedDeviceId);
            return;
        }

        readOnceWithTimeout(
                FirebaseDatabase.getInstance(RTDB_URL)
                        .getReference("devices")
                        .child(deviceId)
                        .child("alerts")
                        .child(alertKey),
                new BoundedRead() {
                    @Override
                    public void onValue(@NonNull DataSnapshot snapshot) {
                        // A wrong-typed flag reads as null (never throws inside this callback).
                        Boolean active = FirebaseSafeRead.bool(snapshot);
                        if (!Boolean.TRUE.equals(active)) {
                            Log.i(TAG, "Discarded stale parameter alert " + alertKey
                                    + " for " + deviceId + "; active=" + active);
                            Log.i(TAG, "DROP_REASON stale_parameter_state type=" + alertKey);
                            return;
                        }

                        Log.d(TAG, "VALIDATION_PASS type=" + alertKey + " deviceId=" + deviceId);
                        presentParameterAlert(title, body, eventId, alertKey, deviceId);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        // A failed read cannot confirm that this parameter alert is current.
                        Log.w(TAG, "Parameter alert validation cancelled; notification discarded",
                                error.toException());
                    }

                    @Override
                    public void onTimeout() {
                        // Unanswered read: a parameter out of range is exactly the
                        // kind of alert that must not be held back or lost.
                        Log.w(TAG, "VALIDATION_TIMEOUT type=" + alertKey + " deviceId=" + deviceId
                                + "; showing unverified");
                        presentParameterAlert(title, body, eventId, alertKey, deviceId);
                    }
                });
    }

    private void presentParameterAlert(String title, String body, String eventId,
                                       String alertKey, String deviceId) {
        showNotification(title, body, eventId, null, deviceId, false);
        Log.d(TAG, "POPUP_ATTEMPT type=" + alertKey);
        boolean shown = MainActivity.showForegroundParameterAlert(
                alertKey, eventId, deviceId);
        Log.d(TAG, "POPUP_RESULT type=" + alertKey + " accepted=" + shown);
    }

    /** How long a validation read may take before the notification is shown unverified. */
    private static final long VALIDATION_READ_TIMEOUT_MS = 8000L;

    /** Callbacks of readOnceWithTimeout(); exactly one of the three is invoked, on the main thread. */
    private interface BoundedRead {
        void onValue(@NonNull DataSnapshot snapshot);
        void onCancelled(@NonNull DatabaseError error);
        void onTimeout();
    }

    /**
     * A single-value read that cannot wait forever. addListenerForSingleValueEvent
     * on its own never calls back while the database connection is down, which
     * left the alert it was validating waiting indefinitely.
     */
    private void readOnceWithTimeout(DatabaseReference reference, BoundedRead callback) {
        // One shared one-shot guard: whichever of value / cancelled / timeout
        // claims it first is the only one that ever runs the callback.
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable[] timeout = new Runnable[1];
        final ValueEventListener listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!finished.compareAndSet(false, true)) return;
                handler.removeCallbacks(timeout[0]);
                callback.onValue(snapshot);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (!finished.compareAndSet(false, true)) return;
                handler.removeCallbacks(timeout[0]);
                callback.onCancelled(error);
            }
        };
        timeout[0] = () -> {
            if (!finished.compareAndSet(false, true)) return;
            reference.removeEventListener(listener);
            callback.onTimeout();
        };
        handler.postDelayed(timeout[0], VALIDATION_READ_TIMEOUT_MS);
        reference.addListenerForSingleValueEvent(listener);
    }

    private long parseLong(String value) {
        if (value == null) return -1L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private long numericValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : -1L;
    }

    private boolean isParameterAlert(String type) {
        return "lowWater".equalsIgnoreCase(type)
                || "criticalLowWater".equalsIgnoreCase(type)
                || "ecLow".equalsIgnoreCase(type)
                || "ecHigh".equalsIgnoreCase(type)
                || "phLow".equalsIgnoreCase(type)
                || "phHigh".equalsIgnoreCase(type)
                || "lowAirTemperature".equalsIgnoreCase(type)
                || "highTemperature".equalsIgnoreCase(type)
                || "waterTempOutOfRange".equalsIgnoreCase(type)
                || "waterTempLow".equalsIgnoreCase(type)
                || "humidityLow".equalsIgnoreCase(type)
                || "humidityHigh".equalsIgnoreCase(type)
                || "waterLevelLow".equalsIgnoreCase(type)
                || "waterLevelHigh".equalsIgnoreCase(type);
    }

    private boolean isCriticalAlert(String type) {
        return "safetyLock".equalsIgnoreCase(type)
                || "sensorFault".equalsIgnoreCase(type)
                || "phSubsystemLocked".equalsIgnoreCase(type)
                || "ecSubsystemLocked".equalsIgnoreCase(type)
                || "refillSubsystemLocked".equalsIgnoreCase(type)
                || "coolingSubsystemLocked".equalsIgnoreCase(type);
    }

    private boolean isAutomationLifecycleEvent(String type) {
        return "phHighCorrectionStarted".equalsIgnoreCase(type)
                || "phLowCorrectionStarted".equalsIgnoreCase(type)
                || "phCorrectionCompleted".equalsIgnoreCase(type)
                || "ecLowCorrectionStarted".equalsIgnoreCase(type)
                || "ecHighCorrectionStarted".equalsIgnoreCase(type)
                || "ecCorrectionCompleted".equalsIgnoreCase(type)
                || "refillStarted".equalsIgnoreCase(type)
                || "refillCompleted".equalsIgnoreCase(type)
                || "waterTemperatureCorrectionCompleted".equalsIgnoreCase(type);
    }

    private String automationLifecycleKind(String type) {
        return "phCorrectionCompleted".equalsIgnoreCase(type)
                || "ecCorrectionCompleted".equalsIgnoreCase(type)
                || "refillCompleted".equalsIgnoreCase(type)
                || "waterTemperatureCorrectionCompleted".equalsIgnoreCase(type)
                ? "SUCCESS"
                : "START";
    }

    private void showNotification(String title, String messageBody, String eventId, String connectivityDeviceId,
                                   String readMarkDeviceId, boolean openWifiConfigurationOnTap) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                && androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Notification permission denied; tray notification skipped");
            Log.w(TAG, "DROP_REASON tray_permission_denied");
            return;
        }

        Log.d(TAG, "TRAY_ATTEMPT eventId=" + eventId
                + " connectivityDeviceId=" + connectivityDeviceId);

        // Computed here (not just below, where the original code only used it
        // for notify()) and reused as the PendingIntent's own request code.
        // Every prior notification shared request code 0, which made every
        // FLAG_IMMUTABLE PendingIntent "the same" to Android regardless of
        // this Intent's own extras - tapping any tray entry reopened
        // whichever Intent happened to be built first. That already meant
        // taps could silently launch a stale destination; it would also have
        // made the new mark-read extras below apply to the wrong
        // notification (or never update past the first one shown).
        int notificationId = connectivityDeviceId != null && !connectivityDeviceId.isEmpty()
                ? ("device_connectivity_" + connectivityDeviceId).hashCode()
                : eventId != null && !eventId.isEmpty()
                    ? eventId.hashCode()
                    : (title + "|" + messageBody).hashCode();

        android.content.Intent intent = new android.content.Intent(this, MainActivity.class);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // Kept separate from connectivityDeviceId (which only drives the
        // notificationId collapsing scheme above) so marking a tapped
        // notification read never changes which tray entries collapse into
        // which - see MainActivity.markNotificationReadIfRequested().
        if (readMarkDeviceId != null && !readMarkDeviceId.isEmpty()
                && eventId != null && !eventId.isEmpty()) {
            intent.putExtra(MainActivity.EXTRA_MARK_READ_DEVICE_ID, readMarkDeviceId);
            intent.putExtra(MainActivity.EXTRA_MARK_READ_NOTIFICATION_ID, eventId);
        }
        // Consumed by MainActivity.openWifiConfigurationIfRequested() - lets
        // tapping the actual "DEVICE UNREACHABLE" tray notification route
        // straight to Wi-Fi Configuration for the affected device, the same
        // workflow NotificationHelper.showWifiConfigurationRequiredNotification()'s
        // separate, foreground-only notification already offers.
        if (openWifiConfigurationOnTap) {
            intent.putExtra(MainActivity.EXTRA_OPEN_WIFI_CONFIGURATION, true);
        }
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                this, notificationId, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);

        String channelId = NotificationChannels.ALERTS;
        android.net.Uri defaultSoundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION);
        androidx.core.app.NotificationCompat.Builder notificationBuilder =
                new androidx.core.app.NotificationCompat.Builder(this, channelId)
                        .setSmallIcon(R.drawable.basilience_logo)
                        .setContentTitle(title)
                        .setContentText(messageBody)
                        .setAutoCancel(true)
                        .setSound(defaultSoundUri)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setContentIntent(pendingIntent);

        android.app.NotificationManager notificationManager =
                (android.app.NotificationManager) getSystemService(android.content.Context.NOTIFICATION_SERVICE);

        // The channel already exists: MyApp creates it at startup (NotificationChannels).
        notificationManager.notify(notificationId, notificationBuilder.build());
        Log.d(TAG, "TRAY_POSTED notificationId=" + notificationId);
    }
}
