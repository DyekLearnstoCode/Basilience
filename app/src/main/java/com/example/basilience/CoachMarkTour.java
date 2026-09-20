package com.example.basilience;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.OnBackPressedDispatcher;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives a one-step-at-a-time "coach mark" tour: a dimmed overlay with a
 * spotlight cutout around one already-on-screen view, plus a tooltip with
 * Next/Skip actions. Screen-agnostic - callers supply the steps and decide
 * when it should run. The overlay is a full-screen blocking view (the
 * spotlight cutout is a visual effect only, not a touch-through hole), so
 * nothing underneath is reachable until the tour is skipped or finished.
 */
public class CoachMarkTour {

    public static class Step {
        final View target;
        final String title;
        final String description;

        public Step(View target, String title, String description) {
            this.target = target;
            this.title = title;
            this.description = description;
        }
    }

    private final Activity activity;
    private final List<Step> steps;
    private final Runnable onDone;
    private final OnBackPressedCallback backCallback;

    private ViewGroup overlayRoot;
    private CoachMarkOverlayView overlayView;
    private View tooltipView;
    private int currentIndex;
    private boolean isActive;

    public CoachMarkTour(Activity activity, LifecycleOwner viewLifecycleOwner,
                          OnBackPressedDispatcher backDispatcher, List<Step> steps, Runnable onDone) {
        this.activity = activity;
        this.steps = filterVisible(steps);
        this.onDone = onDone;

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        };
        backDispatcher.addCallback(viewLifecycleOwner, backCallback);
    }

    private static List<Step> filterVisible(List<Step> steps) {
        List<Step> result = new ArrayList<>();
        for (Step step : steps) {
            // isShown() (not just getVisibility()) so a target nested inside
            // a conditionally-GONE container (e.g. Reports' reportContentContainer
            // before data loads) is correctly skipped rather than spotlighted
            // with a stale/empty rect.
            if (step.target != null && step.target.isAttachedToWindow() && step.target.isShown()) {
                result.add(step);
            }
        }
        return result;
    }

    public void start() {
        if (steps.isEmpty()) {
            if (onDone != null) onDone.run();
            return;
        }

        ViewGroup decorContent = activity.findViewById(android.R.id.content);
        View firstTarget = steps.get(0).target;

        firstTarget.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (firstTarget.getWidth() <= 0) return;
                firstTarget.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (!isActive) {
                    attachOverlay(decorContent);
                }
            }
        });
    }

    private void attachOverlay(ViewGroup decorContent) {
        isActive = true;
        backCallback.setEnabled(true);

        overlayRoot = new FrameLayout(activity);
        decorContent.addView(overlayRoot, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlayView = new CoachMarkOverlayView(activity);
        overlayRoot.addView(overlayView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tooltipView = LayoutInflater.from(activity)
                .inflate(R.layout.view_coach_mark_tooltip, overlayRoot, false);
        overlayRoot.addView(tooltipView);

        showStep(0);
    }

    private void showStep(int index) {
        currentIndex = index;
        Step step = steps.get(index);

        scrollTargetIntoView(step.target);

        overlayRoot.post(() -> {
            if (!isActive) return;
            positionSpotlightAndTooltip(step);
        });
    }

    private void scrollTargetIntoView(View target) {
        // View.requestRectangleOnScreen() walks the FULL ancestor chain
        // itself, one level at a time, asking each parent in turn to bring
        // the (progressively re-offset) rect into view - correct for any
        // nesting depth. A previous version of this method called
        // NestedScrollView.requestChildRectangleOnScreen(target, ...)
        // directly, which only offsets by target's own left/top relative to
        // its DIRECT parent - wrong for a target like btnActuatorInfo,
        // nested two levels deeper inside its own RelativeLayout. A version
        // after that did the scroll math by hand and called
        // NestedScrollView.scrollTo() directly, which bypassed the nested
        // scrolling dispatch these screens rely on to coordinate with their
        // CoordinatorLayout/AppBarLayout header (app:layout_behavior=
        // "@string/appbar_scrolling_view_behavior") - the header's collapsed
        // state never updated to match, leaving the screen looking stuck.
        // requestRectangleOnScreen() goes through the standard
        // ViewParent/NestedScrollingParent propagation chain, so
        // CoordinatorLayout's own behavior handles that coordination correctly.
        target.requestRectangleOnScreen(new Rect(0, 0, target.getWidth(), target.getHeight()), true);
    }

    private void positionSpotlightAndTooltip(Step step) {
        View target = step.target;

        Rect targetWindowRect = new Rect();
        target.getGlobalVisibleRect(targetWindowRect);
        Rect overlayWindowRect = new Rect();
        overlayRoot.getGlobalVisibleRect(overlayWindowRect);

        float padding = dpToPx(8);
        RectF spotlight = new RectF(
                targetWindowRect.left - overlayWindowRect.left - padding,
                targetWindowRect.top - overlayWindowRect.top - padding,
                targetWindowRect.right - overlayWindowRect.left + padding,
                targetWindowRect.bottom - overlayWindowRect.top + padding);
        overlayView.setSpotlight(spotlight, dpToPx(20));

        TextView tvTitle = tooltipView.findViewById(R.id.tvCoachMarkTitle);
        TextView tvBody = tooltipView.findViewById(R.id.tvCoachMarkBody);
        TextView tvSkip = tooltipView.findViewById(R.id.tvSkipTour);
        MaterialButton btnNext = tooltipView.findViewById(R.id.btnCoachMarkNext);

        tvTitle.setText(step.title);
        tvBody.setText(step.description);
        btnNext.setText(currentIndex == steps.size() - 1 ? "Got it" : "Next");
        tvSkip.setOnClickListener(v -> finish());
        btnNext.setOnClickListener(v -> {
            if (currentIndex + 1 < steps.size()) {
                showStep(currentIndex + 1);
            } else {
                finish();
            }
        });

        int sideInset = (int) dpToPx(16);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tooltipView.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.leftMargin = sideInset;
        lp.rightMargin = sideInset;
        tooltipView.setLayoutParams(lp);

        tooltipView.post(() -> {
            if (!isActive) return;
            int tooltipHeight = tooltipView.getHeight();
            int gap = (int) dpToPx(12);
            boolean roomBelow = spotlight.bottom + gap + tooltipHeight < overlayRoot.getHeight();
            FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) tooltipView.getLayoutParams();
            tlp.gravity = Gravity.TOP;
            tlp.topMargin = roomBelow
                    ? (int) (spotlight.bottom + gap)
                    : Math.max(gap, (int) (spotlight.top - gap - tooltipHeight));
            tooltipView.setLayoutParams(tlp);
        });
    }

    private float dpToPx(float dp) {
        return dp * activity.getResources().getDisplayMetrics().density;
    }

    /** True while the overlay is actually showing (not before start()'s layout wait resolves, not after finish()). */
    public boolean isActive() {
        return isActive;
    }

    /** Ends the tour, wherever it currently is. Safe to call more than once. */
    public void finish() {
        if (!isActive) return;
        isActive = false;
        backCallback.setEnabled(false);
        backCallback.remove();

        if (overlayRoot != null) {
            ViewGroup decorContent = activity.findViewById(android.R.id.content);
            decorContent.removeView(overlayRoot);
            overlayRoot = null;
        }
        if (onDone != null) {
            onDone.run();
        }
    }
}
