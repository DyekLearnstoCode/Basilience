package com.example.basilience;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class Auth_ForgotPass_Activity extends AppCompatActivity {

    private TextInputEditText etForgotEmail;
    private MaterialButton btnResetPassword;
    private TextView tvBackToLogin;
    private NotificationHelper.LoadingHandle loadingHandle;

    // Fixed: Pointing to your actual database helper class
    private Database_Helper helper;

    @Override
    protected void onCreate(Bundle savedInstanceState) { // Fixed syntax error here
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        // 1. Initialize Views
        etForgotEmail = findViewById(R.id.etForgotEmail);
        btnResetPassword = findViewById(R.id.btnResetPassword);
        tvBackToLogin = findViewById(R.id.tvBackToLogin);

        // 2. Initialize your backend helper
        helper = new Database_Helper(); // Fixed: Using your zero-argument constructor

        // 3. Set Up Listeners
        btnResetPassword.setOnClickListener(v -> handlePasswordReset());
        etForgotEmail.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                handlePasswordReset();
                return true;
            }
            return false;
        });

        tvBackToLogin.setOnClickListener(v -> {
            // Close this activity to return to the Login Screen
            finish();
        });
    }

    private void handlePasswordReset() {
        String email = etForgotEmail.getText().toString().trim();

        // Validation
        if (email.isEmpty()) {
            NotificationHelper.showError(this, "Please enter a valid email");
            return;
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            NotificationHelper.showError(this, "Please enter a valid email address");
            return;
        }

        btnResetPassword.setEnabled(false);
        loadingHandle = NotificationHelper.showLoading(this, "Sending reset link...", () -> {
            btnResetPassword.setEnabled(true);
            NotificationHelper.showError(this, "Request timed out. Please check your connection and try again.");
        });

        // Execute original logic
        helper.sendPasswordResetEmail(email)
                .addOnSuccessListener(aVoid -> {
                    if (isFinishing() || isDestroyed()) return;
                    dismissLoading();
                    btnResetPassword.setEnabled(true);
                    NotificationHelper.showSuccessAcknowledgement(this,
                            "Password Reset Email Sent",
                            "If a Basilience account uses this email, a reset link has been sent. Please check your inbox.",
                            this::finish);
                })
                .addOnFailureListener(e -> {
                    if (isFinishing() || isDestroyed()) return;
                    dismissLoading();
                    btnResetPassword.setEnabled(true);
                    NotificationHelper.showError(this,
                            "Password reset is temporarily unavailable. Check your connection and try again.");
                });
    }

    private void dismissLoading() {
        if (loadingHandle != null) loadingHandle.dismiss();
        loadingHandle = null;
    }

    @Override
    protected void onDestroy() {
        dismissLoading();
        super.onDestroy();
    }

}
