package com.example.basilience;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.google.android.material.button.MaterialButton;

public class Auth_Login_Activity extends AppCompatActivity {

    private static final String PREFS_NAME = "basilience_prefs";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";

    private EditText txtemail, txtpassword;
    private CheckBox cbRemember;
    private MaterialButton btnlogin;
    private TextView tvSignup, tvForgotPassword;
    private android.view.View layoutLoading;
    private TextView tvLoadingTitle;

    private Database_Helper helper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        final boolean[] keepSplash = { true };
        splashScreen.setKeepOnScreenCondition(() -> keepSplash[0]);

        helper = new Database_Helper();

        // Check if user is already logged in (Remember Me logic)
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isLoggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, false);
        String currentUid = helper.getCurrentUid();

        if (isLoggedIn && currentUid != null) {
            // User chose "Remember Me" and is still authenticated in Firebase
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.auth_login);
        keepSplash[0] = false;

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        txtemail = findViewById(R.id.etEmail);
        txtpassword = findViewById(R.id.etPassword);
        btnlogin = findViewById(R.id.btnLogin);
        cbRemember = findViewById(R.id.cbRemember);
        tvSignup = findViewById(R.id.tvSignup);
        tvForgotPassword = findViewById(R.id.tvForgotPassword);
        layoutLoading = findViewById(R.id.layoutLoading);
        tvLoadingTitle = findViewById(R.id.tvLoadingTitle);

        btnlogin.setOnClickListener(v -> doLogin());
        tvSignup.setVisibility(android.view.View.VISIBLE);
        tvSignup.setOnClickListener(v -> startActivity(new Intent(this, Auth_Register_Activity.class)));
        tvForgotPassword.setOnClickListener(v -> showForgotPasswordDialog());
    }

    private void showForgotPasswordDialog() {
        String email = txtemail.getText().toString().trim();
        if (email.isEmpty()) {
            NotificationHelper.showError(this, "Enter your email above first");
            return;
        }

        helper.sendPasswordResetEmail(email)
                .addOnSuccessListener(aVoid -> NotificationHelper.showSuccess(this, "Reset link sent to " + email))
                .addOnFailureListener(e -> NotificationHelper.showError(this, "Error: " + e.getMessage()));
    }

    private void showLoading(boolean show, String message) {
        if (tvLoadingTitle != null && message != null) {
            tvLoadingTitle.setText(message);
        }
        layoutLoading.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
        btnlogin.setEnabled(!show);
    }

    private void doLogin() {
        String email = String.valueOf(txtemail.getText()).trim();
        String password = String.valueOf(txtpassword.getText()).trim();

        if (email.isEmpty() || password.isEmpty()) {
            NotificationHelper.showError(this, "Please fill all fields");
            return;
        }

        showLoading(true, "Logging in...");

        helper.loginAuth(email, password)
                .addOnSuccessListener(res -> {
                    String uid = helper.getCurrentUid();
                    if (uid == null) {
                        showLoading(false, null);
                        NotificationHelper.showError(this, "LOGIN FAILED: uid is null");
                        return;
                    }

                    showLoading(true, "Loading profile...");

                    helper.getUserProfile(uid)
                            .addOnSuccessListener(document -> {
                                if (!document.exists()) {
                                    showLoading(false, null);
                                    // If profile is missing, sign out to avoid being stuck
                                    helper.logout();
                                    NotificationHelper.showError(this, "Account found, but no profile exists. Please contact support.");
                                    return;
                                }

                                String role = document.getString("role");
                                String ownerUid = document.getString("ownerAdminUid");
                                // NotificationHelper.showSuccess(this, "LOGIN SUCCESSFUL"); // Optional: skip or show briefly

                                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                                SharedPreferences.Editor editor = prefs.edit();
                                editor.putBoolean(KEY_IS_LOGGED_IN, cbRemember.isChecked());
                                editor.putString("user_role", role);
                                editor.putString("owner_uid", ownerUid);
                                editor.apply();

                                // Redirect based on role
                                Intent intent = new Intent(this, MainActivity.class);
                                showLoading(false, null);
                                startActivity(intent);
                                finish();
                            })
                            .addOnFailureListener(e -> {
                                showLoading(false, null);
                                NotificationHelper.showError(this, "Failed to load profile: " + e.getMessage());
                            });
                })
                .addOnFailureListener(e -> {
                    showLoading(false, null);
                    NotificationHelper.showError(this, "LOGIN FAILED: " + e.getMessage());
                });
    }
}