package com.example.basilience;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;

public class NotificationHelper {

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
        showBaseDialog(context, "Success", message, R.drawable.ic_harvest_green, R.color.primary, null);
    }

    /** Basilience-styled one-action success dialog for flows that need an OK callback. */
    public static void showSuccessAcknowledgement(Context context, String title, String message,
                                                  DialogCallback callback) {
        AlertDialog dialog = showBaseDialog(context, title, message,
                R.drawable.ic_harvest_green, R.color.primary, "OK", null, callback);
        if (dialog != null) dialog.setCancelable(false);
    }

    /**
     * Standard error notification
     */
    public static void showError(Context context, String message) {
        showError(context, "Error", message);
    }

    public static void showError(Context context, String title, String message) {
        showBaseDialog(context, title, message, R.drawable.ic_warning_24, R.color.alert_red, null);
    }

    public static AlertDialog showCriticalAlert(Context context, String title, String message,
                                                DialogCallback acknowledged) {
        AlertDialog dialog = showBaseDialog(context, title, message, R.drawable.ic_warning_24,
                R.color.alert_red, "OK", null, acknowledged);
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
        showBaseDialog(context, title, message, R.drawable.ic_warning_24, R.color.alert_orange, null);
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

    public static UpdatableParameterDialog showParameterAlert(Context context, String message,
                                                               Runnable onViewParameters,
                                                               Runnable onDismiss) {
        if (!isContextUsable(context)) return null;

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_notification, null);
        applyDialogTheme(context, view, R.color.alert_orange);
        ImageView icon = view.findViewById(R.id.dialog_icon);
        TextView titleView = view.findViewById(R.id.dialog_title);
        TextView messageView = view.findViewById(R.id.dialog_message);
        MaterialButton primary = view.findViewById(R.id.dialog_button);
        MaterialButton secondary = view.findViewById(R.id.dialog_button_secondary);

        icon.setImageResource(R.drawable.ic_warning_24);
        titleView.setText("Parameter Alert");
        messageView.setText(message);
        primary.setText("VIEW PARAMETERS");
        secondary.setVisibility(View.VISIBLE);
        secondary.setText("DISMISS");

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

        btnPrimary.setText("CONTINUE");
        btnSecondary.setVisibility(View.VISIBLE);
        btnSecondary.setText("WAIT");
        
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
        applyDialogTheme(context, view, R.color.primary);

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
        applyDialogTheme(context, view, colorRes);

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
        applyDialogTheme(context, view, R.color.primary);

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

        // Add item buttons
        for (int i = 0; i < items.length; i++) {
            MaterialButton btn = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonStyle);
            int index = i;
            btn.setText(items[i]);
            btn.setAllCaps(false);
            btn.setCornerRadius((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, context.getResources().getDisplayMetrics()));
            
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, context.getResources().getDisplayMetrics()));
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
        applyDialogTheme(context, root, R.color.celadon);

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
        applyDialogTheme(context, root, R.color.celadon);

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
        if (icon != null) icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        
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
