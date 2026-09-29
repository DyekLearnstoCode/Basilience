package com.example.basilience;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.bumptech.glide.Glide;
import com.example.basilience.models.GuideSection;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.firestore.DocumentSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin-only structured editor for one Hardware Guide component, scoped to a
 * single device ({@code devices/{deviceId}/hardwareGuide/{componentKey}}).
 * Farmers never reach this screen - HardwareGuideFragment only offers the
 * "Edit Guide" entry point when the signed-in role is Admin (also enforced
 * server-side by firestore.rules, not just this UI gate).
 *
 * <p>The four list fields (Normal Operation, Indicators, Common Problems,
 * Troubleshooting) are plain LinearLayout rows rather than a RecyclerView -
 * each holds at most a handful of short entries, so add/remove/reorder via
 * simple button-driven view moves is enough without ItemTouchHelper/drag
 * machinery or a second adapter class.
 */
public class HardwareGuideEditorFragment extends Fragment {

    public static final String ARG_DEVICE_ID = "deviceId";
    public static final String ARG_COMPONENT_KEY = "componentKey";

    private final HardwareGuideRepository repository = new HardwareGuideRepository();

    private String deviceId;
    private HardwareComponentKey componentKey;
    @Nullable
    private GuideSection defaultSection;

    private ImageView ivEditorImage;
    private MaterialButton btnUploadImage, btnRestoreDefaultImage, btnSaveGuide, btnRestoreDefaultGuide;
    private TextInputEditText etPurpose;
    private LinearLayout containerNormalOperation, containerIndicators, containerCommonProblems, containerTroubleshooting;
    private ProgressBar progressEditor;

    // A newly picked photo staged for upload on Save, or null if the Admin
    // hasn't picked a new one this visit.
    @Nullable
    private Uri pendingImageUri;
    // True once "Restore Default Image" has been confirmed this visit - applied
    // immediately (see confirmRestoreDefaultImage), but the actual Firestore
    // field-delete is deferred to Save so a mis-tap can still be abandoned by
    // navigating away without saving.
    private boolean imageRestoreToDefaultPending;

    private final ActivityResultLauncher<PickVisualMediaRequest> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri == null || !isAdded()) return;
                pendingImageUri = uri;
                imageRestoreToDefaultPending = false;
                Glide.with(this).load(uri).into(ivEditorImage);
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_hardware_guide_editor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Defense-in-depth: HardwareGuideFragment only offers the "Edit Guide"
        // entry point to Admins, and firestore.rules independently rejects an
        // unauthorized write server-side, but neither stops a Farmer who
        // deep-links straight to this destination from seeing the editor UI
        // render first. Checked before touching args/deviceId/componentKey or
        // any Firestore state, so nothing here is initialized for a role that
        // can never use it.
        if (!isAdmin()) {
            NotificationHelper.showError(requireContext(), "You don't have permission to edit this guide.");
            Navigation.findNavController(view).popBackStack();
            return;
        }

        Bundle args = getArguments();
        deviceId = args != null ? args.getString(ARG_DEVICE_ID) : null;
        String keyName = args != null ? args.getString(ARG_COMPONENT_KEY) : null;
        componentKey = keyName != null ? safeValueOf(keyName) : null;

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> Navigation.findNavController(view).popBackStack());
        }

        ivEditorImage = view.findViewById(R.id.ivEditorImage);
        btnUploadImage = view.findViewById(R.id.btnUploadImage);
        btnRestoreDefaultImage = view.findViewById(R.id.btnRestoreDefaultImage);
        btnSaveGuide = view.findViewById(R.id.btnSaveGuide);
        btnRestoreDefaultGuide = view.findViewById(R.id.btnRestoreDefaultGuide);
        etPurpose = view.findViewById(R.id.etPurpose);
        containerNormalOperation = view.findViewById(R.id.containerNormalOperation);
        containerIndicators = view.findViewById(R.id.containerIndicators);
        containerCommonProblems = view.findViewById(R.id.containerCommonProblems);
        containerTroubleshooting = view.findViewById(R.id.containerTroubleshooting);
        progressEditor = view.findViewById(R.id.progressEditor);
        TextView tvTitle = view.findViewById(R.id.tvEditorComponentTitle);

        if (deviceId == null || componentKey == null) {
            NotificationHelper.showError(requireContext(), "Could not open this component's guide editor.");
            Navigation.findNavController(view).popBackStack();
            return;
        }

        defaultSection = HardwareGuideContent.byKey(componentKey);
        if (tvTitle != null && defaultSection != null) tvTitle.setText("Edit Guide — " + defaultSection.getTitle());

        btnUploadImage.setOnClickListener(v -> pickImageLauncher.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));
        btnRestoreDefaultImage.setOnClickListener(v -> confirmRestoreDefaultImage());
        view.findViewById(R.id.btnAddNormalOperation).setOnClickListener(v -> addRow(containerNormalOperation, ""));
        view.findViewById(R.id.btnAddIndicator).setOnClickListener(v -> addRow(containerIndicators, ""));
        view.findViewById(R.id.btnAddCommonProblem).setOnClickListener(v -> addRow(containerCommonProblems, ""));
        view.findViewById(R.id.btnAddTroubleshootingStep).setOnClickListener(v -> addRow(containerTroubleshooting, ""));
        btnSaveGuide.setOnClickListener(v -> saveChanges());
        btnRestoreDefaultGuide.setOnClickListener(v -> confirmRestoreDefaultGuide());

        loadCurrentGuide();
    }

    private void loadCurrentGuide() {
        setLoading(true);
        repository.getOverride(deviceId, componentKey).addOnCompleteListener(task -> {
            if (!isAdded()) return;
            setLoading(false);
            DocumentSnapshot doc = task.isSuccessful() ? task.getResult() : null;
            boolean hasOverride = doc != null && doc.exists();

            String purpose = hasOverride ? doc.getString("purpose") : null;
            List<String> normalOperation = hasOverride ? stringList(doc, "normalOperation") : null;
            List<String> indicators = hasOverride ? stringList(doc, "indicators") : null;
            List<String> commonProblems = hasOverride ? stringList(doc, "commonProblems") : null;
            List<String> troubleshooting = hasOverride ? stringList(doc, "troubleshooting") : null;
            String imageUrl = hasOverride ? doc.getString("imageUrl") : null;

            etPurpose.setText(purpose != null ? purpose : defaultString(defaultSection != null ? defaultSection.getPurpose() : null));
            populateRows(containerNormalOperation, normalOperation != null ? normalOperation : defaultList(defaultSection != null ? defaultSection.getSteps() : null));
            populateRows(containerIndicators, indicators != null ? indicators : defaultList(defaultSection != null ? defaultSection.getIndicators() : null));
            populateRows(containerCommonProblems, commonProblems != null ? commonProblems : defaultList(defaultSection != null ? defaultSection.getCommonProblems() : null));
            populateRows(containerTroubleshooting, troubleshooting != null ? troubleshooting : defaultList(defaultSection != null ? defaultSection.getTroubleshooting() : null));

            if (imageUrl != null) {
                Glide.with(this).load(imageUrl).into(ivEditorImage);
            } else if (defaultSection != null && defaultSection.getImageResId() != 0) {
                ivEditorImage.setImageResource(defaultSection.getImageResId());
            }
        });
    }

    private static String defaultString(@Nullable String value) {
        return value != null ? value : "";
    }

    private static List<String> defaultList(@Nullable List<String> list) {
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static List<String> stringList(DocumentSnapshot doc, String field) {
        Object raw = doc.get(field);
        return raw instanceof List ? new ArrayList<>((List<String>) raw) : null;
    }

    // --- Reorderable list rows ---

    private void addRow(LinearLayout container, String initialText) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_editable_list_row, container, false);
        TextInputEditText editText = row.findViewById(R.id.etRowText);
        editText.setText(initialText);
        row.findViewById(R.id.btnRemoveRow).setOnClickListener(v -> container.removeView(row));
        row.findViewById(R.id.btnMoveUp).setOnClickListener(v -> moveRow(container, row, -1));
        row.findViewById(R.id.btnMoveDown).setOnClickListener(v -> moveRow(container, row, 1));
        container.addView(row);
    }

    private void populateRows(LinearLayout container, List<String> items) {
        container.removeAllViews();
        for (String item : items) addRow(container, item);
    }

    private void moveRow(LinearLayout container, View row, int direction) {
        int index = container.indexOfChild(row);
        int target = index + direction;
        if (target < 0 || target >= container.getChildCount()) return;
        container.removeViewAt(index);
        container.addView(row, target);
    }

    private List<String> readRows(LinearLayout container) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            TextInputEditText editText = container.getChildAt(i).findViewById(R.id.etRowText);
            String text = editText.getText() != null ? editText.getText().toString().trim() : "";
            if (!text.isEmpty()) values.add(text);
        }
        return values;
    }

    // --- Save / restore ---

    private void saveChanges() {
        String purpose = etPurpose.getText() != null ? etPurpose.getText().toString().trim() : "";
        List<String> normalOperation = readRows(containerNormalOperation);
        List<String> indicators = readRows(containerIndicators);
        List<String> commonProblems = readRows(containerCommonProblems);
        List<String> troubleshooting = readRows(containerTroubleshooting);
        Uri imageToUpload = pendingImageUri;

        setLoading(true);
        Runnable writeGuide = () -> repository.saveOverride(deviceId, componentKey, purpose, normalOperation,
                        indicators, commonProblems, troubleshooting, imageToUpload)
                .addOnCompleteListener(task -> {
                    if (!isAdded()) return;
                    setLoading(false);
                    if (task.isSuccessful()) {
                        NotificationHelper.showSuccess(requireContext(), "Guide changes saved.");
                        Navigation.findNavController(requireView()).popBackStack();
                    } else {
                        NotificationHelper.showError(requireContext(), "Unable to save changes. Please try again.");
                    }
                });

        // A pending "Restore Default Image" is written first so it can never
        // race with / be overwritten by the text-fields save below.
        if (imageRestoreToDefaultPending) {
            repository.restoreDefaultImage(deviceId, componentKey).addOnCompleteListener(t -> writeGuide.run());
        } else {
            writeGuide.run();
        }
    }

    private void confirmRestoreDefaultImage() {
        NotificationHelper.showConfirmation(requireContext(), "Restore Default Image?",
                "This component's photo will go back to the factory default photo once you save.",
                "Restore", "Cancel", () -> {
                    pendingImageUri = null;
                    imageRestoreToDefaultPending = true;
                    if (defaultSection != null && defaultSection.getImageResId() != 0) {
                        ivEditorImage.setImageResource(defaultSection.getImageResId());
                    } else {
                        ivEditorImage.setImageDrawable(null);
                    }
                });
    }

    private void confirmRestoreDefaultGuide() {
        NotificationHelper.showConfirmation(requireContext(), "Restore Default Guide?",
                "All customizations for this component - text and photo - will be permanently removed for this device, falling back to the factory guide. This cannot be undone.",
                "Restore", "Cancel", () -> {
                    setLoading(true);
                    repository.restoreDefaultGuide(deviceId, componentKey).addOnCompleteListener(task -> {
                        if (!isAdded()) return;
                        setLoading(false);
                        if (task.isSuccessful()) {
                            NotificationHelper.showSuccess(requireContext(), "Restored to the default guide.");
                            Navigation.findNavController(requireView()).popBackStack();
                        } else {
                            NotificationHelper.showError(requireContext(), "Unable to restore the default guide. Please try again.");
                        }
                    });
                });
    }

    private void setLoading(boolean loading) {
        if (progressEditor != null) progressEditor.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnSaveGuide.setEnabled(!loading);
        btnRestoreDefaultGuide.setEnabled(!loading);
    }

    @Nullable
    private static HardwareComponentKey safeValueOf(String name) {
        try {
            return HardwareComponentKey.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isAdmin() {
        return RoleConstants.isAdmin(requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE));
    }
}
