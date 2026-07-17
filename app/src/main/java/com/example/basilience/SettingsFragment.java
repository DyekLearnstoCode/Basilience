package com.example.basilience;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

public class SettingsFragment extends Fragment {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.settings_main, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        NavController navController = Navigation.findNavController(view);

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> {
                androidx.navigation.Navigation.findNavController(view)
                        .navigate(R.id.DeviceManagementFragment);
            });
        }

        // Account Information
        View btnAccount = view.findViewById(R.id.btnAccount);
        btnAccount.setOnClickListener(v -> navController.navigate(R.id.action_settings_to_accountFragment));

        // About Basilience
        View btnAbout = view.findViewById(R.id.btnAbout);
        btnAbout.setOnClickListener(v -> navController.navigate(R.id.action_settings_to_aboutFragment));

        // Terms and Agreements
        View btnTerms = view.findViewById(R.id.btnTerms);
        btnTerms.setOnClickListener(v -> navController.navigate(R.id.action_settings_to_tosFragment));

        // Logout
        View btnLogout = view.findViewById(R.id.btnLogout);
        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> performLogout());
        }
    }

    private void performLogout() {
        Database_Helper helper = new Database_Helper();
        helper.logout();

        // Clear session preferences
        if (getActivity() != null) {
            android.content.SharedPreferences prefs = getActivity().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
            prefs.edit().clear().apply();

            // Redirect to Login
            android.content.Intent intent = new android.content.Intent(getActivity(), Auth_Login_Activity.class);
            intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            getActivity().finish();
        }
    }
}
