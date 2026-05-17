package com.example.basilience;


import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class UserGuideFragment extends Fragment {
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.guide_main, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        androidx.navigation.NavController navController = androidx.navigation.Navigation.findNavController(view);

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }

        View btnHardware = view.findViewById(R.id.btnHardwareTutorial);
        if (btnHardware != null) {
            btnHardware.setOnClickListener(v -> 
                navController.navigate(R.id.action_userGuideFragment_to_hardwareGuideFragment)
            );
        }

        View btnMobile = view.findViewById(R.id.btnMobileTutorial);
        if (btnMobile != null) {
            btnMobile.setOnClickListener(v -> 
                navController.navigate(R.id.action_userGuideFragment_to_mobileGuideFragment)
            );
        }
    }
}
