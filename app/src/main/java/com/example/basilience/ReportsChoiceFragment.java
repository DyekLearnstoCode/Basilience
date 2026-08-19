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
        View cardParameter = view.findViewById(R.id.cardParameter);
        View cardFogging = view.findViewById(R.id.cardFogging);
        View btnBack = view.findViewById(R.id.btnBack);

        // The whole row reads as tappable, so the card and its arrow share
        // one listener per destination rather than each declaring its own
        // navigation call - there is still exactly one place per destination.
        View.OnClickListener parameterClick = v ->
                navController.navigate(R.id.action_reportschoiceFragment_to_reportsFragment);
        View.OnClickListener foggingClick = v ->
                navController.navigate(R.id.action_reportschoiceFragment_to_foggingReportsFragment);

        if (btnParameter != null) btnParameter.setOnClickListener(parameterClick);
        if (cardParameter != null) cardParameter.setOnClickListener(parameterClick);

        if (btnFoggingReports != null) btnFoggingReports.setOnClickListener(foggingClick);
        if (cardFogging != null) cardFogging.setOnClickListener(foggingClick);

        if (btnBack != null) {
            btnBack.setVisibility(View.GONE);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }
    }
}