package com.example.basilience;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;

public class NotificationHelper {

    public static void showNotification(Context context, String title, String message) {
        if (context == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_notification, null);
        
        TextView tvTitle = view.findViewById(R.id.dialog_title);
        TextView tvMessage = view.findViewById(R.id.dialog_message);
        MaterialButton btnOk = view.findViewById(R.id.dialog_button);
        ImageView imgIcon = view.findViewById(R.id.dialog_icon);

        tvTitle.setText(title);
        tvMessage.setText(message);

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
}