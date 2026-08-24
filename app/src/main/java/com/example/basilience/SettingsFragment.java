package com.example.basilience;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import com.google.firebase.database.FirebaseDatabase;

public class SettingsFragment extends Fragment {
    private static final String PREFS_NAME = "basilience_prefs";
    private static final String KEY_DEVELOPER_MODE_ENABLED = "developer_mode_enabled";

    private View devOptionsContainer;

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

        // Each settings row navigates from anywhere on the card. The circular
        // arrow inside the row is decoration only (see settings_main.xml), so
        // the tap can never be handled twice.

        // Account Information
        bindSettingsRow(view, R.id.rowAccount, navController, R.id.action_settings_to_accountFragment);

        // Parameter Target Ranges - a normal cultivation setting, so it sits
        // with the other Settings rows rather than under Developer Options.
        bindSettingsRow(view, R.id.rowTargetRanges, navController,
                R.id.action_settings_to_targetRangesFragment);

        // About Basilience
        bindSettingsRow(view, R.id.rowAbout, navController, R.id.action_settings_to_aboutFragment);

        // Terms and Agreements
        bindSettingsRow(view, R.id.rowTerms, navController, R.id.action_settings_to_tosFragment);

        // Privacy Policy - sits with Terms; both render from LegalContent.
        bindSettingsRow(view, R.id.rowPrivacy, navController, R.id.action_settings_to_privacyPolicyFragment);

        // Developer Options - the row is also the visibility container, so the
        // admin/dev-mode gating below is unchanged.
        devOptionsContainer = view.findViewById(R.id.devOptionsContainer);
        bindSettingsRow(view, R.id.devOptionsContainer, navController, R.id.action_settings_to_devOptionsFragment);
        updateDeveloperOptionsVisibility();

        // Logout
        View btnLogout = view.findViewById(R.id.btnLogout);
        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> performLogout());
        }
    }

    /** Attaches navigation to the whole settings row rather than just its arrow. */
    private void bindSettingsRow(View root, int rowId, NavController navController, int actionId) {
        View row = root.findViewById(rowId);
        if (row == null) return;
        row.setOnClickListener(v -> navController.navigate(actionId));
    }

    @Override
    public void onResume() {
        super.onResume();
        updateDeveloperOptionsVisibility();
    }

    private void updateDeveloperOptionsVisibility() {
        if (devOptionsContainer == null || getContext() == null) return;

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean isAdmin = "ADMIN".equalsIgnoreCase(prefs.getString("user_role", ""));
        if (!isAdmin) {
            devOptionsContainer.setVisibility(View.GONE);
            return;
        }
        boolean developerModeEnabled = prefs.getBoolean(KEY_DEVELOPER_MODE_ENABLED, false);
        devOptionsContainer.setVisibility(developerModeEnabled ? View.VISIBLE : View.GONE);

        String deviceId = prefs.getString("selected_device_id", null);
        if (deviceId == null || deviceId.trim().isEmpty()) return;
        FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app")
                .getReference("devices").child(deviceId).child("settings").child("devModeEnabled")
                .get().addOnSuccessListener(snapshot -> {
                    if (!isAdded() || devOptionsContainer == null) return;
                    boolean enabled = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                    prefs.edit().putBoolean(KEY_DEVELOPER_MODE_ENABLED, enabled).apply();
                    devOptionsContainer.setVisibility(enabled ? View.VISIBLE : View.GONE);
                });
    }

    private void performLogout() {
        NotificationHelper.showConfirmation(requireContext(), "Logout", "Are you sure you want to log out?", () -> {
            Database_Helper helper = new Database_Helper();
            helper.logout().addOnCompleteListener(task -> {
                if (!isAdded() || getActivity() == null) return;
                android.content.SharedPreferences prefs = getActivity().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
                // Clear only session/identity state (same keys Auth_Login_Activity's own
                // clearInvalidSession() removes) - a blanket clear() would also wipe
                // device-local UI state like onboarding completion, which must survive
                // logout so the walkthrough doesn't reappear for a returning user.
                prefs.edit()
                        .remove("is_logged_in")
                        .remove("user_role")
                        .remove("owner_uid")
                        .remove("selected_device_id")
                        .apply();

                android.content.Intent intent = new android.content.Intent(getActivity(), Auth_Login_Activity.class);
                intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                getActivity().finish();
            });
        });
    }
}
