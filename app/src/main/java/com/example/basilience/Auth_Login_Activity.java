package com.example.basilience;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseNetworkException;
import com.google.android.material.button.MaterialButton;

public class Auth_Login_Activity extends AppCompatActivity {

    private static final String PREFS_NAME = "basilience_prefs";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";
    private static final String KEY_NOTIFICATION_PERMISSION_REQUESTED =
            "notification_permission_requested";
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

    // Splash overlay state. Both flags must be set before the app is
    // uncovered; splashOverlayHidden makes the reveal idempotent so a late
    // callback cannot restart the fade.
    private android.view.View splashOverlay;
    private boolean splashAnimationDone;
    private boolean splashContentReady;
    private boolean splashOverlayHidden;
    private static final long SPLASH_OVERLAY_MAX_WAIT_MS = 1500L;
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // Permission affects Android tray notifications only. Login continues either way.
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        // First-install walkthrough gate: checked before any of the existing
        // splash-overlay/session-restore logic below runs, so that logic stays
        // completely untouched and only ever executes once onboarding is done.
        // OnboardingActivity hands back to this same Activity (fresh Intent)
        // when it finishes, at which point this check passes through normally.
        if (!OnboardingActivity.hasCompletedOnboarding(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        final boolean[] keepSplash = {true};
        splashScreen.setKeepOnScreenCondition(() -> keepSplash[0]);

        // EXIT ONLY - this hands the native launch splash over to the Lottie
        // overlay underneath, which is what actually animates the logo.
        //
        // The listener fires only once the app is ready to draw, i.e. after
        // all of this onCreate. Startup work can take seconds, so anything
        // animated here would sit frozen for that whole time and only move
        // at the very end; that is why the reveal lives in the overlay,
        // which starts as soon as the content view exists.
        //
        // Both surfaces share the same celadon background and logo position,
        // so this cross-fade lands on an identical backdrop instead of a
        // second, visibly different splash.
        //
        // Presentation only: routing, session validation and the login UI
        // have all already run underneath, and nothing here gates them. The
        // fade removes the splash via withEndAction, which still fires when
        // the animator duration scale is 0 (animations disabled), so the
        // splash can never be stranded on screen.
        splashScreen.setOnExitAnimationListener(splashViewProvider ->
                fadeOutSplash(splashViewProvider.getView(), splashViewProvider));

        helper = new Database_Helper();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isLoggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, false);
        String currentUid = helper.getCurrentUid();

        setContentView(R.layout.auth_login);
        keepSplash[0] = false;

        startSplashOverlay();

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        txtemail = findViewById(R.id.etEmail);
        txtpassword = findViewById(R.id.etPassword);
        btnlogin = findViewById(R.id.btnLogin);
        cbRemember = findViewById(R.id.cbRemember);
        tvSignup = findViewById(R.id.tvSignup);
        tvForgotPassword = findViewById(R.id.tvForgotPassword);
        layoutLoading = findViewById(R.id.layoutLoading);
        tvLoadingTitle = findViewById(R.id.tvLoadingTitle);

        requestNotificationPermissionAtStartup();

        btnlogin.setOnClickListener(v -> doLogin());
        txtpassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                doLogin();
                return true;
            }
            return false;
        });
        tvSignup.setVisibility(android.view.View.VISIBLE);
        tvSignup.setOnClickListener(v -> startActivity(new Intent(this, Auth_Register_Activity.class)));
        tvForgotPassword.setOnClickListener(v -> startActivity(new Intent(this, Auth_ForgotPass_Activity.class)));

        if (isLoggedIn && currentUid != null) {
            revalidateRememberedSession(currentUid);
        }

        // Startup work above is done and the content is laid out, so the app
        // is ready to be revealed as soon as the splash animation allows.
        // Routing decisions are made above and never inside an animation
        // callback - the overlay only controls when the cover comes off.
        splashContentReady = true;
        maybeRevealApp();
    }

    // ------------------------------------------------------------------
    // Splash overlay (presentation only)
    //
    // The native system splash is the launch surface and shows a static
    // logo; this overlay sits on top of the login content showing the same
    // mark on the same celadon background, and plays the Lottie reveal.
    // Because it is an ordinary view rather than the system splash icon, it
    // is free of Android 12's circular icon mask and can render the logo
    // considerably larger.
    //
    // The app is revealed only once BOTH the animation has finished and
    // startup is ready, so a long initialization never truncates the
    // animation and a fast one never leaves a half-played logo.
    // ------------------------------------------------------------------

    private void startSplashOverlay() {
        splashOverlay = findViewById(R.id.splashOverlay);
        com.airbnb.lottie.LottieAnimationView lottie = findViewById(R.id.splashLottie);
        if (splashOverlay == null || lottie == null) {
            splashAnimationDone = true;
            return;
        }

        // With animations disabled there is nothing to play: jump straight to
        // the composition's final frame so the settled logo is still shown,
        // then let the normal reveal path continue.
        if (animatorDurationScale() == 0f) {
            lottie.setProgress(1f);
            splashAnimationDone = true;
            return;
        }

        lottie.addAnimatorListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                splashAnimationDone = true;
                maybeRevealApp();
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                splashAnimationDone = true;
                maybeRevealApp();
            }
        });
        lottie.playAnimation();

        // Safety net: if the composition ever fails to load or its end
        // callback does not arrive, the overlay must not strand the user on
        // a covered screen. This only ever fires early, never late.
        mainHandler.postDelayed(() -> {
            if (!splashAnimationDone) {
                splashAnimationDone = true;
                maybeRevealApp();
            }
        }, SPLASH_OVERLAY_MAX_WAIT_MS);
    }

    private float animatorDurationScale() {
        return android.provider.Settings.Global.getFloat(getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    }

    /** Uncovers the app once the reveal has played and startup is ready. */
    private void maybeRevealApp() {
        if (!splashAnimationDone || !splashContentReady || splashOverlayHidden) return;
        if (splashOverlay == null) return;
        splashOverlayHidden = true;

        splashOverlay.animate()
                .alpha(0f)
                .setDuration(180L)
                .withEndAction(() -> splashOverlay.setVisibility(android.view.View.GONE))
                .start();
    }

    /**
     * Splash-to-app transition: fades the splash out and always removes it,
     * even with animations off. The logo is never moved or scaled here - by
     * this point it has long since finished its entrance and settled.
     */
    private void fadeOutSplash(android.view.View splashView,
                               androidx.core.splashscreen.SplashScreenViewProvider provider) {
        splashView.animate()
                .alpha(0f)
                .setDuration(190L)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(provider::remove)
                .start();
    }

    private void requestNotificationPermissionAtStartup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)) return;

        prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply();
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private long layoutLoadingShownAt;

    private void showLoading(boolean show, String message) {
        if (isFinishing() || isDestroyed()) return;
        if (tvLoadingTitle != null && message != null) {
            tvLoadingTitle.setText(message);
        }
        if (show) {
            layoutLoadingShownAt = SystemClock.elapsedRealtime();
            layoutLoading.setVisibility(android.view.View.VISIBLE);
            layoutLoading.bringToFront();
        } else if (layoutLoading.getVisibility() == android.view.View.VISIBLE) {
            NotificationHelper.hideLoaderAfterMinimumDuration(layoutLoadingShownAt, () -> {
                if (!isFinishing() && !isDestroyed()) layoutLoading.setVisibility(android.view.View.GONE);
            });
        }
        btnlogin.setEnabled(!show);
    }

    private void doLogin() {
        String email = String.valueOf(txtemail.getText()).trim();
        String password = String.valueOf(txtpassword.getText()).trim();

        if (email.isEmpty() || password.isEmpty()) {
            NotificationHelper.showError(this, "Please fill all fields");
            return;
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            NotificationHelper.showError(this, "Please enter a valid email address");
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
                        NotificationHelper.showError(this, "Unable to sign you in. Please try again.");
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
                                            .remove("is_developer")
                                            .apply();
                                    NotificationHelper.showInfo(this, "Account Profile Missing",
                                            "Your sign-in account exists, but your Basilience profile could not be found. Please contact your administrator or recover the account profile.");
                                    return;
                                }

                                String role = document.getString("role");
                                String ownerUid = document.getString("ownerAdminUid");
                                // isDeveloper is never self-service - only settable via the
                                // Firebase Console/Admin SDK (see firestore.rules) - and gates
                                // the developer-only subset of Developer Options, separate from
                                // (and narrower than) the ADMIN role check that gates the screen
                                // itself. Defaults false for every existing/normal account.
                                boolean isDeveloper = Boolean.TRUE.equals(document.getBoolean("isDeveloper"));
                                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                                SharedPreferences.Editor editor = prefs.edit();
                                editor.putBoolean(KEY_IS_LOGGED_IN, cbRemember.isChecked());
                                editor.putString("user_role", role);
                                editor.putString("owner_uid", ownerUid);
                                editor.putBoolean("is_developer", isDeveloper);
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
                                    NotificationHelper.showError(this, "Unable to load your Basilience account profile.");
                                }
                            });
                },
                e -> {
                    showLoading(false, null);
                    if (isBackendReachabilityFailure(e)) {
                        NotificationHelper.showError(this, BACKEND_UNAVAILABLE_MESSAGE);
                    } else {
                        NotificationHelper.showError(this, "Email or password is incorrect.");
                    }
                });
    }

    private void revalidateRememberedSession(String uid) {
        showLoading(true, "Restoring session...");
        awaitBackendTask(helper.getUserProfile(uid), document -> {
            String role = document.exists() ? document.getString("role") : null;
            if (!RoleConstants.ROLE_ADMIN.equalsIgnoreCase(role)
                    && !RoleConstants.ROLE_FARMER.equalsIgnoreCase(role)) {
                clearInvalidSession();
                NotificationHelper.showInfo(this, "Account Profile Missing",
                        "Your saved sign-in is no longer linked to a valid Basilience profile.");
                return;
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString("user_role", role)
                    .putString("owner_uid", document.getString("ownerAdminUid"))
                    .putBoolean("is_developer", Boolean.TRUE.equals(document.getBoolean("isDeveloper")))
                    .apply();
            navigateToMain();
        }, error -> {
            if (isBackendReachabilityFailure(error)) {
                showBackendUnavailable();
            } else {
                clearInvalidSession();
                NotificationHelper.showError(this, "Unable to restore your Basilience session.");
            }
        });
    }

    private void clearInvalidSession() {
        helper.logout();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove(KEY_IS_LOGGED_IN)
                .remove("user_role")
                .remove("owner_uid")
                .remove("selected_device_id")
                .remove("is_developer")
                .apply();
        showLoading(false, null);
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
