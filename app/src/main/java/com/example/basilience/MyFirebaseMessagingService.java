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

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.d(TAG, "FCM token refreshed for current installation");
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
        }
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        
        Log.d(TAG, "From: " + remoteMessage.getFrom());

        String title = "Device Alert";
        String body = "System alert received";
        String eventId = remoteMessage.getData().get("notificationId");
        String notificationType = remoteMessage.getData().get("type");
        String deviceId = remoteMessage.getData().get("deviceId");

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

            // Notification payloads are automatically posted by FCM only while the
            // app is backgrounded. Foreground delivery always posts exactly one tray
            // card here; only explicitly critical types also receive one popup.
            showNotification(title, body, eventId, null);
            if (isParameterAlert(notificationType)) {
                MainActivity.showForegroundParameterAlert(notificationType, eventId, deviceId);
            } else if ("safetyLock".equalsIgnoreCase(notificationType)
                    || "sensorFault".equalsIgnoreCase(notificationType)) {
                MainActivity.showForegroundAlert(title, body, eventId);
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
            return;
        }

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
                            return;
                        }

                        showNotification(title, body, eventId, deviceId);
                        if (!isOffline) {
                            MainActivity.showForegroundRecovery(title, body, eventId);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        // A failed presence read is not evidence that the device is offline.
                        Log.w(TAG, "Connectivity validation cancelled; notification discarded", error.toException());
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
                || "ecLow".equalsIgnoreCase(type)
                || "phLow".equalsIgnoreCase(type)
                || "phHigh".equalsIgnoreCase(type)
                || "highTemperature".equalsIgnoreCase(type)
                || "waterTempOutOfRange".equalsIgnoreCase(type);
    }

    private void showNotification(String title, String messageBody, String eventId, String connectivityDeviceId) {
        android.content.Intent intent = new android.content.Intent(this, MainActivity.class);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(this, 0, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE);

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

        int notificationId = connectivityDeviceId != null && !connectivityDeviceId.isEmpty()
                ? ("device_connectivity_" + connectivityDeviceId).hashCode()
                : eventId != null && !eventId.isEmpty()
                    ? eventId.hashCode()
                    : (title + "|" + messageBody).hashCode();
        notificationManager.notify(notificationId, notificationBuilder.build());
    }
}
