package com.example.basilience;

import android.graphics.Color;
import android.view.View;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Edge-to-edge for every Activity, handled once at the Activity's content
 * root instead of per screen.
 *
 * From targetSdk 35 Android draws behind the status and navigation bars and
 * ignores statusBarColor, so content has to be moved out from under them
 * explicitly. enable() opts in on every Android version (so the look does not
 * differ by API level) and fit() reproduces the old "content sits between the
 * bars" layout: the top strip shows the window background (celadon, the same
 * colour statusBarColor used to paint), and no fragment or layout needs its
 * own inset padding.
 */
final class SystemBarInsets {

    private SystemBarInsets() {}

    /**
     * Call before setContentView(). Bars are transparent with dark icons on
     * every version: the app is light-only (no night theme, force-dark off),
     * so the auto style, which follows the phone's dark mode, would draw
     * white icons over the light celadon header.
     */
    static void enable(ComponentActivity activity) {
        EdgeToEdge.enable(activity,
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                SystemBarStyle.light(Color.TRANSPARENT, Color.argb(0x80, 0x1B, 0x1B, 0x1B)));
    }

    /**
     * Pads contentRoot by the status bar, display cutout and side insets, and
     * decides who takes the bottom inset (navigation bar, or the keyboard when
     * includeKeyboard is set and it is taller):
     *
     * - selfHandlingChild: a child that pads itself for the bottom inset
     *   (MainActivity's BottomNavigationView, which extends its own white
     *   background under the navigation bar). While it is visible the bottom
     *   inset is passed through to it untouched.
     * - paddedView: otherwise this view gets the inset as extra bottom
     *   padding, so its own background reaches the screen edge and content
     *   never ends up under the navigation bar. It may be contentRoot itself.
     *
     * Call ViewCompat.requestApplyInsets(contentRoot) when selfHandlingChild's
     * visibility changes.
     */
    static void fit(View contentRoot, @Nullable View selfHandlingChild, @Nullable View paddedView,
                    boolean includeKeyboard) {
        final int left = contentRoot.getPaddingLeft();
        final int top = contentRoot.getPaddingTop();
        final int right = contentRoot.getPaddingRight();
        final int rootBottom = contentRoot.getPaddingBottom();
        final int paddedBase = paddedView != null ? paddedView.getPaddingBottom() : 0;
        final int barTypes = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();

        ViewCompat.setOnApplyWindowInsetsListener(contentRoot, (view, insets) -> {
            Insets bars = insets.getInsets(barTypes);
            int bottomInset = includeKeyboard
                    ? Math.max(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                    : bars.bottom;

            boolean childHandles = selfHandlingChild != null && selfHandlingChild.getVisibility() == View.VISIBLE;
            boolean padHere = !childHandles && paddedView != null;

            boolean paddedIsRoot = paddedView == view;
            view.setPadding(left + bars.left, top + bars.top, right + bars.right,
                    rootBottom + (padHere && paddedIsRoot ? bottomInset : 0));
            if (paddedView != null && !paddedIsRoot) {
                paddedView.setPadding(paddedView.getPaddingLeft(), paddedView.getPaddingTop(),
                        paddedView.getPaddingRight(), paddedBase + (padHere ? bottomInset : 0));
            }

            // Only the bottom inset can still be needed downstream.
            Insets remaining = Insets.of(0, 0, 0, childHandles ? bars.bottom : 0);
            return new WindowInsetsCompat.Builder(insets).setInsets(barTypes, remaining).build();
        });
    }
}
