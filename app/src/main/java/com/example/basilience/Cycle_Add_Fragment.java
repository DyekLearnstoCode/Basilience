package com.example.basilience;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;

import java.util.Calendar;

public class Cycle_Add_Fragment extends Fragment {

    private static final String TAG = "Cycle_Add_Fragment";
    private TextView tvCycleNumber;
    private EditText etStartDate, etHarvestFrequency;
    private TextInputLayout layoutHarvestFrequency;
    private Button btnSave;
    private Database_Helper dbHelper;
    private View layoutLoading;
    private long layoutLoadingShownAt;
    private TextView tvLoadingTitle;

    private int cycleNo = 1;
    private Timestamp startDateTimestamp;

    public Cycle_Add_Fragment() {
        super(R.layout.cycle_add);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dbHelper = new Database_Helper();
        checkCycleCreateAccess(view);

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v ->
                    Navigation.findNavController(view).popBackStack()
            );
        }

        tvCycleNumber = view.findViewById(R.id.etCycleNumber);
        etStartDate = view.findViewById(R.id.etStartDate);
        etHarvestFrequency = view.findViewById(R.id.etHarvestFrequency);
        layoutHarvestFrequency = view.findViewById(R.id.layoutHarvestFrequency);
        btnSave = view.findViewById(R.id.btnSaveCycle);

        layoutLoading = view.findViewById(R.id.layoutLoading);
        tvLoadingTitle = view.findViewById(R.id.tvLoadingTitle);

        if (getArguments() != null) {
            cycleNo = getArguments().getInt("cycleNo", 1);
        }

        tvCycleNumber.setText("Cycle #" + cycleNo);

        etStartDate.setFocusable(false);
        etStartDate.setClickable(true);
        etStartDate.setOnClickListener(v -> showDatePicker());

        btnSave.setOnClickListener(v -> saveCycleToDb(view));
    }

    /**
     * Leaves the screen if this user may not create a cycle on the selected
     * device. Admins and assigned Farmers may; anyone else is sent back.
     *
     * This is an early-exit convenience only - Database_Helper.addCycle() and
     * the Firestore rules enforce the same policy on the write itself.
     */
    private void checkCycleCreateAccess(View view) {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", null);

        dbHelper.checkCycleOperatorPermission(deviceId).addOnFailureListener(e -> {
            if (!isAdded()) return;
            NotificationHelper.showError(getContext(),
                    "You do not have permission to create a growth cycle for this device.");
            Navigation.findNavController(view).popBackStack();
        });
    }

    private void showDatePicker() {
        Calendar cal = Calendar.getInstance();
        DatePickerDialog dlg = new DatePickerDialog(
                requireContext(),
                (picker, year, month, day) -> {
                    Calendar selectedCal = Calendar.getInstance();
                    selectedCal.set(year, month, day, 0, 0, 0);
                    startDateTimestamp = new Timestamp(selectedCal.getTime());
                    // Same "MMM dd, yyyy" convention the cycle list/harvest screens use,
                    // so a date never reads differently depending on where it's shown.
                    etStartDate.setText(DateUtils.formatDate(startDateTimestamp));
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
        );
        dlg.show();
    }

    private void saveCycleToDb(View view) {
        if (startDateTimestamp == null) {
            NotificationHelper.showError(requireContext(), "Select start date");
            return;
        }

        SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
        String deviceId = prefs.getString("selected_device_id", null);

        if (deviceId == null) {
            NotificationHelper.showError(requireContext(), "No device selected");
            return;
        }

        if (layoutLoading != null) {
            tvLoadingTitle.setText(R.string.loading_saving);
            layoutLoadingShownAt = SystemClock.elapsedRealtime();
            layoutLoading.setVisibility(View.VISIBLE);
            layoutLoading.bringToFront();
        }
        btnSave.setEnabled(false);

        // Single Active Cycle Rule check
        dbHelper.getCycles(deviceId).addOnSuccessListener(queryDocumentSnapshots -> {
            if (CycleStatus.hasActive(queryDocumentSnapshots)) {
                hideLayoutLoading();
                btnSave.setEnabled(true);
                NotificationHelper.showWarning(requireContext(), "Active Cycle Exists", "A device can only have one ACTIVE cycle. Please complete the current cycle before starting a new one.");
            } else {
                proceedWithSaving(view, deviceId);
            }
        }).addOnFailureListener(e -> {
            hideLayoutLoading();
            btnSave.setEnabled(true);
            Log.e(TAG, "Active cycle verification failed", e);
            NotificationHelper.showError(requireContext(), "Unable to verify existing cycles. Please try again.");
        });
    }

    private void proceedWithSaving(View view, String deviceId) {
        if (layoutHarvestFrequency != null) layoutHarvestFrequency.setError(null);
        String freqStr = etHarvestFrequency.getText().toString().trim();
        int frequency = 5;
        if (!freqStr.isEmpty()) {
            try {
                frequency = Integer.parseInt(freqStr);
            } catch (NumberFormatException error) {
                btnSave.setEnabled(true);
                hideLayoutLoading();
                if (layoutHarvestFrequency != null) layoutHarvestFrequency.setError("Enter a valid number of days");
                return;
            }
        }
        if (frequency <= 0 || frequency > 365) {
            btnSave.setEnabled(true);
            hideLayoutLoading();
            if (layoutHarvestFrequency != null) layoutHarvestFrequency.setError("Frequency must be between 1 and 365 days");
            return;
        }

        Cycle newCycle = new Cycle(cycleNo, startDateTimestamp, "ACTIVE");
        newCycle.setHarvestFrequencyDays(frequency);
        
        // Calculate initial nextHarvestDate: startDate + frequency
        Calendar cal = Calendar.getInstance();
        cal.setTime(startDateTimestamp.toDate());
        cal.add(Calendar.DAY_OF_YEAR, frequency);
        newCycle.setNextHarvestDate(new Timestamp(cal.getTime()));

        dbHelper.setSelectedDeviceId(deviceId);
        dbHelper.addCycle(newCycle).addOnCompleteListener(task -> {
            hideLayoutLoading();
            if (task.isSuccessful()) {
                NotificationHelper.showSuccess(requireContext(), "Cycle saved successfully");
                Navigation.findNavController(view).popBackStack();
            } else {
                btnSave.setEnabled(true);
                NotificationHelper.showError(requireContext(), "Error saving cycle");
            }
        });
    }

    /** Hides the loading overlay, never sooner than the minimum visible duration. */
    private void hideLayoutLoading() {
        if (layoutLoading == null || layoutLoading.getVisibility() != View.VISIBLE) return;
        NotificationHelper.hideLoaderAfterMinimumDuration(layoutLoadingShownAt, () -> {
            if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
        });
    }
}
