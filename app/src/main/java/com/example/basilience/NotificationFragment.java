package com.example.basilience;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FieldPath;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.functions.FirebaseFunctions;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NotificationFragment extends Fragment {
    private static final String TAG = "NotificationFragment";

    private enum FilterType { ALL, UNREAD, READ }
    private FilterType currentFilter = FilterType.ALL;

    // Orthogonal to FilterType (read state) - narrows by WHAT the
    // notification is about. SYSTEM_OTHER covers every non-"parameter" type
    // (harvest/hardware/connectivity/info). UNSPECIFIED is "parameter"-typed
    // but its specific parameter couldn't be resolved (see
    // NotificationParameterKeys) - kept distinct from SYSTEM_OTHER per
    // design: a parameter alert must never be silently mixed in with
    // non-parameter system notifications just because its key is unknown.
    private enum CategoryFilter { ALL, PH, EC, WATER_TEMP, AIR_TEMP, HUMIDITY, WATER_LEVEL, UNSPECIFIED, SYSTEM_OTHER }
    private CategoryFilter currentCategory = CategoryFilter.ALL;
    private MaterialButton btnCategoryDropdown;
    // Cached from the last background render pass so opening the dropdown
    // never has to rescan allRawNotifications on the main thread.
    private final Map<CategoryFilter, Integer> categoryUnreadCounts = new EnumMap<>(CategoryFilter.class);
    private boolean hasUnspecifiedCategory;

    private RecyclerView recyclerView;
    private TextView tvEmptyState;
    private android.widget.ProgressBar progressBar;
    // Recorded fresh in onViewCreated() - not a field initializer - since the
    // Fragment instance can be reused across multiple view creations (e.g.
    // navigating away and back), and each one needs its own loading window.
    private long viewShownAtElapsedRealtime;
    private NotificationAdapter adapter;
    private final List<NotificationAdapter.NotificationItem> allRawNotifications = new ArrayList<>();
    // allRawNotifications is rebuilt from these two on every change: liveNotifications
    // tracks the live-synced recent window (replaced wholesale on each snapshot),
    // olderNotifications accumulates pages fetched on demand via "Load Older".
    // Merged with a docId-keyed LinkedHashMap (see mergeRawNotifications()) so a
    // notification can never be rendered twice even under a pathological edge
    // case (e.g. a backdated offline-queue replay landing exactly on a page
    // boundary) - cheap insurance on top of the stable (timestamp, docId)
    // tiebreaker ordering Database_Helper's queries now use.
    private final List<NotificationAdapter.NotificationItem> liveNotifications = new ArrayList<>();
    private final List<NotificationAdapter.NotificationItem> olderNotifications = new ArrayList<>();

    private Database_Helper dbHelper;
    private ListenerRegistration notificationListener;
    // Scoped to the selected device's perDevice count (unlike MainActivity's
    // badge, which sums across every device) - drives "Mark all as read"'s
    // enabled state off the TRUE full-history unread count for this device,
    // not just whatever page happens to be loaded in allRawNotifications.
    private ListenerRegistration counterListener;
    private long trueUnreadCountForDevice = 0;

    private MaterialButton btnLoadMore;
    private android.widget.ProgressBar progressLoadMore;
    // Cursor for the next "Load Older" fetch. Only moved by the live listener
    // while no pagination has happened yet (paginationStarted == false) - once
    // the user has paginated, the live window's churn must not disturb where
    // the next older page picks up.
    private DocumentSnapshot oldestLoadedDoc;
    private boolean paginationStarted;
    private boolean hasMoreOlderNotifications;
    private boolean loadingOlderNotifications;

    private MaterialButton btnFilterAll, btnFilterUnread, btnFilterRead;
    private boolean markingAllRead;
    // Resolved per fragment instance rather than cached statically, so read
    // state follows the signed-in account and cannot leak across a logout.
    private String currentUid;
    private NotificationHelper.LoadingHandle loadingHandle;

    // --- Selection mode ---
    private boolean selectionModeActive;
    private final Set<String> selectedDocIds = new HashSet<>();
    private MaterialButton btnSelectMode, btnMarkSelectedRead, btnCancelSelection;
    private View rowCategoryAndSelect, rowSelectionActions;
    private TextView tvSelectionCount;

    // Off-main-thread sort/filter/group - see applyFilterAndRender(). A
    // single background thread is enough (renders are cheap relative to a
    // Firestore round trip and are naturally serialized one at a time); the
    // generation counter discards a stale result if a newer render request
    // supersedes it before it finishes.
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int renderGeneration = 0;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.notification_feature, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dbHelper = new Database_Helper();

        currentUid = dbHelper.getCurrentUid();
        viewShownAtElapsedRealtime = android.os.SystemClock.elapsedRealtime();
        selectionModeActive = false;
        selectedDocIds.clear();

        recyclerView = view.findViewById(R.id.recyclerNotifications);
        tvEmptyState = view.findViewById(R.id.tvEmptyNotifications);
        progressBar = view.findViewById(R.id.progressNotifications);
        btnFilterAll = view.findViewById(R.id.btnFilterAll);
        btnFilterUnread = view.findViewById(R.id.btnFilterUnread);
        btnFilterRead = view.findViewById(R.id.btnFilterRead);
        btnCategoryDropdown = view.findViewById(R.id.btnCategoryDropdown);
        btnSelectMode = view.findViewById(R.id.btnSelectMode);
        rowCategoryAndSelect = view.findViewById(R.id.rowCategoryAndSelect);
        rowSelectionActions = view.findViewById(R.id.rowSelectionActions);
        tvSelectionCount = view.findViewById(R.id.tvSelectionCount);
        btnMarkSelectedRead = view.findViewById(R.id.btnMarkSelectedRead);
        btnCancelSelection = view.findViewById(R.id.btnCancelSelection);
        btnLoadMore = view.findViewById(R.id.btnLoadMore);
        progressLoadMore = view.findViewById(R.id.progressLoadMore);
        if (btnLoadMore != null) btnLoadMore.setOnClickListener(v -> loadOlderNotifications());

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        adapter = new NotificationAdapter(item -> {
            if (item.isHeader) return;
            if (selectionModeActive) {
                toggleSelection(item);
                return;
            }
            openDetailAndMarkRead(item);
        }, this::markAllAsRead);
        recyclerView.setAdapter(adapter);

        // Setup filter button click handlers
        if (btnFilterAll != null) btnFilterAll.setOnClickListener(v -> setFilter(FilterType.ALL));
        if (btnFilterUnread != null) btnFilterUnread.setOnClickListener(v -> setFilter(FilterType.UNREAD));
        if (btnFilterRead != null) btnFilterRead.setOnClickListener(v -> setFilter(FilterType.READ));

        if (btnCategoryDropdown != null) btnCategoryDropdown.setOnClickListener(this::showCategoryMenu);
        if (btnSelectMode != null) btnSelectMode.setOnClickListener(v -> setSelectionMode(true));
        if (btnCancelSelection != null) btnCancelSelection.setOnClickListener(v -> setSelectionMode(false));
        if (btnMarkSelectedRead != null) btnMarkSelectedRead.setOnClickListener(v -> markSelectedAsRead());

        // Selection is now carried by view state rather than a second XML
        // style, so the starting filter has to be reflected explicitly.
        updateFilterUI();
        updateCategoryButtonLabel();
        updateSelectionCount();

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setVisibility(View.GONE);

        loadNotifications();
    }

    private void setFilter(FilterType filter) {
        this.currentFilter = filter;
        updateFilterUI();
        applyFilterAndRender();
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
            applyFilterAndRender();
            return true;
        });
        menu.show();
    }

    private String categoryMenuLabel(CategoryFilter category, String baseLabel) {
        Integer count = categoryUnreadCounts.get(category);
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

    /** Single-pass classification (replaces a previous O(9n) match-against-every-category scan). */
    private CategoryFilter classifyItem(NotificationAdapter.NotificationItem item) {
        if (!NotificationAdapter.NotificationItem.TYPE_PARAMETER.equals(item.type)) return CategoryFilter.SYSTEM_OTHER;
        if (item.parameterKey == null) return CategoryFilter.UNSPECIFIED;
        switch (item.parameterKey) {
            case NotificationParameterKeys.PH: return CategoryFilter.PH;
            case NotificationParameterKeys.EC: return CategoryFilter.EC;
            case NotificationParameterKeys.WATER_TEMP: return CategoryFilter.WATER_TEMP;
            case NotificationParameterKeys.AIR_TEMP: return CategoryFilter.AIR_TEMP;
            case NotificationParameterKeys.HUMIDITY: return CategoryFilter.HUMIDITY;
            case NotificationParameterKeys.WATER_LEVEL: return CategoryFilter.WATER_LEVEL;
            default: return CategoryFilter.UNSPECIFIED;
        }
    }

    // --- Selection mode ---

    private void setSelectionMode(boolean active) {
        selectionModeActive = active;
        if (!active) selectedDocIds.clear();
        if (rowCategoryAndSelect != null) rowCategoryAndSelect.setVisibility(active ? View.GONE : View.VISIBLE);
        if (rowSelectionActions != null) rowSelectionActions.setVisibility(active ? View.VISIBLE : View.GONE);
        adapter.setSelectionMode(active, selectedDocIds);
        updateSelectionCount();
    }

    private void toggleSelection(NotificationAdapter.NotificationItem item) {
        if (item.docId == null) return;
        if (!selectedDocIds.remove(item.docId)) selectedDocIds.add(item.docId);
        adapter.refreshSelectionState();
        updateSelectionCount();
    }

    private void updateSelectionCount() {
        if (tvSelectionCount != null) {
            tvSelectionCount.setText(selectedDocIds.size() + " selected");
        }
        if (btnMarkSelectedRead != null) {
            btnMarkSelectedRead.setEnabled(!selectedDocIds.isEmpty());
        }
    }

    private void markSelectedAsRead() {
        String deviceId = dbHelper.getSelectedDeviceId();
        if (deviceId == null || deviceId.isEmpty() || selectedDocIds.isEmpty() || !isAdded()) return;

        final List<String> ids = new ArrayList<>(selectedDocIds);
        btnMarkSelectedRead.setEnabled(false);
        dbHelper.markNotificationsRead(deviceId, ids).addOnSuccessListener(unused -> {
            if (!isAdded()) return;
            for (NotificationAdapter.NotificationItem item : allRawNotifications) {
                if (item.docId != null && ids.contains(item.docId)) item.isReadForCurrentUser = true;
            }
            setSelectionMode(false);
            applyFilterAndRender();
            NotificationHelper.showSuccess(requireContext(), "Marked " + ids.size() + " notification(s) as read.");
        }).addOnFailureListener(e -> {
            if (!isAdded()) return;
            btnMarkSelectedRead.setEnabled(true);
            Log.e(TAG, "Failed to mark selected notifications as read", e);
            NotificationHelper.showError(requireContext(), "Unable to mark selected notifications as read. Please try again.");
        });
    }

    // --- Tap-to-detail ---

    /**
     * Normal (non-selection-mode) tap: shows a read-only detail popup - Close
     * is its only action, matching every other single-acknowledgement dialog
     * in the app (NotificationHelper.showInfo) rather than a bespoke layout -
     * and marks just that one notification read, same optimistic-with-revert
     * behavior as before.
     */
    private void openDetailAndMarkRead(NotificationAdapter.NotificationItem item) {
        if (!isAdded()) return;
        NotificationHelper.showInfo(requireContext(), NotificationAdapter.titleForType(item.type),
                buildDetailMessage(item), "Close");

        if (item.isReadForCurrentUser || item.docId == null) return;
        if (dbHelper.getSelectedDeviceId() == null || currentUid == null) return;

        item.isReadForCurrentUser = true;
        applyFilterAndRender();

        FirebaseFirestore.getInstance()
                .collection("devices")
                .document(dbHelper.getSelectedDeviceId())
                .collection("notifications")
                .document(item.docId)
                .update(FieldPath.of("readBy", currentUid), FieldValue.serverTimestamp())
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    Log.e(TAG, "Failed to mark notification as read", e);
                    item.isReadForCurrentUser = false;
                    applyFilterAndRender();
                    NotificationHelper.showError(requireContext(),
                            "Unable to mark this notification as read. Please try again.");
                });
    }

    private String buildDetailMessage(NotificationAdapter.NotificationItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append(item.message);
        // occurredAtMs (true device-observed time), when present, beats the
        // insertion-time timestamp field for display - see
        // NotificationAdapter.displayTimestamp()'s comment for why sorting
        // must never do the same.
        String timeLabel = item.occurredAtMs != null ? "Occurred" : "Received";
        sb.append("\n\n").append(timeLabel).append(": ")
                .append(DateUtils.formatDateTime(NotificationAdapter.displayTimestamp(item)));
        if (item.offlineRecorded) {
            sb.append("\n").append(item.smsFallbackUsed
                    ? "Delivered by SMS while the device was offline."
                    : "Recorded while the device was offline.");
        } else if (NotificationAdapter.NotificationItem.TYPE_HARVEST.equals(item.type)) {
            String recorder = item.recorderName != null ? item.recorderName : item.recorderUid;
            if (recorder != null && !recorder.trim().isEmpty()) {
                sb.append("\nRecorded by: ").append(recorder);
            }
        }
        return sb.toString();
    }

    // --- Loading / pagination ---

    private void loadNotifications() {
        if (notificationListener != null) notificationListener.remove();

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
                        .addOnSuccessListener(queryDocumentSnapshots -> {
                            if (!isAdded()) return;
                            if (!queryDocumentSnapshots.isEmpty()) {
                                String fetchedId = queryDocumentSnapshots.getDocuments().get(0).getId();
                                dbHelper.setSelectedDeviceId(fetchedId);
                                startListeningToNotifications();
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

        startListeningToNotifications();
    }

    private void startListeningToNotifications() {
        if (notificationListener != null) notificationListener.remove();
        if (counterListener != null) counterListener.remove();

        String deviceId = dbHelper.getSelectedDeviceId();

        if (getView() != null) {
            NotificationHelper.bindDeviceLabel(
                    getView().findViewById(R.id.tvDeviceScopeLabel), deviceId);
        }

        paginationStarted = false;
        olderNotifications.clear();
        oldestLoadedDoc = null;
        hasMoreOlderNotifications = false;
        loadingOlderNotifications = false;
        if (btnLoadMore != null) btnLoadMore.setVisibility(View.GONE);
        if (progressLoadMore != null) progressLoadMore.setVisibility(View.GONE);

        notificationListener = dbHelper.listenToNotifications((value, error) -> {
            if (!isAdded()) return;
            if (error != null) {
                Log.e(TAG, "Notification listener failed", error);
                if (error instanceof FirebaseFirestoreException
                        && ((FirebaseFirestoreException) error).getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    showEmptyState("Notifications are not available for this device.");
                } else {
                    showEmptyState("Could not load notifications.");
                }
                return;
            }
            if (value == null) {
                showEmptyState("No notifications yet.");
                return;
            }

            List<QueryDocumentSnapshot> docs = new ArrayList<>();
            for (QueryDocumentSnapshot doc : value) docs.add(doc);

            liveNotifications.clear();
            for (QueryDocumentSnapshot doc : docs) {
                NotificationAdapter.NotificationItem item = parseNotificationItem(doc);
                if (item != null) liveNotifications.add(item);
            }
            mergeRawNotifications();

            if (!paginationStarted) {
                oldestLoadedDoc = docs.isEmpty() ? null : docs.get(docs.size() - 1);
                hasMoreOlderNotifications = docs.size() >= Database_Helper.NOTIFICATIONS_LIVE_PAGE_SIZE;
                updateLoadMoreButtonVisibility();
            }

            applyFilterAndRender();
        });

        if (deviceId != null) startCounterListener(deviceId);
    }

    /**
     * Scoped to this one device's perDevice unread count (unlike
     * MainActivity's nav badge, which sums across every device this account
     * can see) - the authoritative signal for whether "Mark all as read" has
     * any real work to do, since allRawNotifications only ever holds
     * whatever page has been loaded/paginated so far.
     */
    private void startCounterListener(String deviceId) {
        if (currentUid == null) return;
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
                    updateMarkAllReadState();
                });
    }

    private NotificationAdapter.NotificationItem parseNotificationItem(QueryDocumentSnapshot doc) {
        try {
            String docId     = doc.getId();
            String message   = doc.getString("message");
            String type      = doc.getString("type");
            Long   timestamp = readTimestampMillis(doc);
            // Per-user read state. The legacy document-wide isRead field is
            // deliberately not consulted: it is shared by everyone assigned
            // to the device and is what made one user's tap clear the badge
            // for the whole farm. A document with no readBy entry for this
            // user - including every historical one - is unread.
            boolean readForCurrentUser = currentUid != null
                    && doc.get(FieldPath.of("readBy", currentUid)) != null;
            // Only present on a notification tied to an actual stored
            // harvest record (see functions/onHarvestCreated) - absent
            // for the pre-harvest reminder, which must never show one.
            String recorderName = doc.getString("recorderName");
            String recorderUid  = doc.getString("recorderUid");
            // Only present on an event replayed from the firmware's
            // offline queue (see functions/onNotificationQueued) -
            // absent for every normal real-time notification.
            Boolean offlineRecorded = doc.getBoolean("offlineRecorded");
            Boolean smsFallbackUsed = doc.getBoolean("smsFallbackUsed");
            // Display-only true device time for an offline-queue replay -
            // see NotificationItem.occurredAtMs's own comment for why this
            // must never be used for sort/pagination.
            Long occurredAtMs = doc.getLong("occurredAtMs");

            if (message == null || type == null || timestamp == null) {
                Log.w(TAG, "Skipping malformed notification document: " + doc.getReference().getPath());
                return null;
            }
            NotificationAdapter.NotificationItem item = new NotificationAdapter.NotificationItem(
                    docId, message, timestamp, type, readForCurrentUser,
                    recorderName, recorderUid
            );
            item.offlineRecorded = offlineRecorded != null && offlineRecorded;
            item.smsFallbackUsed = smsFallbackUsed != null && smsFallbackUsed;
            item.occurredAtMs = occurredAtMs;
            item.parameterKey = NotificationParameterKeys.resolve(type, doc.getString("parameterKey"), docId);
            return item;
        } catch (Exception e) {
            Log.w(TAG, "Skipping malformed notification document: " + doc.getReference().getPath(), e);
            return null;
        }
    }

    /**
     * Merges the live window and paginated-older pages, de-duplicated by
     * docId (a LinkedHashMap preserves the append order below: live items
     * first, so a docId that somehow appears in both wins as the live
     * version). Structurally these two lists shouldn't overlap under normal
     * monotonic timestamps (see Database_Helper's ordering comment), but a
     * backdated offline-queue replay arriving after pagination has already
     * passed that time range is a real, if rare, way a docId could
     * theoretically show up twice - this makes that a silent no-op instead
     * of a duplicated row.
     */
    private void mergeRawNotifications() {
        LinkedHashMap<String, NotificationAdapter.NotificationItem> byId = new LinkedHashMap<>();
        for (NotificationAdapter.NotificationItem item : liveNotifications) {
            if (item.docId != null) byId.put(item.docId, item);
        }
        for (NotificationAdapter.NotificationItem item : olderNotifications) {
            if (item.docId != null) byId.putIfAbsent(item.docId, item);
        }
        allRawNotifications.clear();
        allRawNotifications.addAll(byId.values());
    }

    private void loadOlderNotifications() {
        if (loadingOlderNotifications || oldestLoadedDoc == null || !isAdded()) return;

        loadingOlderNotifications = true;
        paginationStarted = true;
        if (btnLoadMore != null) btnLoadMore.setVisibility(View.GONE);
        if (progressLoadMore != null) progressLoadMore.setVisibility(View.VISIBLE);

        dbHelper.loadOlderNotifications(oldestLoadedDoc)
                .addOnSuccessListener(snapshot -> {
                    if (!isAdded()) return;
                    loadingOlderNotifications = false;
                    if (progressLoadMore != null) progressLoadMore.setVisibility(View.GONE);

                    List<QueryDocumentSnapshot> docs = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : snapshot) docs.add(doc);

                    for (QueryDocumentSnapshot doc : docs) {
                        NotificationAdapter.NotificationItem item = parseNotificationItem(doc);
                        if (item != null) olderNotifications.add(item);
                    }
                    if (!docs.isEmpty()) {
                        oldestLoadedDoc = docs.get(docs.size() - 1);
                    }
                    hasMoreOlderNotifications = docs.size() >= Database_Helper.NOTIFICATIONS_OLDER_PAGE_SIZE;

                    mergeRawNotifications();
                    applyFilterAndRender();
                    updateLoadMoreButtonVisibility();
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    loadingOlderNotifications = false;
                    if (progressLoadMore != null) progressLoadMore.setVisibility(View.GONE);
                    Log.e(TAG, "Failed to load older notifications", e);
                    NotificationHelper.showError(requireContext(),
                            "Unable to load older notifications. Please try again.");
                    updateLoadMoreButtonVisibility();
                });
    }

    private void updateLoadMoreButtonVisibility() {
        if (btnLoadMore == null) return;
        btnLoadMore.setVisibility(hasMoreOlderNotifications && !loadingOlderNotifications
                ? View.VISIBLE : View.GONE);
    }

    // --- Mark all as read (server-side, full device history) ---

    /**
     * Delegates to the markAllNotificationsReadForDevice callable rather
     * than writing readBy on whatever's loaded client-side - allRawNotifications
     * only ever holds the live window plus however much has been paginated,
     * never the device's full history (which can be thousands of documents).
     * See functions/index.js for the server-side design (single counter
     * reconciliation, bulkReadUids suppression marker, idempotent on retry).
     */
    private void markAllAsRead() {
        if (markingAllRead || !isAdded()) return;
        String deviceId = dbHelper.getSelectedDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            NotificationHelper.showError(requireContext(), "No device selected.");
            return;
        }
        if (trueUnreadCountForDevice <= 0) {
            NotificationHelper.showSuccess(requireContext(), "All notifications are already read.");
            return;
        }

        markingAllRead = true;
        updateMarkAllReadState();
        loadingHandle = NotificationHelper.showLoading(requireContext(), "Marking notifications as read...", () -> {
            if (!isAdded()) return;
            markingAllRead = false;
            updateMarkAllReadState();
            NotificationHelper.showError(requireContext(), "Request timed out. Please refresh before trying again.");
        });

        Map<String, Object> data = new HashMap<>();
        data.put("deviceId", deviceId);
        FirebaseFunctions.getInstance().getHttpsCallable("markAllNotificationsReadForDevice")
                .call(data)
                .addOnSuccessListener(result -> {
                    if (!isAdded()) return;
                    dismissLoading();
                    markingAllRead = false;
                    for (NotificationAdapter.NotificationItem item : allRawNotifications) item.isReadForCurrentUser = true;
                    applyFilterAndRender();
                    updateMarkAllReadState();
                    NotificationHelper.showSuccess(requireContext(), "All notifications marked as read.");
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    dismissLoading();
                    markingAllRead = false;
                    updateMarkAllReadState();
                    Log.e(TAG, "Failed to mark all notifications as read", e);
                    NotificationHelper.showError(requireContext(), "Unable to mark notifications as read. Please try again.");
                });
    }

    private void updateMarkAllReadState() {
        if (adapter != null) adapter.setMarkAllReadState(trueUnreadCountForDevice > 0, markingAllRead);
    }

    // --- Filtering / sorting / grouping (off main thread) ---

    private static final class RenderResult {
        final List<NotificationAdapter.NotificationItem> rendered;
        final Map<CategoryFilter, Integer> categoryUnread;
        final boolean hasUnspecified;
        @Nullable final String emptyMessage;

        RenderResult(List<NotificationAdapter.NotificationItem> rendered,
                     Map<CategoryFilter, Integer> categoryUnread, boolean hasUnspecified,
                     @Nullable String emptyMessage) {
            this.rendered = rendered;
            this.categoryUnread = categoryUnread;
            this.hasUnspecified = hasUnspecified;
            this.emptyMessage = emptyMessage;
        }
    }

    private void applyFilterAndRender() {
        final int generation = ++renderGeneration;
        final FilterType filter = currentFilter;
        final CategoryFilter category = currentCategory;
        // Shallow copy: only the list structure needs to be stable for the
        // background pass, not the individual item objects (all mutated
        // fields are simple booleans flipped on the main thread, and any
        // render in flight when one changes is immediately followed by
        // another applyFilterAndRender() call that supersedes it).
        final List<NotificationAdapter.NotificationItem> snapshot = new ArrayList<>(allRawNotifications);

        bgExecutor.execute(() -> {
            RenderResult result = computeRenderResult(snapshot, filter, category);
            mainHandler.post(() -> {
                if (generation != renderGeneration || !isAdded()) return;
                applyRenderResult(result);
            });
        });
    }

    private RenderResult computeRenderResult(List<NotificationAdapter.NotificationItem> source,
                                              FilterType filter, CategoryFilter category) {
        Map<CategoryFilter, Integer> categoryUnread = new EnumMap<>(CategoryFilter.class);
        boolean hasUnspecified = false;
        List<NotificationAdapter.NotificationItem> filtered = new ArrayList<>();

        for (NotificationAdapter.NotificationItem item : source) {
            CategoryFilter itemCategory = classifyItem(item);
            if (itemCategory == CategoryFilter.UNSPECIFIED) hasUnspecified = true;
            if (!item.isReadForCurrentUser) {
                categoryUnread.merge(itemCategory, 1, Integer::sum);
            }

            if (filter == FilterType.UNREAD && item.isReadForCurrentUser) continue;
            if (filter == FilterType.READ && !item.isReadForCurrentUser) continue;
            if (category != CategoryFilter.ALL && itemCategory != category) continue;
            filtered.add(item);
        }

        if (filtered.isEmpty()) {
            String categorySuffix = category == CategoryFilter.ALL ? "" : " in this category";
            String message;
            if (filter == FilterType.UNREAD) {
                message = "No unread notifications" + categorySuffix + ".";
            } else if (filter == FilterType.READ) {
                message = "No read notifications" + categorySuffix + ".";
            } else if (!categorySuffix.isEmpty()) {
                message = "No notifications in this category yet.";
            } else {
                message = "No notifications yet.";
            }
            return new RenderResult(Collections.emptyList(), categoryUnread, hasUnspecified, message);
        }

        Collections.sort(filtered, (a, b) -> Long.compare(b.timestamp, a.timestamp));

        List<NotificationAdapter.NotificationItem> rendered = new ArrayList<>(filtered.size() + 8);
        String lastMonth = "";
        SimpleDateFormat monthFormat = new SimpleDateFormat("MMMM yyyy", Locale.US);

        for (NotificationAdapter.NotificationItem item : filtered) {
            String currentMonth = monthFormat.format(new Date(item.timestamp));
            if (!currentMonth.equals(lastMonth)) {
                rendered.add(NotificationAdapter.NotificationItem.createHeader(currentMonth, rendered.isEmpty()));
                lastMonth = currentMonth;
            }
            rendered.add(item);
        }

        return new RenderResult(rendered, categoryUnread, hasUnspecified, null);
    }

    private void applyRenderResult(RenderResult result) {
        categoryUnreadCounts.clear();
        categoryUnreadCounts.putAll(result.categoryUnread);
        hasUnspecifiedCategory = result.hasUnspecified;
        updateCategoryButtonLabel();

        if (result.emptyMessage != null) {
            showEmptyState(result.emptyMessage);
            return;
        }

        hideProgressBar();
        recyclerView.setVisibility(View.VISIBLE);
        if (tvEmptyState != null) tvEmptyState.setVisibility(View.GONE);
        adapter.submitList(result.rendered);
    }

    private void updateFilterUI() {
        if (getContext() == null) return;

        if (btnFilterAll != null) btnFilterAll.setSelected(currentFilter == FilterType.ALL);
        if (btnFilterUnread != null) btnFilterUnread.setSelected(currentFilter == FilterType.UNREAD);
        if (btnFilterRead != null) btnFilterRead.setSelected(currentFilter == FilterType.READ);
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
        adapter.submitList(Collections.emptyList());
        recyclerView.setVisibility(View.GONE);
        if (tvEmptyState != null) {
            tvEmptyState.setText(message);
            tvEmptyState.setVisibility(View.VISIBLE);
        }
    }

    private Long readTimestampMillis(QueryDocumentSnapshot doc) {
        Object raw = doc.get("timestamp");
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof Timestamp) {
            return ((Timestamp) raw).toDate().getTime();
        }
        return null;
    }

    @Override
    public void onDestroyView() {
        dismissLoading();
        super.onDestroyView();
        if (notificationListener != null) notificationListener.remove();
        if (counterListener != null) counterListener.remove();
    }

    @Override
    public void onDestroy() {
        bgExecutor.shutdown();
        super.onDestroy();
    }

    private void dismissLoading() {
        if (loadingHandle != null) loadingHandle.dismiss();
        loadingHandle = null;
    }
}
