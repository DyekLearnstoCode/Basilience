package com.example.basilience;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
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

    private static final String PREFS_NAME = "UserPrefs";
    private static final String KEY_IS_LOGGED_IN = "isLoggedIn";

    private TextInputEditText etClaimToken;
    private MaterialButton btnClaimDevice;
    private MaterialButton btnLogout;
    private RecyclerView recyclerDevices;

    private View layoutLoading;
    private TextView tvLoadingTitle;

    private Database_Helper dbHelper;
    private DeviceAdapter deviceAdapter;
    private List<Device> deviceList;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.device_main, container, false);

        dbHelper = new Database_Helper();

        etClaimToken = view.findViewById(R.id.etClaimToken);
        btnClaimDevice = view.findViewById(R.id.btnClaimDevice);
        recyclerDevices = view.findViewById(R.id.recyclerDevices);
        btnLogout = view.findViewById(R.id.btnLogout);

        layoutLoading = view.findViewById(R.id.layoutLoading);
        tvLoadingTitle = view.findViewById(R.id.tvLoadingTitle);

        recyclerDevices.setLayoutManager(new LinearLayoutManager(getActivity()));
        deviceList = new ArrayList<>();

        // 🔥 IN-UPDATE: Dalawa na ang listener dito (Single Tap & Long Press)
        deviceAdapter = new DeviceAdapter(
                deviceList,
                // 1. Single Tap -> Pupunta sa Home Dashboard
                device -> {
                    Bundle bundle = new Bundle();
                    bundle.putString("selected_device_id", device.getDevice_name());

                    androidx.navigation.Navigation.findNavController(view)
                            .navigate(R.id.home, bundle);
                },
                // 2. 🔥 Long Press -> Lalabas ang Confirmation Dialog para mag-Unclaim
                device -> NotificationHelper.showConfirmation(
                        requireContext(),
                        "Unclaim Device",
                        "Are you sure you want to unclaim " + device.getDevice_name() + "?",
                        () -> unclaimDevice(device)
                )
        );
        recyclerDevices.setAdapter(deviceAdapter);

        // Claim Device Action
        btnClaimDevice.setOnClickListener(v -> {
            String token = etClaimToken.getText().toString().trim();
            if (!token.isEmpty()) {
                dbHelper.claimDevice(token)
                        .addOnSuccessListener(aVoid -> {
                            if (!isAdded()) return;
                            NotificationHelper.showSuccess(requireContext(), "Device successfully claimed!");
                            etClaimToken.setText("");
                            loadDevices();
                        })
                        .addOnFailureListener(e -> {
                            if (!isAdded()) return;
                            NotificationHelper.showError(requireContext(), e.getMessage());
                        });
            } else {
                Toast.makeText(getActivity(), "Please enter a device token code", Toast.LENGTH_SHORT).show();
            }
        });

        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> logout());
        }

        loadDevices();

        return view;
    }

    private void loadDevices() {
        dbHelper.getMyDevices()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (!isAdded()) return;
                    deviceList.clear();
                    for (QueryDocumentSnapshot doc : queryDocumentSnapshots) {
                        Device device = doc.toObject(Device.class);
                        deviceList.add(device);
                    }
                    deviceAdapter.notifyDataSetChanged();
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    Toast.makeText(getActivity(), "Error loading devices: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }
    private void unclaimDevice(Device device) {
        if (layoutLoading != null && tvLoadingTitle != null) {
            tvLoadingTitle.setText(R.string.loading_saving);
            layoutLoading.setVisibility(View.VISIBLE);
        }

        // Gamitin ang device_name o document ID depende sa setup ng Database_Helper mo
        dbHelper.unclaimDevice(device.getDevice_name())
                .addOnSuccessListener(aVoid -> {
                    if (!isAdded()) return;
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    NotificationHelper.showSuccess(requireContext(), "Device unclaimed successfully!");
                    loadDevices(); // Refresh listahan
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    NotificationHelper.showError(requireContext(), "Failed to unclaim: " + e.getMessage());
                });
    }

    private void logout() {
        NotificationHelper.showConfirmation(requireContext(), "Logout", "Are you sure you want to log out?", () -> {
            if (layoutLoading != null && tvLoadingTitle != null) {
                tvLoadingTitle.setText(R.string.loading_logging_out);
                layoutLoading.setVisibility(View.VISIBLE);
            }

            dbHelper.logout();

            if (getActivity() != null) {
                SharedPreferences borderPrefs = getActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                borderPrefs.edit().putBoolean(KEY_IS_LOGGED_IN, false).apply();

                Intent intent = new Intent(getActivity(), Auth_Login_Activity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

                View fragmentView = getView();
                if (fragmentView != null) {
                    fragmentView.postDelayed(() -> {
                        startActivity(intent);
                        if (getActivity() != null) getActivity().finish();
                    }, 600);
                } else {
                    startActivity(intent);
                    if (getActivity() != null) getActivity().finish();
                }
            }
        });
    }
}