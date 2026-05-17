package com.example.basilience;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import java.util.HashMap;
import java.util.Map;

public class Personnel_Details_Fragment extends Fragment {

    private EditText etName, etRole, etEmail, etPhone;
    private Button btnEdit, btnSave, btnDelete, btnResetPassword;
    private View layoutLoading;
    private TextView tvLoadingTitle;

    private Database_Helper helper;
    private String personnelId;

    public Personnel_Details_Fragment() {
        super(R.layout.personnel_details);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        helper = new Database_Helper();
        NavController navController = Navigation.findNavController(view);

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }

        etName = view.findViewById(R.id.etName);
        etRole = view.findViewById(R.id.etRole);
        etEmail = view.findViewById(R.id.etEmail);
        etPhone = view.findViewById(R.id.etPhone);

        btnEdit = view.findViewById(R.id.btnEdit);
        btnSave = view.findViewById(R.id.btnSave);
        btnDelete = view.findViewById(R.id.btnDelete);
        btnResetPassword = view.findViewById(R.id.btnResetPassword);

        layoutLoading = view.findViewById(R.id.layoutLoading);
        tvLoadingTitle = view.findViewById(R.id.tvLoadingTitle);

        btnSave.setVisibility(View.GONE);
        setEditable(false);

        personnelId = getArguments() != null ? getArguments().getString("personnelId") : null;
        if (personnelId == null || personnelId.isEmpty()) {
            NotificationHelper.showError(requireContext(), "Missing personnelId");
            navController.popBackStack();
            return;
        }

        // Load latest data from Firestore
        helper.getPersonnelForCurrentAdmin(personnelId)
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) {
                        NotificationHelper.showError(requireContext(), "Personnel not found");
                        navController.popBackStack();
                        return;
                    }
                    etName.setText(safe(doc.getString("name")));
                    etRole.setText(safe(doc.getString("role")));
                    etEmail.setText(safe(doc.getString("email")));
                    etPhone.setText(safe(doc.getString("phone")));
                })
                .addOnFailureListener(e ->
                        NotificationHelper.showError(requireContext(), "Failed: " + e.getMessage())
                );

        btnEdit.setOnClickListener(v -> {
            setEditable(true);
            btnEdit.setVisibility(View.GONE);
            btnSave.setVisibility(View.VISIBLE);
        });

        btnSave.setOnClickListener(v -> saveChanges(navController));

        btnDelete.setOnClickListener(v -> new android.app.AlertDialog.Builder(requireContext())
                .setTitle("Remove Personnel")
                .setMessage("Are you sure you want to remove this personnel? This will also unlink their account.")
                .setPositiveButton("Yes", (dialog, which) -> deletePersonnel(navController))
                .setNegativeButton("Cancel", null)
                .show());

        btnResetPassword.setOnClickListener(v -> {
            String email = etEmail.getText().toString().trim();
            if (email.isEmpty()) return;

            btnResetPassword.setEnabled(false);
            helper.sendPasswordResetEmail(email)
                    .addOnSuccessListener(unused -> {
                        NotificationHelper.showSuccess(requireContext(), "Reset email sent to " + email);
                        btnResetPassword.setEnabled(true);
                    })
                    .addOnFailureListener(e -> {
                        NotificationHelper.showError(requireContext(), "Error: " + e.getMessage());
                        btnResetPassword.setEnabled(true);
                    });
        });
    }

    private void saveChanges(NavController navController) {
        String name = etName.getText().toString().trim();
        String role = etRole.getText().toString().trim();
        String email = etEmail.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();

        if (name.isEmpty() || email.isEmpty() || phone.isEmpty()) {
            NotificationHelper.showError(requireContext(), "Fill required fields");
            return;
        }

        if (layoutLoading != null) {
            tvLoadingTitle.setText(R.string.loading_saving);
            layoutLoading.setVisibility(View.VISIBLE);
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put("name", name);
        updates.put("role", role);
        updates.put("email", email);
        updates.put("phone", phone);

        btnSave.setEnabled(false);

        // This helper method syncs both the admin's subcollection and the personnel's main profile
        helper.updatePersonnelForCurrentAdmin(personnelId, updates)
                .addOnSuccessListener(unused -> {
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    NotificationHelper.showSuccess(requireContext(), "Personnel and Account Synced");
                    Bundle result = new Bundle();
                    result.putBoolean("updated", true);
                    getParentFragmentManager().setFragmentResult("personnel_details_result", result);
                    navController.popBackStack();
                })
                .addOnFailureListener(e -> {
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    btnSave.setEnabled(true);
                    NotificationHelper.showError(requireContext(), "Failed: " + e.getMessage());
                });
    }

    private void deletePersonnel(NavController navController) {
        if (layoutLoading != null) {
            tvLoadingTitle.setText(R.string.loading_deleting);
            layoutLoading.setVisibility(View.VISIBLE);
        }
        btnDelete.setEnabled(false);

        helper.deletePersonnelForCurrentAdmin(personnelId)
                .addOnSuccessListener(unused -> {
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    NotificationHelper.showSuccess(requireContext(), "Personnel unlinked successfully");
                    Bundle result = new Bundle();
                    result.putBoolean("deleted", true);
                    getParentFragmentManager().setFragmentResult("personnel_details_result", result);
                    navController.popBackStack();
                })
                .addOnFailureListener(e -> {
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    btnDelete.setEnabled(true);
                    NotificationHelper.showError(requireContext(), "Failed: " + e.getMessage());
                });
    }

    private void setEditable(boolean enabled) {
        EditText[] fields = { etName, etRole, etPhone };
        for (EditText field : fields) {
            field.setEnabled(enabled);
            field.setFocusable(enabled);
            field.setFocusableInTouchMode(enabled);
            field.setCursorVisible(enabled);
        }
        // Email is used as the Login ID and cannot be changed here to maintain sync with Auth.
        etEmail.setEnabled(false);
    }

    private String safe(String v) { return v != null ? v : ""; }
}