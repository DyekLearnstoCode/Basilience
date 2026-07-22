package com.example.basilience;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.card.MaterialCardView;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class MainActivity extends AppCompatActivity {

    private MaterialCardView activeAlertBanner;
    private TextView alertTitle;
    private TextView alertMessage;

    private AlertManager alertManager;
    private BottomNavigationView bottomNav;

    private ValueEventListener summaryAlertListener;
    private DatabaseReference summaryStatusRef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Ensure status bar icons are dark (for light background)
        WindowInsetsControllerCompat windowInsetsController =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        windowInsetsController.setAppearanceLightStatusBars(true);

        setContentView(R.layout.activity_main);

        // 1. Initialize Banner and HIDE it immediately
        activeAlertBanner = findViewById(R.id.activeAlertBanner);
        if (activeAlertBanner != null) {
            activeAlertBanner.setVisibility(View.GONE); // Make sure it's gone
            activeAlertBanner.setTranslationY(-300f);
        }

        alertTitle = findViewById(R.id.alertTitle);
        alertMessage = findViewById(R.id.alertMessage);

        // Task 2: Global Settings Button
        // The button is inside layout_header which is included in fragments, not directly in activity_main.
        // We will handle it via the destination changed listener or by finding it when fragments are attached.
        
        // 2. ALERT FIXES:
        alertManager = new AlertManager(this);

        // alertManager.startListening(); // <--- REMOVED from startup as per Task 2
        // startAlertBannerListener();    // <--- REMOVED from startup as per Task 2

        bottomNav = findViewById(R.id.bottom_navigation);

        NavHostFragment navHostFragment =
                (NavHostFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.nav_host_fragment);

        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();

            NavigationUI.setupWithNavController(bottomNav, navController);

            // Task 3: Refined Global Settings logic using Fragment Lifecycle Callbacks
            // This ensures the gear icon is found and configured as soon as the fragment view is created.
            getSupportFragmentManager().registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f, @NonNull View v, @Nullable Bundle savedInstanceState) {
                    super.onFragmentViewCreated(fm, f, v, savedInstanceState);
                    View globalSettings = v.findViewById(R.id.btnGlobalSettings);
                    if (globalSettings != null) {
                        int id = navController.getCurrentDestination() != null ? navController.getCurrentDestination().getId() : -1;

                        // Hide settings button on settings-related screens
                        boolean isSettingsScreen = (id == R.id.settings || id == R.id.accountFragment ||
                                id == R.id.aboutFragment || id == R.id.tosFragment);
                        globalSettings.setVisibility(isSettingsScreen ? View.GONE : View.VISIBLE);

                        globalSettings.setOnClickListener(view -> {
                            // Prevent multiple rapid clicks and ensure we only navigate if not already at the destination
                            if (navController.getCurrentDestination() != null &&
                                    navController.getCurrentDestination().getId() != R.id.settings) {
                                navController.navigate(R.id.settings, null, new androidx.navigation.NavOptions.Builder()
                                        .setLaunchSingleTop(true)
                                        .build());
                            }
                        });
                    }
                }
            }, true);

            // --- DESTINATION LISTENER ---
            navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
                int id = destination.getId();

                if (id == R.id.DeviceManagementFragment) {
                    bottomNav.setVisibility(View.GONE);
                    
                    // Task 2.i: Management Summary Logic
                    startManagementSummaryListener();
                    alertManager.stopListening();
                    
                } else {
                    stopManagementSummaryListener(); // Ensure summary is hidden when leaving management

                    if (id == R.id.home) {
                        bottomNav.setVisibility(View.VISIBLE);
                        // Task 2.ii: Dashboard Logic
                        SharedPreferences prefs = getSharedPreferences("basilience_prefs", MODE_PRIVATE);
                        String deviceId = prefs.getString("selected_device_id", null);
                        alertManager.setDeviceId(deviceId);
                        alertManager.startListening();
                    } else {
                        bottomNav.setVisibility(View.VISIBLE);
                    }
                }

                // Menu selection logic...
                updateBottomNavSelection(id);
            });

            // Selection Listener...
            bottomNav.setOnItemSelectedListener(item -> {
                int itemId = item.getItemId();
                if (itemId == R.id.home) {
                    navController.navigate(R.id.home, null, new androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(navController.getGraph().getStartDestinationId(), true)
                            .setLaunchSingleTop(true)
                            .build());
                    return true;
                }
                return NavigationUI.onNavDestinationSelected(item, navController);
            });
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
    }

    // Task 2.i: Listen to Firebase (device/status) and toggle summaryAlertCard
    private void startManagementSummaryListener() {
        if (summaryAlertListener != null) return;

        SharedPreferences prefs = getSharedPreferences("basilience_prefs", MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", null);
        String path = (deviceId != null) ? "devices/" + deviceId + "/status" : "device/status";

        summaryStatusRef = FirebaseDatabase.getInstance().getReference(path);
        summaryAlertListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                // Find views dynamically as they are part of Fragment layouts
                MaterialCardView card = findViewById(R.id.summaryAlertCard);
                TextView msg = findViewById(R.id.tvSummaryAlertMessage);

                if (card == null || msg == null) return;

                boolean phUp = Boolean.TRUE.equals(snapshot.child("phUp").getValue(Boolean.class));
                boolean phDown = Boolean.TRUE.equals(snapshot.child("phDown").getValue(Boolean.class));
                boolean nutrients = Boolean.TRUE.equals(snapshot.child("nutrients").getValue(Boolean.class));

                if (phUp || phDown || nutrients) {
                    card.setVisibility(View.VISIBLE);
                    StringBuilder sb = new StringBuilder("Device Alert: ");
                    if (phUp || phDown) sb.append("pH deviate. Automated dosing active. ");
                    if (nutrients) sb.append("Nutrient pump running.");
                    msg.setText(sb.toString().trim());
                } else {
                    card.setVisibility(View.GONE);
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {}
        };
        summaryStatusRef.addValueEventListener(summaryAlertListener);
    }

    private void stopManagementSummaryListener() {
        if (summaryStatusRef != null && summaryAlertListener != null) {
            summaryStatusRef.removeEventListener(summaryAlertListener);
            summaryAlertListener = null;
        }
        // Ensure card is hidden when leaving the screen
        MaterialCardView card = findViewById(R.id.summaryAlertCard);
        if (card != null) card.setVisibility(View.GONE);
    }

    // Helper method to keep onCreate clean
    private void updateBottomNavSelection(int id) {
        if (id == R.id.home || id == R.id.parametersFragment || id == R.id.userGuideFragment ||
                id == R.id.hardwareGuideFragment || id == R.id.mobileGuideFragment ||
                id == R.id.reportschoiceFragment || id == R.id.reportsFragment ||
                id == R.id.foggingReportsFragment || id == R.id.cycleDetailsFragment ||
                id == R.id.cycleaddFragment || id == R.id.harvestLogFragment ||
                id == R.id.personnelFragment || id == R.id.personneladdFragment ||
                id == R.id.personneldetailsFragment) {
            bottomNav.getMenu().findItem(R.id.home).setChecked(true);
        } else if (id == R.id.Notification) {
            bottomNav.getMenu().findItem(R.id.Notification).setChecked(true);
        }
    }

    // The listener is still here if you need it later, but it won't run unless called
    private void startAlertBannerListener() {
        FirebaseDatabase.getInstance(
                        "https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app"
                )
                .getReference("device")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        // ... (Logic for showing the banner)
                    }
                    @Override
                    public void onCancelled(DatabaseError error) {}
                });
    }
}