package com.example.basilience;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HarvestLogFragment extends Fragment {

    private TextView tvCycleLabel, tvExpectedDate;
    private EditText etWeight;
    private Button btnSave;
    private RecyclerView recyclerHarvest;
    private LineChart harvestChart;
    
    private Database_Helper dbHelper;
    private int cycleNo;
    private HarvestAdapter adapter;
    private List<HarvestEntry> harvestList = new ArrayList<>();
    private ListenerRegistration harvestListener;
    
    // Internal class for Harvest Entry if no separate file
    public static class HarvestEntry {
        public String date;
        public double weight;
        public HarvestEntry() {}
        public HarvestEntry(String date, double weight) {
            this.date = date;
            this.weight = weight;
        }
    }

    public HarvestLogFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.cycle_harvest_log, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        dbHelper = new Database_Helper();
        NavController navController = Navigation.findNavController(view);

        if (getArguments() != null) {
            cycleNo = getArguments().getInt("cycleNo", 1);
        }

        tvCycleLabel = view.findViewById(R.id.tvCycleLabel);
        tvCycleLabel.setText("Cycle #" + cycleNo);

        tvExpectedDate = view.findViewById(R.id.tvExpectedDate);
        etWeight = view.findViewById(R.id.etWeight);
        btnSave = view.findViewById(R.id.btnSaveHarvest);
        recyclerHarvest = view.findViewById(R.id.recyclerHarvest);
        harvestChart = view.findViewById(R.id.harvestChart);

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }

        btnSave.setOnClickListener(v -> saveHarvest());

        // Setup RecyclerView
        adapter = new HarvestAdapter(harvestList);
        recyclerHarvest.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerHarvest.setAdapter(adapter);
        
        loadHarvestData();
    }

    private void loadHarvestData() {
        if (harvestListener != null) harvestListener.remove();

        dbHelper.resolveDataUid().addOnSuccessListener(uid -> {
            if (uid != null) {
                dbHelper.setTargetUid(uid);
                harvestListener = dbHelper.listenToHarvestEntries(cycleNo, (value, error) -> {
                    if (error != null || value == null) return;

                    harvestList.clear();
                    List<Entry> chartEntries = new ArrayList<>();
                    int index = 0;

                    for (QueryDocumentSnapshot doc : value) {
                        HarvestEntry entry = doc.toObject(HarvestEntry.class);
                        harvestList.add(entry);
                        chartEntries.add(new Entry(index++, (float) entry.weight));
                    }
                    adapter.notifyDataSetChanged();
                    updateChart(chartEntries);
                });
            }
        });
    }

    private void updateChart(List<Entry> chartEntries) {
        if (chartEntries.isEmpty() || getContext() == null) return;

        LineDataSet dataSet = new LineDataSet(chartEntries, "Harvest Weight (g)");
        dataSet.setColor(getResources().getColor(R.color.primary));
        dataSet.setValueTextColor(getResources().getColor(R.color.black));
        dataSet.setCircleColor(getResources().getColor(R.color.primary));
        dataSet.setLineWidth(2f);
        dataSet.setCircleRadius(4f);
        dataSet.setDrawCircleHole(false);
        dataSet.setValueTextSize(10f);
        dataSet.setDrawFilled(true);
        dataSet.setFillColor(getResources().getColor(R.color.primary));

        LineData lineData = new LineData(dataSet);
        harvestChart.setData(lineData);
        harvestChart.invalidate(); // refresh
        harvestChart.getDescription().setEnabled(false);
        harvestChart.getLegend().setEnabled(false);
    }

    private void saveHarvest() {
        String weightStr = etWeight.getText().toString().trim();
        if (weightStr.isEmpty()) {
            NotificationHelper.showError(getContext(), "Enter weight");
            return;
        }

        double weight = Double.parseDouble(weightStr);
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

        Map<String, Object> entry = new HashMap<>();
        entry.put("date", date);
        entry.put("weight", weight);

        dbHelper.resolveDataUid().addOnSuccessListener(uid -> {
            if (uid != null) {
                dbHelper.setTargetUid(uid);
                dbHelper.addHarvestEntry(cycleNo, entry)
                        .addOnSuccessListener(aVoid -> {
                            NotificationHelper.showSuccess(getContext(), "Harvest saved");
                            etWeight.setText("");
                        })
                        .addOnFailureListener(e -> NotificationHelper.showError(getContext(), "Error: " + e.getMessage()));
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (harvestListener != null) harvestListener.remove();
    }
}
