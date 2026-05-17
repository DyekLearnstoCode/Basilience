package com.example.basilience;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

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
            Toast.makeText(requireContext(), "Missing personnelId", Toast.LENGTH_LONG).show();
            navController.popBackStack();
            return;
        }

        // Load latest data from Firestore
        helper.getPersonnelForCurrentAdmin(personnelId)
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) {
                        Toast.makeText(requireContext(), "Personnel not found", Toast.LENGTH_LONG).show();
                        navController.popBackStack();
                        return;
                    }
                    etName.setText(safe(doc.getString("name")));
                    etRole.setText(safe(doc.getString("role")));
                    etEmail.setText(safe(doc.getString("email")));
                    etPhone.setText(safe(doc.getString("phone")));
                })
                .addOnFailureListener(e ->
                        Toast.makeText(requireContext(), "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
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
                        Toast.makeText(requireContext(), "Reset email sent to " + email, Toast.LENGTH_LONG).show();
                        btnResetPassword.setEnabled(true);
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
            Toast.makeText(requireContext(), "Fill required fields", Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(requireContext(), "Personnel and Account Synced", Toast.LENGTH_SHORT).show();
                    Bundle result = new Bundle();
                    result.putBoolean("updated", true);
                    getParentFragmentManager().setFragmentResult("personnel_details_result", result);
                    navController.popBackStack();
                })
                .addOnFailureListener(e -> {
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    btnSave.setEnabled(true);
                    Toast.makeText(requireContext(), "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
                    Toast.makeText(requireContext(), "Personnel unlinked successfully", Toast.LENGTH_SHORT).show();
                    Bundle result = new Bundle();
                    result.putBoolean("deleted", true);
                    getParentFragmentManager().setFragmentResult("personnel_details_result", result);
                    navController.popBackStack();
                })
                .addOnFailureListener(e -> {
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    btnDelete.setEnabled(true);
                    Toast.makeText(requireContext(), "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
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