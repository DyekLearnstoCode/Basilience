package com.example.basilience;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NotificationFragment extends Fragment {

    private RecyclerView recyclerView;
    private TextView tvEmptyState;
    private NotificationAdapter adapter;
    private final List<NotificationAdapter.NotificationItem> notificationList = new ArrayList<>();
    private Database_Helper dbHelper;
    private ListenerRegistration notificationListener;

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
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new NotificationAdapter(notificationList);
        recyclerView.setAdapter(adapter);

        // Back button
        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.GONE);
        }

        loadNotifications();
    }

    private void loadNotifications() {
        if (notificationListener != null) notificationListener.remove();

        // Resolve deviceId from dbHelper or shared prefs fallback
        String deviceId = dbHelper.getSelectedDeviceId();
        if (deviceId == null) {
            android.content.SharedPreferences prefs =
                    requireContext().getSharedPreferences("basilience_prefs",
                            android.content.Context.MODE_PRIVATE);
            deviceId = prefs.getString("selected_device_id", null);
            if (deviceId != null) dbHelper.setSelectedDeviceId(deviceId);
        }

        if (deviceId == null) {
            showEmptyState("No device selected.");
            return;
        }

        notificationListener = dbHelper.listenToNotifications((value, error) -> {
            if (!isAdded()) return;
            if (error != null || value == null) {
                showEmptyState("Could not load notifications.");
                return;
            }

            List<NotificationAdapter.NotificationItem> rawList = new ArrayList<>();
            for (QueryDocumentSnapshot doc : value) {
                try {
                    String message   = doc.getString("message");
                    String type      = doc.getString("type");
                    Long   timestamp = doc.getLong("timestamp");

                    if (message != null && type != null && timestamp != null) {
                        rawList.add(new NotificationAdapter.NotificationItem(message, timestamp, type));
                    }
                } catch (Exception e) {
                    // skip malformed documents
                }
            }

            if (rawList.isEmpty()) {
                showEmptyState("No notifications yet.");
                return;
            }

            // Sort newest first
            Collections.sort(rawList, (a, b) -> Long.compare(b.timestamp, a.timestamp));

            // Group by Month with section headers
            notificationList.clear();
            String lastMonth = "";
            SimpleDateFormat monthFormat = new SimpleDateFormat("MMMM yyyy", Locale.US);

            for (NotificationAdapter.NotificationItem item : rawList) {
                String currentMonth = monthFormat.format(new Date(item.timestamp));
                if (!currentMonth.equals(lastMonth)) {
                    notificationList.add(NotificationAdapter.NotificationItem.createHeader(currentMonth));
                    lastMonth = currentMonth;
                }
                notificationList.add(item);
            }

            recyclerView.setVisibility(View.VISIBLE);
            if (tvEmptyState != null) tvEmptyState.setVisibility(View.GONE);
            adapter.notifyDataSetChanged();
        });
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (notificationListener != null) notificationListener.remove();
    }
}
