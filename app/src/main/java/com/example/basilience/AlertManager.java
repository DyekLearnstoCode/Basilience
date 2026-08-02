package com.example.basilience;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Listens to the RTDB /alerts and /status nodes for a selected device.
 * Uses edge detection (false → true) to avoid duplicate notifications.
 * Writes Firestore notification documents and shows in-app popups.
 */
public class AlertManager {

    private static final String TAG = "AlertManager";

    private final Database_Helper dbHelper;
    private final MainActivity activity;

    // Tracks the last known state of each alert flag to detect edges
    private final Map<String, Boolean> previousStates = new HashMap<>();

    private DatabaseReference alertsRef;
    private DatabaseReference statusRef;
    private ValueEventListener alertsListener;
    private ValueEventListener statusListener;

    private String deviceId;

    public AlertManager(MainActivity activity) {
        this.activity = activity;
        this.dbHelper = new Database_Helper();
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    // -------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------

    public void startListening() {
        if (deviceId == null || deviceId.isEmpty()) {
            Log.w(TAG, "startListening: No deviceId provided, skipping.");
            return;
        }

        if (alertsListener != null) return; // Already listening

        dbHelper.setSelectedDeviceId(deviceId);

        FirebaseDatabase rtdb = FirebaseDatabase.getInstance(
                "https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app");

        // --- Alerts node ---
        alertsRef = rtdb.getReference("devices").child(deviceId).child("alerts");
        alertsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                checkFlag(snapshot, "lowWater");
                checkFlag(snapshot, "ecLow");
                checkFlag(snapshot, "phOutOfRange");
                checkFlag(snapshot, "waterTempOutOfRange");
                checkFlag(snapshot, "highTemperature");
                checkFlag(snapshot, "sensorFault");
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Alerts listener cancelled: " + error.getMessage());
            }
        };
        alertsRef.addValueEventListener(alertsListener);

        // --- Status node (for safetyLock and reservoirLocked) ---
        statusRef = rtdb.getReference("devices").child(deviceId).child("status");
        statusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                checkFlag(snapshot, "safetyLock");
                checkFlag(snapshot, "reservoirLocked");
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Status listener cancelled: " + error.getMessage());
            }
        };
        statusRef.addValueEventListener(statusListener);
    }

    public void stopListening() {
        if (alertsRef != null && alertsListener != null) {
            alertsRef.removeEventListener(alertsListener);
            alertsListener = null;
        }
        if (statusRef != null && statusListener != null) {
            statusRef.removeEventListener(statusListener);
            statusListener = null;
        }
        previousStates.clear();
    }

    // -------------------------------------------------------
    // Edge Detection
    // -------------------------------------------------------

    /**
     * Checks if a boolean flag has transitioned from false → true.
     * Only fires a notification on that rising edge, not while it stays true.
     */
    private void checkFlag(DataSnapshot snapshot, String key) {
        Boolean current = snapshot.child(key).getValue(Boolean.class);
        if (current == null) current = false;

        Boolean previous = previousStates.getOrDefault(key, false);

        if (current && !previous) {
            // Rising edge detected — fire notification
            createNotification(key);
        }

        previousStates.put(key, current);
    }

    // -------------------------------------------------------
    // Notification Creation
    // -------------------------------------------------------

    private void createNotification(String alertKey) {
        String firestoreMessage;
        String popupTitle;
        String popupBody;
        String notificationType;

        switch (alertKey) {

            case "phOutOfRange":
                firestoreMessage = "pH level is outside the safe range.";
                popupTitle       = "CRITICAL pH ALERT";
                popupBody        = "⚠ pH OUT OF RANGE\n\nCurrent Action:\nCorrecting pH using dosing pumps.";
                notificationType = NotificationAdapter.NotificationItem.TYPE_PARAMETER;
                break;

            case "ecLow":
                firestoreMessage = "EC level is below the safe range.";
                popupTitle       = "EC WARNING";
                popupBody        = "⚠ EC LOW\n\nCurrent Action:\nDosing nutrient solution.";
                notificationType = NotificationAdapter.NotificationItem.TYPE_PARAMETER;
                break;

            case "highTemperature":
                firestoreMessage = "Air temperature is above safe limits.";
                popupTitle       = "TEMPERATURE WARNING";
                popupBody        = "⚠ HIGH TEMPERATURE\n\nCurrent Action:\nActivating cooling fans.";
                notificationType = NotificationAdapter.NotificationItem.TYPE_PARAMETER;
                break;

            case "waterTempOutOfRange":
                firestoreMessage = "Water temperature is outside the safe range.";
                popupTitle       = "WATER TEMP WARNING";
                popupBody        = "⚠ WATER TEMP OUT OF RANGE\n\nCurrent Action:\nActivating reservoir cooling.";
                notificationType = NotificationAdapter.NotificationItem.TYPE_PARAMETER;
                break;

            case "lowWater":
                firestoreMessage = "Water level is critically low.";
                popupTitle       = "CRITICAL WATER LEVEL ALERT";
                popupBody        = "⚠ LOW WATER LEVEL\n\nCurrent Action:\nRefilling reservoir.";
                notificationType = NotificationAdapter.NotificationItem.TYPE_PARAMETER;
                break;

            case "sensorFault":
                firestoreMessage = "One or more sensors are not responding.";
                popupTitle       = "SENSOR FAULT";
                popupBody        = "⚠ SENSOR FAULT DETECTED\n\nAutomation has been paused.\nCheck sensor connections.";
                notificationType = NotificationAdapter.NotificationItem.TYPE_HARDWARE;
                break;

            case "safetyLock":
                firestoreMessage = "Safety lock has been activated. All actuators stopped.";
                popupTitle       = "SAFETY LOCK ACTIVE";
                popupBody        = "🔒 SAFETY LOCK ACTIVATED\n\nAll actuators have been stopped.\nRestart the device to reset.";
                notificationType = NotificationAdapter.NotificationItem.TYPE_HARDWARE;
                break;

            case "reservoirLocked":
                firestoreMessage = "Reservoir is locked due to an active dosing operation or safety event.";
                popupTitle       = "RESERVOIR LOCKED";
                popupBody        = "🔒 RESERVOIR LOCKED\n\nAn automated dosing or safety event is active.";
                notificationType = NotificationAdapter.NotificationItem.TYPE_HARDWARE;
                break;

            default:
                firestoreMessage = "A system alert was detected.";
                popupTitle       = "System Alert";
                popupBody        = "A system alert was detected. Check device status.";
                notificationType = NotificationAdapter.NotificationItem.TYPE_INFO;
                break;
        }

        // Write persistent notification to Firestore (Handled by Cloud Function to prevent duplicates)
        // dbHelper.addNotification(firestoreMessage, notificationType);

        // Show in-app popup (on UI thread)
        final String finalTitle = popupTitle;
        final String finalBody = popupBody;
        final String finalType = notificationType;
        
        activity.runOnUiThread(() -> {
            if (NotificationAdapter.NotificationItem.TYPE_PARAMETER.equals(finalType)) {
                // Parameter alerts are typically warnings (orange) as requested
                NotificationHelper.showWarning(activity, finalTitle, finalBody);
            } else if (NotificationAdapter.NotificationItem.TYPE_HARDWARE.equals(finalType)) {
                // Hardware issues are also shown as warnings/alerts
                NotificationHelper.showWarning(activity, finalTitle, finalBody);
            } else {
                NotificationHelper.showNotification(activity, finalTitle, finalBody);
            }
        });
    }
}