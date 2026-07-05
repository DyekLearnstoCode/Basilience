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
    private View layoutLoading;

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
        layoutLoading = findViewById(R.id.layoutLoading);

        // 2. Initialize your backend helper
        helper = new Database_Helper(); // Fixed: Using your zero-argument constructor

        // 3. Set Up Listeners
        btnResetPassword.setOnClickListener(v -> handlePasswordReset());

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

        // Show Loading Overlay
        layoutLoading.setVisibility(View.VISIBLE);

        // Execute original logic
        helper.sendPasswordResetEmail(email)
                .addOnSuccessListener(aVoid -> {
                    // Hide Loading Overlay
                    layoutLoading.setVisibility(View.GONE);
                    NotificationHelper.showSuccess(this, "Reset link sent to " + email);

                    // Automatically return to login after success
                    finish();
                })
                .addOnFailureListener(e -> {
                    // Hide Loading Overlay
                    layoutLoading.setVisibility(View.GONE);
                    NotificationHelper.showError(this, "Error: " + e.getMessage());
                });
    }
}