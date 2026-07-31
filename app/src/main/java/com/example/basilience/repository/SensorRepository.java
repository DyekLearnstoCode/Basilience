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

        stopListening();

        sensorsRef = FirebaseDatabase
                .getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app")
                .getReference("devices")
                .child(deviceId)
                .child("sensors");

        sensorsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {

                if (!snapshot.exists()) {
                    Log.d("SensorRepository", "Sensors node does not exist.");
                    return;
                }

                SensorData data = snapshot.getValue(SensorData.class);

                if (data != null) {
                    liveData.postValue(data);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("SensorRepository",
                        "Firebase Error",
                        error.toException());
            }
        };

        sensorsRef.addValueEventListener(sensorsListener);
    }

    public void stopListening() {

        if (sensorsRef != null && sensorsListener != null) {
            sensorsRef.removeEventListener(sensorsListener);
        }

        sensorsRef = null;
        sensorsListener = null;
    }
}
