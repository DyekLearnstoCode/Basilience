package com.example.basilience;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.HashMap;
import java.util.Map;

public class AccountFragment extends Fragment {

    private static final String PREFS_NAME = "basilience_prefs";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";

    private TextInputEditText etName, etEmail, etPhone;
    private MaterialButton btnUpdate, btnChangePassword, btnLogout;
    private Database_Helper helper;
    private View layoutLoading;
    private TextView tvLoadingTitle;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.settings_account, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        helper = new Database_Helper();

        etName = view.findViewById(R.id.etName);
        etEmail = view.findViewById(R.id.etEmail);
        etPhone = view.findViewById(R.id.etPhone);
        btnUpdate = view.findViewById(R.id.btnChangeName);
        btnChangePassword = view.findViewById(R.id.btnChangePassword);
        btnLogout = view.findViewById(R.id.btnLogout);

        layoutLoading = view.findViewById(R.id.layoutLoading);
        tvLoadingTitle = view.findViewById(R.id.tvLoadingTitle);

        // Load current user data
        loadUserData();

        btnUpdate.setOnClickListener(v -> updateProfile());
        btnChangePassword.setOnClickListener(v -> changePassword());
        btnLogout.setOnClickListener(v -> logout());

        // Back button from layout_header
        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> {
                androidx.navigation.Navigation.findNavController(view).popBackStack();
            });
        }
    }

    private void loadUserData() {
        String uid = helper.getCurrentUid();
        if (uid == null) return;

        helper.getUserProfile(uid).addOnSuccessListener(document -> {
            if (document.exists()) {
                etName.setText(document.getString("name"));
                etEmail.setText(document.getString("email"));
                etPhone.setText(document.getString("phone"));
            }
        }).addOnFailureListener(e -> NotificationHelper.showError(getContext(), "Failed to load profile"));
    }

    private void updateProfile() {
        String uid = helper.getCurrentUid();
        if (uid == null) return;

        String name = etName.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();

        if (name.isEmpty()) {
            NotificationHelper.showError(getContext(), "Name cannot be empty");
            return;
        }

        if (layoutLoading != null) {
            tvLoadingTitle.setText(R.string.loading_saving);
            layoutLoading.setVisibility(View.VISIBLE);
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put("name", name);
        updates.put("phone", phone);

        helper.updateUserProfile(uid, updates)
                .addOnSuccessListener(aVoid -> {
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    NotificationHelper.showSuccess(getContext(), "Profile updated");
                })
                .addOnFailureListener(e -> {
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    NotificationHelper.showError(getContext(), "Update failed: " + e.getMessage());
                });
    }

    private void changePassword() {
        String email = etEmail.getText().toString().trim();
        if (email.isEmpty()) return;

        if (layoutLoading != null) {
            tvLoadingTitle.setText(R.string.loading_please_wait);
            layoutLoading.setVisibility(View.VISIBLE);
        }

        helper.sendPasswordResetEmail(email)
                .addOnSuccessListener(aVoid -> {
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    NotificationHelper.showSuccess(getContext(), "Password reset email sent");
                })
                .addOnFailureListener(e -> {
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    NotificationHelper.showError(getContext(), "Error: " + e.getMessage());
                });
    }

    private void logout() {
        NotificationHelper.showConfirmation(requireContext(), "Logout", "Are you sure you want to log out?", () -> {
            if (layoutLoading != null) {
                tvLoadingTitle.setText(R.string.loading_logging_out);
                layoutLoading.setVisibility(View.VISIBLE);
            }

            // Clear Firebase Auth
            helper.logout();

            // Clear SharedPreferences
            if (getActivity() != null) {
                SharedPreferences borderPrefs = getActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                borderPrefs.edit().putBoolean(KEY_IS_LOGGED_IN, false).apply();

                // Redirect to Login Activity
                Intent intent = new Intent(getActivity(), Auth_Login_Activity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

                // Add a small delay to show the logout animation if it's too fast
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
