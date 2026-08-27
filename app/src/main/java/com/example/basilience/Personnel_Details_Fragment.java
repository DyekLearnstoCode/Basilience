package com.example.basilience;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class Personnel_Details_Fragment extends Fragment {

    private static final String TAG = "Personnel_Details_Fragment";

    private TextInputEditText etName, etRole, etEmail, etPhone;
    private TextView tvName, tvRole, tvEmail, tvPhone, tvDateAdded, tvLoadingTitle;
    private MaterialButton btnSave, btnDelete;
    private View layoutViewMode, layoutEditMode, layoutLoading;
    private LinearLayout layoutAssignedDevices;

    private Database_Helper helper;
    private String personnelId;
    private String savedName = "";
    private String savedRole = "";
    private String savedEmail = "";
    private String savedPhone = "";
    private Long createdAt;

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

        layoutViewMode = view.findViewById(R.id.layoutPersonnelView);
        layoutEditMode = view.findViewById(R.id.layoutPersonnelEdit);
        layoutLoading = view.findViewById(R.id.layoutLoading);
        tvLoadingTitle = view.findViewById(R.id.tvLoadingTitle);
        tvName = view.findViewById(R.id.tvPersonnelName);
        tvRole = view.findViewById(R.id.tvPersonnelRole);
        tvEmail = view.findViewById(R.id.tvPersonnelEmail);
        tvPhone = view.findViewById(R.id.tvPersonnelPhone);
        tvDateAdded = view.findViewById(R.id.tvPersonnelDateAdded);
        etName = view.findViewById(R.id.etName);
        etRole = view.findViewById(R.id.etRole);
        etEmail = view.findViewById(R.id.etEmail);
        etPhone = view.findViewById(R.id.etPhone);
        btnSave = view.findViewById(R.id.btnSave);
        btnDelete = view.findViewById(R.id.btnDelete);
        layoutAssignedDevices = view.findViewById(R.id.layoutAssignedDevices);

        layoutLoading = view.findViewById(R.id.layoutLoading);
        tvLoadingTitle = view.findViewById(R.id.tvLoadingTitle);


        personnelId = getArguments() != null ? getArguments().getString("personnelId") : null;
        if (personnelId == null || personnelId.isEmpty()) {
            NotificationHelper.showError(requireContext(), "Missing personnelId");
            navController.popBackStack();
            return;
        }

        view.findViewById(R.id.btnEdit).setOnClickListener(v -> showEditMode());
        view.findViewById(R.id.btnCancelEdit).setOnClickListener(v -> showViewMode());
        btnSave.setOnClickListener(v -> saveChanges());
        view.findViewById(R.id.btnResetPassword).setOnClickListener(v -> showChangePasswordDialog());
        view.findViewById(R.id.btnAssignDevice).setOnClickListener(v -> showAssignDeviceDialog());
        btnDelete.setOnClickListener(v -> NotificationHelper.showDestructiveConfirmation(
                requireContext(), "Unlink Personnel",
                "This will remove the personnel from your account but will not delete their Basilience account. Their device access will also be removed.",
                "Unlink", () -> deletePersonnel(navController)));

        showViewMode();
        loadPersonnel(navController);
        loadAssignments();
    }

    private void loadPersonnel(NavController navController) {
        showLoading(true, "Loading personnel...");
        helper.getPersonnelForCurrentAdmin(personnelId)
                .addOnSuccessListener(doc -> {
                    if (!isAdded()) return;
                    showLoading(false, null);
                    if (!doc.exists()) {
                        NotificationHelper.showError(requireContext(), "Personnel not found");
                        navController.popBackStack();
                        return;
                    }
                    savedName = safe(doc.getString("fullName"));
                    savedRole = safe(doc.getString("role"));
                    savedEmail = safe(doc.getString("email"));
                    savedPhone = safe(doc.getString("phone"));
                    createdAt = readEpochMillis(doc, "createdAt");
                    renderSavedValues();
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    showLoading(false, null);
                    Log.e(TAG, "Failed to load personnel record", e);
                    NotificationHelper.showError(requireContext(), "Unable to load this personnel record. Please try again.");
                });
    }

    private void showEditMode() {
        populateEditFields();
        // Entering edit mode has to unlock the inputs as well as swap the
        // panels: the edit panel was previously shown with its fields still
        // disabled from setup, so nothing in it could be typed into and the
        // Save button stayed hidden.
        setEditable(true);
        btnSave.setVisibility(View.VISIBLE);
        layoutViewMode.setVisibility(View.GONE);
        layoutEditMode.setVisibility(View.VISIBLE);
        etName.requestFocus();
    }

    private void showViewMode() {
        populateEditFields();
        setEditable(false);
        layoutEditMode.setVisibility(View.GONE);
        layoutViewMode.setVisibility(View.VISIBLE);
    }

    private void populateEditFields() {
        etName.setText(savedName);
        etRole.setText(RoleConstants.displayName(savedRole));
        etEmail.setText(savedEmail);
        etPhone.setText(savedPhone);
    }

    private void renderSavedValues() {
        tvName.setText(display(savedName));
        tvRole.setText(RoleConstants.displayName(savedRole));
        tvEmail.setText(display(savedEmail));
        tvPhone.setText(display(savedPhone));
        tvDateAdded.setText(formatDate(createdAt));
        populateEditFields();
    }

    private void loadAssignments() {
        layoutAssignedDevices.removeAllViews();
        helper.getAssignmentsForUser(personnelId)
                .addOnSuccessListener(assignments -> {
                    if (!isAdded()) return;
                    for (DocumentSnapshot assignment : assignments.getDocuments()) {
                        String deviceId = assignment.getString("deviceId");
                        if (deviceId != null) {
                            addDeviceViewToLayout(deviceId,
                                    readEpochMillis(assignment, "assignedAt"));
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to load assigned devices", e);
                    if (isAdded()) NotificationHelper.showError(requireContext(),
                            "Unable to load assigned devices. Please try again.");
                });
    }

    private void addDeviceViewToLayout(String deviceId, Long assignedAt) {
        View deviceView = getLayoutInflater().inflate(
                R.layout.item_assigned_device_card, layoutAssignedDevices, false);
        TextView tvDeviceName = deviceView.findViewById(R.id.tvDeviceName);
        TextView tvDeviceId = deviceView.findViewById(R.id.tvDeviceId);
        TextView tvStatus = deviceView.findViewById(R.id.tvDeviceStatus);
        TextView tvAssigned = deviceView.findViewById(R.id.tvDateAssigned);
        ImageView btnRemove = deviceView.findViewById(R.id.btnRemoveDevice);

        tvDeviceName.setText("Device");
        tvDeviceId.setText("Device ID: " + deviceId);
        tvStatus.setText("Status: —");
        tvAssigned.setText("Date Assigned: " + formatDate(assignedAt));

        helper.getDeviceDocument(deviceId).addOnSuccessListener(device -> {
            if (!isAdded() || !device.exists()) return;
            String name = device.getString("deviceName");
            if (name == null || name.trim().isEmpty()) name = device.getString("name");
            tvDeviceName.setText(display(name));
            tvStatus.setText("Status: " + display(device.getString("status")));
        });

        btnRemove.setOnClickListener(v -> NotificationHelper.showDestructiveConfirmation(
                requireContext(), "Remove Assignment",
                "Are you sure you want to remove " + deviceId + " from this user?",
                "Remove", () -> {
                    showLoading(true, "Removing assignment...");
                    helper.removeAssignment(deviceId, personnelId)
                            .addOnSuccessListener(unused -> {
                                if (!isAdded()) return;
                                showLoading(false, null);
                                layoutAssignedDevices.removeView(deviceView);
                            })
                            .addOnFailureListener(e -> {
                                if (!isAdded()) return;
                                showLoading(false, null);
                                Log.e(TAG, "Failed to remove device assignment", e);
                                NotificationHelper.showError(requireContext(), "Unable to remove this device assignment. Please try again.");
                            });
                }));

        layoutAssignedDevices.addView(deviceView);
    }

    private void showAssignDeviceDialog() {
        helper.getMyDevices().addOnSuccessListener(devices -> {
            if (!isAdded()) return;
            if (devices.isEmpty()) {
                NotificationHelper.showError(requireContext(), "You have no claimed devices to assign.");
                return;
            }

            List<String> deviceIds = new ArrayList<>();
            for (DocumentSnapshot device : devices.getDocuments()) deviceIds.add(device.getId());
            helper.getAssignmentsForUser(personnelId).addOnSuccessListener(assignments -> {
                if (!isAdded()) return;
                java.util.HashSet<String> assignedIds = new java.util.HashSet<>();
                for (DocumentSnapshot assignment : assignments) {
                    String id = assignment.getString("deviceId");
                    if (id != null) assignedIds.add(id);
                }
                String[] labels = new String[deviceIds.size()];
                for (int i = 0; i < deviceIds.size(); i++) {
                    labels[i] = assignedIds.contains(deviceIds.get(i))
                            ? deviceIds.get(i) + " (Assigned)" : deviceIds.get(i);
                }
                NotificationHelper.showSelectionDialog(requireContext(), "Assign Device", labels, index -> {
                    String selectedId = deviceIds.get(index);
                    if (assignedIds.contains(selectedId)) {
                        NotificationHelper.showInfo(requireContext(), "Already Assigned",
                                "Device is already assigned to this personnel.");
                        return;
                    }
                    showLoading(true, "Assigning device...");
                    helper.assignDeviceToUser(selectedId, personnelId, RoleConstants.ROLE_FARMER)
                            .addOnSuccessListener(unused -> {
                                if (!isAdded()) return;
                                showLoading(false, null);
                                NotificationHelper.showSuccess(requireContext(), "Device assigned successfully");
                                loadAssignments();
                            })
                            .addOnFailureListener(e -> {
                                if (!isAdded()) return;
                                showLoading(false, null);
                                Log.e(TAG, "Failed to assign device", e);
                                NotificationHelper.showError(requireContext(), "Unable to assign this device. Please try again.");
                            });
                });
            }).addOnFailureListener(e -> {
                Log.e(TAG, "Failed to load existing assignments", e);
                if (isAdded()) NotificationHelper.showError(requireContext(),
                        "Unable to load existing assignments. Please try again.");
            });
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to load claimed devices", e);
            if (isAdded()) NotificationHelper.showError(requireContext(),
                    "Unable to load your devices. Please try again.");
        });
    }

    private void saveChanges() {
        String name = value(etName);
        String rawPhone = value(etPhone);
        if (name.isEmpty()) {
            etName.setError("Personnel name is required");
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

        btnSave.setEnabled(false);
        showLoading(true, getString(R.string.loading_saving));
        helper.updatePersonnelForCurrentAdmin(personnelId, updates)
                .addOnSuccessListener(unused -> {
                    if (!isAdded()) return;
                    showLoading(false, null);
                    btnSave.setEnabled(true);
                    savedName = name;
                    savedPhone = phone;
                    renderSavedValues();
                    showViewMode();
                    Bundle result = new Bundle();
                    result.putBoolean("updated", true);
                    getParentFragmentManager().setFragmentResult("personnel_details_result", result);
                    NotificationHelper.showSuccess(requireContext(), "Personnel updated");
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    showLoading(false, null);
                    btnSave.setEnabled(true);
                    Log.e(TAG, "Failed to save personnel edits", e);
                    NotificationHelper.showError(requireContext(), "Unable to save your changes. Please try again.");
                });
    }

    private void showChangePasswordDialog() {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_change_password, null);
        TextInputLayout currentLayout = content.findViewById(R.id.layoutCurrentPassword);
        currentLayout.setHint("Admin Password");
        TextInputEditText adminPassword = content.findViewById(R.id.etCurrentPassword);
        TextInputEditText newPassword = content.findViewById(R.id.etNewPassword);
        TextInputEditText confirm = content.findViewById(R.id.etConfirmNewPassword);
        TextInputLayout newLayout = content.findViewById(R.id.layoutNewPassword);
        TextInputLayout confirmLayout = content.findViewById(R.id.layoutConfirmNewPassword);
        if (newLayout != null) newLayout.setHelperText(PasswordPolicy.REQUIREMENTS);

        NotificationHelper.showCustomViewDialog(requireContext(), "Change Personnel Password",
                "Confirm your Admin password, then set a new password for this Personnel account.",
                content, "Save New Password", "Cancel", (dialog, ignored) -> {
                    String adminSecret = value(adminPassword);
                    String next = value(newPassword);
                    String confirmation = value(confirm);
                    if (adminSecret.isEmpty()) {
                        adminPassword.setError("Admin password is required");
                        return;
                    }
                    if (newLayout != null) newLayout.setError(null);
                    if (confirmLayout != null) confirmLayout.setError(null);

                    String passwordError = PasswordPolicy.validate(next);
                    if (passwordError != null) {
                        if (newLayout != null) newLayout.setError(passwordError);
                        else newPassword.setError(passwordError);
                        return;
                    }
                    if (!next.equals(confirmation)) {
                        if (confirmLayout != null) confirmLayout.setError("Passwords do not match");
                        else confirm.setError("Passwords do not match");
                        return;
                    }

                    FirebaseUser admin = FirebaseAuth.getInstance().getCurrentUser();
                    if (admin == null || admin.getEmail() == null) {
                        NotificationHelper.showError(requireContext(), "Unable to verify the Admin account.");
                        return;
                    }

                    dialog.dismiss();
                    showLoading(true, "Changing Personnel password...");
                    admin.reauthenticate(EmailAuthProvider.getCredential(admin.getEmail(), adminSecret))
                            .onSuccessTask(unused -> helper.changePersonnelPassword(personnelId, next))
                            .addOnSuccessListener(unused -> {
                                if (!isAdded()) return;
                                showLoading(false, null);
                                NotificationHelper.showSuccess(requireContext(),
                                        "Personnel password changed successfully.");
                            })
                            .addOnFailureListener(e -> {
                                if (!isAdded()) return;
                                showLoading(false, null);
                                String message = e instanceof FirebaseAuthInvalidCredentialsException
                                        ? "The Admin password is incorrect."
                                        : "Unable to change this personnel's password. Please try again.";
                                if (!(e instanceof FirebaseAuthInvalidCredentialsException)) {
                                    Log.e(TAG, "Failed to change personnel password", e);
                                }
                                NotificationHelper.showError(requireContext(), message);
                            });
                });
    }

    private void deletePersonnel(NavController navController) {
        showLoading(true, getString(R.string.loading_deleting));
        btnDelete.setEnabled(false);
        helper.deletePersonnelForCurrentAdmin(personnelId)
                .addOnSuccessListener(unused -> {
                    if (!isAdded()) return;
                    showLoading(false, null);
                    NotificationHelper.showSuccess(requireContext(), "Personnel unlinked successfully");
                    Bundle result = new Bundle();
                    result.putBoolean("deleted", true);
                    getParentFragmentManager().setFragmentResult("personnel_details_result", result);
                    navController.popBackStack();
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    showLoading(false, null);
                    btnDelete.setEnabled(true);
                    Log.e(TAG, "Failed to delete personnel", e);
                    NotificationHelper.showError(requireContext(), "Unable to remove this personnel. Please try again.");
                });
    }

    private long layoutLoadingShownAt;

    private void showLoading(boolean show, String title) {
        if (!isAdded() || layoutLoading == null) return;
        if (show) {
            if (title != null && tvLoadingTitle != null) tvLoadingTitle.setText(title);
            layoutLoadingShownAt = SystemClock.elapsedRealtime();
            layoutLoading.setVisibility(View.VISIBLE);
            layoutLoading.bringToFront();
        } else if (layoutLoading.getVisibility() == View.VISIBLE) {
            NotificationHelper.hideLoaderAfterMinimumDuration(layoutLoadingShownAt, () -> {
                if (isAdded() && layoutLoading != null) layoutLoading.setVisibility(View.GONE);
            });
        }
    }

    /**
     * Unlocks only the fields this screen actually saves.
     *
     * saveChanges() writes fullName and phone, so those are the two editable
     * fields. Email is the Login ID and must stay in sync with Firebase Auth,
     * and Position is a role assignment rather than profile text - both are
     * shown read-only for context. Passwords are never edited here; they go
     * through the separate Change Password dialog.
     */
    private void setEditable(boolean enabled) {
        EditText[] editableFields = { etName, etPhone };
        for (EditText field : editableFields) {
            field.setEnabled(enabled);
            field.setFocusable(enabled);
            field.setFocusableInTouchMode(enabled);
            field.setCursorVisible(enabled);
        }
        // Email is used as the Login ID and cannot be changed here to maintain sync with Auth.
        etEmail.setEnabled(false);
        etRole.setEnabled(false);
    }

    private static Long readEpochMillis(DocumentSnapshot doc, String field) {
        Object value = doc.get(field);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof Timestamp) return ((Timestamp) value).toDate().getTime();
        return null;

    }

    private static String formatDate(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) return "—";
        SimpleDateFormat format = new SimpleDateFormat("MMM d, yyyy", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Manila"));
        return format.format(new Date(epochMillis));
    }

    private static String value(EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String display(String value) {
        return value == null || value.trim().isEmpty() ? "—" : value;
    }
}
