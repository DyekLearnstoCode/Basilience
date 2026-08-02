package com.example.basilience;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import java.util.HashMap;
import java.util.Map;

import android.widget.LinearLayout;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.DocumentSnapshot;
import android.app.AlertDialog;
import java.util.ArrayList;
import java.util.List;

public class Personnel_Details_Fragment extends Fragment {

    private EditText etName, etRole, etEmail, etPhone;
    private Button btnEdit, btnSave, btnDelete, btnResetPassword, btnAssignDevice;
    private LinearLayout layoutAssignedDevices;
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
        btnAssignDevice = view.findViewById(R.id.btnAssignDevice);
        layoutAssignedDevices = view.findViewById(R.id.layoutAssignedDevices);

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
                    etName.setText(safe(doc.getString("fullName"))); // Changed from "name"
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

        btnDelete.setOnClickListener(v -> NotificationHelper.showConfirmation(
                requireContext(),
                "Remove Personnel",
                "Are you sure you want to remove this personnel? This will also unlink their account.",
                () -> deletePersonnel(navController)
        ));

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

        btnAssignDevice.setOnClickListener(v -> showAssignDeviceDialog());

        loadAssignments();
    }

    private void loadAssignments() {
        if (personnelId == null) return;
        layoutAssignedDevices.removeAllViews();

        helper.getAssignmentsForUser(personnelId)
                .addOnSuccessListener(qs -> {
                    for (DocumentSnapshot doc : qs.getDocuments()) {
                        String deviceId = doc.getString("deviceId");
                        addDeviceViewToLayout(deviceId);
                    }
                });
    }

    private void addDeviceViewToLayout(String deviceId) {
        View deviceView = getLayoutInflater().inflate(R.layout.item_assigned_device_card, layoutAssignedDevices, false);
        TextView tvId = deviceView.findViewById(R.id.tvDeviceId);
        ImageView btnRemove = deviceView.findViewById(R.id.btnRemoveDevice);

        tvId.setText(deviceId);
        btnRemove.setOnClickListener(v -> {
            NotificationHelper.showConfirmation(requireContext(), "Remove Assignment",
                    "Are you sure you want to remove " + deviceId + " from this user?",
                    () -> {
                        showLoading(true, "Removing assignment...");
                        helper.removeAssignment(deviceId, personnelId)
                                .addOnSuccessListener(unused -> {
                                    showLoading(false, null);
                                    layoutAssignedDevices.removeView(deviceView);
                                })
                                .addOnFailureListener(e -> {
                                    showLoading(false, null);
                                    NotificationHelper.showError(requireContext(), "Failed: " + e.getMessage());
                                });
                    });
        });

        layoutAssignedDevices.addView(deviceView);
    }

    private void showAssignDeviceDialog() {
        helper.getMyDevices().addOnSuccessListener(qs -> {
            if (qs.isEmpty()) {
                NotificationHelper.showError(requireContext(), "You have no claimed devices to assign.");
                return;
            }

            List<String> deviceIds = new ArrayList<>();
            for (DocumentSnapshot doc : qs.getDocuments()) {
                deviceIds.add(doc.getId());
            }

            String[] items = deviceIds.toArray(new String[0]);
            NotificationHelper.showSelectionDialog(requireContext(), "Assign Device", items, index -> {
                String selectedId = items[index];
                helper.assignDeviceToUser(selectedId, personnelId, RoleConstants.ROLE_FARMER)
                        .addOnSuccessListener(unused -> {
                            NotificationHelper.showSuccess(requireContext(), "Device assigned successfully");
                            loadAssignments();
                        })
                        .addOnFailureListener(e -> NotificationHelper.showError(requireContext(), "Error: " + e.getMessage()));
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

        showLoading(true, getString(R.string.loading_saving));

        Map<String, Object> updates = new HashMap<>();
        updates.put("fullName", name); // Changed from "name"
        updates.put("role", role);
        updates.put("email", email);
        updates.put("phone", phone);

        btnSave.setEnabled(false);

        // This helper method syncs both the admin's subcollection and the personnel's main profile
        helper.updatePersonnelForCurrentAdmin(personnelId, updates)
                .addOnSuccessListener(unused -> {
                    showLoading(false, null);
                    NotificationHelper.showSuccess(requireContext(), "Personnel and Account Synced");
                    Bundle result = new Bundle();
                    result.putBoolean("updated", true);
                    getParentFragmentManager().setFragmentResult("personnel_details_result", result);
                    navController.popBackStack();
                })
                .addOnFailureListener(e -> {
                    showLoading(false, null);
                    btnSave.setEnabled(true);
                    NotificationHelper.showError(requireContext(), "Failed: " + e.getMessage());
                });
    }

    private void deletePersonnel(NavController navController) {
        showLoading(true, getString(R.string.loading_deleting));
        btnDelete.setEnabled(false);

        helper.deletePersonnelForCurrentAdmin(personnelId)
                .addOnSuccessListener(unused -> {
                    showLoading(false, null);
                    NotificationHelper.showSuccess(requireContext(), "Personnel unlinked successfully");
                    Bundle result = new Bundle();
                    result.putBoolean("deleted", true);
                    getParentFragmentManager().setFragmentResult("personnel_details_result", result);
                    navController.popBackStack();
                })
                .addOnFailureListener(e -> {
                    showLoading(false, null);
                    btnDelete.setEnabled(true);
                    NotificationHelper.showError(requireContext(), "Failed: " + e.getMessage());
                });
    }

    private void showLoading(boolean show, String title) {
        if (layoutLoading != null) {
            if (show) {
                if (title != null && tvLoadingTitle != null) tvLoadingTitle.setText(title);
                layoutLoading.setVisibility(View.VISIBLE);
            } else {
                layoutLoading.setVisibility(View.GONE);
            }
        }
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
