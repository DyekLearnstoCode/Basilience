package com.example.basilience;

import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

        String title = "Device Alert";
        String body = "System alert received";
        String eventId = remoteMessage.getData().get("notificationId");
        String notificationType = remoteMessage.getData().get("type");
        String deviceId = remoteMessage.getData().get("deviceId");
        Log.d(TAG, "FCM_TYPE type=" + notificationType + " deviceId=" + deviceId
                + " eventId=" + eventId);

        if (eventId != null && !eventId.isEmpty()
                && processedNotificationIds.putIfAbsent(eventId, Boolean.TRUE) != null) {
            Log.d(TAG, "DUPLICATE_EVENT_SUPPRESSED eventId=" + eventId);
            return;
        }

        if (remoteMessage.getNotification() != null) {
            title = remoteMessage.getNotification().getTitle();
            body = remoteMessage.getNotification().getBody();
        } else if (remoteMessage.getData().size() > 0) {
            if (remoteMessage.getData().containsKey("title")) {
                title = remoteMessage.getData().get("title");
            }
            if (remoteMessage.getData().containsKey("body")) {
                body = remoteMessage.getData().get("body");
            }
        }

        if (title != null && body != null) {
            if (isConnectivityType(notificationType)) {
                validateAndShowConnectivity(
                        title,
                        body,
                        eventId,
                        notificationType,
                        deviceId,
                        remoteMessage.getData());
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
                showNotification(title, body, eventId, null, deviceId);
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
            showNotification(title, body, eventId, null, deviceId);
            if (isCriticalAlert(notificationType)) {
                Log.d(TAG, "POPUP_ATTEMPT type=" + notificationType);
                boolean shown = MainActivity.showForegroundAlert(title, body, eventId);
                Log.d(TAG, "POPUP_RESULT type=" + notificationType + " accepted=" + shown);
            }
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
        FirebaseDatabase.getInstance(RTDB_URL)
                .getReference("devices")
                .child(deviceId)
                .child("status")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        Long newestEvent = latestConnectivityEventByDevice.get(deviceId);
                        // A newer delivery can invalidate an offline callback that
                        // was already waiting on RTDB. Recovery is never rejected
                        // for this reason: a later heartbeat is confirming evidence.
                        if (isOffline && newestEvent != null && newestEvent > generatedAt) {
                            Log.i(TAG, "Discarded superseded connectivity event for " + deviceId);
                            Log.i(TAG, "DROP_REASON newer_connectivity_event deviceId=" + deviceId);
                            return;
                        }

                        Boolean online = snapshot.child("online").getValue(Boolean.class);
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

                        if (!isOffline) {
                            NotificationHelper.clearWifiConfigurationRequiredNotification(
                                    MyFirebaseMessagingService.this, deviceId);
                        }

                        NotificationHelper.recordCloudConnectivityPresentation(
                                MyFirebaseMessagingService.this, deviceId, isOffline);

                        showNotification(title, body, eventId, deviceId, deviceId);
                        Log.d(TAG, "POPUP_ATTEMPT type=" + type);
                        boolean shown;
                        if (isOffline) {
                            shown = MainActivity.showForegroundAlert(title, body, eventId);
                        } else {
                            shown = MainActivity.showForegroundRecovery(title, body, eventId);
                        }
                        Log.d(TAG, "POPUP_RESULT type=" + type + " accepted=" + shown);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        // A failed presence read is not evidence that the device is offline.
                        Log.w(TAG, "Connectivity validation cancelled; notification discarded", error.toException());
                    }
                });
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

        FirebaseDatabase.getInstance(RTDB_URL)
                .getReference("devices")
                .child(deviceId)
                .child("alerts")
                .child(alertKey)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        Boolean active = snapshot.getValue(Boolean.class);
                        if (!Boolean.TRUE.equals(active)) {
                            Log.i(TAG, "Discarded stale parameter alert " + alertKey
                                    + " for " + deviceId + "; active=" + active);
                            Log.i(TAG, "DROP_REASON stale_parameter_state type=" + alertKey);
                            return;
                        }

                        Log.d(TAG, "VALIDATION_PASS type=" + alertKey + " deviceId=" + deviceId);
                        showNotification(title, body, eventId, null, deviceId);
                        Log.d(TAG, "POPUP_ATTEMPT type=" + alertKey);
                        boolean shown = MainActivity.showForegroundParameterAlert(
                                alertKey, eventId, deviceId);
                        Log.d(TAG, "POPUP_RESULT type=" + alertKey + " accepted=" + shown);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        // A failed read cannot confirm that this parameter alert is current.
                        Log.w(TAG, "Parameter alert validation cancelled; notification discarded",
                                error.toException());
                    }
                });
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
                                   String readMarkDeviceId) {
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
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                this, notificationId, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);

        String channelId = "alerts";
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

        // Since android Oreo notification channel is needed.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(channelId,
                    "System Alerts",
                    android.app.NotificationManager.IMPORTANCE_HIGH);
            notificationManager.createNotificationChannel(channel);
        }

        notificationManager.notify(notificationId, notificationBuilder.build());
        Log.d(TAG, "TRAY_POSTED notificationId=" + notificationId);
    }
}
