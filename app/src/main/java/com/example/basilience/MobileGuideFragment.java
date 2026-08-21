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
     * out of the list entirely for non-Admin accounts, rather than shown with
     * just a label - the same role check used everywhere else in the app
     * (SharedPreferences "user_role"), not a new permission concept.
     */
    private List<GuideSection> visibleSections() {
        SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
        boolean isAdmin = RoleConstants.ROLE_ADMIN.equalsIgnoreCase(prefs.getString("user_role", ""));

        List<GuideSection> all = MobileGuideContent.sections();
        if (isAdmin) return all;

        List<GuideSection> visible = new ArrayList<>();
        for (GuideSection section : all) {
            if (!section.isAdminOnly()) visible.add(section);
        }
        return visible;
    }
}
