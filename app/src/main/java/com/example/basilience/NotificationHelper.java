package com.example.basilience;

import android.content.Context;
import android.graphics.PorterDuff;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

public class NotificationHelper {

    public interface DialogCallback {
        void onConfirmed();
    }

    public interface TripleActionCallback {
        void onAction1(); // e.g., Share
        void onAction2(); // e.g., Open
    }

    /**
     * Standard success notification
     */
    public static void showSuccess(Context context, String message) {
        showBaseDialog(context, "Success", message, R.drawable.ic_harvest_green, R.color.primary, null);
    }

    /**
     * Standard error notification
     */
    public static void showError(Context context, String message) {
        showBaseDialog(context, "Error", message, R.drawable.ic_warning_24, R.color.alert_red, null);
    }

    /**
     * Standard warning notification
     */
    public static void showWarning(Context context, String message) {
        showBaseDialog(context, "Warning", message, R.drawable.ic_warning_24, R.color.alert_orange, null);
    }

    /**
     * Standard info notification
     */
    public static void showInfo(Context context, String title, String message) {
        showBaseDialog(context, title, message, R.drawable.ic_science_24, R.color.primary, null);
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

    /**
     * Shows a Snackbar
     */
    public static void showSnackbar(View view, String message) {
        if (view == null) return;
        Snackbar.make(view, message, Snackbar.LENGTH_LONG).show();
    }

    public static void showHarvestNotReadyDialog(Context context, String nextDate, String countdown, DialogCallback callback) {
        if (context == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_notification, null);

        ImageView icon = view.findViewById(R.id.dialog_icon);
        TextView tvTitle = view.findViewById(R.id.dialog_title);
        TextView tvMessage = view.findViewById(R.id.dialog_message);
        MaterialButton btnPrimary = view.findViewById(R.id.dialog_button);
        MaterialButton btnSecondary = view.findViewById(R.id.dialog_button_secondary);

        int primaryColor = ContextCompat.getColor(context, R.color.primary);
        int blackColor = ContextCompat.getColor(context, R.color.black);

        icon.setImageResource(R.drawable.ic_harvest); // Ensure this exists
        icon.setColorFilter(primaryColor, PorterDuff.Mode.SRC_IN);

        tvTitle.setText("Harvest Not Ready");
        tvTitle.setTextColor(primaryColor);

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
        if (context == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_notification, null);

        TextView tvTitle = view.findViewById(R.id.dialog_title);
        TextView tvMessage = view.findViewById(R.id.dialog_message);
        MaterialButton btnPrimary = view.findViewById(R.id.dialog_button);
        MaterialButton btnSecondary = view.findViewById(R.id.dialog_button_secondary);
        
        tvTitle.setText(title);
        tvMessage.setText(message);
        
        btnPrimary.setText(btn1Text);
        btnSecondary.setVisibility(View.VISIBLE);
        btnSecondary.setText(btn2Text);

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
        
        dialog.show();
    }

    /**
     * Base dialog builder used by all standardization methods
     */
    private static void showBaseDialog(Context context, String title, String message, 
                                     @DrawableRes int iconRes, int colorRes, DialogCallback callback) {
        showBaseDialog(context, title, message, iconRes, colorRes, "OK", "Cancel", callback);
    }

    private static void showBaseDialog(Context context, String title, String message, 
                                     @DrawableRes int iconRes, int colorRes,
                                     String positiveLabel, String negativeLabel, DialogCallback callback) {
        if (context == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_notification, null);

        ImageView icon = view.findViewById(R.id.dialog_icon);
        TextView tvTitle = view.findViewById(R.id.dialog_title);
        TextView tvMessage = view.findViewById(R.id.dialog_message);
        MaterialButton btnPrimary = view.findViewById(R.id.dialog_button);
        MaterialButton btnSecondary = view.findViewById(R.id.dialog_button_secondary);

        int color = ContextCompat.getColor(context, colorRes);

        tvTitle.setText(title);
        tvTitle.setTextColor(color);
        tvMessage.setText(message);

        if (icon != null) {
            icon.setImageResource(iconRes);
            icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }

        AlertDialog dialog = builder.setView(view).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        if (callback != null) {
            btnPrimary.setText(positiveLabel);
            btnSecondary.setVisibility(View.VISIBLE);
            btnSecondary.setText(negativeLabel);
            btnSecondary.setOnClickListener(v -> dialog.dismiss());
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
}