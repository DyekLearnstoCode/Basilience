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
        startListening(deviceId, liveData, null);
    }

    /**
     * @param readErrorLiveData optional; posted true if the sensors listener is cancelled
     *                          (e.g. permission denied) and false once a read succeeds again,
     *                          so the UI can show/hide an "unable to load sensor data" notice.
     */
    public void startListening(String deviceId, MutableLiveData<SensorData> liveData,
                                MutableLiveData<Boolean> readErrorLiveData) {

        stopListening();

        sensorsRef = FirebaseDatabase
                .getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app")
                .getReference("devices")
                .child(deviceId)
                .child("sensors");

        sensorsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {

                if (readErrorLiveData != null) {
                    readErrorLiveData.postValue(false);
                }

                if (!snapshot.exists()) {
                    Log.d("SensorRepository", "Sensors node does not exist.");
                    liveData.postValue(new SensorData());
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
                if (readErrorLiveData != null) {
                    readErrorLiveData.postValue(true);
                }
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
