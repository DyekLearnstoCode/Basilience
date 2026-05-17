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

public class Personnel_Add_Fragment extends Fragment {

    private EditText etName, etEmail, etPhone, etPassword, etConfirm;
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
        btnSave = view.findViewById(R.id.btnSave);
        layoutLoading = view.findViewById(R.id.layoutLoading);
        tvLoadingTitle = view.findViewById(R.id.tvLoadingTitle);

        btnSave.setOnClickListener(v -> saveFarmer());
    }

    private void saveFarmer() {
        String name = etName.getText().toString().trim();
        String email = etEmail.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();
        String password = etPassword.getText().toString();
        String confirm = etConfirm.getText().toString();

        if (name.isEmpty() || email.isEmpty() || phone.isEmpty()) {
            Toast.makeText(requireContext(), "Fill required fields", Toast.LENGTH_SHORT).show();
            return;
        }

        if (password.length() < 6) {
            Toast.makeText(requireContext(), "Password must be at least 6 characters", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!password.equals(confirm)) {
            Toast.makeText(requireContext(), "Passwords do not match", Toast.LENGTH_SHORT).show();
            return;
        }

        if (layoutLoading != null) {
            tvLoadingTitle.setText(R.string.loading_creating_account);
            layoutLoading.setVisibility(View.VISIBLE);
        }
        btnSave.setEnabled(false);

        helper.createFarmerAccountAndAssignToCurrentAdmin(name, email, phone, password)
                .addOnSuccessListener(unused -> {
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    Bundle result = new Bundle();
                    result.putBoolean("added", true);
                    getParentFragmentManager().setFragmentResult("personnel_add_result", result);

                    Toast.makeText(requireContext(), "Farmer account created", Toast.LENGTH_SHORT).show();
                    getParentFragmentManager().popBackStack();
                })
                .addOnFailureListener(e -> {
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    btnSave.setEnabled(true);
                    Toast.makeText(requireContext(), "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }
}