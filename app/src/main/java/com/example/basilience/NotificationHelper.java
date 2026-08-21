package com.example.basilience;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

public class NotificationHelper {

    private static final String CONNECTIVITY_PREFS = "connectivity_notifications";
    private static final String WIFI_NOTIFICATION_CHANNEL = "wifi_configuration";
    private static final String LOCAL_AP_DEVICE_KEY = "local_setup_ap_device";
    private static final String LOCAL_AP_CONFIRMED_AT_KEY = "local_setup_ap_confirmed_at";
    private static final String CLOUD_PRESENTATION_PREFIX = "cloud_connectivity_presentation_";
    private static final long LOCAL_AP_CONFIRMATION_VALID_MS = 30_000L;
    public static final long DEFAULT_LOADING_TIMEOUT_MS = 20_000L;
    private static final WeakHashMap<Context, LoadingHandle> ACTIVE_LOADING = new WeakHashMap<>();

    public static final class LoadingHandle {
        private final WeakReference<Context> contextRef;
        private final AlertDialog dialog;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable timeoutAction;
        private boolean finished;

        private LoadingHandle(Context context, AlertDialog dialog, long timeoutMs, Runnable onTimeout) {
            contextRef = new WeakReference<>(context);
            this.dialog = dialog;
            timeoutAction = () -> {
                if (finished) return;
                dismiss();
                Context current = contextRef.get();
                if (current != null && isContextUsable(current) && onTimeout != null) onTimeout.run();
            };
            handler.postDelayed(timeoutAction, timeoutMs);
        }

        public void dismiss() {
            if (finished) return;
            finished = true;
            handler.removeCallbacks(timeoutAction);
            if (dialog.isShowing()) dialog.dismiss();
            Context current = contextRef.get();
            synchronized (ACTIVE_LOADING) {
                if (current != null && ACTIVE_LOADING.get(current) == this) ACTIVE_LOADING.remove(current);
            }
        }

        public boolean isShowing() {
            return !finished && dialog.isShowing();
        }
    }

    public static LoadingHandle showLoading(Context context, String message, Runnable onTimeout) {
        return showLoading(context, message, DEFAULT_LOADING_TIMEOUT_MS, onTimeout);
    }

    public static LoadingHandle showLoading(Context context, String message,
                                            long timeoutMs, Runnable onTimeout) {
        if (!isContextUsable(context)) return null;
        synchronized (ACTIVE_LOADING) {
            LoadingHandle existing = ACTIVE_LOADING.get(context);
            if (existing != null) existing.dismiss();

            View view = LayoutInflater.from(context).inflate(R.layout.dialog_loading, null);
            TextView messageView = view.findViewById(R.id.tvLoadingMessage);
            messageView.setText(message);
            AlertDialog dialog = new AlertDialog.Builder(context).setView(view).create();
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(ignored -> {
                synchronized (ACTIVE_LOADING) {
                    LoadingHandle active = ACTIVE_LOADING.get(context);
                    if (active != null && !active.dialog.isShowing()) ACTIVE_LOADING.remove(context);
                }
            });
            dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }
            LoadingHandle handle = new LoadingHandle(context, dialog, Math.max(1_000L, timeoutMs), onTimeout);
            ACTIVE_LOADING.put(context, handle);
            return handle;
        }
    }

    public static void showWifiConfigurationRequiredNotification(Context context, String deviceId) {
        Context appContext = context.getApplicationContext();
        if (DeviceConnectionManager.getInstance().getConnectivityState().getValue()
                == DeviceConnectivityState.ONLINE
                || isCloudConnectivityPresentationOnline(appContext, deviceId)) {
            return;
        }
        recordSetupApConfirmation(appContext, deviceId);
        NotificationManager manager = (NotificationManager) appContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(appContext,
                        android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        String key = "wifi_configuration_required_" + deviceId;
        android.content.SharedPreferences prefs = appContext.getSharedPreferences(
                CONNECTIVITY_PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(key, false)) return;

        if (manager == null) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    WIFI_NOTIFICATION_CHANNEL,
                    "Wi-Fi Configuration",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }

        Intent intent = new Intent(appContext, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(MainActivity.EXTRA_OPEN_WIFI_CONFIGURATION, true);
        PendingIntent pendingIntent = PendingIntent.getActivity(appContext,
                key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                appContext, WIFI_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.basilience_logo)
                .setContentTitle("Wi-Fi Configuration Required")
                .setContentText("The Basilience device is powered and running locally, but normal Wi-Fi connectivity is unavailable.")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        "The Basilience device is powered and running locally, but normal Wi-Fi connectivity is unavailable. Open Wi-Fi Configuration to reconnect the device."))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        manager.notify(key.hashCode(), builder.build());
        prefs.edit().putBoolean(key, true).apply();
    }

    public static void cancelCloudConnectivityNotification(Context context, String deviceId) {
        if (deviceId == null) return;
        NotificationManager manager = (NotificationManager) context.getApplicationContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(("device_connectivity_" + deviceId).hashCode());
        }
    }

    public static void recordCloudConnectivityPresentation(
            Context context, String deviceId, boolean offline) {
        if (deviceId == null) return;
        context.getApplicationContext().getSharedPreferences(
                CONNECTIVITY_PREFS, Context.MODE_PRIVATE).edit()
                .putString(CLOUD_PRESENTATION_PREFIX + deviceId, offline ? "offline" : "online")
                .apply();
    }

    public static boolean isCloudConnectivityPresentationOffline(
            Context context, String deviceId) {
        return "offline".equals(context.getApplicationContext().getSharedPreferences(
                CONNECTIVITY_PREFS, Context.MODE_PRIVATE)
                .getString(CLOUD_PRESENTATION_PREFIX + deviceId, null));
    }

    public static boolean isCloudConnectivityPresentationOnline(
            Context context, String deviceId) {
        return "online".equals(context.getApplicationContext().getSharedPreferences(
                CONNECTIVITY_PREFS, Context.MODE_PRIVATE)
                .getString(CLOUD_PRESENTATION_PREFIX + deviceId, null));
    }

    public static void markCloudConnectivitySupersededByOrange(
            Context context, String deviceId) {
        if (deviceId == null) return;
        context.getApplicationContext().getSharedPreferences(
                CONNECTIVITY_PREFS, Context.MODE_PRIVATE).edit()
                .putString(CLOUD_PRESENTATION_PREFIX + deviceId, "orange")
                .apply();
    }

    public static void clearWifiConfigurationRequiredNotification(Context context, String deviceId) {
        Context appContext = context.getApplicationContext();
        String key = "wifi_configuration_required_" + deviceId;
        dismissWifiConfigurationRequiredNotification(appContext, deviceId);
        clearSetupApConfirmation(appContext, deviceId);
        appContext.getSharedPreferences(CONNECTIVITY_PREFS, Context.MODE_PRIVATE)
                .edit().remove(key).apply();
    }

    public static void dismissWifiConfigurationRequiredNotification(Context context, String deviceId) {
        Context appContext = context.getApplicationContext();
        String key = "wifi_configuration_required_" + deviceId;
        NotificationManager manager = (NotificationManager) appContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(key.hashCode());
    }

    public static void clearSetupApConfirmation(Context context, String deviceId) {
        Context appContext = context.getApplicationContext();
        android.content.SharedPreferences prefs = appContext.getSharedPreferences(
                CONNECTIVITY_PREFS, Context.MODE_PRIVATE);
        if (deviceId != null && deviceId.equals(prefs.getString(LOCAL_AP_DEVICE_KEY, null))) {
            prefs.edit().remove(LOCAL_AP_DEVICE_KEY).remove(LOCAL_AP_CONFIRMED_AT_KEY).apply();
        }
    }

    public static boolean isSetupApRecentlyConfirmed(Context context, String deviceId) {
        if (deviceId == null) return false;
        android.content.SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(CONNECTIVITY_PREFS, Context.MODE_PRIVATE);
        if (!deviceId.equals(prefs.getString(LOCAL_AP_DEVICE_KEY, null))) return false;
        long confirmedAt = prefs.getLong(LOCAL_AP_CONFIRMED_AT_KEY, 0L);
        return confirmedAt > 0L
                && System.currentTimeMillis() - confirmedAt <= LOCAL_AP_CONFIRMATION_VALID_MS;
    }

    private static void recordSetupApConfirmation(Context context, String deviceId) {
        if (deviceId == null) return;
        context.getSharedPreferences(CONNECTIVITY_PREFS, Context.MODE_PRIVATE).edit()
                .putString(LOCAL_AP_DEVICE_KEY, deviceId)
                .putLong(LOCAL_AP_CONFIRMED_AT_KEY, System.currentTimeMillis())
                .apply();
    }

    public static final class UpdatableParameterDialog {
        private final AlertDialog dialog;
        private final TextView messageView;

        private UpdatableParameterDialog(AlertDialog dialog, TextView messageView) {
            this.dialog = dialog;
            this.messageView = messageView;
        }

        public void updateMessage(String message) {
            messageView.setText(message);
        }

        public boolean isShowing() {
            return dialog.isShowing();
        }

        public void dismiss() {
            dialog.dismiss();
        }
    }

    public interface DialogCallback {
        void onConfirmed();
    }

    public interface TripleActionCallback {
        void onAction1(); // e.g., Share
        void onAction2(); // e.g., Open
    }

    public interface CustomViewDialogCallback {
        void onConfirmed(AlertDialog dialog, View customView);
    }

    /**
     * Standard success notification
     */
    public static void showSuccess(Context context, String message) {
        showBaseDialog(context, "Success", message, R.drawable.ic_harvest_green, R.color.dialog_success, null);
    }

    /** Basilience-styled one-action success dialog for flows that need an OK callback. */
    public static AlertDialog showSuccessAcknowledgement(Context context, String title, String message,
                                                         DialogCallback callback) {
        AlertDialog dialog = showBaseDialog(context, title, message,
                R.drawable.ic_harvest_green, R.color.dialog_success, "OK", null, callback);
        if (dialog != null) dialog.setCancelable(false);
        return dialog;
    }

    /**
     * Standard error notification
     */
    public static void showError(Context context, String message) {
        showError(context, "Error", message);
    }

    public static void showError(Context context, String title, String message) {
        showBaseDialog(context, title, message, R.drawable.ic_warning_24, R.color.dialog_critical, null);
    }

    public static AlertDialog showCriticalAlert(Context context, String title, String message,
                                                DialogCallback acknowledged) {
        AlertDialog dialog = showBaseDialog(context, title, message, R.drawable.ic_warning_24,
                R.color.dialog_critical, "OK", null, acknowledged);
        if (dialog != null) dialog.setCancelable(false);
        return dialog;
    }

    /**
     * Standard warning notification
     */
    public static void showWarning(Context context, String message) {
        showWarning(context, "Warning", message);
    }

    public static void showWarning(Context context, String title, String message) {
        showBaseDialog(context, title, message, R.drawable.ic_warning_24, R.color.dialog_warning, null);
    }

    /**
     * Standard info notification
     */
    public static void showInfo(Context context, String title, String message) {
        showBaseDialog(context, title, message, R.drawable.ic_science_24, R.color.primary, null);
    }

    public static void showInfo(Context context, String title, String message,
                                String acknowledgementLabel) {
        showBaseDialog(context, title, message, R.drawable.ic_science_24, R.color.primary,
                acknowledgementLabel, null, null);
    }

    /** Trackable one-action informational dialog for automatic lifecycle feedback. */
    public static AlertDialog showAutomationAcknowledgement(
            Context context,
            String title,
            String message,
            DialogCallback callback) {
        AlertDialog dialog = showBaseDialog(context, title, message,
                R.drawable.ic_science_24, R.color.actuator_auto, "OK", null, callback);
        if (dialog != null) dialog.setCancelable(false);
        return dialog;
    }

    public static UpdatableParameterDialog showParameterAlert(Context context, String message,
                                                               Runnable onViewParameters,
                                                               Runnable onDismiss) {
        if (!isContextUsable(context)) return null;

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_notification, null);
        applyDialogTheme(context, view, R.color.dialog_warning);
        ImageView icon = view.findViewById(R.id.dialog_icon);
        TextView titleView = view.findViewById(R.id.dialog_title);
        TextView messageView = view.findViewById(R.id.dialog_message);
        MaterialButton primary = view.findViewById(R.id.dialog_button);
        MaterialButton secondary = view.findViewById(R.id.dialog_button_secondary);

        icon.setImageResource(R.drawable.ic_warning_24);
        titleView.setText("Parameter Alert");
        messageView.setText(message);
        primary.setText("View Parameters");
        secondary.setVisibility(View.VISIBLE);
        secondary.setText("Dismiss");

        AlertDialog dialog = new AlertDialog.Builder(context).setView(view).create();
        dialog.setCancelable(false);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        primary.setOnClickListener(v -> {
            dialog.dismiss();
            if (onViewParameters != null) onViewParameters.run();
        });
        secondary.setOnClickListener(v -> {
            dialog.dismiss();
            if (onDismiss != null) onDismiss.run();
        });
        dialog.show();
        return new UpdatableParameterDialog(dialog, messageView);
    }

    /**
     * Standard confirmation dialog
     */
    public static void showConfirmation(Context context, String title, String message, DialogCallback callback) {
        showConfirmation(context, title, message, "Confirm", "Cancel", callback);
    }

    /**
     * Confirmation dialog with custom button labels
     */
    public static void showConfirmation(Context context, String title, String message, 
                                      String positiveLabel, String negativeLabel, DialogCallback callback) {
        showBaseDialog(context, title, message, R.drawable.basilience_logo, R.color.primary, 
                      positiveLabel, negativeLabel, callback);
    }

    public static void showDestructiveConfirmation(Context context, String title, String message,
                                                   String positiveLabel,
                                                   DialogCallback callback) {
        showBaseDialog(context, title, message, R.drawable.ic_warning_24, R.color.alert_red,
                positiveLabel, "Cancel", callback);
    }

    /**
     * Shows a Snackbar
     */
    public static void showSnackbar(View view, String message) {
        if (view == null) return;
        Snackbar.make(view, message, Snackbar.LENGTH_LONG).show();
    }

    public static void showHarvestNotReadyDialog(Context context, String nextDate, String countdown, DialogCallback callback) {
        if (!isContextUsable(context)) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_notification, null);
        applyDialogTheme(context, view, R.color.primary);

        ImageView icon = view.findViewById(R.id.dialog_icon);
        TextView tvTitle = view.findViewById(R.id.dialog_title);
        TextView tvMessage = view.findViewById(R.id.dialog_message);
        MaterialButton btnPrimary = view.findViewById(R.id.dialog_button);
        MaterialButton btnSecondary = view.findViewById(R.id.dialog_button_secondary);

        int blackColor = ContextCompat.getColor(context, R.color.black);

        if (icon != null) {
            icon.setImageResource(R.drawable.ic_harvest_not_ready);
            icon.setColorFilter(ContextCompat.getColor(context, R.color.alert_orange),
                    PorterDuff.Mode.SRC_IN);
        }
        if (tvTitle != null) tvTitle.setText("Harvest Not Ready");

        // Build improved date hierarchy message
        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder();
        ssb.append("Scheduled Date:\n");
        int startDate = ssb.length();
        ssb.append(nextDate);
        ssb.setSpan(new android.text.style.RelativeSizeSpan(1.4f), startDate, ssb.length(), 0);
        ssb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), startDate, ssb.length(), 0);
        ssb.setSpan(new android.text.style.ForegroundColorSpan(blackColor), startDate, ssb.length(), 0);

        ssb.append("\n\n");
        ssb.append(countdown);
        
        tvMessage.setText(ssb);

        AlertDialog dialog = builder.setView(view).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        btnPrimary.setText("Continue");
        btnSecondary.setVisibility(View.VISIBLE);
        btnSecondary.setText("Wait");
        
        btnSecondary.setOnClickListener(v -> dialog.dismiss());
        btnPrimary.setOnClickListener(v -> {
            dialog.dismiss();
            if (callback != null) callback.onConfirmed();
        });

        dialog.show();
    }
    public static void showTripleActionDialog(Context context, String title, String message,
                                            String btn1Text, String btn2Text, String btn3Text,
                                            TripleActionCallback callback) {
        if (!isContextUsable(context)) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_notification, null);
        // Keeps the layout's default full-colour Basilience logo untinted.
        applyDialogTheme(context, view, R.color.primary, false);

        TextView tvTitle = view.findViewById(R.id.dialog_title);
        TextView tvMessage = view.findViewById(R.id.dialog_message);
        MaterialButton btnPrimary = view.findViewById(R.id.dialog_button);
        MaterialButton btnSecondary = view.findViewById(R.id.dialog_button_secondary);
        MaterialButton btnTertiary = view.findViewById(R.id.dialog_button_tertiary);
        
        tvTitle.setText(title);
        tvMessage.setText(message);
        
        btnPrimary.setText(btn1Text);
        btnSecondary.setVisibility(View.VISIBLE);
        btnSecondary.setText(btn2Text);
        btnTertiary.setVisibility(View.VISIBLE);
        btnTertiary.setText(btn3Text);

        AlertDialog dialog = builder.setView(view).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        btnPrimary.setOnClickListener(v -> {
            dialog.dismiss();
            if (callback != null) callback.onAction1();
        });
        
        btnSecondary.setOnClickListener(v -> {
            dialog.dismiss();
            if (callback != null) callback.onAction2();
        });
        btnTertiary.setOnClickListener(v -> dialog.dismiss());
        
        dialog.show();
    }

    /**
     * Base dialog builder used by all standardization methods
     */
    private static AlertDialog showBaseDialog(Context context, String title, String message,
                                     @DrawableRes int iconRes, int colorRes, DialogCallback callback) {
        return showBaseDialog(context, title, message, iconRes, colorRes, "OK", "Cancel", callback);
    }

    private static AlertDialog showBaseDialog(Context context, String title, String message,
                                     @DrawableRes int iconRes, int colorRes,
                                     String positiveLabel, String negativeLabel, DialogCallback callback) {
        if (!isContextUsable(context)) return null;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_notification, null);
        // The full-colour Basilience logo is left untinted; monochrome
        // status vectors still take the dialog's (often severity) colour.
        applyDialogTheme(context, view, colorRes, iconRes != R.drawable.basilience_logo);

        ImageView icon = view.findViewById(R.id.dialog_icon);
        TextView tvTitle = view.findViewById(R.id.dialog_title);
        TextView tvMessage = view.findViewById(R.id.dialog_message);
        MaterialButton btnPrimary = view.findViewById(R.id.dialog_button);
        MaterialButton btnSecondary = view.findViewById(R.id.dialog_button_secondary);

        tvTitle.setText(title);
        tvMessage.setText(message);

        if (icon != null) {
            icon.setImageResource(iconRes);
        }

        AlertDialog dialog = builder.setView(view).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        if (callback != null) {
            btnPrimary.setText(positiveLabel);
            if (negativeLabel != null && !negativeLabel.isEmpty()) {
                btnSecondary.setVisibility(View.VISIBLE);
                btnSecondary.setText(negativeLabel);
                btnSecondary.setOnClickListener(v -> dialog.dismiss());
            } else {
                btnSecondary.setVisibility(View.GONE);
            }
            btnPrimary.setOnClickListener(v -> {
                dialog.dismiss();
                callback.onConfirmed();
            });
        } else {
            btnPrimary.setText(positiveLabel);
            btnSecondary.setVisibility(View.GONE);
            btnPrimary.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
        return dialog;
    }

    /**
     * Scrollable, left-aligned guide/help dialog.
     *
     * <p>Unlike {@link #showInfo}, which centres its body text and cannot
     * scroll, this renders long-form help as heading/body pairs aligned to
     * the start edge, inside a ScrollView capped to a share of the screen
     * so the close action stays reachable on short phones.
     *
     * @param sections each entry is {heading, body}; a blank heading renders
     *                 body-only, which suits a short lead-in paragraph
     */
    public static void showGuideDialog(Context context, String title, String[][] sections,
                                       String closeLabel) {
        if (!isContextUsable(context)) return;

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_report_guide, null);
        TextView tvTitle = view.findViewById(R.id.guideTitle);
        LinearLayout container = view.findViewById(R.id.guideSections);
        ScrollView scroll = view.findViewById(R.id.guideScroll);
        MaterialButton btnClose = view.findViewById(R.id.guideClose);

        tvTitle.setText(title);
        if (closeLabel != null && !closeLabel.isEmpty()) btnClose.setText(closeLabel);

        float density = context.getResources().getDisplayMetrics().density;
        for (int i = 0; i < sections.length; i++) {
            String heading = sections[i].length > 0 ? sections[i][0] : null;
            String body = sections[i].length > 1 ? sections[i][1] : null;

            if (heading != null && !heading.isEmpty()) {
                TextView tvHeading = new TextView(context);
                tvHeading.setText(heading);
                tvHeading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                tvHeading.setTextColor(ContextCompat.getColor(context, R.color.text_dark));
                tvHeading.setTypeface(tvHeading.getTypeface(), android.graphics.Typeface.BOLD);
                tvHeading.setGravity(android.view.Gravity.START);
                tvHeading.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                hp.topMargin = (int) ((i == 0 ? 0 : 18) * density);
                hp.bottomMargin = (int) (6 * density);
                container.addView(tvHeading, hp);
            }

            if (body != null && !body.isEmpty()) {
                TextView tvBody = new TextView(context);
                tvBody.setText(body);
                tvBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                tvBody.setTextColor(ContextCompat.getColor(context, R.color.nav_inactive));
                tvBody.setLineSpacing(4 * density, 1f);
                tvBody.setGravity(android.view.Gravity.START);
                tvBody.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                if (heading == null || heading.isEmpty()) {
                    bp.topMargin = (int) ((i == 0 ? 0 : 18) * density);
                }
                container.addView(tvBody, bp);
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(context).setView(view).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        btnClose.setOnClickListener(v -> dialog.dismiss());

        // Let the content size itself naturally, then clamp only if it would
        // otherwise push the close button past the bottom of the screen.
        int maxScrollHeight = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.62f);
        scroll.post(() -> {
            if (scroll.getHeight() > maxScrollHeight) {
                ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                lp.height = maxScrollHeight;
                scroll.setLayoutParams(lp);
            }
        });

        dialog.show();
    }

    public interface MultiActionCallback {
        void onItemSelected(int index);
    }

    /**
     * Shows a list selection dialog using the custom Basilience UI
     */
    public static void showSelectionDialog(Context context, String title, String[] items, MultiActionCallback callback) {
        if (!isContextUsable(context)) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_notification, null);
        // Keeps the layout's default full-colour Basilience logo untinted.
        applyDialogTheme(context, view, R.color.primary, false);

        TextView tvTitle = view.findViewById(R.id.dialog_title);
        TextView tvMessage = view.findViewById(R.id.dialog_message);
        tvMessage.setVisibility(View.GONE); // No message for list selection

        LinearLayout container = (LinearLayout) tvMessage.getParent();
        
        // Hide standard buttons
        view.findViewById(R.id.dialog_button).setVisibility(View.GONE);
        view.findViewById(R.id.dialog_button_secondary).setVisibility(View.GONE);

        tvTitle.setText(title);

        AlertDialog dialog = builder.setView(view).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        // Add item buttons.
        //
        // These are built in code rather than inflated, so they used to fall
        // back to the Material library's default filled style - and because
        // Theme.Basilience never declares a colorPrimary, that default
        // resolved to the library's purple. Every colour is therefore set
        // explicitly here: a white list-row surface with a thin neutral
        // border, dark-green label and a soft green ripple.
        float density = context.getResources().getDisplayMetrics().density;
        for (int i = 0; i < items.length; i++) {
            MaterialButton btn = new MaterialButton(context, null,
                    com.google.android.material.R.attr.materialButtonStyle);
            int index = i;
            btn.setText(items[i]);
            btn.setAllCaps(false);
            btn.setCornerRadius((int) (12 * density));
            btn.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.white)));
            btn.setStrokeColor(ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.status_completed_border)));
            btn.setStrokeWidth((int) density);
            btn.setTextColor(ContextCompat.getColor(context, R.color.text_dark));
            btn.setRippleColor(ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.background_light)));
            btn.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            btn.setPadding((int) (16 * density), (int) (12 * density),
                    (int) (16 * density), (int) (12 * density));
            btn.setInsetTop(0);
            btn.setInsetBottom(0);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, (int) (8 * density));
            btn.setLayoutParams(params);

            btn.setOnClickListener(v -> {
                dialog.dismiss();
                if (callback != null) callback.onItemSelected(index);
            });
            container.addView(btn);
        }

        dialog.show();
    }

    /**
     * Shows a dialog with a custom view (for forms) while maintaining the Basilience UI style
     */
    public static AlertDialog showCustomViewDialog(Context context, String title, View customView) {
        if (!isContextUsable(context)) return null;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View root = LayoutInflater.from(context).inflate(R.layout.dialog_custom_notification, null);
        applyDialogTheme(context, root, R.color.primary, false);

        TextView tvTitle = root.findViewById(R.id.dialog_title);
        TextView tvMessage = root.findViewById(R.id.dialog_message);
        tvMessage.setVisibility(View.GONE);
        
        // Hide standard buttons as custom forms usually have their own
        root.findViewById(R.id.dialog_button).setVisibility(View.GONE);
        root.findViewById(R.id.dialog_button_secondary).setVisibility(View.GONE);

        tvTitle.setText(title);

        LinearLayout container = (LinearLayout) tvMessage.getParent();
        container.addView(customView, container.indexOfChild(tvMessage));

        AlertDialog dialog = builder.setView(root).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        
        dialog.show();
        return dialog;
    }

    public static AlertDialog showCustomViewDialog(Context context, String title, String message,
                                                   View customView, String positiveLabel,
                                                   String negativeLabel,
                                                   CustomViewDialogCallback callback) {
        if (!isContextUsable(context)) return null;

        View root = LayoutInflater.from(context).inflate(R.layout.dialog_custom_notification, null);
        applyDialogTheme(context, root, R.color.primary, false);

        TextView titleView = root.findViewById(R.id.dialog_title);
        TextView messageView = root.findViewById(R.id.dialog_message);
        MaterialButton primary = root.findViewById(R.id.dialog_button);
        MaterialButton secondary = root.findViewById(R.id.dialog_button_secondary);
        LinearLayout container = (LinearLayout) messageView.getParent();

        titleView.setText(title);
        messageView.setText(message);
        container.addView(customView, container.indexOfChild(messageView) + 1);
        primary.setText(positiveLabel);
        secondary.setVisibility(View.VISIBLE);
        secondary.setText(negativeLabel);

        AlertDialog dialog = new AlertDialog.Builder(context).setView(root).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        secondary.setOnClickListener(v -> dialog.dismiss());
        primary.setOnClickListener(v -> {
            if (callback != null) callback.onConfirmed(dialog, customView);
        });
        dialog.show();
        return dialog;
    }

    /**
     * Legacy support - will be phased out
     */
    public static void showNotification(Context context, String title, String message) {
        if (title.equalsIgnoreCase("Success")) {
            showSuccess(context, message);
        } else if (title.equalsIgnoreCase("Error")) {
            showError(context, message);
        } else {
            showInfo(context, title, message);
        }
    }

    /**
     * Applies standard styling to custom dialog views
     */
    private static void applyDialogTheme(Context context, View view, int colorRes) {
        applyDialogTheme(context, view, colorRes, true);
    }

    /**
     * @param tintIcon whether the dialog icon should be flattened to the
     *                 dialog colour. True for the monochrome status vectors
     *                 (warning/science/harvest icons), which are drawn as
     *                 silhouettes and rely on this for their severity
     *                 colour. False when the icon is the full-colour
     *                 basilience_logo.png: a SRC_IN filter would flatten the
     *                 real artwork into a single-colour blob, which is what
     *                 made the branded dialogs look washed out.
     */
    private static void applyDialogTheme(Context context, View view, int colorRes, boolean tintIcon) {
        int color = ContextCompat.getColor(context, colorRes);
        if (view instanceof MaterialCardView) {
            ((MaterialCardView) view).setStrokeColor(color);
        }

        ImageView icon = view.findViewById(R.id.dialog_icon);
        TextView tvTitle = view.findViewById(R.id.dialog_title);
        MaterialButton btnPrimary = view.findViewById(R.id.dialog_button);
        MaterialButton btnSecondary = view.findViewById(R.id.dialog_button_secondary);
        MaterialButton btnTertiary = view.findViewById(R.id.dialog_button_tertiary);

        if (tvTitle != null) tvTitle.setTextColor(color);
        if (icon != null) {
            if (tintIcon) {
                icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            } else {
                icon.clearColorFilter();
            }
        }

        if (btnPrimary != null) {
            btnPrimary.setBackgroundTintList(ColorStateList.valueOf(color));
        }
        
        if (btnSecondary != null) {
            btnSecondary.setTextColor(color);
            btnSecondary.setStrokeColor(ColorStateList.valueOf(color));
        }

        if (btnTertiary != null) {
            btnTertiary.setTextColor(color);
        }
    }

    private static boolean isContextUsable(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                Activity activity = (Activity) current;
                return !activity.isFinishing() && !activity.isDestroyed();
            }
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) break;
            current = base;
        }
        return false;
    }
}
