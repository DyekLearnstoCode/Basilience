package com.example.basilience;

import android.content.Context;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

/**
 * Small shared "Edit Video Tutorial" form (title/description/URL/optional
 * thumbnail) used by both the Hardware Guide's per-device video override
 * (HardwareGuideFragment, HardwareGuideRepository) and the Mobile Guide's
 * single global video config (MobileGuideFragment, MobileGuideVideoRepository).
 * Kept as one dialog rather than duplicated per-guide UI since the fields and
 * validation are identical - only where the values get saved differs, which
 * the caller supplies via {@link SaveCallback}.
 */
final class VideoGuideEditorDialog {

    private VideoGuideEditorDialog() {}

    interface SaveCallback {
        void onSave(String title, String description, String url, String thumbnailUrl);
    }

    static void show(Context context, @Nullable String currentTitle, @Nullable String currentDescription,
                      @Nullable String currentUrl, @Nullable String currentThumbnailUrl, SaveCallback callback) {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_video_guide, null);
        TextInputEditText etTitle = dialogView.findViewById(R.id.etVideoTitle);
        TextInputEditText etDescription = dialogView.findViewById(R.id.etVideoDescription);
        TextInputEditText etUrl = dialogView.findViewById(R.id.etVideoUrl);
        TextInputEditText etThumbnailUrl = dialogView.findViewById(R.id.etVideoThumbnailUrl);
        MaterialButton btnSave = dialogView.findViewById(R.id.btnSaveVideo);
        MaterialButton btnCancel = dialogView.findViewById(R.id.btnCancel);

        if (currentTitle != null) etTitle.setText(currentTitle);
        if (currentDescription != null) etDescription.setText(currentDescription);
        if (currentUrl != null) etUrl.setText(currentUrl);
        if (currentThumbnailUrl != null) etThumbnailUrl.setText(currentThumbnailUrl);

        AlertDialog dialog = NotificationHelper.showCustomViewDialog(context, "Edit Video Tutorial", dialogView);
        if (dialog == null) return;

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                NotificationHelper.hideKeyboard(v);
                String title = textOf(etTitle);
                String description = textOf(etDescription);
                String url = textOf(etUrl);
                String thumbnailUrl = textOf(etThumbnailUrl);

                // Blank URL is the intended "Coming Soon" state, not an error -
                // only a NON-blank value that isn't a plausible web URL is
                // rejected here, so a malformed value never reaches Firestore
                // in the first place (GuideSectionAdapter's own
                // ActivityNotFoundException catch is the last line of defense
                // for anything that still gets through, e.g. a URL nothing on
                // the device can open).
                if (!url.isEmpty() && !Patterns.WEB_URL.matcher(url).matches()) {
                    NotificationHelper.showError(context, "Enter a valid video URL (starting with http:// or https://), or leave it blank for Coming Soon.");
                    return;
                }
                if (!thumbnailUrl.isEmpty() && !Patterns.WEB_URL.matcher(thumbnailUrl).matches()) {
                    NotificationHelper.showError(context, "Enter a valid thumbnail URL, or leave it blank.");
                    return;
                }

                callback.onSave(title, description, url, thumbnailUrl);
                dialog.dismiss();
            });
        }
    }

    private static String textOf(TextInputEditText editText) {
        return editText.getText() != null ? editText.getText().toString().trim() : "";
    }
}
