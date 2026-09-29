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
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.List;

public class MobileGuideFragment extends Fragment {

    private final MobileGuideVideoRepository videoRepository = new MobileGuideVideoRepository();
    @Nullable
    private ListenerRegistration videoListener;
    @Nullable
    private GuideSectionAdapter adapter;

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

        // Bundled "Coming Soon" default renders immediately - the same
        // offline-first shape as the Hardware Guide - and the Firestore
        // listener below then overlays the globally-configured video, if any,
        // onto just that one section.
        boolean editable = isAdmin();
        adapter = new GuideSectionAdapter(visibleSections(), editable, editable ? this::openVideoEditor : null);
        recyclerView.setAdapter(adapter);

        videoListener = videoRepository.observe(
                (videoTitle, videoDescription, videoUrl, videoThumbnailUrl) -> {
                    if (!isAdded() || adapter == null) return;
                    adapter.setSections(withVideoOverlay(visibleSections(), videoTitle, videoDescription, videoUrl, videoThumbnailUrl));
                },
                error -> {
                    // Bundled "Coming Soon" default (already shown above) stays
                    // on screen - same not-fatal handling as HardwareGuideFragment.
                });
    }

    /** Replaces the video-card section's title/description/URL/thumbnail with the globally-configured values, leaving every other section untouched. */
    private static List<GuideSection> withVideoOverlay(List<GuideSection> sections, @Nullable String videoTitle,
                                                         @Nullable String videoDescription, @Nullable String videoUrl,
                                                         @Nullable String videoThumbnailUrl) {
        List<GuideSection> merged = new ArrayList<>(sections.size());
        for (GuideSection section : sections) {
            if (section.hasVideoCard()) {
                GuideSection.Builder b = section.toBuilder();
                if (videoTitle != null) b.video(videoTitle, videoDescription != null ? videoDescription : section.getVideoDescription());
                else if (videoDescription != null) b.video(section.getVideoTitle(), videoDescription);
                if (videoUrl != null) b.videoUrl(videoUrl);
                if (videoThumbnailUrl != null) b.videoThumbnailUrl(videoThumbnailUrl);
                merged.add(b.build());
            } else {
                merged.add(section);
            }
        }
        return merged;
    }

    private void openVideoEditor(@Nullable HardwareComponentKey ignored) {
        if (!isAdded()) return;
        GuideSection bundled = bundledVideoSection();
        videoRepository.getOnce().addOnCompleteListener(task -> {
            if (!isAdded()) return;
            DocumentSnapshot doc = task.isSuccessful() ? task.getResult() : null;
            boolean hasOverride = doc != null && doc.exists();
            String title = hasOverride ? doc.getString("videoTitle") : null;
            String description = hasOverride ? doc.getString("videoDescription") : null;
            String url = hasOverride ? doc.getString("videoUrl") : null;
            String thumbnailUrl = hasOverride ? doc.getString("videoThumbnailUrl") : null;

            VideoGuideEditorDialog.show(requireContext(),
                    title != null ? title : (bundled != null ? bundled.getVideoTitle() : null),
                    description != null ? description : (bundled != null ? bundled.getVideoDescription() : null),
                    url != null ? url : (bundled != null ? bundled.getVideoUrl() : null),
                    thumbnailUrl,
                    (newTitle, newDescription, newUrl, newThumbnailUrl) -> {
                        videoRepository.save(newTitle, newDescription, newUrl, newThumbnailUrl)
                                .addOnSuccessListener(v -> {
                                    if (isAdded()) NotificationHelper.showSuccess(requireContext(), "Video tutorial updated.");
                                })
                                .addOnFailureListener(e -> {
                                    if (isAdded()) NotificationHelper.showError(requireContext(), "Unable to save changes. Please try again.");
                                });
                    });
        });
    }

    @Nullable
    private GuideSection bundledVideoSection() {
        for (GuideSection section : MobileGuideContent.sections()) {
            if (section.hasVideoCard()) return section;
        }
        return null;
    }

    private boolean isAdmin() {
        return RoleConstants.isAdmin(requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE));
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (videoListener != null) {
            videoListener.remove();
            videoListener = null;
        }
        adapter = null;
    }
}
