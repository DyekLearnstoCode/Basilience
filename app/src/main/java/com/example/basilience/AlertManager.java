package com.example.basilience;

import android.util.Log;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
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

    public void startListening() {

        DatabaseReference alertsRef =
                FirebaseDatabase.getInstance(
                        "https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app"
                ).getReference("device/alerts");

        alertsRef.addValueEventListener(new ValueEventListener() {

            @Override
            public void onDataChange(DataSnapshot snapshot) {

                for (DataSnapshot child : snapshot.getChildren()) {

                    String alertName = child.getKey();

                    Boolean current =
                            child.getValue(Boolean.class);

                    if (alertName == null || current == null)
                        continue;

                    boolean previous =
                            previousStates.getOrDefault(
                                    alertName,
                                    false
                            );

                    if (!previous && current) {

                        createNotification(alertName);
                    }

                    previousStates.put(
                            alertName,
                            current
                    );
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {

                Log.e(
                        "AlertManager",
                        error.getMessage()
                );
            }
        });
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