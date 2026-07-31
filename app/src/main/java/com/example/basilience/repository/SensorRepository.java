package com.example.basilience.repository;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;

import com.example.basilience.models.SensorData;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class SensorRepository {

    private DatabaseReference sensorsRef;
    private ValueEventListener sensorsListener;

    public void startListening(String deviceId, MutableLiveData<SensorData> liveData) {
        sensorsRef = FirebaseDatabase.getInstance().getReference("devices").child(deviceId).child("sensors");
        
        sensorsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                SensorData data = snapshot.getValue(SensorData.class);
                if (data != null) {
                    liveData.setValue(data);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("SensorRepository", "RTDB Error: " + error.getMessage());
            }
        };
        
        sensorsRef.addValueEventListener(sensorsListener);
    }

    public void stopListening() {
        if (sensorsRef != null && sensorsListener != null) {
            sensorsRef.removeEventListener(sensorsListener);
        }
    }
}
