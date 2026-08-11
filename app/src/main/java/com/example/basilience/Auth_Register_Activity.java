package com.example.basilience;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
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

        // Siguraduhing ligtas ang Database_Helper constructor mo kung walang context na ipinapasa
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
        tvLogin.setOnClickListener(v -> finish());
    }

    private void showLoading(boolean show, String message) {
        if (isFinishing() || isDestroyed()) return;
        if (tvLoadingTitle != null && message != null) tvLoadingTitle.setText(message);
        if (layoutLoading != null) layoutLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && layoutLoading != null) layoutLoading.bringToFront();
        if (btnSignup != null) btnSignup.setEnabled(!show);
    }

    private void registerUser() {
        String name = String.valueOf(etName.getText()).trim();
        String email = String.valueOf(etEmail.getText()).trim();
        String password = String.valueOf(etPassword.getText()).trim();
        String confirmPassword = String.valueOf(etConfirmPassword.getText()).trim();

        // 1. Basic empty validations
        if (name.isEmpty() || email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            NotificationHelper.showError(this, "Please fill all fields");
            return;
        }

        // 2. Email format validation
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            NotificationHelper.showError(this, "Please enter a valid email address");
            return;
        }

        // 3. Password minimum length validation
        if (password.length() < 6) {
            NotificationHelper.showError(this, "Password must be at least 6 characters");
            return;
        }

        if (!password.equals(confirmPassword)) {
            NotificationHelper.showError(this, "Passwords do not match");
            return;
        }

        showLoading(true, "Creating account...");

        helper.registerAuth(email, password)
                .addOnSuccessListener(authResult -> {
                    // Check if activity is still alive
                    if (isFinishing() || isDestroyed()) return;

                    String uid = helper.getCurrentUid();
                    if (uid == null) {
                        showLoading(false, null);
                        NotificationHelper.showError(this, "Registration failed: uid is null");
                        return;
                    }

                    helper.createUserProfile(uid, name, email, "", RoleConstants.ROLE_ADMIN, null)
                            .addOnSuccessListener(unused -> {
                                if (isFinishing() || isDestroyed()) return;

                                helper.sendEmailVerification(new Database_Helper.EmailVerificationCallback() {
                                    @Override
                                    public void onSuccess() {
                                        if (isFinishing() || isDestroyed()) return;
                                        showLoading(false, null);
                                        showVerifyEmailDialog();
                                    }

                                    @Override
                                    public void onFailure(String errorMessage) {
                                        if (isFinishing() || isDestroyed()) return;
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
                                if (isFinishing() || isDestroyed()) return;
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
                    if (isFinishing() || isDestroyed()) return;
                    showLoading(false, null);
                    if (e instanceof com.google.firebase.auth.FirebaseAuthUserCollisionException) {
                        showAccountAlreadyExistsDialog();
                    } else {
                        NotificationHelper.showError(this,
                                "Registration could not be completed. Check your connection and details, then try again.");
                    }
                });
    }

    private void showVerifyEmailDialog() {
        if (isFinishing() || isDestroyed()) return;

        NotificationHelper.showSuccessAcknowledgement(this, "Registration Complete",
                "Please check your email to verify your account before logging in.", () -> {
                    helper.logout();
                    gotoLogin();
                });
    }

    private void gotoLogin() {
        finish();
    }

    private void showAccountAlreadyExistsDialog() {
        NotificationHelper.showTripleActionDialog(this, "Account Already Exists",
                "An existing Basilience account uses this email. Please sign in or reset your password.",
                "Sign In", "Forgot Password", "Cancel",
                new NotificationHelper.TripleActionCallback() {
                    @Override
                    public void onAction1() {
                        finish();
                    }

                    @Override
                    public void onAction2() {
                        startActivity(new Intent(Auth_Register_Activity.this,
                                Auth_ForgotPass_Activity.class));
                    }
                });
    }
}
