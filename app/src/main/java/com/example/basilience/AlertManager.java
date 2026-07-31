package com.example.basilience;

import android.util.Log;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.example.basilience.NotificationHelper;

import java.util.HashMap;
import java.util.Map;

public class AlertManager {

    private final Database_Helper dbHelper;

    private final Map<String, Boolean> previousStates =
            new HashMap<>();

    private final MainActivity activity;

    public AlertManager(MainActivity activity) {
        this.activity = activity;
        dbHelper = new Database_Helper();
    }

    private ListenerRegistration notificationsListener;
    private String deviceId;

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public void startListening() {
        if (deviceId == null || deviceId.isEmpty()) {
            Log.w("AlertManager", "startListening: No deviceId provided, skipping listener.");
            return;
        }

        if (notificationsListener != null) return; // Already listening

        dbHelper.setSelectedDeviceId(deviceId);

        notificationsListener = dbHelper.listenToNotifications((snapshots, e) -> {
            if (e != null) {
                Log.e("AlertManager", "Firestore Listen failed: " + e.getMessage());
                return;
            }

            if (snapshots != null) {
                for (DocumentSnapshot doc : snapshots.getDocuments()) {
                    // Check if notification is already processed via metadata/SharedPreferences if needed
                    // For now, simple implementation of creating notification from new documents
                    String message = doc.getString("message");
                    String type = doc.getString("type");
                    Long timestamp = doc.getLong("timestamp");

                    if (timestamp != null && (System.currentTimeMillis() - timestamp < 10000)) { // Only show recent ones (last 10s)
                         activity.runOnUiThread(() ->
                            NotificationHelper.showNotification(
                                    activity,
                                    "System Alert",
                                    message
                            )
                        );
                    }
                }
            }
        });
    }

    public void stopListening() {
        if (notificationsListener != null) {
            notificationsListener.remove();
            notificationsListener = null;
        }
    }

    private void createNotification(String alertName) {

        String firestoreMessage;
        String popupMessage;

        switch (alertName) {

            case "phOutOfRange":

                firestoreMessage =
                        "pH level is outside the safe range.";

                popupMessage =
                        "⚠ pH OUT OF RANGE\n\n" +
                                "Current Action:\n" +
                                "Correcting pH using dosing pumps.";

                break;

            case "ecLow":

                firestoreMessage =
                        "EC level is below the safe range.";

                popupMessage =
                        "⚠ EC LOW\n\n" +
                                "Current Action:\n" +
                                "Dosing nutrient solution.";

                break;

            case "highTemperature":

                firestoreMessage =
                        "Temperature is above safe limits.";

                popupMessage =
                        "⚠ HIGH TEMPERATURE\n\n" +
                                "Current Action:\n" +
                                "Activating cooling fans.";

                break;

            case "lowWater":

                firestoreMessage =
                        "Water level is critically low.";

                popupMessage =
                        "⚠ LOW WATER LEVEL\n\n" +
                                "Current Action:\n" +
                                "Refilling reservoir.";

                break;

            default:

                firestoreMessage =
                        "System alert detected.";

                popupMessage =
                        "System alert detected.";
        }

        String popupTitle;

        switch (alertName) {

            case "phOutOfRange":
                popupTitle = "CRITICAL pH ALERT";
                break;

            case "ecLow":
                popupTitle = "EC WARNING";
                break;

            case "highTemperature":
                popupTitle = "TEMPERATURE WARNING";
                break;

            case "lowWater":
                popupTitle = "CRITICAL WATER LEVEL ALERT";
                break;

            default:
                popupTitle = "System Alert";
        }

        activity.runOnUiThread(() ->
                NotificationHelper.showNotification(
                        activity,
                        popupTitle,
                        popupMessage
                )
        );

        dbHelper.addNotification(
                firestoreMessage,
                "parameter"
        );
    }
}