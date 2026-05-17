package com.example.basilience;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import java.util.Calendar;
import java.util.Locale;

public class Cycle_Add_Fragment extends Fragment {

    private TextView tvCycleNumber, tvEndDate;
    private EditText etStartDate;
    private Button btnSave;
    private Database_Helper dbHelper;
    private View layoutLoading;
    private TextView tvLoadingTitle;

    private int cycleNo = 1;
    private String startDateIso = ""; // YYYY-MM-DD

    public Cycle_Add_Fragment() {
        super(R.layout.cycle_add);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dbHelper = new Database_Helper();

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v ->
                    Navigation.findNavController(view).popBackStack()
            );
        }

        tvCycleNumber = view.findViewById(R.id.etCycleNumber);
        tvEndDate = view.findViewById(R.id.etEndDate);
        etStartDate = view.findViewById(R.id.etStartDate);
        btnSave = view.findViewById(R.id.btnSaveCycle);

        layoutLoading = view.findViewById(R.id.layoutLoading);
        tvLoadingTitle = view.findViewById(R.id.tvLoadingTitle);

        if (getArguments() != null) {
            cycleNo = getArguments().getInt("cycleNo", 1);
        }

        tvCycleNumber.setText("Cycle #" + cycleNo);
        tvEndDate.setHint("Auto-generated");

        etStartDate.setFocusable(false);
        etStartDate.setClickable(true);
        etStartDate.setOnClickListener(v -> showDatePicker());

        btnSave.setOnClickListener(v -> saveCycleToDb(view));
    }

    private void showDatePicker() {
        Calendar cal = Calendar.getInstance();
        DatePickerDialog dlg = new DatePickerDialog(
                requireContext(),
                (picker, year, month, day) -> {
                    int mm = month + 1;
                    String display = String.format(Locale.US, "%02d/%02d/%04d", day, mm, year);
                    etStartDate.setText(display);
                    startDateIso = String.format(Locale.US, "%04d-%02d-%02d", year, mm, day);
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
        );
        dlg.show();
    }

    private void saveCycleToDb(View view) {
        if (startDateIso.isEmpty()) {
            NotificationHelper.showError(requireContext(), "Select start date");
            return;
        }

        if (layoutLoading != null) {
            tvLoadingTitle.setText(R.string.loading_saving);
            layoutLoading.setVisibility(View.VISIBLE);
        }
        btnSave.setEnabled(false);
        Cycle newCycle = new Cycle(cycleNo, startDateIso, "");

        dbHelper.resolveDataUid().addOnSuccessListener(uid -> {
            if (uid != null) {
                dbHelper.setTargetUid(uid);
                dbHelper.addCycle(newCycle).addOnCompleteListener(task -> {
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    if (task.isSuccessful()) {
                        NotificationHelper.showSuccess(requireContext(), "Cycle saved successfully");
                        Navigation.findNavController(view).popBackStack();
                    } else {
                        btnSave.setEnabled(true);
                        NotificationHelper.showError(requireContext(), "Error saving cycle");
                    }
                });
            } else {
                if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                btnSave.setEnabled(true);
                NotificationHelper.showError(requireContext(), "Auth error");
            }
        });
    }
}
