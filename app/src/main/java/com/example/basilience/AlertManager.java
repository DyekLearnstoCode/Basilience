package com.example.basilience;

import android.util.Log;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

public class AlertManager {

    private final Database_Helper dbHelper;

    private final Map<String, Boolean> previousStates =
            new HashMap<>();

    public AlertManager() {
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

    private void createNotification(
            String alertName
    ) {

        String message;

        switch (alertName) {

            case "phOutOfRange":
                message =
                        "pH level is outside the safe range.";
                break;

            case "ecLow":
                message =
                        "EC level is below the safe range.";
                break;

            case "highTemperature":
                message =
                        "Temperature is above safe limits.";
                break;

            case "lowWater":
                message =
                        "Water level is critically low.";
                break;

            default:
                message =
                        "System alert detected.";
        }

        dbHelper.addNotification(
                message,
                "parameter"
        );
    }
}