package com.example.basilience;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NotificationFragment extends Fragment {
    private static final String TAG = "NotificationFragment";

    private enum FilterType { ALL, UNREAD, READ }
    private FilterType currentFilter = FilterType.ALL;

    private RecyclerView recyclerView;
    private TextView tvEmptyState;
    private NotificationAdapter adapter;
    private final List<NotificationAdapter.NotificationItem> notificationList = new ArrayList<>();
    private final List<NotificationAdapter.NotificationItem> allRawNotifications = new ArrayList<>();
    
    private Database_Helper dbHelper;
    private ListenerRegistration notificationListener;

    private MaterialButton btnFilterAll, btnFilterUnread, btnFilterRead;
    private boolean markingAllRead;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.notification_feature, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dbHelper = new Database_Helper();

        recyclerView = view.findViewById(R.id.recyclerNotifications);
        tvEmptyState = view.findViewById(R.id.tvEmptyNotifications);
        btnFilterAll = view.findViewById(R.id.btnFilterAll);
        btnFilterUnread = view.findViewById(R.id.btnFilterUnread);
        btnFilterRead = view.findViewById(R.id.btnFilterRead);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        
        adapter = new NotificationAdapter(notificationList, item -> {
            if (!item.isRead && item.docId != null && dbHelper.getSelectedDeviceId() != null) {
                // Mark notification as read in Firestore
                item.isRead = true;
                FirebaseFirestore.getInstance()
                        .collection("devices")
                        .document(dbHelper.getSelectedDeviceId())
                        .collection("notifications")
                        .document(item.docId)
                        .update("isRead", true);
                
                applyFilterAndRender();
            }
        }, this::markAllAsRead);
        recyclerView.setAdapter(adapter);

        // Setup filter button click handlers
        if (btnFilterAll != null) btnFilterAll.setOnClickListener(v -> setFilter(FilterType.ALL));
        if (btnFilterUnread != null) btnFilterUnread.setOnClickListener(v -> setFilter(FilterType.UNREAD));
        if (btnFilterRead != null) btnFilterRead.setOnClickListener(v -> setFilter(FilterType.READ));

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setVisibility(View.GONE);

        loadNotifications();
    }

    private void setFilter(FilterType filter) {
        this.currentFilter = filter;
        updateFilterUI();
        applyFilterAndRender();
    }

    private void updateFilterUI() {
        if (getContext() == null) return;
        int white = ContextCompat.getColor(requireContext(), R.color.white);
        int black = ContextCompat.getColor(requireContext(), R.color.black);

        if (btnFilterAll != null) {
            btnFilterAll.setBackgroundResource(currentFilter == FilterType.ALL ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            btnFilterAll.setTextColor(currentFilter == FilterType.ALL ? white : black);
        }
        if (btnFilterUnread != null) {
            btnFilterUnread.setBackgroundResource(currentFilter == FilterType.UNREAD ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            btnFilterUnread.setTextColor(currentFilter == FilterType.UNREAD ? white : black);
        }
        if (btnFilterRead != null) {
            btnFilterRead.setBackgroundResource(currentFilter == FilterType.READ ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            btnFilterRead.setTextColor(currentFilter == FilterType.READ ? white : black);
        }
    }

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

            allRawNotifications.clear();
            for (QueryDocumentSnapshot doc : value) {
                try {
                    String docId     = doc.getId();
                    String message   = doc.getString("message");
                    String type      = doc.getString("type");
                    Long   timestamp = readTimestampMillis(doc);
                    Boolean isRead   = doc.getBoolean("isRead");

                    if (message != null && type != null && timestamp != null) {
                        allRawNotifications.add(new NotificationAdapter.NotificationItem(
                                docId, message, timestamp, type, isRead != null && isRead
                        ));
                    } else {
                        Log.w(TAG, "Skipping malformed notification document: " + doc.getReference().getPath());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Skipping malformed notification document: " + doc.getReference().getPath(), e);
                }
            }

            applyFilterAndRender();
            updateMarkAllReadState();
        });
    }

    private void markAllAsRead() {
        if (markingAllRead) return;
        String deviceId = dbHelper.getSelectedDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            NotificationHelper.showError(requireContext(), "No device selected.");
            return;
        }
        List<String> unreadIds = new ArrayList<>();
        for (NotificationAdapter.NotificationItem item : allRawNotifications) {
            if (!item.isRead && item.docId != null) unreadIds.add(item.docId);
        }
        if (unreadIds.isEmpty()) {
            NotificationHelper.showSuccess(requireContext(), "All notifications are already read.");
            updateMarkAllReadState();
            return;
        }
        markingAllRead = true;
        updateMarkAllReadState();
        dbHelper.markNotificationsRead(deviceId, unreadIds).addOnSuccessListener(unused -> {
            if (!isAdded()) return;
            for (NotificationAdapter.NotificationItem item : allRawNotifications) item.isRead = true;
            markingAllRead = false;
            applyFilterAndRender();
            updateMarkAllReadState();
            NotificationHelper.showSuccess(requireContext(), "All notifications marked as read.");
        }).addOnFailureListener(e -> {
            if (!isAdded()) return;
            markingAllRead = false;
            updateMarkAllReadState();
            NotificationHelper.showError(requireContext(), "Unable to mark notifications as read: " + e.getMessage());
        });
    }

    private void updateMarkAllReadState() {
        boolean hasUnread = false;
        for (NotificationAdapter.NotificationItem item : allRawNotifications) if (!item.isRead) { hasUnread = true; break; }
        adapter.setMarkAllReadState(hasUnread, markingAllRead);
    }

    private void applyFilterAndRender() {
        List<NotificationAdapter.NotificationItem> filtered = new ArrayList<>();
        for (NotificationAdapter.NotificationItem item : allRawNotifications) {
            if (currentFilter == FilterType.UNREAD && item.isRead) continue;
            if (currentFilter == FilterType.READ && !item.isRead) continue;
            filtered.add(item);
        }

        if (filtered.isEmpty()) {
            if (currentFilter == FilterType.UNREAD) {
                showEmptyState("No unread notifications.");
            } else if (currentFilter == FilterType.READ) {
                showEmptyState("No read notifications.");
            } else {
                showEmptyState("No notifications yet.");
            }
            return;
        }

        // Sort newest first
        Collections.sort(filtered, (a, b) -> Long.compare(b.timestamp, a.timestamp));

        // Group by month
        notificationList.clear();
        String lastMonth = "";
        SimpleDateFormat monthFormat = new SimpleDateFormat("MMMM yyyy", Locale.US);

        for (NotificationAdapter.NotificationItem item : filtered) {
            String currentMonth = monthFormat.format(new Date(item.timestamp));
            if (!currentMonth.equals(lastMonth)) {
                notificationList.add(NotificationAdapter.NotificationItem.createHeader(
                        currentMonth, notificationList.isEmpty()));
                lastMonth = currentMonth;
            }
            notificationList.add(item);
        }

        recyclerView.setVisibility(View.VISIBLE);
        if (tvEmptyState != null) tvEmptyState.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void showEmptyState(String message) {
        notificationList.clear();
        adapter.notifyDataSetChanged();
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
        super.onDestroyView();
        if (notificationListener != null) notificationListener.remove();
    }
}
