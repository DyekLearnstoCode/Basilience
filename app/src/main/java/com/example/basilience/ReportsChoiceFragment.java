package com.example.basilience;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import com.google.android.material.button.MaterialButton;

public class ReportsChoiceFragment extends Fragment {

    public ReportsChoiceFragment() {
        super(R.layout.reports_main);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        NavController navController = Navigation.findNavController(view);

        View btnParameter = view.findViewById(R.id.btnParameter);
        View btnFoggingReports = view.findViewById(R.id.btnFoggingReports);
        View btnBack = view.findViewById(R.id.btnBack);

        if (btnParameter != null) {
            btnParameter.setOnClickListener(v ->
                    navController.navigate(R.id.action_reportschoiceFragment_to_reportsFragment)
            );
        }

        if (btnFoggingReports != null) {
            btnFoggingReports.setOnClickListener(v ->
                    navController.navigate(R.id.action_reportschoiceFragment_to_foggingReportsFragment)
            );
        }

        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }
    }
}