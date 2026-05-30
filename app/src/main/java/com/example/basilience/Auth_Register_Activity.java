package com.example.basilience;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
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
        if (tvLoadingTitle != null && message != null) tvLoadingTitle.setText(message);
        layoutLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        btnSignup.setEnabled(!show);
    }

    private void registerUser() {
        String name = String.valueOf(etName.getText()).trim();
        String email = String.valueOf(etEmail.getText()).trim();
        String password = String.valueOf(etPassword.getText()).trim();
        String confirmPassword = String.valueOf(etConfirmPassword.getText()).trim();

        if (name.isEmpty() || email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            NotificationHelper.showError(this, "Please fill all fields");
            return;
        }
        if (!password.equals(confirmPassword)) {
            NotificationHelper.showError(this, "Passwords do not match");
            return;
        }

        showLoading(true, "Creating account...");

        helper.registerAuth(email, password)
                .addOnSuccessListener(authResult -> {
                    String uid = helper.getCurrentUid();
                    if (uid == null) {
                        showLoading(false, null);
                        NotificationHelper.showError(this, "Registration failed: uid is null");
                        return;
                    }
                    helper.createUserProfile(uid, name, email, "admin")
                            .addOnSuccessListener(unused -> {
                                helper.sendEmailVerification(new Database_Helper.EmailVerificationCallback() {
                                    @Override
                                    public void onSuccess() {
                                        showLoading(false, null);
                                        showVerifyEmailDialog();
                                    }
                                    @Override
                                    public void onFailure(String errorMessage) {
                                        showLoading(false, null);
                                        NotificationHelper.showError(
                                                Auth_Register_Activity.this,
                                                "Failed to send verification email: " + errorMessage
                                        );
                                        helper.logout();
                                        gotoLogin();
                                    }
                                });
                            })
                            .addOnFailureListener(e -> {
                                showLoading(false, null);
                                NotificationHelper.showError(
                                        Auth_Register_Activity.this,
                                        "Failed to save user profile: " + e.getMessage()
                                );
                                helper.logout();
                                gotoLogin();
                            });
                })
                .addOnFailureListener(e -> {
                    showLoading(false, null);
                    NotificationHelper.showError(this, "Registration failed: " + e.getMessage());
                });
    }

    // This dialog appears centered and waits for user
    private void showVerifyEmailDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Registration Complete")
                .setMessage("Please check your email to verify your account before logging in.")
                .setCancelable(false)
                .setPositiveButton("OK", (dialog, which) -> {
                    helper.logout();
                    gotoLogin();
                })
                .show();
    }

    private void gotoLogin() {
        startActivity(new Intent(Auth_Register_Activity.this, Auth_Login_Activity.class));
        finish();
    }
}