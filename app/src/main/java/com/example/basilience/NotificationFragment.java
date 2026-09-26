package com.example.basilience;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.paging.LoadState;
import androidx.paging.PagingData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads from Room via Paging 3 (see NotificationRepository) - this Fragment
 * no longer holds an in-memory list of notifications at all. The Firestore
 * live listener and "Load Older" pagination both live in the repository and
 * only ever sync into Room; everything this screen renders comes back out
 * through Room's reactive queries.
 */
public class NotificationFragment extends Fragment {
    private static final String TAG = "NotificationFragment";

    private NotificationRepository.ReadFilter currentFilter = NotificationRepository.ReadFilter.ALL;

    // Orthogonal to ReadFilter - narrows by WHAT the notification is about.
    // SYSTEM_OTHER covers every non-"parameter" type. UNSPECIFIED is
    // "parameter"-typed but its specific parameter couldn't be resolved (see
    // NotificationParameterKeys) - kept distinct from SYSTEM_OTHER so a
    // parameter alert is never silently mixed in with non-parameter system
    // notifications just because its key is unknown.
    private enum CategoryFilter { ALL, PH, EC, WATER_TEMP, AIR_TEMP, HUMIDITY, WATER_LEVEL, UNSPECIFIED, SYSTEM_OTHER }
    private CategoryFilter currentCategory = CategoryFilter.ALL;
    private MaterialButton btnCategoryDropdown;
    // Cached from the repository's grouped-count query so opening the
    // dropdown never has to run a fresh query on tap.
    private final Map<String, Integer> categoryUnreadCounts = new HashMap<>();
    private boolean hasUnspecifiedCategory;

    private RecyclerView recyclerView;
    private TextView tvEmptyState;
    private android.widget.ProgressBar progressBar;
    private long viewShownAtElapsedRealtime;
    private NotificationAdapter adapter;

    private Database_Helper dbHelper;
    private NotificationRepository repository;
    // Scoped to the selected device's perDevice count (unlike MainActivity's
    // nav badge, which sums across every device) - drives "Mark all as
    // read"'s enabled state off the TRUE full-history unread count for this
    // device. Room only ever holds whatever's been synced/paginated so far,
    // never necessarily the complete history, so it cannot answer this by
    // itself - the server-side counter document stays the source for this,
    // same as the nav badge.
    private ListenerRegistration counterListener;
    private long trueUnreadCountForDevice = 0;

    private final MutableLiveData<CategoryFilter> categoryFilterLiveData = new MutableLiveData<>();
    private final MutableLiveData<NotificationRepository.ReadFilter> readFilterLiveData = new MutableLiveData<>();

    private MaterialButton btnLoadMore;
    private android.widget.ProgressBar progressLoadMore;

    private MaterialButton btnFilterAll, btnFilterUnread, btnFilterRead, btnMarkAllRead;
    private boolean markingAllRead;
    // Resolved per fragment instance rather than cached statically, so read
    // state follows the signed-in account and cannot leak across a logout.
    private String currentUid;
    private NotificationHelper.LoadingHandle loadingHandle;

    // --- Selection mode ---
    private boolean selectionModeActive;
    private final Set<String> selectedIds = new HashSet<>();
    private MaterialButton btnSelectMode, btnMarkSelectedRead, btnCancelSelection;
    private View rowCategoryAndSelect, rowSelectionActions;
    private TextView tvSelectionCount;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.notification_feature, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dbHelper = new Database_Helper();
        repository = new NotificationRepository(requireContext().getApplicationContext());

        currentUid = dbHelper.getCurrentUid();
        viewShownAtElapsedRealtime = android.os.SystemClock.elapsedRealtime();
        selectionModeActive = false;
        selectedIds.clear();

        recyclerView = view.findViewById(R.id.recyclerNotifications);
        tvEmptyState = view.findViewById(R.id.tvEmptyNotifications);
        progressBar = view.findViewById(R.id.progressNotifications);
        btnFilterAll = view.findViewById(R.id.btnFilterAll);
        btnFilterUnread = view.findViewById(R.id.btnFilterUnread);
        btnFilterRead = view.findViewById(R.id.btnFilterRead);
        btnCategoryDropdown = view.findViewById(R.id.btnCategoryDropdown);
        btnSelectMode = view.findViewById(R.id.btnSelectMode);
        btnMarkAllRead = view.findViewById(R.id.btnMarkAllRead);
        rowCategoryAndSelect = view.findViewById(R.id.rowCategoryAndSelect);
        rowSelectionActions = view.findViewById(R.id.rowSelectionActions);
        tvSelectionCount = view.findViewById(R.id.tvSelectionCount);
        btnMarkSelectedRead = view.findViewById(R.id.btnMarkSelectedRead);
        btnCancelSelection = view.findViewById(R.id.btnCancelSelection);
        btnLoadMore = view.findViewById(R.id.btnLoadMore);
        progressLoadMore = view.findViewById(R.id.progressLoadMore);
        // Real click listener is wired in startObserving() once deviceId is
        // known (it closes over deviceId/currentUid).

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        adapter = new NotificationAdapter(item -> {
            if (selectionModeActive) {
                toggleSelection(item);
                return;
            }
            openDetailAndMarkRead(item);
        });
        recyclerView.setAdapter(adapter);
        // addLoadStateListener takes a Kotlin (CombinedLoadStates) -> Unit -
        // a void-returning method reference doesn't satisfy that (Unit is a
        // real return value, not literally void), so the lambda must return
        // Unit.INSTANCE explicitly.
        adapter.addLoadStateListener(states -> {
            onLoadStatesChanged(states);
            return kotlin.Unit.INSTANCE;
        });

        if (btnFilterAll != null) btnFilterAll.setOnClickListener(v -> setFilter(NotificationRepository.ReadFilter.ALL));
        if (btnFilterUnread != null) btnFilterUnread.setOnClickListener(v -> setFilter(NotificationRepository.ReadFilter.UNREAD));
        if (btnFilterRead != null) btnFilterRead.setOnClickListener(v -> setFilter(NotificationRepository.ReadFilter.READ));

        if (btnCategoryDropdown != null) btnCategoryDropdown.setOnClickListener(this::showCategoryMenu);
        if (btnSelectMode != null) btnSelectMode.setOnClickListener(v -> setSelectionMode(true));
        if (btnCancelSelection != null) btnCancelSelection.setOnClickListener(v -> setSelectionMode(false));
        if (btnMarkSelectedRead != null) btnMarkSelectedRead.setOnClickListener(v -> markSelectedAsRead());
        if (btnMarkAllRead != null) btnMarkAllRead.setOnClickListener(v -> markAllAsRead());

        updateFilterUI();
        updateCategoryButtonLabel();
        updateSelectionCount();
        updateMarkAllReadButtonState();

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setVisibility(View.GONE);

        loadNotifications();
    }

    private void setFilter(NotificationRepository.ReadFilter filter) {
        this.currentFilter = filter;
        updateFilterUI();
        readFilterLiveData.setValue(filter);
    }

    // --- Category dropdown ---

    private void showCategoryMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 0, 0, categoryMenuLabel(CategoryFilter.ALL, "All Categories"));
        menu.getMenu().add(0, 1, 1, categoryMenuLabel(CategoryFilter.PH, "pH"));
        menu.getMenu().add(0, 2, 2, categoryMenuLabel(CategoryFilter.EC, "EC"));
        menu.getMenu().add(0, 3, 3, categoryMenuLabel(CategoryFilter.WATER_TEMP, "Water Temp"));
        menu.getMenu().add(0, 4, 4, categoryMenuLabel(CategoryFilter.AIR_TEMP, "Air Temp"));
        menu.getMenu().add(0, 5, 5, categoryMenuLabel(CategoryFilter.HUMIDITY, "Humidity"));
        menu.getMenu().add(0, 6, 6, categoryMenuLabel(CategoryFilter.WATER_LEVEL, "Water Level"));
        if (hasUnspecifiedCategory) {
            menu.getMenu().add(0, 7, 7, categoryMenuLabel(CategoryFilter.UNSPECIFIED, "Unspecified"));
        }
        menu.getMenu().add(0, 8, 8, categoryMenuLabel(CategoryFilter.SYSTEM_OTHER, "System & Other"));

        menu.setOnMenuItemClickListener(menuItem -> {
            CategoryFilter[] byOrder = {
                    CategoryFilter.ALL, CategoryFilter.PH, CategoryFilter.EC, CategoryFilter.WATER_TEMP,
                    CategoryFilter.AIR_TEMP, CategoryFilter.HUMIDITY, CategoryFilter.WATER_LEVEL,
                    CategoryFilter.UNSPECIFIED, CategoryFilter.SYSTEM_OTHER
            };
            currentCategory = byOrder[menuItem.getItemId()];
            updateCategoryButtonLabel();
            categoryFilterLiveData.setValue(currentCategory);
            return true;
        });
        menu.show();
    }

    private String categoryMenuLabel(CategoryFilter category, String baseLabel) {
        Integer count = categoryUnreadCounts.get(categoryDbValue(category));
        return (count != null && count > 0) ? baseLabel + " (" + count + ")" : baseLabel;
    }

    private void updateCategoryButtonLabel() {
        if (btnCategoryDropdown == null) return;
        btnCategoryDropdown.setText(categoryDisplayLabel(currentCategory));
    }

    private String categoryDisplayLabel(CategoryFilter category) {
        switch (category) {
            case PH: return "pH";
            case EC: return "EC";
            case WATER_TEMP: return "Water Temp";
            case AIR_TEMP: return "Air Temp";
            case HUMIDITY: return "Humidity";
            case WATER_LEVEL: return "Water Level";
            case UNSPECIFIED: return "Unspecified";
            case SYSTEM_OTHER: return "System & Other";
            default: return "All Categories";
        }
    }

    /** The string space NotificationEntity.category is written in at sync time (see NotificationRepository.classifyCategory). */
    @Nullable
    private String categoryDbValue(CategoryFilter category) {
        switch (category) {
            case PH: return NotificationParameterKeys.PH;
            case EC: return NotificationParameterKeys.EC;
            case WATER_TEMP: return NotificationParameterKeys.WATER_TEMP;
            case AIR_TEMP: return NotificationParameterKeys.AIR_TEMP;
            case HUMIDITY: return NotificationParameterKeys.HUMIDITY;
            case WATER_LEVEL: return NotificationParameterKeys.WATER_LEVEL;
            case UNSPECIFIED: return "UNSPECIFIED";
            case SYSTEM_OTHER: return "SYSTEM_OTHER";
            default: return null; // ALL
        }
    }

    // --- Selection mode ---

    private void setSelectionMode(boolean active) {
        selectionModeActive = active;
        if (!active) selectedIds.clear();
        if (rowCategoryAndSelect != null) rowCategoryAndSelect.setVisibility(active ? View.GONE : View.VISIBLE);
        if (rowSelectionActions != null) rowSelectionActions.setVisibility(active ? View.VISIBLE : View.GONE);
        adapter.setSelectionMode(active, selectedIds);
        updateSelectionCount();
    }

    private void toggleSelection(NotificationEntity item) {
        if (item.notificationId == null) return;
        if (!selectedIds.remove(item.notificationId)) selectedIds.add(item.notificationId);
        adapter.refreshSelectionState();
        updateSelectionCount();
    }

    private void updateSelectionCount() {
        if (tvSelectionCount != null) tvSelectionCount.setText(selectedIds.size() + " selected");
        if (btnMarkSelectedRead != null) btnMarkSelectedRead.setEnabled(!selectedIds.isEmpty());
    }

    private void markSelectedAsRead() {
        String deviceId = dbHelper.getSelectedDeviceId();
        if (deviceId == null || deviceId.isEmpty() || selectedIds.isEmpty() || !isAdded() || currentUid == null) return;

        final List<String> ids = new ArrayList<>(selectedIds);
        btnMarkSelectedRead.setEnabled(false);
        repository.markSelectedRead(currentUid, deviceId, ids,
                unused -> {
                    if (!isAdded()) return;
                    setSelectionMode(false);
                    NotificationHelper.showSuccess(requireContext(), "Marked " + ids.size() + " notification(s) as read.");
                },
                e -> {
                    if (!isAdded()) return;
                    btnMarkSelectedRead.setEnabled(true);
                    Log.e(TAG, "Failed to mark selected notifications as read", e);
                    NotificationHelper.showError(requireContext(), "Unable to mark selected notifications as read. Please try again.");
                });
    }

    // --- Tap-to-detail ---

    /**
     * Normal (non-selection-mode) tap: shows a read-only detail popup - Close
     * is its only action - and marks just that one notification read. Room's
     * own reactivity (not manual list mutation) is what makes the row update
     * immediately - the repository writes the optimistic Room row before the
     * Firestore call even goes out.
     */
    private void openDetailAndMarkRead(NotificationEntity item) {
        if (!isAdded()) return;
        String title = NotificationAdapter.titleForType(item.type);
        String message = buildDetailMessage(item);
        HardwareComponentKey component = HardwareComponentKey.resolve(item.type, item.notificationId);
        if (component != null) {
            NotificationHelper.showConfirmation(requireContext(), title, message, "View Guide", "Close",
                    () -> openHardwareGuide(component, item.deviceId));
        } else {
            NotificationHelper.showInfo(requireContext(), title, message, "Close");
        }

        if (item.isRead) return;
        String deviceId = dbHelper.getSelectedDeviceId();
        if (deviceId == null || currentUid == null) return;

        repository.markRead(currentUid, deviceId, item.notificationId, e -> {
            if (!isAdded()) return;
            Log.e(TAG, "Failed to mark notification as read", e);
            NotificationHelper.showError(requireContext(),
                    "Unable to mark this notification as read. Please try again.");
        });
    }

    private void openHardwareGuide(HardwareComponentKey component, String deviceId) {
        if (!isAdded()) return;
        Bundle args = new Bundle();
        args.putString(HardwareGuideFragment.ARG_TARGET_COMPONENT_KEY, component.name());
        args.putString(HardwareGuideFragment.ARG_DEVICE_ID, deviceId);
        androidx.navigation.Navigation.findNavController(requireView())
                .navigate(R.id.hardwareGuideFragment, args);
    }

    private String buildDetailMessage(NotificationEntity item) {
        StringBuilder sb = new StringBuilder();
        sb.append(item.message);
        String timeLabel = item.occurredAtMs != null ? "Occurred" : "Received";
        sb.append("\n\n").append(timeLabel).append(": ")
                .append(DateUtils.formatDateTime(NotificationAdapter.displayTimestamp(item)));
        if (item.offlineRecorded) {
            sb.append("\n").append(item.smsFallbackUsed
                    ? "Delivered by SMS while the device was offline."
                    : "Recorded while the device was offline.");
        } else if (NotificationEntity.TYPE_HARVEST.equals(item.type)) {
            String recorder = item.recorderName != null ? item.recorderName : item.recorderUid;
            if (recorder != null && !recorder.trim().isEmpty()) {
                sb.append("\nRecorded by: ").append(recorder);
            }
        }
        return sb.toString();
    }

    // --- Loading / sync ---

    private void loadNotifications() {
        String deviceId = dbHelper.getSelectedDeviceId();
        if (deviceId == null && getContext() != null) {
            android.content.SharedPreferences prefs =
                    requireContext().getSharedPreferences("basilience_prefs",
                            android.content.Context.MODE_PRIVATE);
            deviceId = prefs.getString("selected_device_id", null);
            if (deviceId != null) dbHelper.setSelectedDeviceId(deviceId);
        }

        if (deviceId == null) {
            String uid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
            if (uid != null) {
                dbHelper.getMyDevices()
                        .addOnSuccessListener(deviceDocuments -> {
                            if (!isAdded()) return;
                            if (!deviceDocuments.isEmpty()) {
                                String fetchedId = deviceDocuments.get(0).getId();
                                dbHelper.setSelectedDeviceId(fetchedId);
                                startObserving(fetchedId);
                            } else {
                                showEmptyState("No registered devices found.");
                            }
                        })
                        .addOnFailureListener(e -> {
                            if (!isAdded()) return;
                            Log.e(TAG, "Failed to resolve assigned devices for notifications", e);
                            showEmptyState("Could not find registered device.");
                        });
                return;
            } else {
                showEmptyState("No device selected.");
                return;
            }
        }

        startObserving(deviceId);
    }

    private void startObserving(String deviceId) {
        if (getView() != null) {
            NotificationHelper.bindDeviceLabel(getView().findViewById(R.id.tvDeviceScopeLabel), deviceId);
        }
        if (currentUid == null) {
            showEmptyState("No device selected.");
            return;
        }

        repository.startSync(currentUid, deviceId, error -> {
            if (!isAdded()) return;
            if (error instanceof FirebaseFirestoreException
                    && ((FirebaseFirestoreException) error).getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                showEmptyState("Notifications are not available for this device.");
            } else {
                showEmptyState("Could not load notifications.");
            }
        });

        startCounterListener(deviceId);
        observeCategoryCounts(deviceId);

        if (btnLoadMore != null) {
            btnLoadMore.setOnClickListener(v -> repository.loadOlderPage(currentUid, deviceId, e -> {
                if (!isAdded()) return;
                NotificationHelper.showError(requireContext(),
                        "Unable to load older notifications. Please try again.");
            }));
        }
        repository.hasMoreOlderState().observe(getViewLifecycleOwner(), hasMore -> updateLoadMoreButtonVisibility());
        repository.loadingOlderState().observe(getViewLifecycleOwner(), loading -> updateLoadMoreButtonVisibility());

        readFilterLiveData.setValue(currentFilter);
        categoryFilterLiveData.setValue(currentCategory);

        androidx.lifecycle.LiveData<PagingData<NotificationEntity>> pagingLiveData =
                Transformations.switchMap(readFilterLiveData, filter ->
                        Transformations.switchMap(categoryFilterLiveData, category ->
                                repository.observe(currentUid, deviceId, filter, categoryDbValue(category))));

        pagingLiveData.observe(getViewLifecycleOwner(), data -> adapter.submitData(getLifecycle(), data));
    }

    private void observeCategoryCounts(String deviceId) {
        repository.unreadCountsByCategory(currentUid, deviceId).observe(getViewLifecycleOwner(), counts -> {
            categoryUnreadCounts.clear();
            if (counts != null) {
                for (NotificationDao.CategoryCount c : counts) categoryUnreadCounts.put(c.category, c.cnt);
            }
        });
        repository.hasUnspecifiedCategory(currentUid, deviceId).observe(getViewLifecycleOwner(), has -> {
            hasUnspecifiedCategory = Boolean.TRUE.equals(has);
        });
    }

    /**
     * Scoped to this one device's perDevice unread count (unlike
     * MainActivity's nav badge, which sums across every device) - the
     * authoritative signal for whether "Mark all as read" has any real work
     * to do, since Room may only hold whatever's been synced/paginated so
     * far, never necessarily the device's complete history.
     */
    private void startCounterListener(String deviceId) {
        if (currentUid == null) return;
        if (counterListener != null) counterListener.remove();
        counterListener = FirebaseFirestore.getInstance()
                .collection("users").document(currentUid)
                .collection("counters").document("notifications")
                .addSnapshotListener((snapshot, error) -> {
                    if (!isAdded()) return;
                    if (error != null) {
                        Log.e(TAG, "Notification counter listener failed", error);
                        return;
                    }
                    long count = 0;
                    if (snapshot != null && snapshot.exists()) {
                        Object perDeviceRaw = snapshot.get("perDevice");
                        if (perDeviceRaw instanceof Map) {
                            Object value = ((Map<?, ?>) perDeviceRaw).get(deviceId);
                            if (value instanceof Number) count = ((Number) value).longValue();
                        }
                    }
                    trueUnreadCountForDevice = Math.max(0, count);
                    updateMarkAllReadButtonState();
                });
    }

    private void updateLoadMoreButtonVisibility() {
        if (btnLoadMore == null) return;
        boolean hasMore = Boolean.TRUE.equals(repository.hasMoreOlderState().getValue());
        boolean loading = Boolean.TRUE.equals(repository.loadingOlderState().getValue());
        btnLoadMore.setVisibility(hasMore && !loading ? View.VISIBLE : View.GONE);
        if (progressLoadMore != null) progressLoadMore.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    // --- Mark all as read (server-side, full device history) ---

    private void markAllAsRead() {
        if (markingAllRead || !isAdded()) return;
        String deviceId = dbHelper.getSelectedDeviceId();
        if (deviceId == null || deviceId.isEmpty() || currentUid == null) {
            NotificationHelper.showError(requireContext(), "No device selected.");
            return;
        }
        if (trueUnreadCountForDevice <= 0) {
            NotificationHelper.showSuccess(requireContext(), "All notifications are already read.");
            return;
        }

        markingAllRead = true;
        updateMarkAllReadButtonState();
        loadingHandle = NotificationHelper.showLoading(requireContext(), "Marking notifications as read...", () -> {
            if (!isAdded()) return;
            markingAllRead = false;
            updateMarkAllReadButtonState();
            NotificationHelper.showError(requireContext(), "Request timed out. Please refresh before trying again.");
        });

        repository.markAllRead(currentUid, deviceId,
                result -> {
                    if (!isAdded()) return;
                    dismissLoading();
                    markingAllRead = false;
                    updateMarkAllReadButtonState();
                    NotificationHelper.showSuccess(requireContext(), "All notifications marked as read.");
                },
                e -> {
                    if (!isAdded()) return;
                    dismissLoading();
                    markingAllRead = false;
                    updateMarkAllReadButtonState();
                    Log.e(TAG, "Failed to mark all notifications as read", e);
                    NotificationHelper.showError(requireContext(), "Unable to mark notifications as read. Please try again.");
                });
    }

    private void updateMarkAllReadButtonState() {
        if (btnMarkAllRead == null) return;
        boolean enabled = trueUnreadCountForDevice > 0 && !markingAllRead;
        btnMarkAllRead.setEnabled(enabled);
        btnMarkAllRead.setText(markingAllRead ? "Marking as read…" : "Mark all as read");
    }

    // --- Paging load state -> loading/empty UI ---

    private void onLoadStatesChanged(@NonNull androidx.paging.CombinedLoadStates loadStates) {
        if (!isAdded()) return;
        boolean isEmpty = adapter.getItemCount() == 0;
        LoadState refresh = loadStates.getRefresh();

        if (refresh instanceof LoadState.Loading && isEmpty) {
            recyclerView.setVisibility(View.GONE);
            if (tvEmptyState != null) tvEmptyState.setVisibility(View.GONE);
            return;
        }
        hideProgressBar();

        if (refresh instanceof LoadState.Error) {
            showEmptyState("Could not load notifications.");
            return;
        }

        if (isEmpty) {
            showEmptyState(emptyStateMessage());
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            if (tvEmptyState != null) tvEmptyState.setVisibility(View.GONE);
        }
    }

    private String emptyStateMessage() {
        String categorySuffix = currentCategory == CategoryFilter.ALL ? "" : " in this category";
        if (currentFilter == NotificationRepository.ReadFilter.UNREAD) {
            return "No unread notifications" + categorySuffix + ".";
        } else if (currentFilter == NotificationRepository.ReadFilter.READ) {
            return "No read notifications" + categorySuffix + ".";
        } else if (!categorySuffix.isEmpty()) {
            return "No notifications in this category yet.";
        }
        return "No notifications yet.";
    }

    private void updateFilterUI() {
        if (getContext() == null) return;
        if (btnFilterAll != null) btnFilterAll.setSelected(currentFilter == NotificationRepository.ReadFilter.ALL);
        if (btnFilterUnread != null) btnFilterUnread.setSelected(currentFilter == NotificationRepository.ReadFilter.UNREAD);
        if (btnFilterRead != null) btnFilterRead.setSelected(currentFilter == NotificationRepository.ReadFilter.READ);
    }

    private void hideProgressBar() {
        if (progressBar == null || progressBar.getVisibility() == View.GONE) return;
        NotificationHelper.hideLoaderAfterMinimumDuration(viewShownAtElapsedRealtime,
                () -> {
                    if (isAdded() && progressBar != null) progressBar.setVisibility(View.GONE);
                });
    }

    private void showEmptyState(String message) {
        hideProgressBar();
        recyclerView.setVisibility(View.GONE);
        if (tvEmptyState != null) {
            tvEmptyState.setText(message);
            tvEmptyState.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onDestroyView() {
        dismissLoading();
        super.onDestroyView();
        repository.stopSync();
        if (counterListener != null) counterListener.remove();
    }

    private void dismissLoading() {
        if (loadingHandle != null) loadingHandle.dismiss();
        loadingHandle = null;
    }
}
