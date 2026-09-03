package com.example.basilience;

import android.content.SharedPreferences;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WifiConfigFragment extends Fragment {

    private static final String TAG = "WiFiConfig";
    private static final long RECONNECT_CONFIRMATION_TIMEOUT_MS = 45000L;

    private TextInputEditText etSsid, etPassword;
    private TextInputLayout layoutSsid;
    private TextView tvWifiStatus;
    private Button btnSaveWifi, btnCancelWifi;
    private ImageView btnBack;

    private View wifiLoadingOverlay;
    private TextView tvWifiLoadingTitle, tvWifiLoadingStatus;

    private DatabaseReference deviceRef;
    private String selectedDeviceId;
    private ValueEventListener wifiStatusListener;
    private ValueEventListener lastServerSeenListener;
    private boolean isCurrentlyOnline = false;
    private DeviceConnectivityState connectivityState = DeviceConnectivityState.RECONNECTING;
    private Boolean lastReportedWifiConnected = null;
    private boolean setupApReachable = false;
    private boolean setupApCheckInProgress = false;
    private boolean awaitingReconnect = false;
    private boolean reconnectSuccessDialogShown = false;
    private Long lastServerSeen = null;
    private long provisioningAttemptStartTime = 0L;
    private Long provisioningAttemptStartLastSeen = null;
    private String pendingProvisioningSsid = "";
    private boolean initialApCheck = true;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService networkExecutor;

    private final Runnable reconnectTimeout = () -> {
        if (!awaitingReconnect || !isAdded()) return;
        Log.d(TAG, "[WiFiSuccessTrace] reconnect timeout fired");
        awaitingReconnect = false;
        provisioningAttemptStartLastSeen = null;
        hideLoading();
        NotificationHelper.showInfo(requireContext(), "Credentials Saved",
                "The Wi-Fi credentials were saved, but the device has not reconnected yet. " +
                        "If the password is invalid, reconnect to Basilience-Setup and try again.");
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.settings_wifi_config, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        networkExecutor = Executors.newSingleThreadExecutor();

        etSsid = view.findViewById(R.id.etSsid);
        layoutSsid = view.findViewById(R.id.layoutSsid);
        etPassword = view.findViewById(R.id.etPassword);
        tvWifiStatus = view.findViewById(R.id.tvWifiStatus);
        btnSaveWifi = view.findViewById(R.id.btnSaveWifi);
        btnCancelWifi = view.findViewById(R.id.btnCancelWifi);
        btnBack = view.findViewById(R.id.btnBack);

        wifiLoadingOverlay = view.findViewById(R.id.wifiLoadingOverlay);
        tvWifiLoadingTitle = view.findViewById(R.id.tvWifiLoadingTitle);
        tvWifiLoadingStatus = view.findViewById(R.id.tvWifiLoadingStatus);

        NavController navController = Navigation.findNavController(view);

        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }
        btnCancelWifi.setOnClickListener(v -> navController.popBackStack());

        SharedPreferences prefs = requireActivity().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", "");

        if (deviceId.isEmpty()) {
            Toast.makeText(getContext(), "Device ID not found", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedDeviceId = deviceId;

        // Defensive, not redundant - see the matching call/comment in
        // Parameters_Monitoring_Fragment.startRealTimeMonitoring(). Confirmed
        // live bug: after a credential push and device reboot, this screen's
        // status stayed stuck on RECONNECTING even after leaving and
        // returning, because nothing here ever (re-)established monitoring
        // for the singleton itself.
        DeviceConnectionManager.getInstance().monitorDevice(deviceId);

        DeviceConnectionManager.getInstance().getConnectivityState().observe(
                getViewLifecycleOwner(), state -> {
                    connectivityState = state == null
                            ? DeviceConnectivityState.RECONNECTING : state;
                    isCurrentlyOnline = connectivityState == DeviceConnectivityState.ONLINE;
                    if (isCurrentlyOnline && selectedDeviceId != null) {
                        setupApReachable = false;
                        NotificationHelper.clearWifiConfigurationRequiredNotification(
                                requireContext(), selectedDeviceId);
                    }
                    updateStatusUI();
                    maybeConfirmProvisioningReconnect();
                });

        deviceRef = FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app")
                .getReference("devices").child(deviceId);
        Log.d(TAG, "[WiFiSuccessTrace] listener path=devices/" + selectedDeviceId + "/status");

        btnSaveWifi.setOnClickListener(v -> handleSaveCredentials());
        attachWifiStatusListener();
        checkSetupApReachability();

    }

    private void handleSaveCredentials() {
        String ssid = etSsid.getText() != null ? etSsid.getText().toString().trim() : "";
        String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";

        if (layoutSsid != null) layoutSsid.setError(null);
        if (ssid.isEmpty()) {
            if (layoutSsid != null) layoutSsid.setError("Network name cannot be empty");
            return;
        }

        NotificationHelper.showConfirmation(requireContext(),
                "Change Wi-Fi Credentials?",
                "Connect this phone to the 'Basilience-Setup' Wi-Fi network, then Basilience will send the credentials directly to the device over its local setup page. An internet connection is not required for this step.",
                "Send Locally", "Cancel", () -> sendWifiCommandLocal(ssid, password));
    }

    private void sendWifiCommandLocal(String ssid, String password) {
        if (networkExecutor == null || networkExecutor.isShutdown()) return;
        // Capture before /setup so a fast reconnect cannot become this attempt's baseline.
        provisioningAttemptStartTime = System.currentTimeMillis();
        provisioningAttemptStartLastSeen = lastServerSeen;
        reconnectSuccessDialogShown = false;
        showLoading("Sending Wi-Fi configuration...", "Using the Basilience-Setup local Wi-Fi network...");
        btnSaveWifi.setEnabled(false);
        Context appContext = requireContext().getApplicationContext();

        networkExecutor.execute(() -> {
            try {
                int responseCode = LocalProvisioningClient.sendCredentials(appContext, ssid, password);

                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    btnSaveWifi.setEnabled(true);
                    if (responseCode == 200) {
                        // The device accepted the credentials but hasn't actually
                        // reconnected yet - that's confirmed asynchronously by
                        // whichever comes first: attachWifiStatusListener()'s
                        // Firebase listeners noticing a fresh heartbeat on the new
                        // network (maybeConfirmProvisioningReconnect()), the local
                        // AP still answering with a "connected" status
                        // (pollLocalProvisioningStatus()), or reconnectTimeout
                        // firing after RECONNECT_CONFIRMATION_TIMEOUT_MS with a
                        // "saved but not reconnected yet" message. Deliberately NOT
                        // nulling provisioningAttemptStartLastSeen here (unlike the
                        // failure/exception branches below, which do abandon the
                        // attempt) - it's this attempt's baseline for
                        // hasFreshHeartbeatForProvisioningAttempt() and needs to
                        // survive until one of those three outcomes resolves it.
                        awaitingReconnect = true;
                        pendingProvisioningSsid = ssid;
                        etSsid.setText("");
                        etPassword.setText("");
                        showLoading("Waiting for Reconnection...",
                                "Basilience is reconnecting to \"" + ssid + "\"...");
                        mainHandler.removeCallbacks(reconnectTimeout);
                        mainHandler.postDelayed(reconnectTimeout, RECONNECT_CONFIRMATION_TIMEOUT_MS);
                        mainHandler.postDelayed(WifiConfigFragment.this::pollLocalProvisioningStatus, 1000);
                    } else {
                        provisioningAttemptStartLastSeen = null;
                        hideLoading();
                        Log.w(TAG, "Device rejected Wi-Fi configuration, HTTP " + responseCode);
                        NotificationHelper.showError(requireContext(), "Wi-Fi Setup Failed",
                                "The device rejected the Wi-Fi configuration. Please check the network name and password and try again.");
                    }
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    btnSaveWifi.setEnabled(true);
                    provisioningAttemptStartLastSeen = null;
                    hideLoading();
                    NotificationHelper.showError(requireContext(), "Wi-Fi Configuration Failed",
                            "Wi-Fi configuration could not be sent.\nReconnect to Basilience-Setup and try again.");
                });
            }
        });
    }

    private void attachWifiStatusListener() {
        if (deviceRef == null) return;

        wifiStatusListener = deviceRef.child("status").child("wifiConnected").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snapshot) {
                Boolean connected = snapshot.getValue(Boolean.class);
                if (lastReportedWifiConnected == null || !lastReportedWifiConnected.equals(connected)) {
                    Log.d(TAG, "wifiConnected=" + connected);
                }
                lastReportedWifiConnected = connected;
                updateStatusUI();
                maybeConfirmProvisioningReconnect();
            }

            @Override
            public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) {
                lastReportedWifiConnected = null;
                updateStatusUI();
            }
        });

        lastServerSeenListener = deviceRef.child("status").child("lastServerSeen").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snapshot) {
                Long seen = DeviceConnectionManager.readLongValue(snapshot);
                if (seen != null && !seen.equals(lastServerSeen)) {
                    Log.d(TAG, "lastServerSeen updated");
                }
                lastServerSeen = seen;
                maybeConfirmProvisioningReconnect();
            }

            @Override
            public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) {
                Log.w(TAG, "lastServerSeen listener cancelled", error.toException());
            }
        });
    }

    private void maybeConfirmProvisioningReconnect() {
        // Local /status is authoritative for a local provisioning attempt. Firebase
        // state remains available for normal status UI but cannot complete this flow.
        if (awaitingReconnect) return;
        boolean freshHeartbeat = hasFreshHeartbeatForProvisioningAttempt();
        Log.d(TAG, "[WiFiSuccessTrace] pending=" + awaitingReconnect
                + " wifiConnected=" + lastReportedWifiConnected
                + " lastServerSeen=" + lastServerSeen
                + " baseline=" + provisioningAttemptStartLastSeen
                + " freshHeartbeat=" + freshHeartbeat
                + " dialogShown=" + reconnectSuccessDialogShown);
        // Deliberately NOT gated on isCurrentlyOnline/DeviceConnectivityState.ONLINE
        // here (unlike updateStatusUI()'s ordinary connectivity badge, which
        // stays exactly as-is) - that classification requires its own
        // freshness window on top of a periodic 2s re-check, real extra
        // latency on top of the one signal this flow actually needs: a
        // fresh heartbeat since the reconnect attempt started. Waiting for
        // "Online" too was confirming success later than the device had
        // already proven it reconnected.
        Log.d(TAG, "[WiFiSuccessTrace] successCheck = pending && wifiConnected && freshHeartbeat && !dialogShown");

        if (!awaitingReconnect) {
            Log.d(TAG, "[WiFiSuccessTrace] blocked: pending=false");
            return;
        }
        if (!isAdded()) {
            Log.d(TAG, "[WiFiSuccessTrace] blocked: fragment not added");
            return;
        }
        if (!Boolean.TRUE.equals(lastReportedWifiConnected)) {
            Log.d(TAG, "[WiFiSuccessTrace] blocked: wifiConnected=" + lastReportedWifiConnected);
            return;
        }
        if (!freshHeartbeat) {
            Log.d(TAG, "[WiFiSuccessTrace] blocked: lastServerSeen not newer");
            return;
        }
        if (reconnectSuccessDialogShown) {
            Log.d(TAG, "[WiFiSuccessTrace] blocked: dialogShown=true");
            return;
        }

        awaitingReconnect = false;
        reconnectSuccessDialogShown = true;
        mainHandler.removeCallbacks(reconnectTimeout);
        hideLoading();
        Log.d(TAG, "[WiFiSuccessTrace] SHOWING WIFI CONNECTED DIALOG");
        NotificationHelper.showSuccessAcknowledgement(requireContext(), "Wi-Fi Connected",
                "Your Basilience device is now connected to the new Wi-Fi network.", null);
        Log.d(TAG, "[WiFiSuccessTrace] WIFI CONNECTED DIALOG display call returned");
    }

    private void pollLocalProvisioningStatus() {
        if (!awaitingReconnect || networkExecutor == null || networkExecutor.isShutdown()) return;
        Context appContext = requireContext().getApplicationContext();
        networkExecutor.execute(() -> {
            try {
                LocalProvisioningClient.ProvisioningStatus status =
                        LocalProvisioningClient.getProvisioningStatus(appContext);
                mainHandler.post(() -> handleLocalProvisioningStatus(status));
            } catch (Exception ignored) {
                Log.d(TAG, "[PROVISION-TRACE] Android /status failed: " + ignored);
                // The AP can be briefly busy while switching to AP+STA; retry while bounded.
                mainHandler.postDelayed(this::pollLocalProvisioningStatus, 1000);
            }
        });
    }

    private void handleLocalProvisioningStatus(LocalProvisioningClient.ProvisioningStatus status) {
        Log.d(TAG, "[PROVISION-TRACE] connected result delivered to WifiConfigFragment");
        Log.d(TAG, "[PROVISION-TRACE] fragmentAdded=" + isAdded()
                + " activityAvailable=" + (getActivity() != null)
                + " pollingAttemptActive=" + awaitingReconnect
                + " successDialogShown=" + reconnectSuccessDialogShown);
        if (!isAdded() || !awaitingReconnect) return;
        if (status.connected || "connected".equals(status.status)) {
            awaitingReconnect = false;
            reconnectSuccessDialogShown = true;
            mainHandler.removeCallbacks(reconnectTimeout);
            hideLoading();
            String connectedSsid = status.ssid.isEmpty() ? pendingProvisioningSsid : status.ssid;
            Log.d(TAG, "[PROVISION-TRACE] SHOW WIFI CONNECTED DIALOG");
            NotificationHelper.showSuccessAcknowledgement(requireContext(), "Wi-Fi Connected",
                    "Your Basilience device is now connected to " + connectedSsid + ".", null);
            Log.d(TAG, "[PROVISION-TRACE] WIFI CONNECTED DIALOG SHOWN");
            return;
        }
        if ("connection_failed".equals(status.status)) {
            Log.d(TAG, "[PROVISION-TRACE] provisioning failed");
            awaitingReconnect = false;
            mainHandler.removeCallbacks(reconnectTimeout);
            hideLoading();
            NotificationHelper.showError(requireContext(), "Wi-Fi Connection Failed",
                    "The device could not connect to the selected Wi-Fi network. Check the network name and password, then try again.");
            return;
        }
        mainHandler.postDelayed(this::pollLocalProvisioningStatus, 1000);
    }

    private boolean hasFreshHeartbeatForProvisioningAttempt() {
        if (lastServerSeen == null) return false;
        if (provisioningAttemptStartLastSeen != null) {
            return lastServerSeen > provisioningAttemptStartLastSeen;
        }
        return lastServerSeen >= provisioningAttemptStartTime;
    }

    private void checkSetupApReachability() {
        if (networkExecutor == null || networkExecutor.isShutdown() || awaitingReconnect
                || setupApCheckInProgress) return;
        setupApCheckInProgress = true;
        final String requestDeviceId = selectedDeviceId;
        final boolean showDetectionLoading = initialApCheck;
        if (showDetectionLoading) {
            initialApCheck = false;
            showLoading("Checking Device Setup Mode...", "Checking the local Wi-Fi network...");
        }
        Context appContext = requireContext().getApplicationContext();
        networkExecutor.execute(() -> {
            boolean reachable = LocalProvisioningClient.isSetupApReachable(appContext);

            mainHandler.post(() -> {
                setupApCheckInProgress = false;
                if (!isAdded()) return;
                String currentDeviceId = requireContext().getSharedPreferences(
                        "basilience_prefs", Context.MODE_PRIVATE)
                        .getString("selected_device_id", null);
                if (requestDeviceId == null || !requestDeviceId.equals(currentDeviceId)) return;
                if (isCurrentlyOnline) {
                    setupApReachable = false;
                    updateStatusUI();
                    return;
                }
                setupApReachable = reachable;
                if (reachable) {
                    NotificationHelper.showWifiConfigurationRequiredNotification(
                            requireContext(), requestDeviceId);
                    MainActivity.onLocalSetupApConfirmed(requestDeviceId);
                }
                if (showDetectionLoading) {
                    hideLoading();
                    if (!reachable && !isCurrentlyOnline) {
                        NotificationHelper.showError(requireContext(), "Unable to Reach Device",
                                "Make sure your phone is connected to Basilience-Setup.");
                    }
                }
                updateStatusUI();
            });
        });
    }

    private long wifiLoadingShownAt;

    private void showLoading(String title, String status) {
        if (isAdded() && wifiLoadingOverlay != null) {
            wifiLoadingShownAt = SystemClock.elapsedRealtime();
            wifiLoadingOverlay.setVisibility(View.VISIBLE);
            wifiLoadingOverlay.bringToFront();
            if (tvWifiLoadingTitle != null) tvWifiLoadingTitle.setText(title);
            if (tvWifiLoadingStatus != null) {
                tvWifiLoadingStatus.setText(status);
                tvWifiLoadingStatus.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
            }
        }
    }

    private void hideLoading() {
        if (isAdded() && wifiLoadingOverlay != null && wifiLoadingOverlay.getVisibility() == View.VISIBLE) {
            NotificationHelper.hideLoaderAfterMinimumDuration(wifiLoadingShownAt, () -> {
                if (isAdded() && wifiLoadingOverlay != null) wifiLoadingOverlay.setVisibility(View.GONE);
            });
        }
    }

    private void updateStatusUI() {
        if (tvWifiStatus == null || !isAdded()) return;

        if (setupApReachable) {
            tvWifiStatus.setText("● WI-FI CONFIGURATION REQUIRED");
            tvWifiStatus.setTextColor(androidx.core.content.ContextCompat.getColor(
                    requireContext(), R.color.device_status_reconnecting));
        } else {
            tvWifiStatus.setText("● " + connectivityState.getLabel());
            tvWifiStatus.setTextColor(androidx.core.content.ContextCompat.getColor(
                    requireContext(), connectivityState.getColorRes()));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        checkSetupApReachability();
    }

    @Override
    public void onDestroyView() {
        awaitingReconnect = false;
        provisioningAttemptStartLastSeen = null;
        mainHandler.removeCallbacksAndMessages(null);
        if (networkExecutor != null) {
            networkExecutor.shutdownNow();
            networkExecutor = null;
        }
        if (deviceRef != null && wifiStatusListener != null) {
            deviceRef.child("status").child("wifiConnected").removeEventListener(wifiStatusListener);
        }
        if (deviceRef != null && lastServerSeenListener != null) {
            deviceRef.child("status").child("lastServerSeen").removeEventListener(lastServerSeenListener);
        }
        super.onDestroyView();
    }
}
