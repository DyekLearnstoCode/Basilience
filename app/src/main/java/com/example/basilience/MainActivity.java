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
import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.lang.ref.WeakReference;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_OPEN_WIFI_CONFIGURATION = "open_wifi_configuration";

    private static WeakReference<MainActivity> foregroundActivity = new WeakReference<>(null);
    private static final Map<String, String> activeParameterAlerts = new LinkedHashMap<>();
    private static final Set<String> presentedParameterEventIds = new HashSet<>();
    private static String parameterAlertDeviceId;
    private NotificationHelper.UpdatableParameterDialog parameterAlertDialog;
    private androidx.appcompat.app.AlertDialog criticalAlertDialog;
    private androidx.appcompat.app.AlertDialog connectivityRecoveryDialog;
    private androidx.appcompat.app.AlertDialog automationLifecycleDialog;
    private boolean criticalAlertShowing;
    private final ArrayDeque<CriticalAlertRequest> pendingCriticalAlerts = new ArrayDeque<>();
    private CriticalAlertRequest activeCriticalAlert;
    private final ArrayDeque<AutomationLifecycleRequest> pendingAutomationLifecycleAlerts =
            new ArrayDeque<>();
    private AutomationLifecycleRequest activeAutomationLifecycleAlert;

    private static final String DEVICE_UNREACHABLE_TITLE = "Basilience Device Unreachable";

    private static final class CriticalAlertRequest {
        final String title;
        final String message;
        final boolean connectivity;

        CriticalAlertRequest(String title, String message) {
            this.title = title;
            this.message = message;
            this.connectivity = DEVICE_UNREACHABLE_TITLE.equals(title);
        }
    }

    private static final class AutomationLifecycleRequest {
        final String title;
        final String message;
        final String eventId;
        final String episodeId;
        final boolean success;

        AutomationLifecycleRequest(String title, String message, String eventId,
                                   String episodeId, boolean success) {
            this.title = title;
            this.message = message;
            this.eventId = eventId;
            this.episodeId = episodeId;
            this.success = success;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
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
        
        DeviceConnectionManager.getInstance().getConnectivityState().observe(this, state -> {
            // Presence UI observes backend-owned status. Cloud Functions alone create
            // persistent alert history and send FCM events.
            if (state == DeviceConnectivityState.ONLINE) {
                String selectedDeviceId = prefs.getString("selected_device_id", null);
                if (selectedDeviceId != null) {
                    NotificationHelper.clearWifiConfigurationRequiredNotification(
                            this, selectedDeviceId);
                }
                clearStaleConnectivityOutageAlerts();
            } else if (state == DeviceConnectivityState.OFFLINE) {
                String selectedDeviceId = prefs.getString("selected_device_id", null);
                if (selectedDeviceId != null) {
                    NotificationHelper.recordCloudConnectivityPresentation(
                            this, selectedDeviceId, true);
                }
            }
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

            openWifiConfigurationIfRequested(navController, getIntent());

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
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (host != null) openWifiConfigurationIfRequested(host.getNavController(), intent);
    }

    private void openWifiConfigurationIfRequested(NavController controller, android.content.Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_OPEN_WIFI_CONFIGURATION, false)) return;
        intent.removeExtra(EXTRA_OPEN_WIFI_CONFIGURATION);
        if (controller.getCurrentDestination() == null
                || controller.getCurrentDestination().getId() != R.id.wifiConfigFragment) {
            controller.navigate(R.id.wifiConfigFragment);
        }
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

        activity.runOnUiThread(() -> {
            if (DEVICE_UNREACHABLE_TITLE.equals(title)) {
                activity.enqueueConnectivityCriticalAlert(title, message);
            } else {
                activity.enqueueCriticalAlert(title, message);
            }
        });
        return true;
    }

    public static boolean showForegroundRecovery(String title, String message, String eventId) {
        MainActivity activity = foregroundActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;

        String stableId = eventId == null || eventId.trim().isEmpty()
                ? title + "|" + message
                : eventId;
        SharedPreferences prefs = activity.getSharedPreferences("basilience_prefs", MODE_PRIVATE);
        Set<String> shownIds = new HashSet<>(prefs.getStringSet(
                "shown_recovery_alert_ids", new HashSet<>()));
        if (shownIds.contains(stableId)) return true;
        shownIds.add(stableId);
        prefs.edit().putStringSet("shown_recovery_alert_ids", shownIds).apply();

        activity.runOnUiThread(() -> activity.showConnectivityRecovery(title, message));
        return true;
    }

    public static boolean showForegroundAutomationLifecycle(
            String title,
            String message,
            String eventId,
            String lifecycleKind) {
        MainActivity activity = foregroundActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;

        boolean success = "SUCCESS".equals(lifecycleKind);
        if (!success && !"START".equals(lifecycleKind)) return false;

        String stableId = eventId == null || eventId.trim().isEmpty()
                ? title + "|" + message
                : eventId;
        String episodeId = automationLifecycleEpisodeId(stableId);
        SharedPreferences prefs = activity.getSharedPreferences("basilience_prefs", MODE_PRIVATE);
        Set<String> completedEpisodes = new HashSet<>(prefs.getStringSet(
                "completed_automation_lifecycle_episodes", new HashSet<>()));
        if (!success && completedEpisodes.contains(episodeId)) return true;

        Set<String> shownIds = new HashSet<>(prefs.getStringSet(
                "shown_automation_lifecycle_ids", new HashSet<>()));
        if (shownIds.contains(stableId)) return true;
        shownIds.add(stableId);

        SharedPreferences.Editor editor = prefs.edit()
                .putStringSet("shown_automation_lifecycle_ids", shownIds);
        if (success) {
            completedEpisodes.add(episodeId);
            editor.putStringSet("completed_automation_lifecycle_episodes", completedEpisodes);
        }
        editor.apply();

        AutomationLifecycleRequest request = new AutomationLifecycleRequest(
                title, message, stableId, episodeId, success);
        activity.runOnUiThread(() -> activity.enqueueAutomationLifecycleAlert(request));
        return true;
    }

    private static String automationLifecycleEpisodeId(String eventId) {
        if (eventId.endsWith("_start")) {
            return eventId.substring(0, eventId.length() - "_start".length());
        }
        if (eventId.endsWith("_success")) {
            return eventId.substring(0, eventId.length() - "_success".length());
        }
        return eventId;
    }

    public static void onLocalSetupApConfirmed(String deviceId) {
        MainActivity activity = foregroundActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || deviceId == null) return;
        String selectedDeviceId = activity.getSharedPreferences(
                "basilience_prefs", MODE_PRIVATE).getString("selected_device_id", null);
        if (!deviceId.equals(selectedDeviceId)) return;

        DeviceConnectivityState currentState = DeviceConnectionManager.getInstance()
                .getConnectivityState().getValue();
        if (currentState == DeviceConnectivityState.ONLINE
                || !NotificationHelper.isSetupApRecentlyConfirmed(activity, deviceId)) {
            return;
        }

        activity.runOnUiThread(() -> {
            String currentSelectedDeviceId = activity.getSharedPreferences(
                    "basilience_prefs", MODE_PRIVATE).getString("selected_device_id", null);
            DeviceConnectivityState latestState = DeviceConnectionManager.getInstance()
                    .getConnectivityState().getValue();
            if (!deviceId.equals(currentSelectedDeviceId)
                    || latestState != DeviceConnectivityState.OFFLINE
                    || !NotificationHelper.isSetupApRecentlyConfirmed(activity, deviceId)
                    || !activity.hasDeviceUnreachablePresentation(deviceId)) {
                return;
            }
            activity.clearStaleConnectivityOutageAlerts();
            NotificationHelper.cancelCloudConnectivityNotification(activity, deviceId);
            NotificationHelper.markCloudConnectivitySupersededByOrange(activity, deviceId);
        });
    }

    private boolean hasDeviceUnreachablePresentation(String deviceId) {
        if (activeCriticalAlert != null && activeCriticalAlert.connectivity) return true;
        for (CriticalAlertRequest request : pendingCriticalAlerts) {
            if (request.connectivity) return true;
        }
        return NotificationHelper.isCloudConnectivityPresentationOffline(this, deviceId);
    }

    private void enqueueConnectivityCriticalAlert(String title, String message) {
        dismissConnectivityRecovery();
        pendingCriticalAlerts.removeIf(request -> request.connectivity);
        if (activeCriticalAlert != null && activeCriticalAlert.connectivity
                && title.equals(activeCriticalAlert.title)) {
            return;
        }
        enqueueCriticalAlert(title, message);
    }

    private void showConnectivityRecovery(String title, String message) {
        pendingCriticalAlerts.removeIf(request -> request.connectivity);
        if (activeCriticalAlert != null && activeCriticalAlert.connectivity) {
            if (criticalAlertDialog != null && criticalAlertDialog.isShowing()) {
                criticalAlertDialog.dismiss();
            }
            criticalAlertDialog = null;
            activeCriticalAlert = null;
            criticalAlertShowing = false;
        }

        dismissConnectivityRecovery();
        connectivityRecoveryDialog = NotificationHelper.showSuccessAcknowledgement(
                this, title, message, () -> {
                    connectivityRecoveryDialog = null;
                    showNextCriticalAlert();
                });
    }

    private void dismissConnectivityRecovery() {
        if (connectivityRecoveryDialog != null && connectivityRecoveryDialog.isShowing()) {
            connectivityRecoveryDialog.dismiss();
        }
        connectivityRecoveryDialog = null;
    }

    private void clearStaleConnectivityOutageAlerts() {
        pendingCriticalAlerts.removeIf(request -> request.connectivity);
        if (activeCriticalAlert == null || !activeCriticalAlert.connectivity) return;

        if (criticalAlertDialog != null && criticalAlertDialog.isShowing()) {
            criticalAlertDialog.dismiss();
        }
        criticalAlertDialog = null;
        activeCriticalAlert = null;
        criticalAlertShowing = false;

        if (connectivityRecoveryDialog == null || !connectivityRecoveryDialog.isShowing()) {
            showNextCriticalAlert();
        }
    }

    private void enqueueCriticalAlert(String title, String message) {
        deferAutomationLifecycleAlertForCritical();
        pendingCriticalAlerts.add(new CriticalAlertRequest(title, message));
        dismissParameterAlertForCritical();
        showNextCriticalAlert();
    }

    private void showNextCriticalAlert() {
        if (criticalAlertShowing) return;
        CriticalAlertRequest request = pendingCriticalAlerts.poll();
        if (request == null) {
            if (!showNextAutomationLifecycleAlert()) {
                showPendingParameterAlertIfNeeded();
            }
            return;
        }

        criticalAlertShowing = true;
        activeCriticalAlert = request;
        criticalAlertDialog = NotificationHelper.showCriticalAlert(this, request.title,
                request.message, () -> {
                    criticalAlertShowing = false;
                    criticalAlertDialog = null;
                    activeCriticalAlert = null;
                    showNextCriticalAlert();
                });
        if (criticalAlertDialog == null) {
            criticalAlertShowing = false;
            activeCriticalAlert = null;
            showNextCriticalAlert();
        }
    }

    private void enqueueAutomationLifecycleAlert(AutomationLifecycleRequest request) {
        if (request.success) {
            pendingAutomationLifecycleAlerts.removeIf(pending ->
                    !pending.success && pending.episodeId.equals(request.episodeId));

            if (activeAutomationLifecycleAlert != null
                    && !activeAutomationLifecycleAlert.success
                    && activeAutomationLifecycleAlert.episodeId.equals(request.episodeId)) {
                if (automationLifecycleDialog != null && automationLifecycleDialog.isShowing()) {
                    automationLifecycleDialog.dismiss();
                }
                automationLifecycleDialog = null;
                activeAutomationLifecycleAlert = null;
            }
        }

        pendingAutomationLifecycleAlerts.add(request);
        if (criticalAlertShowing || !pendingCriticalAlerts.isEmpty()) return;
        showNextAutomationLifecycleAlert();
    }

    private boolean showNextAutomationLifecycleAlert() {
        if (criticalAlertShowing || !pendingCriticalAlerts.isEmpty()) return false;
        if (automationLifecycleDialog != null && automationLifecycleDialog.isShowing()) return true;

        AutomationLifecycleRequest request = pendingAutomationLifecycleAlerts.poll();
        if (request == null) return false;

        dismissParameterAlertForCritical();
        activeAutomationLifecycleAlert = request;
        NotificationHelper.DialogCallback acknowledged = () -> {
            automationLifecycleDialog = null;
            activeAutomationLifecycleAlert = null;
            if (!showNextAutomationLifecycleAlert()) {
                showPendingParameterAlertIfNeeded();
            }
        };

        automationLifecycleDialog = request.success
                ? NotificationHelper.showSuccessAcknowledgement(
                        this, request.title, request.message, acknowledged)
                : NotificationHelper.showAutomationAcknowledgement(
                        this, request.title, request.message, acknowledged);

        if (automationLifecycleDialog == null) {
            activeAutomationLifecycleAlert = null;
            return showNextAutomationLifecycleAlert();
        }
        return true;
    }

    private void deferAutomationLifecycleAlertForCritical() {
        if (activeAutomationLifecycleAlert == null) return;

        pendingAutomationLifecycleAlerts.addFirst(activeAutomationLifecycleAlert);
        if (automationLifecycleDialog != null && automationLifecycleDialog.isShowing()) {
            automationLifecycleDialog.dismiss();
        }
        automationLifecycleDialog = null;
        activeAutomationLifecycleAlert = null;
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
                currentParameterAlertStates.put("ecHigh",
                        Boolean.TRUE.equals(snapshot.child("ecHigh").getValue(Boolean.class)));
                currentParameterAlertStates.put("phLow",
                        Boolean.TRUE.equals(snapshot.child("phLow").getValue(Boolean.class)));
                currentParameterAlertStates.put("phHigh",
                        Boolean.TRUE.equals(snapshot.child("phHigh").getValue(Boolean.class)));
                currentParameterAlertStates.put("lowAirTemperature",
                        Boolean.TRUE.equals(snapshot.child("lowAirTemperature").getValue(Boolean.class)));
                currentParameterAlertStates.put("highTemperature",
                        Boolean.TRUE.equals(snapshot.child("highTemperature").getValue(Boolean.class)));
                currentParameterAlertStates.put("waterTempOutOfRange",
                        Boolean.TRUE.equals(snapshot.child("waterTempOutOfRange").getValue(Boolean.class)));
                currentParameterAlertStates.put("waterTempLow",
                        Boolean.TRUE.equals(snapshot.child("waterTempLow").getValue(Boolean.class)));
                currentParameterAlertStates.put("humidityLow",
                        Boolean.TRUE.equals(snapshot.child("humidityLow").getValue(Boolean.class)));
                currentParameterAlertStates.put("humidityHigh",
                        Boolean.TRUE.equals(snapshot.child("humidityHigh").getValue(Boolean.class)));
                currentParameterAlertStates.put("waterLevelLow",
                        Boolean.TRUE.equals(snapshot.child("waterLevelLow").getValue(Boolean.class)));
                currentParameterAlertStates.put("waterLevelHigh",
                        Boolean.TRUE.equals(snapshot.child("waterLevelHigh").getValue(Boolean.class)));
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
            if ("EC — High".equals(only)) return "Electrical conductivity is above the acceptable range.";
            if ("pH — Low".equals(only)) return "pH is below the configured range.";
            if ("pH — High".equals(only)) return "pH is above the configured range.";
            if ("Air Temperature — Low".equals(only)) return "Air temperature is below the acceptable range.";
            if ("Air Temperature — High".equals(only)) return "Air Temperature is above the configured range.";
            if ("Water Temperature — Low".equals(only)) return "Water temperature is below the configured minimum range.";
            if ("Humidity — Low".equals(only)) return "Humidity is below the configured minimum range.";
            if ("Humidity — High".equals(only)) return "Humidity is above the configured maximum range.";
            if ("Water Level — High".equals(only)) return "Reservoir water level is above the configured maximum range.";
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
        if ("ecHigh".equalsIgnoreCase(type)) return "EC — High";
        if ("phLow".equalsIgnoreCase(type)) return "pH — Low";
        if ("phHigh".equalsIgnoreCase(type)) return "pH — High";
        if ("lowAirTemperature".equalsIgnoreCase(type)) return "Air Temperature — Low";
        if ("highTemperature".equalsIgnoreCase(type)) return "Air Temperature — High";
        if ("waterTempOutOfRange".equalsIgnoreCase(type)) return "Water Temperature — Out of Range";
        if ("waterTempLow".equalsIgnoreCase(type)) return "Water Temperature — Low";
        if ("humidityLow".equalsIgnoreCase(type)) return "Humidity — Low";
        if ("humidityHigh".equalsIgnoreCase(type)) return "Humidity — High";
        if ("waterLevelLow".equalsIgnoreCase(type)) return "Water Level — Low";
        if ("waterLevelHigh".equalsIgnoreCase(type)) return "Water Level — High";
        return null;
    }

    @Override
    protected void onDestroy() {
        stopCurrentParameterAlertListener();
        if (parameterAlertDialog != null && parameterAlertDialog.isShowing()) parameterAlertDialog.dismiss();
        parameterAlertDialog = null;
        if (criticalAlertDialog != null && criticalAlertDialog.isShowing()) criticalAlertDialog.dismiss();
        criticalAlertDialog = null;
        if (automationLifecycleDialog != null && automationLifecycleDialog.isShowing()) {
            automationLifecycleDialog.dismiss();
        }
        automationLifecycleDialog = null;
        activeAutomationLifecycleAlert = null;
        pendingAutomationLifecycleAlerts.clear();
        dismissConnectivityRecovery();
        activeCriticalAlert = null;
        pendingCriticalAlerts.clear();
        super.onDestroy();
    }
}
