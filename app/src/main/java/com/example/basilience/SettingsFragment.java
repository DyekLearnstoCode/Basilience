package com.example.basilience;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

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

public class SettingsFragment extends Fragment {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.settings_main, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        NavController navController = Navigation.findNavController(view);

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> {
                navController.popBackStack();
            });
        }

        // Account Information
        View btnAccount = view.findViewById(R.id.btnAccount);
        btnAccount.setOnClickListener(v -> navController.navigate(R.id.action_settings_to_accountFragment));

        // About Basilience
        View btnAbout = view.findViewById(R.id.btnAbout);
        btnAbout.setOnClickListener(v -> navController.navigate(R.id.action_settings_to_aboutFragment));

        // Terms and Agreements
        View btnTerms = view.findViewById(R.id.btnTerms);
        btnTerms.setOnClickListener(v -> navController.navigate(R.id.action_settings_to_tosFragment));

        // Wi-Fi Configuration (Admin Only)
        View wifiConfigContainer = view.findViewById(R.id.wifiConfigContainer);
        View btnWifiConfig = view.findViewById(R.id.btnWifiConfig);
        if (getActivity() != null) {
            android.content.SharedPreferences prefs = getActivity().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
            String role = prefs.getString("user_role", "FARMER");
            if ("ADMIN".equalsIgnoreCase(role)) {
                wifiConfigContainer.setVisibility(View.VISIBLE);
                btnWifiConfig.setOnClickListener(v -> navController.navigate(R.id.action_settings_to_wifiConfigFragment));
            }
        }

        // Developer Options
        View devOptionsContainer = view.findViewById(R.id.devOptionsContainer);
        View btnDevOptions = view.findViewById(R.id.btnDevOptions);
        
        Database_Helper dbHelper = new Database_Helper();
        String currentDeviceId = dbHelper.getSelectedDeviceId();
        if (currentDeviceId == null && getActivity() != null) {
            android.content.SharedPreferences prefs = getActivity().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
            currentDeviceId = prefs.getString("selected_device_id", null);
        }
        
        if (currentDeviceId != null) {
            String rtdbUrl = "https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app";
            DatabaseReference devModeRef = FirebaseDatabase.getInstance(rtdbUrl)
                    .getReference("devices/" + currentDeviceId + "/settings/devModeEnabled");
            
            // Helpful debug toast - you can remove this later
            android.widget.Toast.makeText(getContext(), "Checking dev mode for: " + currentDeviceId, android.widget.Toast.LENGTH_SHORT).show();

            devModeRef.addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    Object val = snapshot.getValue();
                    boolean isDevMode = false;
                    
                    // Support Boolean, String "true", or Number 1
                    if (val instanceof Boolean) {
                        isDevMode = (Boolean) val;
                    } else if (val instanceof String) {
                        isDevMode = "true".equalsIgnoreCase((String) val);
                    } else if (val instanceof Long) {
                        isDevMode = ((Long) val) == 1;
                    }

                    if (isDevMode) {
                        devOptionsContainer.setVisibility(View.VISIBLE);
                        btnDevOptions.setOnClickListener(v -> navController.navigate(R.id.action_settings_to_devOptionsFragment));
                    } else {
                        devOptionsContainer.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    Log.e("SettingsFragment", "Database error: " + error.getMessage());
                }
            });
        } else {
            // This is likely why it's hidden: no device is selected in the current session
            android.widget.Toast.makeText(getContext(), "Dev Options hidden: No device selected", android.widget.Toast.LENGTH_SHORT).show();
            devOptionsContainer.setVisibility(View.GONE);
        }

        // Logout
        View btnLogout = view.findViewById(R.id.btnLogout);
        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> performLogout());
        }
    }

    private void performLogout() {
        NotificationHelper.showConfirmation(requireContext(), "Logout", "Are you sure you want to log out?", () -> {
            Database_Helper helper = new Database_Helper();
            helper.logout();

            // Clear session preferences
            if (getActivity() != null) {
                android.content.SharedPreferences prefs = getActivity().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
                prefs.edit().clear().apply();

                // Redirect to Login
                android.content.Intent intent = new android.content.Intent(getActivity(), Auth_Login_Activity.class);
                intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                getActivity().finish();
            }
        });
    }
}
