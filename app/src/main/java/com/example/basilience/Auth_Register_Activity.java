package com.example.basilience;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputLayout;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;

public class Auth_Register_Activity extends AppCompatActivity {

    private static final String TAG = "Auth_Register_Activity";
    private TextInputEditText etName, etEmail, etPassword, etConfirmPassword;
    private TextInputLayout layoutPassword, layoutConfirmPassword;
    private MaterialCheckBox cbLegalConsent;
    private TextView tvConsentText, tvConsentError;
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
        layoutPassword = findViewById(R.id.layoutPassword);
        layoutConfirmPassword = findViewById(R.id.layoutConfirmPassword);

        // Requirements are visible before the user submits, from the same
        // shared policy that validates the field.
        if (layoutPassword != null) layoutPassword.setHelperText(PasswordPolicy.REQUIREMENTS);

        cbLegalConsent = findViewById(R.id.cbLegalConsent);
        tvConsentText = findViewById(R.id.tvConsentText);
        tvConsentError = findViewById(R.id.tvConsentError);
        setupLegalConsent();
        btnSignup = findViewById(R.id.btnSignup);
        tvLogin = findViewById(R.id.tvLogin);
        layoutLoading = findViewById(R.id.layoutLoading);
        tvLoadingTitle = findViewById(R.id.tvLoadingTitle);

        btnSignup.setOnClickListener(v -> registerUser());
        tvLogin.setOnClickListener(v -> finish());
        etConfirmPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                registerUser();
                return true;
            }
            return false;
        });
    }

    private long layoutLoadingShownAt;

    private void showLoading(boolean show, String message) {
        if (isFinishing() || isDestroyed()) return;
        if (tvLoadingTitle != null && message != null) tvLoadingTitle.setText(message);
        if (show) {
            layoutLoadingShownAt = SystemClock.elapsedRealtime();
            if (layoutLoading != null) {
                layoutLoading.setVisibility(View.VISIBLE);
                layoutLoading.bringToFront();
            }
        } else if (layoutLoading != null && layoutLoading.getVisibility() == View.VISIBLE) {
            NotificationHelper.hideLoaderAfterMinimumDuration(layoutLoadingShownAt, () -> {
                if (!isFinishing() && !isDestroyed() && layoutLoading != null) layoutLoading.setVisibility(View.GONE);
            });
        }
        if (btnSignup != null) btnSignup.setEnabled(!show);
    }

    /**
     * Builds the consent line with "Terms and Conditions" and "Privacy Policy"
     * as individually tappable spans. Both open a read-only dialog, so either
     * document can be read without an account and without leaving the form
     * half-filled.
     */
    private void setupLegalConsent() {
        if (tvConsentText == null) return;

        final String full = "I have read and agree to the Terms and Conditions and Privacy Policy.";
        final String termsLabel = "Terms and Conditions";
        final String privacyLabel = "Privacy Policy";

        SpannableString text = new SpannableString(full);
        applyDocumentSpan(text, full, termsLabel, LegalContent.TERMS_TITLE, LegalContent.TERMS_BODY);
        applyDocumentSpan(text, full, privacyLabel, LegalContent.PRIVACY_TITLE, LegalContent.PRIVACY_BODY);

        tvConsentText.setText(text);
        tvConsentText.setMovementMethod(LinkMovementMethod.getInstance());

        // Tapping the sentence itself (outside the two links) toggles the box,
        // so the whole line behaves like the label of the checkbox.
        tvConsentText.setOnClickListener(v -> {
            if (cbLegalConsent != null) cbLegalConsent.setChecked(!cbLegalConsent.isChecked());
        });

        if (cbLegalConsent != null) {
            cbLegalConsent.setOnCheckedChangeListener((button, isChecked) -> {
                if (isChecked) clearConsentError();
            });
        }
    }

    private void applyDocumentSpan(SpannableString text, String full, String label,
                                   String dialogTitle, String dialogBody) {
        int start = full.indexOf(label);
        if (start < 0) return;
        int end = start + label.length();

        text.setSpan(new ClickableSpan() {
            @Override
            public void onClick(android.view.View widget) {
                LegalContent.showReadOnly(Auth_Register_Activity.this, dialogTitle, dialogBody);
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void showConsentError(String message) {
        if (tvConsentError == null) {
            NotificationHelper.showError(this, message);
            return;
        }
        tvConsentError.setText(message);
        tvConsentError.setVisibility(View.VISIBLE);
    }

    private void clearConsentError() {
        if (tvConsentError != null) tvConsentError.setVisibility(View.GONE);
    }

    private void clearPasswordErrors() {
        if (layoutPassword != null) layoutPassword.setError(null);
        if (layoutConfirmPassword != null) layoutConfirmPassword.setError(null);
        // setError() hides helper text while an error is showing; restoring it
        // keeps the requirements on screen once the error clears.
        if (layoutPassword != null) layoutPassword.setHelperText(PasswordPolicy.REQUIREMENTS);
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

        // 3. Terms and Privacy Policy must be accepted before an account exists
        if (cbLegalConsent != null && !cbLegalConsent.isChecked()) {
            showConsentError("Please read and accept the Terms and Conditions and Privacy Policy to continue.");
            cbLegalConsent.requestFocus();
            return;
        }

        // 4. Shared strong-password policy (PasswordPolicy)
        clearPasswordErrors();
        String passwordError = PasswordPolicy.validate(password);
        if (passwordError != null) {
            if (layoutPassword != null) layoutPassword.setError(passwordError);
            else NotificationHelper.showError(this, passwordError);
            etPassword.requestFocus();
            return;
        }

        // Mismatch is reported separately, on the field it belongs to.
        if (!password.equals(confirmPassword)) {
            if (layoutConfirmPassword != null) layoutConfirmPassword.setError("Passwords do not match");
            else NotificationHelper.showError(this, "Passwords do not match");
            etConfirmPassword.requestFocus();
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
                        NotificationHelper.showError(this, "Unable to complete registration. Please try again.");
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
                                        Log.e(TAG, "Failed to send verification email: " + errorMessage);
                                        NotificationHelper.showError(
                                                Auth_Register_Activity.this,
                                                "Your account was created, but we couldn't send a verification email. Please try signing in to resend it."
                                        );
                                        helper.logout();
                                        gotoLogin();
                                    }
                                });
                            })
                            .addOnFailureListener(e -> {
                                if (isFinishing() || isDestroyed()) return;
                                showLoading(false, null);
                                Log.e(TAG, "Failed to save user profile", e);
                                NotificationHelper.showError(
                                        Auth_Register_Activity.this,
                                        "Unable to save your profile. Please try again."
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
