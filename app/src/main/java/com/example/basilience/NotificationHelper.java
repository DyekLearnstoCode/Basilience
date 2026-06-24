package com.example.basilience;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import androidx.core.content.ContextCompat;
import android.graphics.PorterDuff;

public class NotificationHelper {

    /**
     * Shows a standard single-button notification dialog.
     */
    public static void showNotification(Context context, String title, String message) {
        if (context == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_notification, null);
        
        TextView tvTitle = view.findViewById(R.id.dialog_title);
        TextView tvMessage = view.findViewById(R.id.dialog_message);
        MaterialButton btnOk = view.findViewById(R.id.dialog_button);
        MaterialButton btnCancel = view.findViewById(R.id.dialog_button_secondary);

        tvTitle.setText(title);
        tvMessage.setText(message);

        ImageView icon = view.findViewById(R.id.dialog_icon);

        int color;
        int iconRes;

        if (title.toLowerCase().contains("ph")) {

            color = ContextCompat.getColor(
                    context,
                    android.R.color.holo_red_dark
            );

            iconRes = R.drawable.ic_science_24;

        } else if (title.toLowerCase().contains("water")) {

            color = ContextCompat.getColor(
                    context,
                    android.R.color.holo_red_dark
            );

            iconRes = R.drawable.ic_water_drop_24;

        } else if (title.toLowerCase().contains("temperature")) {

            color = ContextCompat.getColor(
                    context,
                    android.R.color.holo_orange_dark
            );

            iconRes = R.drawable.ic_device_thermostat_24;

        } else if (title.toLowerCase().contains("ec")) {

            color = ContextCompat.getColor(
                    context,
                    android.R.color.holo_orange_dark
            );

            iconRes = R.drawable.ic_science_24;

        } else {

            color = ContextCompat.getColor(
                    context,
                    android.R.color.holo_red_dark
            );

            iconRes = R.drawable.ic_warning_24;
        }

        if (icon != null) {
            icon.setImageResource(iconRes);
            icon.setColorFilter(
                    color,
                    PorterDuff.Mode.SRC_IN
            );
        }

        tvTitle.setTextColor(color);
        
        // Ensure secondary button is hidden for simple notifications
        if (btnCancel != null) btnCancel.setVisibility(View.GONE);

        AlertDialog dialog = builder.setView(view).create();
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        btnOk.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    public static void showError(Context context, String message) {
        showNotification(context, "Error", message);
    }

    public static void showSuccess(Context context, String message) {
        showNotification(context, "Success", message);
    }

    public interface DialogCallback {
        void onConfirmed();
    }

    /**
     * Shows a confirmation dialog with two buttons (Yes/Cancel).
     */
    public static void showConfirmation(Context context, String title, String message, DialogCallback callback) {
        if (context == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_notification, null);

        TextView tvTitle = view.findViewById(R.id.dialog_title);
        TextView tvMessage = view.findViewById(R.id.dialog_message);
        MaterialButton btnConfirm = view.findViewById(R.id.dialog_button);
        MaterialButton btnCancel = view.findViewById(R.id.dialog_button_secondary);

        tvTitle.setText(title);
        tvMessage.setText(message);
        
        btnConfirm.setText("Yes, Continue");
        
        if (btnCancel != null) {
            btnCancel.setVisibility(View.VISIBLE);
            btnCancel.setText("Cancel");
        }

        AlertDialog dialog = builder.setView(view).create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        btnConfirm.setOnClickListener(v -> {
            dialog.dismiss();
            if (callback != null) callback.onConfirmed();
        });

        dialog.show();
    }
}