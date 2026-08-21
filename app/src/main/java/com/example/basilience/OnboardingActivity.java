package com.example.basilience;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.example.basilience.models.OnboardingPage;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Short, skippable first-install walkthrough (6 pages). This is intentionally
 * separate from the detailed User Guide (MobileGuideFragment/
 * HardwareGuideFragment) - it never explains a state in depth, it just
 * orients a brand-new user.
 *
 * <p>Runs in one of two modes, controlled by {@link #EXTRA_REPLAY_MODE}:
 * <ul>
 *   <li><b>First-launch mode</b> (default) - launched by Auth_Login_Activity
 *   before its normal splash/session logic runs, when onboarding has never
 *   been completed on this install. Finishing (Skip or Get Started) marks
 *   onboarding complete and hands off to Auth_Login_Activity, which then
 *   proceeds through its existing, untouched splash-overlay/session-restore
 *   flow exactly as it does today.</li>
 *   <li><b>Replay mode</b> - launched from the User Guide landing screen by
 *   an already-authenticated user. Finishing (Skip, Get Started, or Back)
 *   just calls {@code finish()} and returns to whatever screen launched it -
 *   it never re-marks onboarding, never touches auth/session state, and
 *   never starts another MainActivity/Auth_Login_Activity instance.</li>
 * </ul>
 */
public class OnboardingActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "basilience_prefs";
    private static final String KEY_HAS_COMPLETED_ONBOARDING = "has_completed_onboarding";
    private static final String EXTRA_REPLAY_MODE = "replay_mode";

    private boolean replayMode;
    private ViewPager2 pager;
    private MaterialButton btnPrimaryAction;
    private View btnSkip;
    private final List<View> dots = new ArrayList<>();
    private int pageCount;

    /** True if the walkthrough has never been completed or skipped on this install. */
    public static boolean hasCompletedOnboarding(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_HAS_COMPLETED_ONBOARDING, false);
    }

    /** Intent for a manual replay (e.g. from the User Guide landing screen). Does not affect first-launch state. */
    public static Intent replayIntent(Context context) {
        Intent intent = new Intent(context, OnboardingActivity.class);
        intent.putExtra(EXTRA_REPLAY_MODE, true);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        replayMode = getIntent().getBooleanExtra(EXTRA_REPLAY_MODE, false);

        pager = findViewById(R.id.onboardingPager);
        btnPrimaryAction = findViewById(R.id.btnPrimaryAction);
        btnSkip = findViewById(R.id.btnSkip);

        List<OnboardingPage> pages = buildPages();
        pageCount = pages.size();
        pager.setAdapter(new OnboardingPagerAdapter(pages));

        buildDotIndicator();

        btnSkip.setOnClickListener(v -> finishOnboarding());
        btnPrimaryAction.setOnClickListener(v -> {
            int current = pager.getCurrentItem();
            if (current < pageCount - 1) {
                pager.setCurrentItem(current + 1);
            } else {
                finishOnboarding();
            }
        });

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateForPage(position);
            }
        });
        updateForPage(0);

        // Replay mode: Back behaves exactly like Skip/Get Started (just leave),
        // never re-marking onboarding and never touching auth/session state.
        // First-launch mode: default Back behavior (finish the task) is already
        // safe here since OnboardingActivity is the only activity on the stack -
        // nothing to loop back into.
        if (replayMode) {
            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    finish();
                }
            });
        }
    }

    private void updateForPage(int position) {
        boolean isLast = position == pageCount - 1;
        btnPrimaryAction.setText(isLast ? "Get Started" : "Next");
        btnSkip.setVisibility(isLast ? View.INVISIBLE : View.VISIBLE);
        for (int i = 0; i < dots.size(); i++) {
            dots.get(i).setBackgroundResource(i == position
                    ? R.drawable.onboarding_dot_active : R.drawable.onboarding_dot_inactive);
        }
    }

    private void buildDotIndicator() {
        android.widget.LinearLayout container = findViewById(R.id.dotIndicatorContainer);
        int dotSize = (int) (8 * getResources().getDisplayMetrics().density);
        int margin = (int) (4 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < pageCount; i++) {
            View dot = new View(this);
            android.widget.LinearLayout.LayoutParams params =
                    new android.widget.LinearLayout.LayoutParams(dotSize, dotSize);
            params.setMargins(margin, 0, margin, 0);
            dot.setLayoutParams(params);
            dot.setBackgroundResource(i == 0 ? R.drawable.onboarding_dot_active : R.drawable.onboarding_dot_inactive);
            container.addView(dot);
            dots.add(dot);
        }
    }

    /** Both Skip and Get Started call this - either one permanently completes first-run onboarding. */
    private void finishOnboarding() {
        if (replayMode) {
            // Already complete; just return to whatever screen launched this (User Guide/MainActivity).
            finish();
            return;
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_HAS_COMPLETED_ONBOARDING, true)
                .apply();
        // Hand off to Auth_Login_Activity's own, unmodified splash-overlay/
        // session-restore flow - onboarding never decides where the user
        // ultimately lands (login form vs. restored session).
        Intent intent = new Intent(this, Auth_Login_Activity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private List<OnboardingPage> buildPages() {
        List<OnboardingPage> pages = new ArrayList<>();

        pages.add(new OnboardingPage(
                "Welcome to Basilience",
                "Basilience helps you monitor and manage your Genovese basil fogponics cultivation system.",
                R.drawable.basilience_logo, null, null));

        pages.add(new OnboardingPage(
                "Monitor Your Cultivation System",
                "Track pH, EC, air temperature, humidity, water temperature, and water level in real time, along with device connectivity and actuator status.",
                0, "Monitoring screen with live parameter readings", null));

        pages.add(new OnboardingPage(
                "Manage Growth Cycles & Harvest",
                "Keep track of your active growth cycle, see upcoming harvest schedules, record harvested weight, and review harvest history.",
                0, "Growth Cycle and Harvest screens", null));

        pages.add(new OnboardingPage(
                "Stay Informed",
                "Parameter and Fogging Reports summarize your system over time, and notifications keep you posted on important alerts and device connectivity.",
                0, "Reports and Notifications screens", null));

        pages.add(new OnboardingPage(
                "Administration Tools",
                "Admin users can also manage personnel, Basilience devices, growth cycle setup, reports, and advanced diagnostic tools where available.",
                0, "Personnel and Device Management screens", "Admin Only"));

        pages.add(new OnboardingPage(
                "You're Ready",
                "Use the User Guide anytime for detailed, image-based instructions on every screen.",
                R.drawable.basilience_logo, null, null));

        return pages;
    }
}
