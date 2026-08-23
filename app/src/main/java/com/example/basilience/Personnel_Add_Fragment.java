package com.example.basilience;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.text.InputFilter;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.textfield.TextInputLayout;

public class Personnel_Add_Fragment extends Fragment {

    private static final String TAG = "Personnel_Add_Fragment";
    private EditText etName, etEmail, etPhone, etPassword, etConfirm;
    private TextInputLayout layoutName, layoutEmail, layoutPhone, layoutPassword, layoutConfirm;
    private Button btnSave;
    private View layoutLoading;
    private TextView tvLoadingTitle;

    private Database_Helper helper;

    public Personnel_Add_Fragment() {
        super(R.layout.personnel_add);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        helper = new Database_Helper();

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v ->
                    getParentFragmentManager().popBackStack()
            );
        }

        etName = view.findViewById(R.id.etName);
        etEmail = view.findViewById(R.id.etEmail);
        etPhone = view.findViewById(R.id.etPhone);
        etPassword = view.findViewById(R.id.etPassword);
        etConfirm = view.findViewById(R.id.etConfirm);
        layoutName = view.findViewById(R.id.layoutName);
        layoutEmail = view.findViewById(R.id.layoutEmail);
        layoutPhone = view.findViewById(R.id.layoutPhone);
        layoutPassword = view.findViewById(R.id.layoutPassword);
        layoutConfirm = view.findViewById(R.id.layoutConfirm);
        btnSave = view.findViewById(R.id.btnSave);
        layoutLoading = view.findViewById(R.id.layoutLoading);
        tvLoadingTitle = view.findViewById(R.id.tvLoadingTitle);

        btnSave.setOnClickListener(v -> saveFarmer());

        etPhone.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(11)
        });

        if (layoutPassword != null) {
            layoutPassword.setHelperTextEnabled(true);
            layoutPassword.setHelperText(PasswordPolicy.REQUIREMENTS);
        }
    }

    private void saveFarmer() {
        clearFieldErrors();

        String name = etName.getText().toString().trim();
        String email = etEmail.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();
        String password = etPassword.getText().toString();
        String confirm = etConfirm.getText().toString();

        boolean valid = true;
        if (name.isEmpty()) {
            if (layoutName != null) layoutName.setError("Required");
            valid = false;
        }
        if (email.isEmpty()) {
            if (layoutEmail != null) layoutEmail.setError("Required");
            valid = false;
        }
        if (phone.isEmpty()) {
            if (layoutPhone != null) layoutPhone.setError("Required");
            valid = false;
        }
        if (!valid) return;

        String normalizedPhone = PhoneNumberUtils.normalizePhilippineMobile(phone);
        if (normalizedPhone == null) {
            if (layoutPhone != null) layoutPhone.setError(PhoneNumberUtils.INVALID_MESSAGE);
            return;
        }
        phone = normalizedPhone;

        String passwordError = PasswordPolicy.validate(password);
        if (passwordError != null) {
            if (layoutPassword != null) layoutPassword.setError(passwordError);
            etPassword.requestFocus();
            return;
        }

        if (!password.equals(confirm)) {
            if (layoutConfirm != null) layoutConfirm.setError("Passwords do not match");
            return;
        }

        showLoading(true, getString(R.string.loading_creating_account));
        btnSave.setEnabled(false);

        helper.createFarmerAccountAndAssignToCurrentAdmin(name, email, phone, password)
                .addOnSuccessListener(unused -> {
                    showLoading(false, null);
                    Bundle result = new Bundle();
                    result.putBoolean("added", true);
                    getParentFragmentManager().setFragmentResult("personnel_add_result", result);

                    NotificationHelper.showSuccess(requireContext(), "Farmer account created & verification email sent");
                    getParentFragmentManager().popBackStack();
                })
                .addOnFailureListener(e -> {
                    showLoading(false, null);
                    btnSave.setEnabled(true);
                    Log.e(TAG, "Failed to create personnel account", e);
                    String rawMessage = e.getMessage() != null ? e.getMessage().toLowerCase(java.util.Locale.US) : "";
                    String message = (rawMessage.contains("already") && rawMessage.contains("email"))
                            ? "This personnel account already exists. Use Add Existing Personnel instead."
                            : "Unable to create the personnel account. Please try again.";
                    NotificationHelper.showError(requireContext(), message);
                });
    }

    private void clearFieldErrors() {
        if (layoutName != null) layoutName.setError(null);
        if (layoutEmail != null) layoutEmail.setError(null);
        if (layoutPhone != null) layoutPhone.setError(null);
        if (layoutPassword != null) {
            layoutPassword.setError(null);
            // setError() suppresses helper text, so the requirements are put
            // back each time the errors are cleared.
            layoutPassword.setHelperText(PasswordPolicy.REQUIREMENTS);
        }
        if (layoutConfirm != null) layoutConfirm.setError(null);
    }

    private void showLoading(boolean show, String title) {
        if (isAdded() && layoutLoading != null) {
            if (show) {
                if (title != null && tvLoadingTitle != null) tvLoadingTitle.setText(title);
                layoutLoading.setVisibility(View.VISIBLE);
                layoutLoading.bringToFront();
            } else {
                layoutLoading.setVisibility(View.GONE);
            }
        }
    }
}
