package com.example.basilience;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class DeviceConnectionManager {

    private static DeviceConnectionManager instance;
    private final MutableLiveData<Boolean> onlineStatus = new MutableLiveData<>(false);
    
    private String currentDeviceId;
    private DatabaseReference statusRef;
    private ValueEventListener statusListener;

    private DeviceConnectionManager() {
        // Singleton pattern
    }

    public static synchronized DeviceConnectionManager getInstance() {
        if (instance == null) {
            instance = new DeviceConnectionManager();
        }
        return instance;
    }

    public LiveData<Boolean> getOnlineStatus() {
        return onlineStatus;
    }

    public void monitorDevice(String deviceId) {
        if (deviceId == null || deviceId.equals(currentDeviceId)) {
            return;
        }

        stopMonitoring();
        currentDeviceId = deviceId;
        
        FirebaseDatabase db = FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app");

        // Listen to the server-driven status flag exclusively
        statusRef = db.getReference("devices").child(deviceId).child("status").child("online");
        statusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean statusOnline = snapshot.getValue(Boolean.class);
                
                // Treat null/missing as false (offline) for safety
                boolean isOnline = statusOnline != null && statusOnline;

                android.util.Log.d("ConnectionManager", "Online status for " + deviceId + ": " + isOnline);
                onlineStatus.setValue(isOnline);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                android.util.Log.e("ConnectionManager", "Status listener cancelled: " + error.getMessage());
                // A cancelled listener is not an authoritative status/online=false event.
                // Retain the last backend-reported presence value.
            }
        };
        statusRef.addValueEventListener(statusListener);
    }

    public void stopMonitoring() {
        if (statusRef != null && statusListener != null) {
            statusRef.removeEventListener(statusListener);
        }
        currentDeviceId = null;
    }
}
