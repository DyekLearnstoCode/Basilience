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
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.auth.FirebaseAuth;
import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.result.ActivityResultLauncher;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.lang.ref.WeakReference;

public class MainActivity extends AppCompatActivity {

    private static WeakReference<MainActivity> foregroundActivity = new WeakReference<>(null);
    private static final Map<String, String> activeParameterAlerts = new LinkedHashMap<>();
    private static final Set<String> presentedParameterEventIds = new HashSet<>();
    private static String parameterAlertDeviceId;
    private NotificationHelper.UpdatableParameterDialog parameterAlertDialog;
    private androidx.appcompat.app.AlertDialog criticalAlertDialog;
    private boolean criticalAlertShowing;
    private final ArrayDeque<CriticalAlertRequest> pendingCriticalAlerts = new ArrayDeque<>();

    private static final class CriticalAlertRequest {
        final String title;
        final String message;

        CriticalAlertRequest(String title, String message) {
            this.title = title;
            this.message = message;
        }
    }

    private MaterialCardView activeAlertBanner;
    private TextView alertTitle;
    private TextView alertMessage;


    private BottomNavigationView bottomNav;

    private ValueEventListener summaryAlertListener;
    private DatabaseReference summaryStatusRef;
    private ValueEventListener currentParameterAlertListener;
    private DatabaseReference currentParameterAlertsRef;
    private String currentParameterAlertDeviceId;
    private final Map<String, Boolean> currentParameterAlertStates = new HashMap<>();

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.d("FCM", "Notification permission granted");
                } else {
                    Log.w("FCM", "Notification permission denied");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        askNotificationPermission();
        retrieveAndSaveFCMToken();
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
        // AlertManager removed to prevent duplicate notifications (handled by Cloud Functions & FCM)

        // Start global connection monitoring
        SharedPreferences prefs = getSharedPreferences("basilience_prefs", MODE_PRIVATE);
        String initialDeviceId = prefs.getString("selected_device_id", null);
        if (initialDeviceId != null) {
            DeviceConnectionManager.getInstance().monitorDevice(initialDeviceId);
        }
        startCurrentParameterAlertListener(initialDeviceId);
        
        DeviceConnectionManager.getInstance().getOnlineStatus().observe(this, isOnline -> {
            // Presence UI observes backend-owned status. Cloud Functions alone create
            // persistent alert history and send FCM events.
        });

        prefs.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
            if ("selected_device_id".equals(key)) {
                String newDeviceId = sharedPreferences.getString("selected_device_id", null);
                if (newDeviceId != null) {
                    DeviceConnectionManager.getInstance().monitorDevice(newDeviceId);
                } else {
                    DeviceConnectionManager.getInstance().stopMonitoring();
                }
                startCurrentParameterAlertListener(newDeviceId);
            }
        });

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
                                id == R.id.aboutFragment || id == R.id.tosFragment ||
                                id == R.id.wifiConfigFragment || id == R.id.devOptionsFragment);
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

                boolean isManagementScreen = (id == R.id.DeviceManagementFragment || id == R.id.personnelFragment || id == R.id.personneladdFragment || id == R.id.personneldetailsFragment);

                if (isManagementScreen) {
                    bottomNav.setVisibility(View.VISIBLE);
                    if (bottomNav.getMenu().findItem(R.id.DeviceManagementFragment) == null) {
                        bottomNav.getMenu().clear();
                        bottomNav.inflateMenu(R.menu.management_bottom_nav_menu);
                        
                        // RBAC: Hide Personnel for Farmers
                        String userRole = prefs.getString("user_role", RoleConstants.ROLE_ADMIN);
                        if (RoleConstants.ROLE_FARMER.equalsIgnoreCase(userRole)) {
                            if (bottomNav.getMenu().findItem(R.id.personnelFragment) != null) {
                                bottomNav.getMenu().findItem(R.id.personnelFragment).setVisible(false);
                            }
                            if (bottomNav.getMenu().findItem(R.id.DeviceManagementFragment) != null) {
                                bottomNav.getMenu().findItem(R.id.DeviceManagementFragment).setVisible(false);
                            }
                        }
                    }
                    
                    if (id == R.id.DeviceManagementFragment) {
                        // Task 2.i: Management Summary Logic
                        startManagementSummaryListener();
                    } else {
                        stopManagementSummaryListener();
                    }
                } else {
                    stopManagementSummaryListener(); // Ensure summary is hidden when leaving management

                    if (id == R.id.home || id == R.id.Notification || id == R.id.reportschoiceFragment) {
                        bottomNav.setVisibility(View.VISIBLE);
                        if (bottomNav.getMenu().findItem(R.id.home) == null) {
                            bottomNav.getMenu().clear();
                            bottomNav.inflateMenu(R.menu.bottom_nav_menu);
                            
                            // RBAC: Hide Reports for Farmers
                            String userRole = prefs.getString("user_role", RoleConstants.ROLE_ADMIN);
                            if (RoleConstants.ROLE_FARMER.equalsIgnoreCase(userRole)) {
                                if (bottomNav.getMenu().findItem(R.id.reportschoiceFragment) != null) {
                                    bottomNav.getMenu().findItem(R.id.reportschoiceFragment).setVisible(false);
                                }
                            }
                        }

                        // RBAC: Navigate away if farmer tries to access reports
                        if (id == R.id.reportschoiceFragment) {
                            String role = prefs.getString("user_role", RoleConstants.ROLE_ADMIN);
                            if (RoleConstants.ROLE_FARMER.equalsIgnoreCase(role)) {
                                navController.navigate(R.id.home);
                            }
                        }

                        if (id == R.id.home) {
                            // Task 2.ii: Dashboard Logic
                            String deviceId = prefs.getString("selected_device_id", null);
                        }
                    } else {
                        bottomNav.setVisibility(View.GONE);
                    }
                }

                // Menu selection logic...
                updateBottomNavSelection(id);
            });

            // Initial RBAC check for Bottom Nav Menu items is handled in listener now

            // Selection Listener...
            bottomNav.setOnItemSelectedListener(item -> {
                int itemId = item.getItemId();
                if (itemId == R.id.home) {
                    navController.navigate(R.id.home, null, new androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(navController.getGraph().getStartDestinationId(), true)
                            .setLaunchSingleTop(true)
                            .build());
                    return true;
                } else if (itemId == R.id.reportschoiceFragment) {
                    navController.navigate(R.id.reportschoiceFragment, null, new androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(navController.getGraph().getStartDestinationId(), false)
                            .setLaunchSingleTop(true)
                            .build());
                    return true;
                } else if (itemId == R.id.DeviceManagementFragment) {
                    navController.navigate(R.id.DeviceManagementFragment, null, new androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(navController.getGraph().getStartDestinationId(), true)
                            .setLaunchSingleTop(true)
                            .build());
                    return true;
                } else if (itemId == R.id.personnelFragment) {
                    navController.navigate(R.id.personnelFragment, null, new androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(navController.getGraph().getStartDestinationId(), false)
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

        if (deviceId == null || deviceId.isEmpty()) {
            return;
        }

        String path = "devices/" + deviceId + "/status";

        summaryStatusRef = FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app").getReference(path);
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
                id == R.id.cycleaddFragment || id == R.id.harvestLogFragment) {
            if (bottomNav.getMenu().findItem(R.id.home) != null) {
                bottomNav.getMenu().findItem(R.id.home).setChecked(true);
            }
        } else if (id == R.id.Notification) {
            if (bottomNav.getMenu().findItem(R.id.Notification) != null) {
                bottomNav.getMenu().findItem(R.id.Notification).setChecked(true);
            }
        } else if (id == R.id.DeviceManagementFragment) {
            if (bottomNav.getMenu().findItem(R.id.DeviceManagementFragment) != null) {
                bottomNav.getMenu().findItem(R.id.DeviceManagementFragment).setChecked(true);
            }
        } else if (id == R.id.personnelFragment || id == R.id.personneladdFragment ||
                id == R.id.personneldetailsFragment) {
            if (bottomNav.getMenu().findItem(R.id.personnelFragment) != null) {
                bottomNav.getMenu().findItem(R.id.personnelFragment).setChecked(true);
            }
        }
    }

    // The listener is still here if you need it later, but it won't run unless called
    private void startAlertBannerListener() {
        SharedPreferences prefs = getSharedPreferences("basilience_prefs", MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", null);

        if (deviceId == null || deviceId.isEmpty()) return;

        FirebaseDatabase.getInstance(
                        "https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app"
                )
                .getReference("devices").child(deviceId)
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        // ... (Logic for showing the banner)
                    }
                    @Override
                    public void onCancelled(DatabaseError error) {}
                });
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED) {
                // FCM SDK (and your app) can post notifications.
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                // Display an educational UI explaining to the user the features that will be enabled
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                // Directly ask for the permission
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void retrieveAndSaveFCMToken() {
        FirebaseMessaging.getInstance().getToken()
            .addOnCompleteListener(task -> {
                if (!task.isSuccessful()) {
                    Log.w("FCM", "Fetching FCM registration token failed", task.getException());
                    return;
                }
                String token = task.getResult();
                Log.d("FCM", "FCM token retrieved for current user");
                
                String uid = FirebaseAuth.getInstance().getUid();
                if (uid != null) {
                    Map<String, Object> update = new HashMap<>();
                    update.put("fcmToken", token);
                    FirebaseFirestore.getInstance().collection("users")
                            .document(uid)
                            .set(update, com.google.firebase.firestore.SetOptions.merge())
                            .addOnSuccessListener(aVoid -> Log.d("FCM", "FCM Token saved to Firestore."))
                            .addOnFailureListener(e -> Log.e("FCM", "Failed to save FCM token", e));
                }
            });
    }

    @Override
    protected void onStart() {
        super.onStart();
        foregroundActivity = new WeakReference<>(this);
    }

    @Override
    protected void onStop() {
        MainActivity current = foregroundActivity.get();
        if (current == this) foregroundActivity.clear();
        super.onStop();
    }

    public static boolean showForegroundAlert(String title, String message, String eventId) {
        MainActivity activity = foregroundActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;

        String stableId = eventId == null || eventId.trim().isEmpty()
                ? title + "|" + message
                : eventId;
        SharedPreferences prefs = activity.getSharedPreferences("basilience_prefs", MODE_PRIVATE);
        Set<String> shownIds = new HashSet<>(prefs.getStringSet("shown_critical_alert_ids", new HashSet<>()));
        if (shownIds.contains(stableId)) return true;
        shownIds.add(stableId);
        prefs.edit().putStringSet("shown_critical_alert_ids", shownIds).apply();

        activity.runOnUiThread(() -> activity.enqueueCriticalAlert(title, message));
        return true;
    }

    private void enqueueCriticalAlert(String title, String message) {
        pendingCriticalAlerts.add(new CriticalAlertRequest(title, message));
        dismissParameterAlertForCritical();
        showNextCriticalAlert();
    }

    private void showNextCriticalAlert() {
        if (criticalAlertShowing) return;
        CriticalAlertRequest request = pendingCriticalAlerts.poll();
        if (request == null) {
            showPendingParameterAlertIfNeeded();
            return;
        }

        criticalAlertShowing = true;
        criticalAlertDialog = NotificationHelper.showCriticalAlert(this, request.title,
                request.message, () -> {
                    criticalAlertShowing = false;
                    criticalAlertDialog = null;
                    showNextCriticalAlert();
                });
        if (criticalAlertDialog == null) {
            criticalAlertShowing = false;
            showNextCriticalAlert();
        }
    }

    public static boolean showForegroundParameterAlert(String type, String eventId, String deviceId) {
        MainActivity activity = foregroundActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;

        String label = parameterLabel(type);
        if (label == null) return false;
        String stableId = eventId == null || eventId.trim().isEmpty()
                ? (deviceId == null ? "" : deviceId) + "|" + type
                : eventId;

        activity.runOnUiThread(() -> activity.addForegroundParameterAlert(type, label, stableId, deviceId));
        return true;
    }

    private void addForegroundParameterAlert(String type, String label, String eventId, String deviceId) {
        if (presentedParameterEventIds.contains(eventId)) return;

        if (deviceId != null && deviceId.equals(currentParameterAlertDeviceId)
                && Boolean.FALSE.equals(currentParameterAlertStates.get(type))) {
            // The FCM event arrived after the live alert had already recovered.
            presentedParameterEventIds.add(eventId);
            return;
        }

        presentedParameterEventIds.add(eventId);

        if (parameterAlertDeviceId != null && deviceId != null && !parameterAlertDeviceId.equals(deviceId)) {
            activeParameterAlerts.clear();
            if (parameterAlertDialog != null && parameterAlertDialog.isShowing()) parameterAlertDialog.dismiss();
            parameterAlertDialog = null;
        }
        parameterAlertDeviceId = deviceId;
        activeParameterAlerts.put(type, label);
        if (criticalAlertShowing) return;
        String content = buildParameterAlertContent();

        if (parameterAlertDialog != null && parameterAlertDialog.isShowing()) {
            parameterAlertDialog.updateMessage(content);
            return;
        }
        parameterAlertDialog = NotificationHelper.showParameterAlert(this, content,
                this::openParametersFromAlert, this::dismissParameterAlerts);
    }

    private void startCurrentParameterAlertListener(String deviceId) {
        stopCurrentParameterAlertListener();

        if (parameterAlertDeviceId != null
                && (deviceId == null || !parameterAlertDeviceId.equals(deviceId))) {
            activeParameterAlerts.clear();
            if (parameterAlertDialog != null && parameterAlertDialog.isShowing()) {
                parameterAlertDialog.dismiss();
            }
            parameterAlertDialog = null;
            parameterAlertDeviceId = null;
        }

        currentParameterAlertStates.clear();
        currentParameterAlertDeviceId = deviceId;

        if (deviceId == null || deviceId.isEmpty()) {
            reconcileCurrentParameterAlerts();
            return;
        }

        currentParameterAlertsRef = FirebaseDatabase.getInstance(
                        "https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app")
                .getReference("devices")
                .child(deviceId)
                .child("alerts");

        currentParameterAlertListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                currentParameterAlertStates.put("lowWater",
                        Boolean.TRUE.equals(snapshot.child("lowWater").getValue(Boolean.class)));
                currentParameterAlertStates.put("ecLow",
                        Boolean.TRUE.equals(snapshot.child("ecLow").getValue(Boolean.class)));
                currentParameterAlertStates.put("phOutOfRange",
                        Boolean.TRUE.equals(snapshot.child("phOutOfRange").getValue(Boolean.class)));
                currentParameterAlertStates.put("highTemperature",
                        Boolean.TRUE.equals(snapshot.child("highTemperature").getValue(Boolean.class)));
                currentParameterAlertStates.put("waterTempOutOfRange",
                        Boolean.TRUE.equals(snapshot.child("waterTempOutOfRange").getValue(Boolean.class)));
                reconcileCurrentParameterAlerts();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("ParameterAlerts", "Current alert listener cancelled", error.toException());
            }
        };

        currentParameterAlertsRef.addValueEventListener(currentParameterAlertListener);
    }

    private void reconcileCurrentParameterAlerts() {
        if (parameterAlertDeviceId != null
                && currentParameterAlertDeviceId != null
                && !parameterAlertDeviceId.equals(currentParameterAlertDeviceId)) {
            return;
        }

        boolean removed = false;
        for (String type : new HashSet<>(activeParameterAlerts.keySet())) {
            if (Boolean.FALSE.equals(currentParameterAlertStates.get(type))) {
                activeParameterAlerts.remove(type);
                removed = true;
            }
        }

        if (!removed) return;

        if (activeParameterAlerts.isEmpty()) {
            if (parameterAlertDialog != null && parameterAlertDialog.isShowing()) {
                parameterAlertDialog.dismiss();
            }
            parameterAlertDialog = null;
            parameterAlertDeviceId = null;
        } else if (parameterAlertDialog != null && parameterAlertDialog.isShowing()) {
            parameterAlertDialog.updateMessage(buildParameterAlertContent());
        }
    }

    private void stopCurrentParameterAlertListener() {
        if (currentParameterAlertsRef != null && currentParameterAlertListener != null) {
            currentParameterAlertsRef.removeEventListener(currentParameterAlertListener);
        }
        currentParameterAlertsRef = null;
        currentParameterAlertListener = null;
    }

    private String buildParameterAlertContent() {
        if (activeParameterAlerts.size() == 1) {
            String only = activeParameterAlerts.values().iterator().next();
            if ("Water Level — Low".equals(only)) return "Water Level is below the configured threshold.";
            if ("EC — Low".equals(only)) return "EC is below the configured range.";
            if ("pH — Out of Range".equals(only)) return "pH is outside the configured range.";
            if ("Air Temperature — High".equals(only)) return "Air Temperature is above the configured range.";
            return "Water Temperature is outside the configured range.";
        }
        StringBuilder content = new StringBuilder(activeParameterAlerts.size()
                + " parameters need attention:");
        for (String label : activeParameterAlerts.values()) content.append("\n\n• ").append(label);
        return content.toString();
    }

    private void dismissParameterAlerts() {
        activeParameterAlerts.clear();
        parameterAlertDeviceId = null;
        parameterAlertDialog = null;
    }

    private void dismissParameterAlertForCritical() {
        if (parameterAlertDialog != null && parameterAlertDialog.isShowing()) parameterAlertDialog.dismiss();
        parameterAlertDialog = null;
    }

    private void showPendingParameterAlertIfNeeded() {
        if (criticalAlertShowing || activeParameterAlerts.isEmpty()) return;

        for (String type : new HashSet<>(activeParameterAlerts.keySet())) {
            if (Boolean.FALSE.equals(currentParameterAlertStates.get(type))) {
                activeParameterAlerts.remove(type);
            }
        }
        if (activeParameterAlerts.isEmpty()) {
            parameterAlertDeviceId = null;
            return;
        }

        String content = buildParameterAlertContent();
        if (parameterAlertDialog != null && parameterAlertDialog.isShowing()) {
            parameterAlertDialog.updateMessage(content);
        } else {
            parameterAlertDialog = NotificationHelper.showParameterAlert(this, content,
                    this::openParametersFromAlert, this::dismissParameterAlerts);
        }
    }

    private void openParametersFromAlert() {
        String deviceId = parameterAlertDeviceId;
        dismissParameterAlerts();
        if (deviceId != null && !deviceId.isEmpty()) {
            getSharedPreferences("basilience_prefs", MODE_PRIVATE).edit()
                    .putString("selected_device_id", deviceId).apply();
        }
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (host == null) return;
        NavController controller = host.getNavController();
        if (controller.getCurrentDestination() == null
                || controller.getCurrentDestination().getId() != R.id.parametersFragment) {
            controller.navigate(R.id.parametersFragment);
        }
    }

    private static String parameterLabel(String type) {
        if ("lowWater".equalsIgnoreCase(type)) return "Water Level — Low";
        if ("ecLow".equalsIgnoreCase(type)) return "EC — Low";
        if ("phOutOfRange".equalsIgnoreCase(type)) return "pH — Out of Range";
        if ("highTemperature".equalsIgnoreCase(type)) return "Air Temperature — High";
        if ("waterTempOutOfRange".equalsIgnoreCase(type)) return "Water Temperature — Out of Range";
        return null;
    }

    @Override
    protected void onDestroy() {
        stopCurrentParameterAlertListener();
        if (parameterAlertDialog != null && parameterAlertDialog.isShowing()) parameterAlertDialog.dismiss();
        parameterAlertDialog = null;
        if (criticalAlertDialog != null && criticalAlertDialog.isShowing()) criticalAlertDialog.dismiss();
        criticalAlertDialog = null;
        pendingCriticalAlerts.clear();
        super.onDestroy();
    }
}
