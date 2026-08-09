package com.example.basilience;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseNetworkException;
import com.google.android.material.button.MaterialButton;

public class Auth_Login_Activity extends AppCompatActivity {

    private static final String PREFS_NAME = "basilience_prefs";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";
    private static final long BACKEND_TIMEOUT_MS = 15000L;
    private static final String BACKEND_UNAVAILABLE_MESSAGE =
            "Unable to connect to Basilience services. Check your internet connection.";

    private EditText txtemail, txtpassword;
    private CheckBox cbRemember;
    private MaterialButton btnlogin;
    private TextView tvSignup, tvForgotPassword;
    private android.view.View layoutLoading;
    private TextView tvLoadingTitle;

    private Database_Helper helper;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        final boolean[] keepSplash = {true};
        splashScreen.setKeepOnScreenCondition(() -> keepSplash[0]);

        helper = new Database_Helper();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isLoggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, false);
        String currentUid = helper.getCurrentUid();

        if (isLoggedIn && currentUid != null) {
            // User chose "Remember Me" and is still authenticated in Firebase
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
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
        tvForgotPassword.setOnClickListener(v -> startActivity(new Intent(this, Auth_ForgotPass_Activity.class)));
    }

    private void showLoading(boolean show, String message) {
        if (isFinishing() || isDestroyed()) return;
        if (tvLoadingTitle != null && message != null) {
            tvLoadingTitle.setText(message);
        }
        layoutLoading.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
        if (show) layoutLoading.bringToFront();
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

        awaitBackendTask(
                helper.loginAuth(email, password),
                res -> {
                    com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
                    String uid = user != null ? user.getUid() : null;

                    if (user == null || uid == null) {
                        showLoading(false, null);
                        NotificationHelper.showError(this, "LOGIN FAILED: uid is null");
                        return;
                    }

                    if (!user.isEmailVerified()) {
                        showLoading(false, null);
                        helper.logout();
                        NotificationHelper.showError(this,
                                "Your email is not verified! Please check your email inbox (including spam/junk), click the verification link, then log in again.");
                        return;
                    }

                    showLoading(true, "Loading profile...");

                    awaitBackendTask(
                            helper.getUserProfile(uid),
                            document -> {
                                if (!document.exists()) {
                                    showLoading(false, null);
                                    helper.logout();
                                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                                    prefs.edit()
                                            .remove(KEY_IS_LOGGED_IN)
                                            .remove("user_role")
                                            .remove("owner_uid")
                                            .remove("selected_device_id")
                                            .apply();
                                    NotificationHelper.showInfo(this, "Account Profile Missing",
                                            "Your sign-in account exists, but your Basilience profile could not be found. Please contact your administrator or recover the account profile.");
                                    return;
                                }

                                String role = document.getString("role");
                                String ownerUid = document.getString("ownerAdminUid");
                                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                                SharedPreferences.Editor editor = prefs.edit();
                                editor.putBoolean(KEY_IS_LOGGED_IN, cbRemember.isChecked());
                                editor.putString("user_role", role);
                                editor.putString("owner_uid", ownerUid);
                                editor.apply();

                                // Device assignments are established only by Admin assignment
                                // operations. Login must consume them and never create/repair access.
                                navigateToMain();
                            },
                            e -> {
                                if (isBackendReachabilityFailure(e)) {
                                    showBackendUnavailable();
                                } else {
                                    showLoading(false, null);
                                    NotificationHelper.showError(this, "Failed to load profile: " + e.getMessage());
                                }
                            });
                },
                e -> {
                    showLoading(false, null);
                    if (isBackendReachabilityFailure(e)) {
                        NotificationHelper.showError(this, BACKEND_UNAVAILABLE_MESSAGE);
                    } else {
                        NotificationHelper.showError(this, "LOGIN FAILED: " + e.getMessage());
                    }
                });
    }

    private <T> void awaitBackendTask(Task<T> task, OnSuccessListener<T> onSuccess, OnFailureListener onFailure) {
        final boolean[] settled = {false};
        Runnable timeout = () -> {
            if (settled[0]) return;
            settled[0] = true;
            onFailure.onFailure(new FirebaseNetworkException(BACKEND_UNAVAILABLE_MESSAGE));
        };

        mainHandler.postDelayed(timeout, BACKEND_TIMEOUT_MS);

        task.addOnSuccessListener(result -> {
                    if (settled[0]) return;
                    settled[0] = true;
                    mainHandler.removeCallbacks(timeout);
                    onSuccess.onSuccess(result);
                })
                .addOnFailureListener(e -> {
                    if (settled[0]) return;
                    settled[0] = true;
                    mainHandler.removeCallbacks(timeout);
                    onFailure.onFailure(e);
                });
    }

    private boolean isBackendReachabilityFailure(Exception e) {
        if (e instanceof FirebaseNetworkException) return true;

        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.US);
                if (lower.contains("network") || lower.contains("timeout") || lower.contains("unreachable")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void showBackendUnavailable() {
        showLoading(false, null);
        NotificationHelper.showError(this, BACKEND_UNAVAILABLE_MESSAGE);
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        showLoading(false, null);
        startActivity(intent);
        finish();
    }
}
