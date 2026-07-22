package com.example.basilience;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class NotificationFragment extends Fragment {

    private RecyclerView recyclerView;
    private NotificationAdapter adapter;
    private List<NotificationAdapter.NotificationItem> notificationList = new ArrayList<>();
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
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new NotificationAdapter(notificationList);
        recyclerView.setAdapter(adapter);

        loadNotifications();

        // Back button configuration
        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> {
                androidx.navigation.Navigation.findNavController(view).popBackStack();
            });
        }
    }

    private void addSampleNotificationsToList() {
        notificationList.add(new NotificationAdapter.NotificationItem(
                "Temperature warning: High heat detected in the chamber.",
                System.currentTimeMillis() - (1000 * 60 * 5),
                NotificationAdapter.NotificationItem.TYPE_PARAMETER
        ));

        notificationList.add(new NotificationAdapter.NotificationItem(
                "Hardware Alert: Water pump is not responding.",
                System.currentTimeMillis() - (1000 * 60 * 15),
                NotificationAdapter.NotificationItem.TYPE_HARDWARE
        ));

        notificationList.add(new NotificationAdapter.NotificationItem(
                "Harvest Ready: Basil batch #12 is ready for harvest!",
                System.currentTimeMillis() - (1000 * 60 * 60 * 3),
                NotificationAdapter.NotificationItem.TYPE_HARVEST
        ));

        notificationList.add(new NotificationAdapter.NotificationItem(
                "Welcome to Basilience! Monitoring is active.",
                System.currentTimeMillis() - (1000 * 60 * 60 * 24),
                NotificationAdapter.NotificationItem.TYPE_INFO
        ));
    }

    private void loadNotifications() {
        if (notificationListener != null) notificationListener.remove();

        // Check for selected device in preferences if not set
        String deviceId = dbHelper.getSelectedDeviceId();
        if (deviceId == null) {
            android.content.SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
            deviceId = prefs.getString("selected_device_id", null);
            if (deviceId != null) {
                dbHelper.setSelectedDeviceId(deviceId);
            }
        }

        if (deviceId != null) {
            notificationListener = dbHelper.listenToNotifications((value, error) -> {
                if (error != null || value == null) return;

                    List<NotificationAdapter.NotificationItem> rawList = new ArrayList<>();
                    
                    // Add Sample Data
                    rawList.add(new NotificationAdapter.NotificationItem(
                            "Temperature warning: High heat detected in the chamber.",
                            System.currentTimeMillis() - (1000 * 60 * 5),
                            NotificationAdapter.NotificationItem.TYPE_PARAMETER
                    ));
                    rawList.add(new NotificationAdapter.NotificationItem(
                            "Hardware Alert: Water pump is not responding.",
                            System.currentTimeMillis() - (1000 * 60 * 60 * 2),
                            NotificationAdapter.NotificationItem.TYPE_HARDWARE
                    ));
                    rawList.add(new NotificationAdapter.NotificationItem(
                            "Harvest Ready: Basil batch #12 is ready for harvest!",
                            System.currentTimeMillis() - (1000L * 60 * 60 * 24 * 3), // 3 days ago
                            NotificationAdapter.NotificationItem.TYPE_HARVEST
                    ));

                    // Add Firebase Data
                    for (QueryDocumentSnapshot doc : value) {
                        NotificationAdapter.NotificationItem item = doc.toObject(NotificationAdapter.NotificationItem.class);
                        rawList.add(item);
                    }

                    // Sort chronologically (Newest first)
                    Collections.sort(rawList, (a, b) -> Long.compare(b.timestamp, a.timestamp));

                    // Group by Month and add headers
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

                    adapter.notifyDataSetChanged();
                });
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (notificationListener != null) notificationListener.remove();
    }
}
