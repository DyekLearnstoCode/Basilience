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

import com.example.basilience.Device;
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
    private MaterialButton btnLogout; // MATCHED: Idinagdag para sa Logout Button sa XML
    private RecyclerView recyclerDevices;

    // Optional: Loading UI (Null-safe kung wala sa kasalukuyang XML)
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

        // I-bind ang views base sa iyong XML IDs
        etClaimToken = view.findViewById(R.id.etClaimToken);
        btnClaimDevice = view.findViewById(R.id.btnClaimDevice);
        recyclerDevices = view.findViewById(R.id.recyclerDevices);
        btnLogout = view.findViewById(R.id.btnLogout); // MATCHED: Kinuha ang ID mula sa XML

        // Subukang hanapin ang loading views kung idadagdag mo sa layout_header o device_main
        layoutLoading = view.findViewById(R.id.layoutLoading);
        tvLoadingTitle = view.findViewById(R.id.tvLoadingTitle);

        // Setup RecyclerView
        recyclerDevices.setLayoutManager(new LinearLayoutManager(getActivity()));
        deviceList = new ArrayList<>();

        deviceAdapter = new DeviceAdapter(deviceList, device -> {
            Bundle bundle = new Bundle();
            bundle.putString("selected_device_id", device.getDevice_name());

            androidx.navigation.Navigation.findNavController(view)
                    .navigate(R.id.home, bundle);
        });
        recyclerDevices.setAdapter(deviceAdapter);

        // Claim Device Action
        btnClaimDevice.setOnClickListener(v -> {
            String token = etClaimToken.getText().toString().trim();
            if (!token.isEmpty()) {
                dbHelper.claimDevice(token)
                        .addOnSuccessListener(aVoid -> {
                            Toast.makeText(getActivity(), "Device successfully claimed!", Toast.LENGTH_LONG).show();
                            etClaimToken.setText("");
                            loadDevices();
                        })
                        .addOnFailureListener(e -> {
                            Toast.makeText(getActivity(), e.getMessage(), Toast.LENGTH_LONG).show();
                        });
            } else {
                Toast.makeText(getActivity(), "Please enter a device token code", Toast.LENGTH_SHORT).show();
            }
        });

        // MATCHED: Ikinabit ang click listener ng Logout button
        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> logout());
        }

        loadDevices();

        return view;
    }

    private void loadDevices() {
        dbHelper.getMyDevices()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    deviceList.clear();
                    for (QueryDocumentSnapshot doc : queryDocumentSnapshots) {
                        Device device = doc.toObject(Device.class);
                        deviceList.add(device);
                    }
                    deviceAdapter.notifyDataSetChanged();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(getActivity(), "Error loading devices: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void logout() {
        NotificationHelper.showConfirmation(requireContext(), "Logout", "Are you sure you want to log out?", () -> {
            // Ligtas na i-check kung may loading views, kung wala ay lalaktawan ito
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