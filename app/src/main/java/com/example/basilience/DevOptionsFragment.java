package com.example.basilience;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;
import com.google.firebase.firestore.DocumentReference;

public class DevOptionsFragment extends Fragment {
    
    private DatabaseReference mockSensorsRef;
    private DatabaseReference settingsRef;
    
    private SwitchCompat switchMockEnable;
    private EditText etPh, etEc, etTemp, etHumidity, etWaterLevel;
    private EditText etMinWaterLevel, etMaxWaterLevel;
    private Button btnPush, btnPushSettings;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dev_options, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        NavController navController = Navigation.findNavController(view);
        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }

        switchMockEnable = view.findViewById(R.id.switchMockEnable);
        etPh = view.findViewById(R.id.etPh);
        etEc = view.findViewById(R.id.etEc);
        etTemp = view.findViewById(R.id.etTemp);
        etHumidity = view.findViewById(R.id.etHumidity);
        etWaterLevel = view.findViewById(R.id.etWaterLevel);
        etMinWaterLevel = view.findViewById(R.id.etMinWaterLevel);
        etMaxWaterLevel = view.findViewById(R.id.etMaxWaterLevel);
        btnPush = view.findViewById(R.id.btnPush);
        btnPushSettings = view.findViewById(R.id.btnPushSettings);
        Button btnInjectLogs = view.findViewById(R.id.btnInjectLogs);

        Database_Helper helper = new Database_Helper();
        String currentDeviceId = helper.getSelectedDeviceId();
        if (currentDeviceId == null && getContext() != null) {
            android.content.SharedPreferences prefs = getContext().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
            currentDeviceId = prefs.getString("selected_device_id", null);
        }

        if (currentDeviceId == null) {
            Toast.makeText(getContext(), "No device selected", Toast.LENGTH_SHORT).show();
            return;
        }

        String rtdbUrl = "https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app";
        mockSensorsRef = FirebaseDatabase.getInstance(rtdbUrl).getReference("devices/" + currentDeviceId + "/commands/mockSensors");
        settingsRef = FirebaseDatabase.getInstance(rtdbUrl).getReference("devices/" + currentDeviceId + "/settings");

        loadCurrentValues();

        btnPush.setOnClickListener(v -> pushMockValues());
        btnPushSettings.setOnClickListener(v -> pushSettings());
        
        final String fDeviceId = currentDeviceId;
        btnInjectLogs.setOnClickListener(v -> injectMockFirestoreLogs(fDeviceId));
    }

    private void loadCurrentValues() {
        mockSensorsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    Boolean enabled = snapshot.child("enabled").getValue(Boolean.class);
                    if (enabled != null) switchMockEnable.setChecked(enabled);
                    
                    Double ph = snapshot.child("ph").getValue(Double.class);
                    if (ph != null) etPh.setText(String.valueOf(ph));

                    Double ec = snapshot.child("ec").getValue(Double.class);
                    if (ec != null) etEc.setText(String.valueOf(ec));

                    Double temp = snapshot.child("airTemperature").getValue(Double.class);
                    if (temp != null) etTemp.setText(String.valueOf(temp));

                    Double humidity = snapshot.child("humidity").getValue(Double.class);
                    if (humidity != null) etHumidity.setText(String.valueOf(humidity));

                    Double waterLevel = snapshot.child("waterLevel").getValue(Double.class);
                    if (waterLevel != null) etWaterLevel.setText(String.valueOf(waterLevel));
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (getContext() != null) {
                    Toast.makeText(getContext(), "Failed to load mock data", Toast.LENGTH_SHORT).show();
                }
            }
        });
        
        settingsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    Double minLevel = snapshot.child("refillStartLevel").getValue(Double.class);
                    if (minLevel != null) etMinWaterLevel.setText(String.valueOf(minLevel));

                    Double maxLevel = snapshot.child("refillStopLevel").getValue(Double.class);
                    if (maxLevel != null) etMaxWaterLevel.setText(String.valueOf(maxLevel));
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (getContext() != null) {
                    Toast.makeText(getContext(), "Failed to load settings data", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void pushMockValues() {
        Map<String, Object> updates = new HashMap<>();
        updates.put("enabled", switchMockEnable.isChecked());
        
        try {
            if (!etPh.getText().toString().isEmpty()) updates.put("ph", Double.parseDouble(etPh.getText().toString()));
            if (!etEc.getText().toString().isEmpty()) updates.put("ec", Double.parseDouble(etEc.getText().toString()));
            if (!etTemp.getText().toString().isEmpty()) updates.put("airTemperature", Double.parseDouble(etTemp.getText().toString()));
            if (!etHumidity.getText().toString().isEmpty()) updates.put("humidity", Double.parseDouble(etHumidity.getText().toString()));
            if (!etWaterLevel.getText().toString().isEmpty()) updates.put("waterLevel", Double.parseDouble(etWaterLevel.getText().toString()));

            mockSensorsRef.updateChildren(updates).addOnCompleteListener(task -> {
                if (task.isSuccessful() && getContext() != null) {
                    Toast.makeText(getContext(), "Mock data pushed to ESP32", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), "Please enter valid numbers", Toast.LENGTH_SHORT).show();
        }
    }

    private void pushSettings() {
        Map<String, Object> updates = new HashMap<>();
        try {
            if (!etMinWaterLevel.getText().toString().isEmpty()) updates.put("refillStartLevel", Double.parseDouble(etMinWaterLevel.getText().toString()));
            if (!etMaxWaterLevel.getText().toString().isEmpty()) updates.put("refillStopLevel", Double.parseDouble(etMaxWaterLevel.getText().toString()));

            settingsRef.updateChildren(updates).addOnCompleteListener(task -> {
                if (task.isSuccessful() && getContext() != null) {
                    Toast.makeText(getContext(), "Firmware settings pushed to ESP32", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), "Please enter valid numbers", Toast.LENGTH_SHORT).show();
        }
    }

    private void injectMockFirestoreLogs(String deviceId) {
        if (getContext() != null) {
            Toast.makeText(getContext(), "Generating mock logs... Please wait.", Toast.LENGTH_LONG).show();
        }

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        
        // Clear old mock data first to prevent overlapping and duplication
        db.collection("devices").document(deviceId).collection("foggingLogs").get().addOnSuccessListener(fogSnaps -> {
            WriteBatch deleteBatch = db.batch();
            for (DocumentSnapshot doc : fogSnaps) {
                deleteBatch.delete(doc.getReference());
            }
            db.collection("devices").document(deviceId).collection("parameterLogs").get().addOnSuccessListener(paramSnaps -> {
                for (DocumentSnapshot doc : paramSnaps) {
                    deleteBatch.delete(doc.getReference());
                }
                deleteBatch.commit().addOnSuccessListener(aVoid -> {
                    // Now inject fresh mock data
                    injectFreshMockData(deviceId);
                });
            });
        });
    }

    private void injectFreshMockData(String deviceId) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        WriteBatch batch = db.batch();
        Random random = new Random();
        long now = System.currentTimeMillis();
        
        // 1. Generate 30 Parameter Logs (one per hour for past 30 hours)
        for (int i = 0; i < 30; i++) {
            long timestamp = now - (i * 3600000L); // 1 hour intervals back in time
            Map<String, Object> log = new HashMap<>();
            log.put("timestamp", timestamp);
            log.put("ph", 5.5 + random.nextDouble() * 1.5); // 5.5 - 7.0
            log.put("ec", 1.0 + random.nextDouble() * 1.0); // 1.0 - 2.0
            log.put("air_temp", 22.0 + random.nextDouble() * 6.0); // 22 - 28
            log.put("humidity", 45.0 + random.nextDouble() * 25.0); // 45 - 70
            log.put("water_temp", 20.0 + random.nextDouble() * 4.0); // 20 - 24
            log.put("water_level", 40.0 + random.nextDouble() * 50.0); // 40 - 90

            DocumentReference ref = db.collection("devices").document(deviceId)
                    .collection("parameterLogs").document("mock_param_" + timestamp);
            batch.set(ref, log);
        }

        // 2. Generate 15 Fogging Logs (sequential over past 24 hours to prevent overlapping)
        long currentSimTime = now;
        for (int i = 0; i < 15; i++) {
            // Gap between fogging events: 30 mins to 2 hours
            long gap = (long)(1800000 + random.nextDouble() * 5400000); 
            long offTime = currentSimTime - gap;
            
            // Duration of fogging: 2 to 7 minutes
            long duration = (long)(120000 + random.nextDouble() * 300000); 
            long onTime = offTime - duration;
            
            boolean isManual = random.nextBoolean();

            // ON Event
            Map<String, Object> onEvent = new HashMap<>();
            onEvent.put("event", "ON");
            onEvent.put("timestamp", onTime);
            onEvent.put("isManual", isManual);
            DocumentReference refOn = db.collection("devices").document(deviceId)
                    .collection("foggingLogs").document("mock_fog_on_" + onTime);
            batch.set(refOn, onEvent);

            // OFF Event
            Map<String, Object> offEvent = new HashMap<>();
            offEvent.put("event", "OFF");
            offEvent.put("timestamp", offTime);
            offEvent.put("isManual", isManual);
            DocumentReference refOff = db.collection("devices").document(deviceId)
                    .collection("foggingLogs").document("mock_fog_off_" + offTime);
            batch.set(refOff, offEvent);
            
            currentSimTime = onTime; // Walk backwards for the next event
        }

        batch.commit().addOnSuccessListener(aVoid -> {
            if (getContext() != null) {
                Toast.makeText(getContext(), "Mock logs injected! Check Reports.", Toast.LENGTH_SHORT).show();
            }
        }).addOnFailureListener(e -> {
            if (getContext() != null) {
                Toast.makeText(getContext(), "Failed to inject: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
}
