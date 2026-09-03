package com.example.basilience;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.basilience.models.GuideSection;

import java.util.ArrayList;
import java.util.List;

public class MobileGuideFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.guide_mobile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> Navigation.findNavController(view).popBackStack());
        }

        RecyclerView recyclerView = view.findViewById(R.id.recyclerGuideSections);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(new GuideSectionAdapter(visibleSections()));
    }

    /**
     * Sections marked adminOnly() (currently just Developer Options) are left
     * out of the list entirely for accounts that couldn't actually reach the
     * real Developer Options screen - not merely non-Admins. The real screen
     * requires the account's Developer Tester entitlement AND developer mode
     * enabled for the selected device (see SettingsFragment's own
     * updateDeveloperOptionsVisibility() and DevOptionsFragment's matching
     * re-check on entry) - an ordinary Admin without that entitlement was
     * previously shown this guidance anyway, even though they'd be denied
     * entry to the screen it describes.
     */
    private List<GuideSection> visibleSections() {
        SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
        String selectedDeviceId = prefs.getString("selected_device_id", null);
        boolean developerOptionsReachable = RoleConstants.isDeveloperTester(prefs)
                && prefs.getBoolean("developer_mode_enabled", false)
                && selectedDeviceId != null
                && selectedDeviceId.equals(prefs.getString(RoleConstants.PREF_DEVELOPER_MODE_DEVICE_ID, null));

        List<GuideSection> all = MobileGuideContent.sections();
        if (developerOptionsReachable) return all;

        List<GuideSection> visible = new ArrayList<>();
        for (GuideSection section : all) {
            if (!section.isAdminOnly()) visible.add(section);
        }
        return visible;
    }
}
