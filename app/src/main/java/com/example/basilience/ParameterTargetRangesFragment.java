package com.example.basilience;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lets an Admin set the acceptable minimum and maximum for every monitored
 * parameter. These write to the existing
 * {@code /devices/{deviceId}/settings} node - the same values the device,
 * Monitoring and Reports already read - so there is no second settings store.
 *
 * <p>Assigned Personnel can open this screen and see the active ranges but not
 * change them, matching the RTDB rule that already restricts writes to
 * {@code settings} to an Admin. The read-only presentation exists so the app
 * agrees with the server rather than offering a Save that would be rejected.
 */
public class ParameterTargetRangesFragment extends Fragment {

    private static final String TAG = "TargetRanges";

    /** One parameter's pair of inputs. */
    private static final class RangeInputs {
        TextInputLayout minLayout, maxLayout;
        TextInputEditText minField, maxField;
        float loadedMin, loadedMax;
    }

    private final Map<ParameterTargetRanges, RangeInputs> inputs =
            new EnumMap<>(ParameterTargetRanges.class);

    private Database_Helper dbHelper;
    private MaterialButton btnSave;
    private View tvViewOnlyNotice;
    private String deviceId;
    private boolean canEdit;
    private boolean loaded;

    private NotificationHelper.LoadingHandle loadingHandle;

    public ParameterTargetRangesFragment() {
        super(R.layout.settings_target_ranges);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dbHelper = new Database_Helper();

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v ->
                    Navigation.findNavController(view).popBackStack());
        }

        btnSave = view.findViewById(R.id.btnSaveTargetRanges);
        tvViewOnlyNotice = view.findViewById(R.id.tvViewOnlyNotice);

        SharedPreferences prefs = requireContext()
                .getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
        deviceId = prefs.getString("selected_device_id", null);
        String role = prefs.getString("user_role", RoleConstants.ROLE_FARMER);
        canEdit = RoleConstants.ROLE_ADMIN.equalsIgnoreCase(role);

        bindInputs(view);

        if (!canEdit) {
            // Matches the RTDB rule: only an Admin may write settings.
            if (tvViewOnlyNotice != null) tvViewOnlyNotice.setVisibility(View.VISIBLE);
            if (btnSave != null) btnSave.setVisibility(View.GONE);
            for (RangeInputs r : inputs.values()) {
                setEditable(r, false);
            }
        } else if (btnSave != null) {
            btnSave.setOnClickListener(v -> save());
        }

        if (deviceId == null || deviceId.isEmpty()) {
            NotificationHelper.showError(requireContext(), "No device selected");
            Navigation.findNavController(view).popBackStack();
            return;
        }

        loadCurrentValues();
    }

    private void bindInputs(View view) {
        register(view, ParameterTargetRanges.PH, R.id.layoutMinPh, R.id.etMinPh,
                R.id.layoutMaxPh, R.id.etMaxPh);
        register(view, ParameterTargetRanges.EC, R.id.layoutMinEc, R.id.etMinEc,
                R.id.layoutMaxEc, R.id.etMaxEc);
        register(view, ParameterTargetRanges.AIR_TEMPERATURE, R.id.layoutMinAirTemp, R.id.etMinAirTemp,
                R.id.layoutMaxAirTemp, R.id.etMaxAirTemp);
        register(view, ParameterTargetRanges.HUMIDITY, R.id.layoutMinHumidity, R.id.etMinHumidity,
                R.id.layoutMaxHumidity, R.id.etMaxHumidity);
        register(view, ParameterTargetRanges.WATER_TEMPERATURE, R.id.layoutMinWaterTemp, R.id.etMinWaterTemp,
                R.id.layoutMaxWaterTemp, R.id.etMaxWaterTemp);
        register(view, ParameterTargetRanges.WATER_LEVEL, R.id.layoutMinWaterLevel, R.id.etMinWaterLevel,
                R.id.layoutMaxWaterLevel, R.id.etMaxWaterLevel);
    }

    private void register(View view, ParameterTargetRanges parameter,
                          int minLayoutId, int minFieldId, int maxLayoutId, int maxFieldId) {
        RangeInputs r = new RangeInputs();
        r.minLayout = view.findViewById(minLayoutId);
        r.maxLayout = view.findViewById(maxLayoutId);
        r.minField = view.findViewById(minFieldId);
        r.maxField = view.findViewById(maxFieldId);
        inputs.put(parameter, r);

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                r.minLayout.setError(null);
                r.maxLayout.setError(null);
                updateDirtyState();
            }
        };
        if (r.minField != null) r.minField.addTextChangedListener(watcher);
        if (r.maxField != null) r.maxField.addTextChangedListener(watcher);
    }

    private void setEditable(RangeInputs r, boolean editable) {
        if (r.minField != null) { r.minField.setEnabled(editable); r.minField.setFocusable(editable); }
        if (r.maxField != null) { r.maxField.setEnabled(editable); r.maxField.setFocusable(editable); }
    }

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    private void loadCurrentValues() {
        loadingHandle = NotificationHelper.showLoading(requireContext(),
                "Loading target ranges...", () -> {
                    if (!isAdded()) return;
                    NotificationHelper.showError(requireContext(),
                            "Request timed out. Please try again.");
                });

        dbHelper.getDeviceSettings(deviceId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;
                dismissLoading();

                for (Map.Entry<ParameterTargetRanges, RangeInputs> entry : inputs.entrySet()) {
                    ParameterTargetRanges parameter = entry.getKey();
                    RangeInputs r = entry.getValue();

                    // A field that has never been written keeps the same value
                    // the device is compiled with, so the screen never shows a
                    // misleading 0 for an absent setting.
                    r.loadedMin = readFloat(snapshot, parameter.minKey, parameter.defaultMin);
                    r.loadedMax = readFloat(snapshot, parameter.maxKey, parameter.defaultMax);

                    r.minField.setText(format(r.loadedMin, parameter.decimals));
                    r.maxField.setText(format(r.loadedMax, parameter.decimals));
                }

                loaded = true;
                updateDirtyState();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (!isAdded()) return;
                dismissLoading();
                Log.e(TAG, "Unable to read device settings", error.toException());
                NotificationHelper.showError(requireContext(),
                        "Unable to load the current target ranges. Please try again.");
            }
        });
    }

    private float readFloat(DataSnapshot snapshot, String key, float fallback) {
        Object value = snapshot.child(key).getValue();
        if (value instanceof Number) {
            float parsed = ((Number) value).floatValue();
            if (!Float.isNaN(parsed) && !Float.isInfinite(parsed)) return parsed;
        }
        return fallback;
    }

    private String format(float value, int decimals) {
        return String.format(Locale.getDefault(), "%." + decimals + "f", value);
    }

    // ------------------------------------------------------------------
    // Dirty state
    // ------------------------------------------------------------------

    private void updateDirtyState() {
        // Always tappable once loaded, regardless of hasChanges() - a disabled
        // button silently swallows a tap with no way to tell the user why
        // nothing happened. save() itself gives the explicit "nothing to
        // save" feedback when that's the case.
        if (btnSave == null || !canEdit) return;
        btnSave.setEnabled(loaded);
    }

    private boolean hasChanges() {
        for (Map.Entry<ParameterTargetRanges, RangeInputs> entry : inputs.entrySet()) {
            RangeInputs r = entry.getValue();
            Float min = parse(r.minField);
            Float max = parse(r.maxField);
            if (min == null || max == null) return true; // let Save surface the error
            if (differs(min, r.loadedMin) || differs(max, r.loadedMax)) return true;
        }
        return false;
    }

    private boolean differs(float a, float b) {
        return Math.abs(a - b) > 0.0001f;
    }

    private Float parse(TextInputEditText field) {
        if (field == null || field.getText() == null) return null;
        String text = field.getText().toString().trim();
        if (text.isEmpty()) return null;
        try {
            float value = Float.parseFloat(text);
            if (Float.isNaN(value) || Float.isInfinite(value)) return null;
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    private void save() {
        // A field the user left blank/invalid means hasChanges() already
        // returns true (see its own "let Save surface the error" comment) -
        // so this only fires when every field still matches what was loaded.
        if (!hasChanges()) {
            NotificationHelper.showInfo(requireContext(), "No Changes",
                    "There are no changes to save.");
            return;
        }

        Map<String, Object> updates = new HashMap<>();
        boolean valid = true;

        for (Map.Entry<ParameterTargetRanges, RangeInputs> entry : inputs.entrySet()) {
            ParameterTargetRanges parameter = entry.getKey();
            RangeInputs r = entry.getValue();

            r.minLayout.setError(null);
            r.maxLayout.setError(null);

            Float min = parse(r.minField);
            Float max = parse(r.maxField);

            if (min == null) {
                r.minLayout.setError("Enter a number");
                valid = false;
            } else if (min < parameter.physicalMin || min > parameter.physicalMax) {
                r.minLayout.setError(outOfBoundsMessage(parameter));
                valid = false;
            }

            if (max == null) {
                r.maxLayout.setError("Enter a number");
                valid = false;
            } else if (max < parameter.physicalMin || max > parameter.physicalMax) {
                r.maxLayout.setError(outOfBoundsMessage(parameter));
                valid = false;
            }

            if (min != null && max != null && min >= max) {
                r.minLayout.setError("Minimum must be lower than maximum.");
                valid = false;
            }

            if (min != null && max != null) {
                updates.put(parameter.minKey, (double) min);
                updates.put(parameter.maxKey, (double) max);
            }
        }

        if (!valid) {
            NotificationHelper.showError(requireContext(),
                    "Please correct the highlighted values.");
            return;
        }

        NotificationHelper.showConfirmation(requireContext(),
                "Save Target Ranges?",
                "This updates the acceptable ranges used for monitoring, alerts, and reports.",
                "Continue", "Cancel", () -> performSave(updates));
    }

    private void performSave(Map<String, Object> updates) {
        btnSave.setEnabled(false);
        loadingHandle = NotificationHelper.showLoading(requireContext(),
                "Saving target ranges...", () -> {
                    if (!isAdded()) return;
                    btnSave.setEnabled(true);
                    NotificationHelper.showError(requireContext(),
                            "Request timed out. Please try again.");
                });

        // One atomic update of every target field rather than a write per
        // field, so the device can never observe a half-applied range.
        dbHelper.saveTargetRanges(deviceId, updates)
                .addOnSuccessListener(unused -> {
                    if (!isAdded()) return;
                    dismissLoading();

                    for (Map.Entry<ParameterTargetRanges, RangeInputs> entry : inputs.entrySet()) {
                        RangeInputs r = entry.getValue();
                        Float min = parse(r.minField);
                        Float max = parse(r.maxField);
                        if (min != null) r.loadedMin = min;
                        if (max != null) r.loadedMax = max;
                    }
                    updateDirtyState();

                    NotificationHelper.showSuccess(requireContext(), "Target ranges saved");
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    dismissLoading();
                    btnSave.setEnabled(true);
                    Log.e(TAG, "Failed to save target ranges", e);
                    NotificationHelper.showError(requireContext(),
                            "Unable to save the target ranges. Please try again.");
                });
    }

    private String outOfBoundsMessage(ParameterTargetRanges parameter) {
        return String.format(Locale.getDefault(), "Enter a value between %s and %s%s",
                format(parameter.physicalMin, parameter.decimals),
                format(parameter.physicalMax, parameter.decimals),
                parameter.unit);
    }

    private void dismissLoading() {
        if (loadingHandle != null) {
            loadingHandle.dismiss();
            loadingHandle = null;
        }
    }

    @Override
    public void onDestroyView() {
        dismissLoading();
        super.onDestroyView();
    }
}
