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

        View cardParameter = view.findViewById(R.id.cardParameter);
        View cardFogging = view.findViewById(R.id.cardFogging);
        View btnBack = view.findViewById(R.id.btnBack);

        // The whole row already reads and behaves as tappable (clickable +
        // selectableItemBackground on the card itself - see reports_main.xml),
        // so it is the only click target now; the redundant arrow button that
        // used to duplicate this same listener has been removed from the
        // layout entirely.
        if (cardParameter != null) {
            cardParameter.setOnClickListener(v ->
                    navController.navigate(R.id.action_reportschoiceFragment_to_reportsFragment));
        }
        if (cardFogging != null) {
            cardFogging.setOnClickListener(v ->
                    navController.navigate(R.id.action_reportschoiceFragment_to_foggingReportsFragment));
        }

        if (btnBack != null) {
            btnBack.setVisibility(View.GONE);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }
    }
}