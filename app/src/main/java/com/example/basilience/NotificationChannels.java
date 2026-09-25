package com.example.basilience;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * Every notification channel the app posts to, created once at startup from
 * MyApp instead of lazily on first use.
 *
 * Creating "alerts" only when a foreground message arrived meant a message
 * that the FCM SDK posts itself while the app is in the background had no
 * such channel to land in and went to Firebase's generic "Miscellaneous"
 * fallback channel. The manifest's default_notification_channel_id points
 * those messages at ALERTS, which therefore has to exist before any message
 * can arrive. Creating an existing channel again is a no-op, and the user's
 * own per-channel settings are never overwritten.
 */
final class NotificationChannels {

    /** Tray alerts for device events. Also the FCM default channel (see the manifest). */
    static final String ALERTS = "alerts";
    /** The "Wi-Fi Configuration Required" reminder. */
    static final String WIFI_CONFIGURATION = "wifi_configuration";

    private NotificationChannels() {}

    static void ensureCreated(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(
                ALERTS, "System Alerts", NotificationManager.IMPORTANCE_HIGH));
        manager.createNotificationChannel(new NotificationChannel(
                WIFI_CONFIGURATION, "Wi-Fi Configuration", NotificationManager.IMPORTANCE_DEFAULT));
    }
}
