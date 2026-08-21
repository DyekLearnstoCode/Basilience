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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseUser;

import java.util.HashMap;
import java.util.Map;

public class AccountFragment extends Fragment {

    private static final String TAG = "AccountFragment";
    private static final String PREFS_NAME = "basilience_prefs";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";

    private View layoutViewMode, layoutEditMode, layoutLoading;
    private TextView tvNameValue, tvEmailValue, tvPhoneValue, tvLoadingTitle;
    private TextInputEditText etName, etEmail, etPhone;
    private MaterialButton btnSaveProfile, btnLogout;
    private Database_Helper helper;

    private String savedName = "";
    private String savedEmail = "";
    private String savedPhone = "";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.settings_account, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        helper = new Database_Helper();

        layoutViewMode = view.findViewById(R.id.layoutAccountView);
        layoutEditMode = view.findViewById(R.id.layoutAccountEdit);
        layoutLoading = view.findViewById(R.id.layoutLoading);
        tvLoadingTitle = view.findViewById(R.id.tvLoadingTitle);
        tvNameValue = view.findViewById(R.id.tvNameValue);
        tvEmailValue = view.findViewById(R.id.tvEmailValue);
        tvPhoneValue = view.findViewById(R.id.tvPhoneValue);
        etName = view.findViewById(R.id.etName);
        etEmail = view.findViewById(R.id.etEmail);
        etPhone = view.findViewById(R.id.etPhone);
        btnSaveProfile = view.findViewById(R.id.btnSaveProfile);
        btnLogout = view.findViewById(R.id.btnLogout);

        view.findViewById(R.id.btnEditProfile).setOnClickListener(v -> showEditMode());
        view.findViewById(R.id.btnCancelEdit).setOnClickListener(v -> showViewMode());
        btnSaveProfile.setOnClickListener(v -> updateProfile());
        view.findViewById(R.id.btnChangePassword).setOnClickListener(v -> showChangePasswordDialog());
        btnLogout.setOnClickListener(v -> logout());

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v ->
                    androidx.navigation.Navigation.findNavController(view).popBackStack());
        }

        showViewMode();
        loadUserData();
    }

    private void loadUserData() {
        String uid = helper.getCurrentUid();
        if (uid == null) return;
        showLoading(true, "Loading account...");
        helper.getUserProfile(uid).addOnSuccessListener(document -> {
            if (!isAdded()) return;
            showLoading(false, null);
            if (!document.exists()) {
                NotificationHelper.showError(requireContext(), "Account profile not found.");
                return;
            }
            savedName = safe(document.getString("fullName"));
            savedEmail = safe(document.getString("email"));
            savedPhone = safe(document.getString("phone"));
            renderSavedValues();
        }).addOnFailureListener(e -> {
            if (!isAdded()) return;
            showLoading(false, null);
            Log.e(TAG, "Failed to load profile", e);
            NotificationHelper.showError(requireContext(), "Unable to load your profile right now. Please try again.");
        });
    }

    private void showEditMode() {
        populateEditFields();
        layoutViewMode.setVisibility(View.GONE);
        layoutEditMode.setVisibility(View.VISIBLE);
    }

    private void showViewMode() {
        populateEditFields();
        layoutEditMode.setVisibility(View.GONE);
        layoutViewMode.setVisibility(View.VISIBLE);
    }

    private void populateEditFields() {
        etName.setText(savedName);
        etEmail.setText(savedEmail);
        etPhone.setText(savedPhone);
    }

    private void renderSavedValues() {
        tvNameValue.setText(display(savedName));
        tvEmailValue.setText(display(savedEmail));
        tvPhoneValue.setText(display(savedPhone));
        populateEditFields();
    }

    private void updateProfile() {
        String uid = helper.getCurrentUid();
        if (uid == null) return;

        String name = value(etName);
        String rawPhone = value(etPhone);
        if (name.isEmpty()) {
            etName.setError("Full name is required");
            return;
        }

        final String phone;
        if (!rawPhone.isEmpty()) {
            String normalizedPhone = PhoneNumberUtils.normalizePhilippineMobile(rawPhone);
            if (normalizedPhone == null) {
                etPhone.setError(PhoneNumberUtils.INVALID_MESSAGE);
                return;
            }
            phone = normalizedPhone;
        } else {
            phone = rawPhone;
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put("fullName", name);
        updates.put("phone", phone);

        btnSaveProfile.setEnabled(false);
        showLoading(true, getString(R.string.loading_saving));
        helper.updateUserProfile(uid, updates)
                .addOnSuccessListener(unused -> {
                    if (!isAdded()) return;
                    showLoading(false, null);
                    btnSaveProfile.setEnabled(true);
                    savedName = name;
                    savedPhone = phone;
                    renderSavedValues();
                    showViewMode();
                    NotificationHelper.showSuccess(requireContext(), "Profile updated");
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    showLoading(false, null);
                    btnSaveProfile.setEnabled(true);
                    Log.e(TAG, "Failed to update profile", e);
                    NotificationHelper.showError(requireContext(), "Unable to save your changes. Please try again.");
                });
    }

    private void showChangePasswordDialog() {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_change_password, null);
        TextInputEditText current = content.findViewById(R.id.etCurrentPassword);
        TextInputEditText next = content.findViewById(R.id.etNewPassword);
        TextInputEditText confirm = content.findViewById(R.id.etConfirmNewPassword);

        NotificationHelper.showCustomViewDialog(requireContext(), "Change Password",
                "Confirm your current password before choosing a new one.", content,
                "Change Password", "Cancel", (dialog, ignored) -> {
                    String oldPassword = value(current);
                    String newPassword = value(next);
                    String confirmation = value(confirm);
                    if (oldPassword.isEmpty()) {
                        current.setError("Old password is required");
                        return;
                    }
                    if (newPassword.length() < 6) {
                        next.setError("Use at least 6 characters");
                        return;
                    }
                    if (!newPassword.equals(confirmation)) {
                        confirm.setError("Passwords do not match");
                        return;
                    }

                    FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                    if (user == null || user.getEmail() == null) {
                        NotificationHelper.showError(requireContext(), "Unable to verify the current account.");
                        return;
                    }

                    dialog.dismiss();
                    showLoading(true, "Changing password...");
                    user.reauthenticate(EmailAuthProvider.getCredential(user.getEmail(), oldPassword))
                            .onSuccessTask(unused -> user.updatePassword(newPassword))
                            .addOnSuccessListener(unused -> {
                                if (!isAdded()) return;
                                showLoading(false, null);
                                NotificationHelper.showSuccess(requireContext(), "Password changed successfully.");
                            })
                            .addOnFailureListener(e -> {
                                if (!isAdded()) return;
                                showLoading(false, null);
                                String message = e instanceof FirebaseAuthInvalidCredentialsException
                                        ? "The old password is incorrect."
                                        : "Unable to change your password. Please try again.";
                                if (!(e instanceof FirebaseAuthInvalidCredentialsException)) {
                                    Log.e(TAG, "Failed to change password", e);
                                }
                                NotificationHelper.showError(requireContext(), message);
                            });
                });
    }

    private void logout() {
        NotificationHelper.showConfirmation(requireContext(), "Logout",
                "Are you sure you want to log out?", () -> {
                    showLoading(true, getString(R.string.loading_logging_out));
                    helper.logout().addOnCompleteListener(task -> {
                        if (!isAdded() || getActivity() == null) return;
                        SharedPreferences prefs = getActivity()
                                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        prefs.edit().putBoolean(KEY_IS_LOGGED_IN, false).apply();
                        Intent intent = new Intent(getActivity(), Auth_Login_Activity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        requireActivity().finish();
                    });
                });
    }

    private void showLoading(boolean show, String title) {
        if (!isAdded() || layoutLoading == null) return;
        if (show && title != null && tvLoadingTitle != null) tvLoadingTitle.setText(title);
        layoutLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) layoutLoading.bringToFront();
    }

    private static String value(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String display(String value) {
        return value == null || value.trim().isEmpty() ? "—" : value;
    }
}
