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

import com.google.firebase.firestore.ListenerRegistration;

public class HardwareGuideFragment extends Fragment {

    /** Optional String-name of a {@link HardwareComponentKey} to jump straight to, e.g. from a notification's "View Guide" action. */
    public static final String ARG_TARGET_COMPONENT_KEY = "targetComponentKey";
    /** Optional device id this guide's Admin customizations belong to. Defaults to the currently selected device when absent. */
    public static final String ARG_DEVICE_ID = "deviceId";

    private final HardwareGuideRepository repository = new HardwareGuideRepository();
    private ListenerRegistration sectionsListener;
    private GuideSectionAdapter adapter;
    private String deviceId;
    // Consumed (set to null) the first time it's actually acted on, so a
    // later live-update from the Firestore listener doesn't re-trigger the
    // scroll/highlight every time this device's overrides change.
    private String pendingTargetKeyName;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.guide_hardware, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> Navigation.findNavController(view).popBackStack());
        }

        Bundle args = getArguments();
        deviceId = args != null ? args.getString(ARG_DEVICE_ID) : null;
        if (deviceId == null) deviceId = prefs().getString("selected_device_id", null);
        pendingTargetKeyName = args != null ? args.getString(ARG_TARGET_COMPONENT_KEY) : null;

        RecyclerView recyclerView = view.findViewById(R.id.recyclerGuideSections);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        // Bundled defaults render immediately (also the only content ever
        // shown if deviceId can't be resolved at all) - the Firestore
        // listener below then merges in this device's saved customizations,
        // exactly like a normal offline-first screen.
        boolean editable = isAdmin() && deviceId != null;
        adapter = new GuideSectionAdapter(HardwareGuideContent.sections(), editable, this::openEditor);
        recyclerView.setAdapter(adapter);

        if (deviceId == null) return;

        sectionsListener = repository.observeSections(deviceId,
                sections -> {
                    if (!isAdded()) return;
                    adapter.setSections(sections);
                    scrollToPendingTargetIfNeeded(recyclerView);
                },
                error -> {
                    // Bundled defaults (already shown above) stay on screen -
                    // the guide remains usable even if this device's
                    // customizations can't be reached right now.
                });
    }

    private boolean isAdmin() {
        return RoleConstants.ROLE_ADMIN.equals(prefs().getString("user_role", null));
    }

    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
    }

    private void openEditor(HardwareComponentKey component) {
        if (!isAdded() || deviceId == null) return;
        Bundle args = new Bundle();
        args.putString(HardwareGuideEditorFragment.ARG_DEVICE_ID, deviceId);
        args.putString(HardwareGuideEditorFragment.ARG_COMPONENT_KEY, component.name());
        Navigation.findNavController(requireView()).navigate(R.id.hardwareGuideEditorFragment, args);
    }

    private void scrollToPendingTargetIfNeeded(RecyclerView recyclerView) {
        if (pendingTargetKeyName == null) return;
        String targetKeyName = pendingTargetKeyName;
        pendingTargetKeyName = null;

        HardwareComponentKey targetKey;
        try {
            targetKey = HardwareComponentKey.valueOf(targetKeyName);
        } catch (IllegalArgumentException e) {
            return; // Unknown/stale key - just show the full guide, same as opening it normally.
        }

        int position = adapter.indexOf(targetKey);
        if (position < 0) return;

        // Posted so the RecyclerView has laid out its children before we ask
        // it to scroll - jumping straight to a position (rather than the top)
        // on first layout otherwise sometimes lands a little off.
        recyclerView.post(() -> {
            ((LinearLayoutManager) recyclerView.getLayoutManager())
                    .scrollToPositionWithOffset(position, 0);
            adapter.setHighlightedPosition(position);
            recyclerView.postDelayed(() -> adapter.setHighlightedPosition(-1), 2500);
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (sectionsListener != null) {
            sectionsListener.remove();
            sectionsListener = null;
        }
    }
}
