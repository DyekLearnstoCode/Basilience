package com.example.basilience;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class Auth_Register_Activity extends AppCompatActivity {

    private TextInputEditText etName, etEmail, etPassword, etConfirmPassword;
    private MaterialButton btnSignup;
    private TextView tvLogin;
    private View layoutLoading;
    private TextView tvLoadingTitle;
    private Database_Helper helper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.auth_register);

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        helper = new Database_Helper();

        etName = findViewById(R.id.etName);
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);
        btnSignup = findViewById(R.id.btnSignup);
        tvLogin = findViewById(R.id.tvLogin);
        layoutLoading = findViewById(R.id.layoutLoading);
        tvLoadingTitle = findViewById(R.id.tvLoadingTitle);

        btnSignup.setOnClickListener(v -> registerUser());
        tvLogin.setOnClickListener(v -> startActivity(new Intent(this, Auth_Login_Activity.class)));
    }

    private void showLoading(boolean show, String message) {
        if (tvLoadingTitle != null && message != null) {
            tvLoadingTitle.setText(message);
        }
        layoutLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        btnSignup.setEnabled(!show);
    }

    private void registerUser() {
        String name = String.valueOf(etName.getText()).trim();
        String email = String.valueOf(etEmail.getText()).trim();
        String password = String.valueOf(etPassword.getText()).trim();
        String confirmPassword = String.valueOf(etConfirmPassword.getText()).trim();

        if (name.isEmpty() || email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            showCustomToast("Please fill all fields");
            return;
        }
        if (!password.equals(confirmPassword)) {
            showCustomToast("Passwords do not match");
            return;
        }

        showLoading(true, "Creating account...");

        // 1) create auth user
        helper.registerAuth(email, password)
                .addOnSuccessListener(res -> {
                    String uid = helper.getCurrentUid();
                    if (uid == null) {
                        showLoading(false, null);
                        showCustomToast("Registration failed: uid is null");
                        return;
                    }

                    showLoading(true, "Saving profile...");

                    // 2) save profile in Firestore
                    helper.createUserProfile(uid, name, email, "admin")
                            .addOnSuccessListener(v -> {
                                showLoading(false, null);
                                showCustomToast("Registration successful");
                                startActivity(new Intent(this, Auth_Login_Activity.class));
                                finish();
                            })
                            .addOnFailureListener(e -> {
                                showLoading(false, null);
                                showCustomToast("Failed to save user data: " + e.getMessage());
                            });
                })
                .addOnFailureListener(e -> {
                    showLoading(false, null);
                    showCustomToast("Registration failed: " + e.getMessage());
                });
    }

    private void showCustomToast(String message) {
        LayoutInflater inflater = getLayoutInflater();
        View layout = inflater.inflate(R.layout.custom_toast, null);

        TextView text = layout.findViewById(R.id.toast_text);
        text.setText(message);

        Toast toast = new Toast(getApplicationContext());
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.setView(layout);
        toast.show();
    }
}