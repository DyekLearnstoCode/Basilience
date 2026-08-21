package com.example.basilience;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.List;

public class DeviceFragment extends Fragment {

    private static final String TAG = "DeviceFragment";
    private static final String PREFS_NAME = "basilience_prefs";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";

    private TextInputEditText etClaimToken;
    private MaterialButton btnClaimDevice;
    private RecyclerView recyclerDevices;
    private View cardClaimDevice;

    private View layoutLoading;
    private TextView tvLoadingTitle;

    private Database_Helper dbHelper;
    private DeviceAdapter deviceAdapter;
    private List<Device> deviceList;
    private TextView tvLoadingDevices;
    private boolean deviceMutationInProgress;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.device_main, container, false);

        dbHelper = new Database_Helper();

        etClaimToken = view.findViewById(R.id.etClaimToken);
        btnClaimDevice = view.findViewById(R.id.btnClaimDevice);
        cardClaimDevice = view.findViewById(R.id.cardClaimDevice);
        recyclerDevices = view.findViewById(R.id.recyclerDevices);

        layoutLoading = view.findViewById(R.id.layoutLoading);
        tvLoadingTitle = view.findViewById(R.id.tvLoadingTitle);
        tvLoadingDevices = view.findViewById(R.id.tvLoadingDevices);

        // Role-based visibility
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String role = prefs.getString("user_role", RoleConstants.ROLE_FARMER);
        
        if (RoleConstants.ROLE_FARMER.equalsIgnoreCase(role)) {
            if (cardClaimDevice != null) cardClaimDevice.setVisibility(View.GONE);
        }

        recyclerDevices.setLayoutManager(new LinearLayoutManager(getActivity()));
        deviceList = new ArrayList<>();

        // 🔥 IN-UPDATE: Dalawa na ang listener dito (Single Tap & Long Press)
        deviceAdapter = new DeviceAdapter(
                deviceList,
                // 1. Single Tap -> Pupunta sa Home Dashboard
                device -> {
                    Bundle bundle = new Bundle();
                    bundle.putString("selected_device_id", device.getDeviceId());

                    androidx.navigation.Navigation.findNavController(view)
                            .navigate(R.id.home, bundle);
                },
                // 2. 🔥 Long Press -> Lalabas ang Confirmation Dialog para mag-Unclaim
                device -> {
                    SharedPreferences currentPrefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    String currentRole = currentPrefs.getString("user_role", RoleConstants.ROLE_FARMER);
                    if (RoleConstants.ROLE_ADMIN.equalsIgnoreCase(currentRole)) {
                        NotificationHelper.showDestructiveConfirmation(
                                requireContext(),
                                "Unclaim Device",
                                "Are you sure you want to unclaim " + device.getDeviceName() + "?",
                                "Unclaim",
                                () -> unclaimDevice(device)
                        );
                    }
                }
        );
        recyclerDevices.setAdapter(deviceAdapter);

        // Claim Device Action
        btnClaimDevice.setOnClickListener(v -> {
            if (deviceMutationInProgress) return;
            String token = etClaimToken.getText().toString().trim();
            if (!token.isEmpty()) {
                deviceMutationInProgress = true;
                btnClaimDevice.setEnabled(false);
                if (layoutLoading != null && tvLoadingTitle != null) {
                    tvLoadingTitle.setText("Claiming device...");
                    layoutLoading.setVisibility(View.VISIBLE);
                    layoutLoading.bringToFront();
                }
                dbHelper.claimDevice(token)
                        .addOnSuccessListener(aVoid -> {
                            if (!isAdded()) return;
                            deviceMutationInProgress = false;
                            btnClaimDevice.setEnabled(true);
                            if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                            NotificationHelper.showSuccess(requireContext(), "Device successfully claimed!");
                            etClaimToken.setText("");
                            loadDevices();
                        })
                        .addOnFailureListener(e -> {
                            if (!isAdded()) return;
                            deviceMutationInProgress = false;
                            btnClaimDevice.setEnabled(true);
                            if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                            Log.e(TAG, "Failed to claim device", e);
                            NotificationHelper.showError(requireContext(), "Unable to claim this device. Please check the token and try again.");
                        });
            } else {
                Toast.makeText(getActivity(), "Please enter a device token code", Toast.LENGTH_SHORT).show();
            }
        });


        loadDevices();

        return view;
    }

    private void loadDevices() {
        if (tvLoadingDevices != null) tvLoadingDevices.setVisibility(View.VISIBLE);
        if (recyclerDevices != null) recyclerDevices.setVisibility(View.GONE);

        dbHelper.getMyDevices()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (!isAdded()) return;
                    if (tvLoadingDevices != null) tvLoadingDevices.setVisibility(View.GONE);
                    
                    deviceList.clear();
                    for (QueryDocumentSnapshot doc : queryDocumentSnapshots) {
                        Device device = doc.toObject(Device.class);
                        deviceList.add(device);
                    }
                    deviceAdapter.notifyDataSetChanged();
                    
                    if (recyclerDevices != null) {
                        if (deviceList.isEmpty()) {
                            tvLoadingDevices.setText("No registered devices");
                            tvLoadingDevices.setVisibility(View.VISIBLE);
                        } else {
                            recyclerDevices.setVisibility(View.VISIBLE);
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    if (tvLoadingDevices != null) {
                        tvLoadingDevices.setText("Error loading devices");
                    }
                    Log.e(TAG, "Failed to load devices", e);
                    Toast.makeText(getActivity(), "Unable to load your devices. Please try again.", Toast.LENGTH_SHORT).show();
                });
    }
    private void unclaimDevice(Device device) {
        if (deviceMutationInProgress) return;
        deviceMutationInProgress = true;
        if (layoutLoading != null && tvLoadingTitle != null) {
            tvLoadingTitle.setText(R.string.loading_saving);
            layoutLoading.setVisibility(View.VISIBLE);
            layoutLoading.bringToFront();
        }

        dbHelper.unclaimDevice(device.getDeviceId())
                .addOnSuccessListener(aVoid -> {
                    if (!isAdded()) return;
                    deviceMutationInProgress = false;
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    clearSelectedDeviceIfUnclaimed(device.getDeviceId());
                    NotificationHelper.showSuccess(requireContext(), "Device unclaimed successfully!");
                    loadDevices(); // Refresh listahan
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    deviceMutationInProgress = false;
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                            Log.e(TAG, "Failed to unclaim device", e);
                            NotificationHelper.showError(requireContext(), "Unable to unclaim this device. Please try again.");
                });
    }

    private void clearSelectedDeviceIfUnclaimed(String deviceId) {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!deviceId.equals(prefs.getString("selected_device_id", null))) return;

        // Removing the preference immediately causes MainActivity's preference listener to
        // stop the active device-presence listener before another device can be selected.
        DeviceConnectionManager.getInstance().stopMonitoring();
        prefs.edit().remove("selected_device_id").apply();
    }
}
