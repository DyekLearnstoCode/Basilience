package com.example.basilience;

import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.HashMap;
import java.util.Map;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "FCMService";

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
            // Notification payloads are automatically posted by FCM only while the
            // app is backgrounded. Foreground delivery always posts exactly one tray
            // card here; only explicitly critical types also receive one popup.
            showNotification(title, body, eventId);
            if (isParameterAlert(notificationType)) {
                MainActivity.showForegroundParameterAlert(notificationType, eventId, deviceId);
            } else if ("safetyLock".equalsIgnoreCase(notificationType)
                    || "sensorFault".equalsIgnoreCase(notificationType)) {
                MainActivity.showForegroundAlert(title, body, eventId);
            }
        }
    }

    private boolean isParameterAlert(String type) {
        return "lowWater".equalsIgnoreCase(type)
                || "ecLow".equalsIgnoreCase(type)
                || "phOutOfRange".equalsIgnoreCase(type)
                || "highTemperature".equalsIgnoreCase(type)
                || "waterTempOutOfRange".equalsIgnoreCase(type);
    }

    private void showNotification(String title, String messageBody, String eventId) {
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

        int notificationId = eventId != null && !eventId.isEmpty()
                ? eventId.hashCode()
                : (title + "|" + messageBody).hashCode();
        notificationManager.notify(notificationId, notificationBuilder.build());
    }
}
