package com.example.basilience;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.basilience.models.FoggingEvent;
import com.example.basilience.models.FoggingReportFilter;
import com.example.basilience.models.FoggingReportSummary;
import com.example.basilience.models.FoggingReportTotals;
import com.example.basilience.models.FoggingSession;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;

public class FoggingReportsFragment extends Fragment {

    private static final String TIMEZONE_ID = "Asia/Manila";
    // Simple, documented cutoffs describing RECORDED operation only - not an
    // agronomic scoring algorithm. A multi-day window is "NORMAL ACTIVITY"
    // when at least this share of its already-elapsed days recorded any
    // fogging at all; a single-day window can't be judged this way, so it's
    // handled separately.
    private static final double MIN_CONSISTENT_ACTIVITY_DAY_RATIO = 0.6;
    // "Manual-heavy" wording only kicks in once manual sessions make up more
    // than this share of the period's sessions.
    private static final double MANUAL_HEAVY_SHARE_THRESHOLD = 0.2;

    // Water Outlook is an operational "will I need to refill soon" estimate,
    // not a historical report - it must stay independent of whatever
    // cycle/period the farmer happens to have selected above, so it always
    // uses this fixed rolling window ending now, never
    // FoggingReportFilter.effectiveStartMs/effectiveEndMs.
    private static final int WATER_OUTLOOK_LOOKBACK_DAYS = 7;
    // Estimation assumptions, not measured device configuration - see the
    // "Estimated ..." UI wording that makes clear this is a rough forecast.
    private static final float WATER_OUTLOOK_TANK_CAPACITY_LITERS = 61.7f;
    private static final float WATER_OUTLOOK_CONSUMPTION_RATE_L_PER_HOUR = 4.8f;
    // Mirrors functions/index.js's SENSOR_LOG_INTERVAL_MS (5 minutes) - the
    // backend throttles new parameterLogs writes to at most one per that
    // interval - multiplied by 3 for headroom so one or two missed/delayed
    // log cycles (a brief connectivity hiccup, a cold start, ordinary
    // throttle-boundary timing) never falsely flag a genuinely fresh reading
    // as stale. Keep in sync if the backend interval ever changes.
    private static final long WATER_OUTLOOK_FRESHNESS_THRESHOLD_MS = 15L * 60 * 1000;

    private Spinner spinnerCycle;
    private BarChart barChart;
    private TextView tvTotalDuration, tvEventCount, tvAvgDuration;
    private TextView tvBreakdownAuto, tvBreakdownAutoDetails, tvBreakdownManual;
    private TextView tvWaterLevel, tvRefillTime, tvRefillThreshold;
    private TextView tvFoggingStatus, tvSessionBreakdown, tvFoggingInterpretation, tvEffectiveRange;
    private TextView tvHeroAutoCount, tvHeroManualCount, tvWaterOutlookMessage;
    private View dotFoggingStatus, heroAccentEdge, heroControlBlock;
    private View waterOutlookValues, waterLevelColumn, refillColumn;
    private View strategySection;
    private android.widget.LinearLayout strategyRows;
    private RecyclerView rvRecentActivity;
    private TextView tvEmptyActivity;
    private View layoutLoading;
    private View reportContentContainer, noCyclesEmptyState;
    private TextView tvNoCyclesEmptyState;
    // The X-axis label strategy for the chart currently on screen - rebuilt
    // each time renderReport() loads new data, and mutated in place by the
    // chart gesture listener as the farmer zooms/pans (same pattern as
    // Parameter Report's AdaptiveTimeAxisFormatter field).
    private AdaptiveTimeAxisFormatter foggingXFormatter;
    // The real spacing (in minutes) between this render's actual buckets -
    // hourly for "Today", daily otherwise. Axis granularity must never drop
    // below this, or gridlines/labels could appear between real bars.
    private float foggingBucketSpacingMinutes = 24 * 60f;

    private FoggingEventAdapter adapter;

    private MaterialButton btnEntireCycle, btnToday, btnWeek, btnMonth, btnCustom;
    // UI-visual only: btnShare is a full-width MaterialButton now (was an
    // icon-only ImageButton); only View-level setOnClickListener() is ever
    // called on it, so this type change carries no behavior difference.
    private MaterialButton btnShare;
    private String currentSelectedFilter = "Entire Cycle";
    private String selectedDeviceId;

    private FirebaseFirestore db;
    private Database_Helper dbHelper;
    private double refillStartLevel = 0.0;

    private final List<Cycle> cycles = new ArrayList<>();
    private Cycle selectedCycle;
    private ListenerRegistration cyclesListener;

    private Long customStartMs;
    private Long customEndMs;

    // The single authoritative state for the report currently on screen,
    // frozen once its data finishes loading. The chart, Recent Activity,
    // farmer summary and PDF export all reuse this exact state instead of
    // each recomputing their own range, so export can never describe a
    // different data subset than what the farmer is looking at.
    private FoggingReportFilter currentFilter;
    private FoggingReportSummary currentSummary;
    // Frozen aggregate totals for the report currently on screen. The screen
    // renders from this and the PDF export is handed the same object, so the
    // two can never present different numbers for the same selection.
    private FoggingReportTotals currentTotals;
    private List<FoggingSession> processedSessions = new ArrayList<>();
    private String currentStatus = "SELECT A CYCLE";
    private String currentInterpretation = "Select a cycle to view a report.";
    // Session counts feed the interpretation's automatic/manual wording. The
    // average session duration deliberately has no field of its own - it
    // lives only in currentTotals, so there is exactly one copy of it.
    private int currentAutoCount;
    private int currentManualCount;

    // Guards Water Outlook's own independent async chain against stale
    // callbacks (fragment detached, or a newer load superseding an older
    // in-flight one) - completely separate from reportRequestGeneration-style
    // guarding on the historical report, since Water Outlook is never
    // re-triggered by filter/cycle changes.
    private long waterOutlookRequestGeneration = 0L;

    // Guards the historical report's own async chain (fetchFoggingLogs ->
    // fetchBoundaryEventAndProcess -> resolveRunningStateAndRender ->
    // renderReport) so that a slow/old request whose callback resolves after
    // a newer cycle/period selection already started loading (or already
    // rendered) can never overwrite that newer state.
    private long reportRequestGeneration = 0L;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.reports_fogging, container, false);

        db = FirebaseFirestore.getInstance();
        dbHelper = new Database_Helper();

        if (getArguments() != null) {
            selectedDeviceId = getArguments().getString("deviceId");
        }

        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) {
            SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE);
            selectedDeviceId = prefs.getString("selected_device_id", null);
        }

        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) {
            Toast.makeText(getContext(), "Please select a device first", Toast.LENGTH_SHORT).show();
        } else {
            dbHelper.setSelectedDeviceId(selectedDeviceId);
        }

        spinnerCycle = view.findViewById(R.id.spinnerCycle);
        barChart = view.findViewById(R.id.barChart);
        ImageButton btnInfo = view.findViewById(R.id.btnInfo);
        if (btnInfo != null) {
            btnInfo.setOnClickListener(v -> showInfoDialog());
        }
        tvTotalDuration = view.findViewById(R.id.tvTotalDuration);
        tvEventCount = view.findViewById(R.id.tvEventCount);
        tvAvgDuration = view.findViewById(R.id.tvAvgDuration);
        tvBreakdownAuto = view.findViewById(R.id.tvBreakdownAuto);
        tvBreakdownAutoDetails = view.findViewById(R.id.tvBreakdownAutoDetails);
        tvBreakdownManual = view.findViewById(R.id.tvBreakdownManual);

        tvWaterLevel = view.findViewById(R.id.tvWaterLevel);
        tvRefillTime = view.findViewById(R.id.tvRefillTime);
        tvRefillThreshold = view.findViewById(R.id.tvRefillThreshold);

        tvFoggingStatus = view.findViewById(R.id.tvFoggingStatus);
        tvSessionBreakdown = view.findViewById(R.id.tvSessionBreakdown);
        tvFoggingInterpretation = view.findViewById(R.id.tvFoggingInterpretation);
        tvEffectiveRange = view.findViewById(R.id.tvEffectiveRange);
        tvHeroAutoCount = view.findViewById(R.id.tvHeroAutoCount);
        tvHeroManualCount = view.findViewById(R.id.tvHeroManualCount);
        tvWaterOutlookMessage = view.findViewById(R.id.tvWaterOutlookMessage);
        dotFoggingStatus = view.findViewById(R.id.dotFoggingStatus);
        heroAccentEdge = view.findViewById(R.id.heroAccentEdge);
        heroControlBlock = view.findViewById(R.id.heroControlBlock);
        waterOutlookValues = view.findViewById(R.id.waterOutlookValues);
        waterLevelColumn = view.findViewById(R.id.waterLevelColumn);
        refillColumn = view.findViewById(R.id.refillColumn);
        strategySection = view.findViewById(R.id.strategySection);
        strategyRows = view.findViewById(R.id.strategyRows);
        reportContentContainer = view.findViewById(R.id.reportContentContainer);
        noCyclesEmptyState = view.findViewById(R.id.noCyclesEmptyState);
        tvNoCyclesEmptyState = view.findViewById(R.id.tvNoCyclesEmptyState);

        rvRecentActivity = view.findViewById(R.id.rvRecentActivity);
        tvEmptyActivity = view.findViewById(R.id.tvEmptyActivity);
        layoutLoading = view.findViewById(R.id.layoutLoading);

        rvRecentActivity.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new FoggingEventAdapter(new ArrayList<>());
        rvRecentActivity.setAdapter(adapter);

        btnEntireCycle = view.findViewById(R.id.btnEntireCycle);
        btnToday = view.findViewById(R.id.btnToday);
        btnWeek = view.findViewById(R.id.btnWeek);
        btnMonth = view.findViewById(R.id.btnMonth);
        btnCustom = view.findViewById(R.id.btnCustom);
        btnShare = view.findViewById(R.id.btnShare);

        if (btnShare != null) {
            btnShare.setOnClickListener(v -> exportPdf());
        }

        spinnerCycle.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (position < 0 || position >= cycles.size()) return;
                Cycle newlySelected = cycles.get(position);
                boolean cycleChanged = selectedCycle == null
                        || !Objects.equals(selectedCycle.getCycleId(), newlySelected.getCycleId());
                selectedCycle = newlySelected;
                if (cycleChanged) {
                    customStartMs = null;
                    customEndMs = null;
                    currentSelectedFilter = "Entire Cycle";
                }
                updatePeriodChipsForSelectedCycle();
                loadData();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        btnEntireCycle.setOnClickListener(v -> updateFilterSelection("Entire Cycle"));
        btnToday.setOnClickListener(v -> updateFilterSelection("Today"));
        btnWeek.setOnClickListener(v -> updateFilterSelection("7 Days"));
        btnMonth.setOnClickListener(v -> updateFilterSelection("30 Days"));
        btnCustom.setOnClickListener(v -> startCustomRangeSelection());

        setupChart();

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            NavController navController = NavHostFragment.findNavController(this);
            btnBack.setOnClickListener(v -> navController.popBackStack());
        }

        fetchRefillThreshold();
        startListeningToCycles();

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (cyclesListener != null) cyclesListener.remove();
    }

    private void setupChart() {
        // Same chart chrome approved on Parameter Report: light grid, muted
        // axis text, no outer border. Visual only - bar values and buckets
        // are untouched.
        int mutedAxisColor = Color.parseColor("#8A2E4F46");
        int hairlineColor = Color.parseColor("#F0F0F0");

        barChart.getDescription().setEnabled(false);
        barChart.setDrawGridBackground(false);
        barChart.setDrawBorders(false);
        barChart.getAxisRight().setEnabled(false);
        // The chart surface already carries the title "Daily Fogging
        // Runtime", so the legend would only repeat it.
        barChart.getLegend().setEnabled(false);
        barChart.setExtraBottomOffset(8f);
        barChart.setHighlightPerTapEnabled(true);
        barChart.setHighlightPerDragEnabled(false);
        // Horizontal zoom/pan only - vertical scale isn't meaningful for a
        // fixed-value-axis runtime chart and would just be confusing.
        barChart.setScaleYEnabled(false);

        XAxis xAxis = barChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setTextColor(mutedAxisColor);
        xAxis.setTextSize(11f);
        xAxis.setAxisLineColor(hairlineColor);
        xAxis.setAvoidFirstLastClipping(true);
        // Granularity/labelCount depend on the loaded bucket spacing (hourly
        // vs daily), so they're (re)set per render in renderReport() rather
        // than fixed here.

        com.github.mikephil.charting.components.YAxis axisLeft = barChart.getAxisLeft();
        axisLeft.setTextColor(mutedAxisColor);
        axisLeft.setTextSize(11f);
        axisLeft.setGridColor(hairlineColor);
        axisLeft.setAxisLineColor(hairlineColor);
        axisLeft.setLabelCount(5, false);
        axisLeft.setAxisMinimum(0f);
        // Y values are fogging runtime in minutes (see renderReport);
        // formatted with unambiguous "min"/"hr" units - never changes the
        // plotted numbers, only how each tick reads.
        axisLeft.setValueFormatter(new com.github.mikephil.charting.formatter.ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                return DurationFormatter.formatAxisMinutes(value);
            }
        });

        // The marker is (re)created per render in renderReport() - it needs
        // the current chart's base timestamp and bucket resolution, which
        // change with each new filter/cycle selection.

        setupFoggingChartGestures();
    }

    // ------------------------------------------------------------------
    // Zoom-aware X axis (adviser feedback): registered once, reused across
    // every render. Horizontal zoom/pan only - vertical scale isn't
    // meaningful for a fixed-value-axis runtime chart. Gesture handling only
    // recomputes label formatting/density and invalidates the chart; it
    // never touches the dataset or requeries anything.
    // ------------------------------------------------------------------

    private void setupFoggingChartGestures() {
        barChart.setOnChartGestureListener(new OnChartGestureListener() {
            @Override public void onChartGestureStart(MotionEvent me, ChartTouchListener.ChartGesture lastPerformedGesture) { }
            @Override public void onChartGestureEnd(MotionEvent me, ChartTouchListener.ChartGesture lastPerformedGesture) { }
            @Override public void onChartLongPressed(MotionEvent me) { }
            @Override public void onChartDoubleTapped(MotionEvent me) { }
            @Override public void onChartSingleTapped(MotionEvent me) { }
            @Override public void onChartFling(MotionEvent me1, MotionEvent me2, float velocityX, float velocityY) { }
            @Override public void onChartScale(MotionEvent me, float scaleX, float scaleY) { refreshFoggingAdaptiveXAxis(); }
            @Override public void onChartTranslate(MotionEvent me, float dX, float dY) { refreshFoggingAdaptiveXAxis(); }
        });
    }

    /** Re-derives label format/density from the chart's current visible range. Cheap: no data requery. */
    private void refreshFoggingAdaptiveXAxis() {
        if (foggingXFormatter == null || barChart == null) return;
        foggingXFormatter.updateVisibleRange(barChart.getLowestVisibleX(), barChart.getHighestVisibleX());
        applyFoggingAdaptiveXAxis(barChart.getXAxis());
        barChart.invalidate();
    }

    /**
     * Applies the formatter's current granularity/label-count to the axis,
     * floored at the real bucket spacing so gridlines/labels never appear
     * between actual bars. Granularity (a hard floor, via
     * setGranularityEnabled) is what actually prevents label overlap;
     * labelCount is only a soft upper bound on top of it.
     */
    private void applyFoggingAdaptiveXAxis(XAxis xAxis) {
        if (foggingXFormatter == null) return;
        float granularity = Math.max(foggingBucketSpacingMinutes, foggingXFormatter.getGranularityMinutes());
        xAxis.setGranularity(granularity);
        xAxis.setGranularityEnabled(true);
        xAxis.setLabelCount(foggingXFormatter.suggestedLabelCount(), false);
    }

    // ------------------------------------------------------------------
    // Cycle loading & selection (same conceptual pattern as Parameter Reports)
    // ------------------------------------------------------------------

    private void startListeningToCycles() {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) {
            showNoCyclesState("Please select a device first.");
            return;
        }
        cyclesListener = dbHelper.listenToCycles((snapshot, e) -> {
            if (!isAdded()) return;
            if (e != null) {
                Log.e("REPORT_CYCLES", "Unable to load cycles", e);
                showNoCyclesState("Unable to load cultivation cycles for this device.");
                return;
            }

            cycles.clear();
            if (snapshot != null) {
                for (DocumentSnapshot doc : snapshot.getDocuments()) {
                    Cycle cycle = doc.toObject(Cycle.class);
                    if (cycle != null) {
                        if (cycle.getCycleId() == null) cycle.setCycleId(doc.getId());
                        cycles.add(cycle);
                    }
                }
            }

            if (cycles.isEmpty()) {
                selectedCycle = null;
                showNoCyclesState("No cultivation cycles exist for this device yet. Start a cycle to begin tracking fogging reports for it.");
                return;
            }

            populateCycleSpinner();
        });
    }

    private void populateCycleSpinner() {
        showReportContent();

        List<String> labels = new ArrayList<>();
        for (Cycle c : cycles) labels.add(cycleSpinnerLabel(c));

        int preselect = -1;
        if (selectedCycle != null) {
            for (int i = 0; i < cycles.size(); i++) {
                if (Objects.equals(selectedCycle.getCycleId(), cycles.get(i).getCycleId())) {
                    preselect = i;
                    break;
                }
            }
        }
        if (preselect < 0) {
            preselect = cycles.size() - 1;
            for (int i = 0; i < cycles.size(); i++) {
                if ("ACTIVE".equals(normalizeCycleStatus(cycles.get(i).getStatus()))) {
                    preselect = i;
                    break;
                }
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCycle.setAdapter(adapter);
        spinnerCycle.setSelection(preselect);
    }

    private String cycleSpinnerLabel(Cycle c) {
        String name = (c.getCycleName() != null && !c.getCycleName().isEmpty()) ? c.getCycleName() : ("Cycle #" + c.getCycleNumber());
        String status = normalizeCycleStatus(c.getStatus());
        String range = DateUtils.formatDate(c.getStartDate()) + " – "
                + ("COMPLETED".equals(status) ? DateUtils.formatDate(c.getEndDate()) : "Present");
        return name + " • " + ("ACTIVE".equals(status) ? "In Progress" : "Completed") + " • " + range;
    }

    private String normalizeCycleStatus(String rawStatus) {
        return (rawStatus == null || rawStatus.isEmpty()) ? "ACTIVE" : rawStatus.toUpperCase(Locale.US);
    }

    private void showNoCyclesState(String message) {
        // Invalidate any in-flight historical report request so its callback
        // can't repopulate currentFilter/currentSummary after this state
        // intentionally clears them.
        ++reportRequestGeneration;
        if (reportContentContainer != null) reportContentContainer.setVisibility(View.GONE);
        if (noCyclesEmptyState != null) noCyclesEmptyState.setVisibility(View.VISIBLE);
        if (tvNoCyclesEmptyState != null) tvNoCyclesEmptyState.setText(message);
        if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
        currentFilter = null;
        currentSummary = null;
        currentTotals = null;
        processedSessions = new ArrayList<>();
    }

    private void showReportContent() {
        if (reportContentContainer != null) reportContentContainer.setVisibility(View.VISIBLE);
        if (noCyclesEmptyState != null) noCyclesEmptyState.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------
    // Period filter chips
    // ------------------------------------------------------------------

    private void updatePeriodChipsForSelectedCycle() {
        boolean isActive = selectedCycle != null && "ACTIVE".equals(normalizeCycleStatus(selectedCycle.getStatus()));
        btnToday.setVisibility(isActive ? View.VISIBLE : View.GONE);
        if (!isActive && "Today".equals(currentSelectedFilter)) {
            currentSelectedFilter = "Entire Cycle";
        }
        refreshFilterChipHighlight();
    }

    private void updateFilterSelection(String selectedFilter) {
        currentSelectedFilter = selectedFilter;
        refreshFilterChipHighlight();
        loadData();
    }

    private void refreshFilterChipHighlight() {
        // V2 segmented control: unselected segments carry no fill of their own
        // (the track surface around them supplies that), only the active
        // segment gets the solid pill.
        //
        // These are MaterialButtons, which manage their own background drawable
        // and ignore setBackgroundColor()/setBackgroundResource() - those calls
        // silently did nothing, leaving every segment on the Material default
        // container colour with text that did not belong on it. Selection now
        // rides on the view's selected state and is resolved by the
        // chip_segment_* colour state lists.
        boolean today = "Today".equals(currentSelectedFilter);
        boolean week = "7 Days".equals(currentSelectedFilter);
        boolean month = "30 Days".equals(currentSelectedFilter);
        boolean custom = "Custom".equals(currentSelectedFilter);

        btnToday.setSelected(today);
        btnWeek.setSelected(week);
        btnMonth.setSelected(month);
        btnCustom.setSelected(custom);
        btnEntireCycle.setSelected(!today && !week && !month && !custom);
    }

    // ------------------------------------------------------------------
    // Custom date range (same pattern as Parameter Reports)
    // ------------------------------------------------------------------

    private void startCustomRangeSelection() {
        if (selectedCycle == null) return;
        long cycleStartMs = currentCycleStartMs();
        long cycleEndMs = currentCycleEndMs();
        long initialStart = customStartMs != null ? customStartMs : cycleStartMs;

        Calendar initCal = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE_ID));
        initCal.setTimeInMillis(initialStart);

        DatePickerDialog startDialog = new DatePickerDialog(requireContext(), (view, year, month, day) -> {
            Calendar picked = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE_ID));
            picked.set(year, month, day, 0, 0, 0);
            picked.set(Calendar.MILLISECOND, 0);
            long pickedStartMs = picked.getTimeInMillis();

            if (pickedStartMs < startOfDayManila(cycleStartMs)) {
                NotificationHelper.showError(getContext(), "Start date cannot be before this cycle's start date ("
                        + DateUtils.formatDate(cycleStartMs) + ").");
                return;
            }
            if (pickedStartMs > endOfDayManila(cycleEndMs)) {
                NotificationHelper.showError(getContext(), "Start date cannot be after this cycle's end date ("
                        + DateUtils.formatDate(cycleEndMs) + ").");
                return;
            }
            promptCustomEndDate(pickedStartMs, cycleStartMs, cycleEndMs);
        }, initCal.get(Calendar.YEAR), initCal.get(Calendar.MONTH), initCal.get(Calendar.DAY_OF_MONTH));
        startDialog.setTitle("Select Start Date");
        startDialog.show();
    }

    private void promptCustomEndDate(long pickedStartMs, long cycleStartMs, long cycleEndMs) {
        long initialEnd = customEndMs != null ? customEndMs : cycleEndMs;
        Calendar initCal = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE_ID));
        initCal.setTimeInMillis(initialEnd);

        DatePickerDialog endDialog = new DatePickerDialog(requireContext(), (view, year, month, day) -> {
            Calendar picked = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE_ID));
            picked.set(year, month, day, 23, 59, 59);
            picked.set(Calendar.MILLISECOND, 999);
            long pickedEndMs = picked.getTimeInMillis();

            if (pickedEndMs > endOfDayManila(cycleEndMs)) {
                NotificationHelper.showError(getContext(), "End date cannot be after this cycle's end date ("
                        + DateUtils.formatDate(cycleEndMs) + ").");
                return;
            }
            if (pickedEndMs < startOfDayManila(cycleStartMs)) {
                NotificationHelper.showError(getContext(), "End date cannot be before this cycle's start date ("
                        + DateUtils.formatDate(cycleStartMs) + ").");
                return;
            }
            if (pickedEndMs < pickedStartMs) {
                NotificationHelper.showError(getContext(), "End date cannot be before the selected start date.");
                return;
            }

            customStartMs = pickedStartMs;
            customEndMs = pickedEndMs;
            updateFilterSelection("Custom");
        }, initCal.get(Calendar.YEAR), initCal.get(Calendar.MONTH), initCal.get(Calendar.DAY_OF_MONTH));
        endDialog.setTitle("Select End Date");
        endDialog.show();
    }

    private long startOfDayManila(long ms) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE_ID));
        cal.setTimeInMillis(ms);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private long endOfDayManila(long ms) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE_ID));
        cal.setTimeInMillis(ms);
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);
        return cal.getTimeInMillis();
    }

    private boolean isSameManilaDay(long startMs, long endMs) {
        Calendar a = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE_ID));
        a.setTimeInMillis(startMs);
        Calendar b = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE_ID));
        b.setTimeInMillis(endMs);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    // ------------------------------------------------------------------
    // Filter state construction
    // ------------------------------------------------------------------

    private long currentCycleStartMs() {
        return selectedCycle != null && selectedCycle.getStartDate() != null
                ? selectedCycle.getStartDate().toDate().getTime() : System.currentTimeMillis();
    }

    private long currentCycleEndMs() {
        if (selectedCycle == null) return System.currentTimeMillis();
        String status = normalizeCycleStatus(selectedCycle.getStatus());
        long endMs;
        if ("COMPLETED".equals(status) && selectedCycle.getEndDate() != null) {
            endMs = selectedCycle.getEndDate().toDate().getTime();
        } else {
            endMs = System.currentTimeMillis();
        }
        return Math.max(endMs, currentCycleStartMs());
    }

    private long[] getRequestedRangeForPeriod(String period, long cycleStartMs, long cycleEndMs) {
        switch (period) {
            case "Today": {
                long todayStartMs = startOfDayManila(System.currentTimeMillis());
                return new long[]{ todayStartMs, System.currentTimeMillis() };
            }
            case "7 Days":
                return new long[]{ cycleEndMs - 7L * 24 * 60 * 60 * 1000, cycleEndMs };
            case "30 Days":
                return new long[]{ cycleEndMs - 30L * 24 * 60 * 60 * 1000, cycleEndMs };
            case "Custom":
                return new long[]{
                        customStartMs != null ? customStartMs : cycleStartMs,
                        customEndMs != null ? customEndMs : cycleEndMs
                };
            case "Entire Cycle":
            default:
                return new long[]{ cycleStartMs, cycleEndMs };
        }
    }

    private FoggingReportFilter buildCurrentFilter() {
        if (selectedCycle == null || selectedDeviceId == null || selectedDeviceId.isEmpty()) return null;

        long cycleStartMs = currentCycleStartMs();
        long cycleEndMs = currentCycleEndMs();

        // Every period is intersected with the selected cycle's own bounds,
        // so fogging activity can never be shown or exported outside the
        // cycle that was actually selected.
        long[] requested = getRequestedRangeForPeriod(currentSelectedFilter, cycleStartMs, cycleEndMs);
        long effectiveStart = Math.max(cycleStartMs, requested[0]);
        long effectiveEnd = Math.min(cycleEndMs, requested[1]);
        if (effectiveEnd < effectiveStart) effectiveEnd = effectiveStart;

        return new FoggingReportFilter(selectedDeviceId, selectedCycle.getCycleId(), cycleSpinnerLabel(selectedCycle),
                normalizeCycleStatus(selectedCycle.getStatus()), cycleStartMs, cycleEndMs,
                currentSelectedFilter, effectiveStart, effectiveEnd);
    }

    // ------------------------------------------------------------------
    // Data loading
    // ------------------------------------------------------------------

    private void fetchRefillThreshold() {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) return;
        FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app").getReference("devices").child(selectedDeviceId).child("settings").child("refillStartLevel")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (snapshot.exists()) {
                        Double val = snapshot.getValue(Double.class);
                        if (val != null) refillStartLevel = val;
                    } else {
                        refillStartLevel = 25.0; // Fallback
                    }
                    tvRefillThreshold.setText("Refill threshold: " + refillStartLevel + "%");
                    loadWaterOutlook();
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    refillStartLevel = 25.0; // Fallback
                    tvRefillThreshold.setText("Refill threshold: " + refillStartLevel + "% (Offline Fallback)");
                    loadWaterOutlook();
                }
            });
    }

    private void loadData() {
        final long requestGeneration = ++reportRequestGeneration;
        FoggingReportFilter filter = buildCurrentFilter();
        if (filter == null) {
            currentFilter = null;
            currentSummary = null;
            currentTotals = null;
            processedSessions = new ArrayList<>();
            showFoggingEmptyState("SELECT A CYCLE", "Select a cycle to view a report.");
            return;
        }

        // Invalidate cached report/export state the moment a new load
        // begins, not only if it later fails - so a previous cycle/period's
        // data can never be presented or exported as though it belongs to
        // this new selection while the new query is still in flight. The
        // loading overlay already blocks interaction visually; this closes
        // the gap at the data layer too.
        currentFilter = null;
        currentSummary = null;
        currentTotals = null;
        processedSessions = new ArrayList<>();

        if (layoutLoading != null) {
            layoutLoading.setVisibility(View.VISIBLE);
            layoutLoading.bringToFront();
        }

        if (tvEffectiveRange != null) {
            tvEffectiveRange.setText("Showing " + DateUtils.formatDate(filter.effectiveStartMs)
                    + " – " + DateUtils.formatDate(filter.effectiveEndMs));
        }

        fetchFoggingLogs(filter, requestGeneration);
    }

    private void fetchFoggingLogs(FoggingReportFilter filter, long requestGeneration) {
        db.collection("devices")
                .document(selectedDeviceId)
                .collection("foggingLogs")
                .whereGreaterThanOrEqualTo("timestamp", filter.effectiveStartMs)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (!isAdded() || requestGeneration != reportRequestGeneration) return;
                    List<FoggingEvent> events = new ArrayList<>();
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        FoggingEvent event = doc.toObject(FoggingEvent.class);
                        if (event != null) {
                            event.id = doc.getId();
                            events.add(event);
                        }
                    }
                    fetchBoundaryEventAndProcess(filter, events, requestGeneration);
                })
                .addOnFailureListener(e -> {
                    if (!isAdded() || requestGeneration != reportRequestGeneration) return;
                    Log.e("FoggingReports", "Error loading logs", e);
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    showReportUnavailable();
                    NotificationHelper.showError(getContext(), "Unable to load the fogging report. Check your connection and try again.");
                });
    }

    // A session that was already running when the report window opened
    // (started before the window, ended inside it) would otherwise show up
    // as an orphan OFF with no matching ON and get discarded entirely,
    // undercounting runtime. Look back for the single most recent event
    // before the window so FoggingReportProcessor can reconstruct that
    // session and clip its counted duration to the window start.
    private void fetchBoundaryEventAndProcess(FoggingReportFilter filter, List<FoggingEvent> events, long requestGeneration) {
        db.collection("devices")
                .document(selectedDeviceId)
                .collection("foggingLogs")
                .whereLessThan("timestamp", filter.effectiveStartMs)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .addOnSuccessListener(boundarySnapshots -> {
                    if (!isAdded() || requestGeneration != reportRequestGeneration) return;
                    if (!boundarySnapshots.isEmpty()) {
                        DocumentSnapshot doc = boundarySnapshots.getDocuments().get(0);
                        FoggingEvent boundaryEvent = doc.toObject(FoggingEvent.class);
                        if (boundaryEvent != null && "ON".equalsIgnoreCase(boundaryEvent.event)) {
                            boundaryEvent.id = doc.getId();
                            events.add(boundaryEvent);
                        }
                    }
                    resolveRunningStateAndRender(filter, events, requestGeneration);
                })
                .addOnFailureListener(e -> {
                    if (!isAdded() || requestGeneration != reportRequestGeneration) return;
                    // Best-effort lookback only; proceed without it rather than
                    // failing the whole report over this secondary query.
                    Log.w("FoggingReports", "Unable to load boundary event for session clipping", e);
                    resolveRunningStateAndRender(filter, events, requestGeneration);
                });
    }

    // A session may only be presented as "Running now" when the device is
    // genuinely reachable right now. actuatorStatus/fogger/running is a
    // last-known value that stays true in RTDB after a device drops offline,
    // so on its own it cannot distinguish "fogging right now" from "was
    // fogging when it vanished". Presence is therefore checked first, using
    // the same rule the rest of the app uses, and the actuator flag is only
    // consulted once presence is confirmed.
    private void resolveRunningStateAndRender(FoggingReportFilter filter, List<FoggingEvent> events, long requestGeneration) {
        FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app")
            .getReference("devices").child(selectedDeviceId).child("status")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (!isAdded() || requestGeneration != reportRequestGeneration) return;

                    Boolean backendOnline = snapshot.child("online").getValue(Boolean.class);
                    Long lastServerSeen = snapshot.child("lastServerSeen").getValue(Long.class);
                    // Reuses DeviceConnectionManager's authoritative presence
                    // rule so Fogging Reports can never disagree with the rest
                    // of the app about whether the device is online.
                    boolean deviceLive = DeviceConnectionManager.resolveState(
                            backendOnline, lastServerSeen, System.currentTimeMillis())
                            == DeviceConnectivityState.ONLINE;

                    if (!deviceLive) {
                        // Stale/offline presence: whatever the actuator flag
                        // still says, there is no trustworthy live running
                        // session. The unmatched ON becomes an incomplete
                        // record inside the processor.
                        renderReport(filter, events, false);
                        return;
                    }
                    resolveActuatorRunningAndRender(filter, events, requestGeneration);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    // Presence could not be established, so it cannot be
                    // trusted. Fail closed rather than risk showing a stale
                    // session as running.
                    if (!isAdded() || requestGeneration != reportRequestGeneration) return;
                    Log.w("FoggingReports", "Unable to read device presence; not treating any session as running", error.toException());
                    renderReport(filter, events, false);
                }
            });
    }

    private void resolveActuatorRunningAndRender(FoggingReportFilter filter, List<FoggingEvent> events, long requestGeneration) {
        FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app")
            .getReference("devices").child(selectedDeviceId).child("actuatorStatus").child("fogger").child("running")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (!isAdded() || requestGeneration != reportRequestGeneration) return;
                    boolean isRunning = false;
                    if (snapshot.exists() && snapshot.getValue(Boolean.class) != null) {
                        isRunning = snapshot.getValue(Boolean.class);
                    }
                    renderReport(filter, events, isRunning);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    // Best-effort only; proceed with isRunning=false rather
                    // than failing the whole report over this RTDB read.
                    if (!isAdded() || requestGeneration != reportRequestGeneration) return;
                    renderReport(filter, events, false);
                }
            });
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void renderReport(FoggingReportFilter filter, List<FoggingEvent> events, boolean isRunning) {
        if (!isAdded()) return;

        long bucketSizeMs = "Today".equals(filter.periodLabel) ? (1000L * 60 * 60) : (1000L * 60 * 60 * 24);
        FoggingReportSummary summary = FoggingReportProcessor.process(events, filter.effectiveStartMs, filter.effectiveEndMs, bucketSizeMs, isRunning);

        currentFilter = filter;
        currentSummary = summary;
        processedSessions = summary.getCompletedSessions();

        int totalSessionCount = summary.getCompletedSessions().size() + (summary.getCurrentlyRunningSession() != null ? 1 : 0);
        int[] autoManual = countAutoManualSessions(summary);
        currentAutoCount = autoManual[0];
        currentManualCount = autoManual[1];

        // One frozen set of aggregate totals, taken straight from the
        // processed summary. Everything below renders from this object, and
        // the PDF export is handed this same object - so the exported
        // summary can never be computed a second, different way.
        currentTotals = FoggingReportTotals.from(summary, totalSessionCount,
                currentAutoCount, currentManualCount);

        String status = computeFoggingStatus(summary, filter, totalSessionCount);
        currentStatus = status;
        currentInterpretation = buildFoggingInterpretation(status, currentAutoCount, currentManualCount);

        if (totalSessionCount == 0) {
            // Water Outlook loads independently (see loadWaterOutlook()) and
            // is not re-derived from this historical summary, so an empty
            // result for the selected report period does not touch it.
            showFoggingEmptyState(status, currentInterpretation);
            return;
        }

        // Recent Activity - at most 10 entries total, running session first.
        List<FoggingSession> recentList = new ArrayList<>();
        if (summary.getCurrentlyRunningSession() != null) {
            recentList.add(summary.getCurrentlyRunningSession());
        }
        for (int i = summary.getCompletedSessions().size() - 1; i >= 0 && recentList.size() < 10; i--) {
            recentList.add(summary.getCompletedSessions().get(i));
        }
        if (recentList.size() > 10) {
            recentList = new ArrayList<>(recentList.subList(0, 10));
        }
        adapter.updateData(recentList);

        if (recentList.isEmpty()) {
            tvEmptyActivity.setVisibility(View.VISIBLE);
            rvRecentActivity.setVisibility(View.GONE);
        } else {
            tvEmptyActivity.setVisibility(View.GONE);
            rvRecentActivity.setVisibility(View.VISIBLE);
        }

        tvFoggingStatus.setText(status);
        applyStatusColor(status);
        // Hero evidence line: the session count, using the same already
        // computed total the metric strip shows.
        tvSessionBreakdown.setText(currentTotals.totalSessionCount == 1
                ? "1 session recorded" : currentTotals.totalSessionCount + " sessions recorded");
        if (heroControlBlock != null) heroControlBlock.setVisibility(View.VISIBLE);
        if (tvHeroAutoCount != null) tvHeroAutoCount.setText(String.valueOf(currentTotals.automaticSessionCount));
        if (tvHeroManualCount != null) tvHeroManualCount.setText(String.valueOf(currentTotals.manualSessionCount));
        tvFoggingInterpretation.setText(currentInterpretation);

        // Every figure below reads from currentTotals - the same object the
        // PDF export receives. Durations are formatted for readability only;
        // the stored millisecond values behind them are unchanged.
        tvTotalDuration.setText(DurationFormatter.formatRuntime(currentTotals.totalRuntimeMs));
        tvEventCount.setText(String.valueOf(currentTotals.totalSessionCount));
        tvAvgDuration.setText(DurationFormatter.formatSession(currentTotals.averageSessionDurationMs));
        tvBreakdownAuto.setText(DurationFormatter.formatRuntime(currentTotals.automaticRuntimeMs));
        tvBreakdownManual.setText(DurationFormatter.formatRuntime(currentTotals.manualRuntimeMs));
        renderStrategyBreakdown(currentTotals);

        // Bucket Generation - daily fogging runtime in minutes (hourly buckets
        // for "Today"). Bucket boundaries/values are unchanged from before;
        // only the X coordinate changed, from a bucket index to minutes
        // elapsed since the report's effective start - the same continuous-
        // time model Parameter Report uses - so the axis can adapt its label
        // precision to the currently visible span instead of being fixed to
        // the full-filter granularity.
        boolean hourlyBuckets = "Today".equals(filter.periodLabel);
        long bucketSpacingMs = hourlyBuckets ? (1000L * 60 * 60) : (1000L * 60 * 60 * 24);
        final long chartBaseMs = filter.effectiveStartMs;

        Map<Long, Long> buckets = summary.getBucketAggregations();
        List<Long> bucketKeys = new ArrayList<>(buckets.keySet());
        Collections.sort(bucketKeys);

        List<BarEntry> barEntries = new ArrayList<>();
        for (long bTime : bucketKeys) {
            float xMinutes = (bTime - chartBaseMs) / 60000f;
            float mins = buckets.get(bTime) / 60000f;
            barEntries.add(new BarEntry(xMinutes, mins));
        }

        BarDataSet ds = new BarDataSet(barEntries, "Fogging Runtime (mins)");
        ds.setColor(ContextCompat.getColor(requireContext(), R.color.primary));
        // Values appear on tap via the marker rather than being printed
        // permanently over every bar.
        ds.setDrawValues(false);
        ds.setHighlightEnabled(true);
        ds.setHighLightAlpha(60);

        BarData barData = new BarData(ds);
        // Bar width scales with the actual bucket spacing in the new
        // minutes-based X unit, keeping the same ~60% fill / 40% gap
        // proportion the old fixed 0.6f (out of 1 index-unit) gave.
        float bucketSpacingMinutes = bucketSpacingMs / 60000f;
        barData.setBarWidth(bucketSpacingMinutes * 0.6f);

        XAxis foggingXAxis = barChart.getXAxis();
        foggingBucketSpacingMinutes = bucketSpacingMinutes;

        // Label format/density adapts to the CURRENT VISIBLE range, not just
        // this full load - initialized here to the full effective range so
        // the full view is immediately correct, then kept in sync by
        // refreshFoggingAdaptiveXAxis() as the farmer zooms/pans. Buckets
        // themselves stay at their real hourly/daily resolution (see above);
        // only how the axis labels the currently visible span changes.
        // applyFoggingAdaptiveXAxis() floors granularity at the real bucket
        // spacing, so labels/gridlines never appear between actual bars.
        foggingXFormatter = new AdaptiveTimeAxisFormatter(chartBaseMs, TIMEZONE_ID);
        float fullSpanMinutes = (filter.effectiveEndMs - filter.effectiveStartMs) / 60000f;
        foggingXFormatter.updateVisibleRange(0f, fullSpanMinutes);
        applyFoggingAdaptiveXAxis(foggingXAxis);
        foggingXAxis.setValueFormatter(foggingXFormatter);

        // Marker derives its date/time straight from the tapped bar's X
        // value now, so it needs the same base timestamp and bucket
        // resolution just used to build the bars - no bucket-index lookup.
        FoggingChartMarkerView marker = new FoggingChartMarkerView(requireContext(), chartBaseMs, hourlyBuckets, TIMEZONE_ID);
        marker.setChartView(barChart);
        barChart.setMarker(marker);

        barChart.setData(barData);
        barChart.highlightValue(null);
        // A new filter/cycle selection always starts at its own full range -
        // any zoom left over from a previous selection's viewport must not
        // carry over onto this one.
        barChart.fitScreen();
        barChart.invalidate();

        // Water Outlook is loaded independently (see loadWaterOutlook()) and
        // does not wait on or derive from this historical report render.
        if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
    }

    private void showFoggingEmptyState(String status, String message) {
        currentStatus = status;
        currentInterpretation = message;
        tvFoggingStatus.setText(status);
        applyStatusColor(status);
        tvSessionBreakdown.setText("");
        tvFoggingInterpretation.setText(message);
        if (heroControlBlock != null) heroControlBlock.setVisibility(View.GONE);

        tvTotalDuration.setText("--");
        tvEventCount.setText("--");
        tvAvgDuration.setText("--");
        tvBreakdownAuto.setText("--");
        tvBreakdownManual.setText("--");
        if (strategyRows != null) strategyRows.removeAllViews();
        if (strategySection != null) strategySection.setVisibility(View.GONE);

        barChart.clear();
        barChart.invalidate();

        adapter.updateData(new ArrayList<>());
        tvEmptyActivity.setVisibility(View.VISIBLE);
        rvRecentActivity.setVisibility(View.GONE);

        if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
    }

    // Distinct from showFoggingEmptyState's "NO FOGGING RECORDS" case: that
    // means the query succeeded and genuinely found nothing, so currentFilter
    // /currentSummary stay valid (Export correctly reports "no data" for
    // that exact selection). This means the query itself failed, so there is
    // no trustworthy report for the current selection at all - the cached
    // fields from whatever cycle/period was previously loaded must be
    // cleared, or a stale report could keep being displayed/exported as if
    // it belonged to the new selection.
    private void showReportUnavailable() {
        currentFilter = null;
        currentSummary = null;
        currentTotals = null;
        processedSessions = new ArrayList<>();
        currentAutoCount = 0;
        currentManualCount = 0;
        // Wording comes from the same builder that feeds the screen and the
        // PDF, so this failure message can't drift from the rest.
        showFoggingEmptyState("REPORT UNAVAILABLE",
                buildFoggingInterpretation("REPORT UNAVAILABLE", 0, 0));
    }

    private void applyStatusColor(String status) {
        int color;
        int containerBg;
        switch (status) {
            // A failed load is a distinct, more urgent condition than
            // "queried successfully and found nothing" - never conflate the
            // two, including in color.
            case "REPORT UNAVAILABLE":
                color = android.R.color.holo_red_dark;
                containerBg = R.drawable.ds_status_pill_critical;
                break;
            case "CHECK FOGGING ACTIVITY":
                color = android.R.color.holo_orange_dark;
                containerBg = R.drawable.ds_status_pill_warning;
                break;
            case "NO FOGGING RECORDS":
            case "INSUFFICIENT DATA":
            case "SELECT A CYCLE":
                color = android.R.color.darker_gray;
                containerBg = R.drawable.ds_status_pill_neutral;
                break;
            default:
                color = android.R.color.holo_green_dark;
                containerBg = R.drawable.ds_status_pill_success;
                break;
        }
        int resolvedColor = ContextCompat.getColor(tvFoggingStatus.getContext(), color);
        tvFoggingStatus.setTextColor(resolvedColor);
        // Compact status pill plus dot/accent edge - all driven by the same
        // status string already selected above, purely visual.
        tvFoggingStatus.setBackgroundResource(containerBg);
        if (dotFoggingStatus != null) {
            dotFoggingStatus.setBackgroundTintList(android.content.res.ColorStateList.valueOf(resolvedColor));
        }
        if (heroAccentEdge != null) {
            heroAccentEdge.setBackgroundTintList(android.content.res.ColorStateList.valueOf(resolvedColor));
        }
    }

    // ------------------------------------------------------------------
    // Farmer-readable status & interpretation (Part 7-8)
    // ------------------------------------------------------------------

    private int[] countAutoManualSessions(FoggingReportSummary summary) {
        int auto = 0;
        int manual = 0;
        for (FoggingSession s : summary.getCompletedSessions()) {
            if (s.isManual()) manual++; else auto++;
        }
        FoggingSession running = summary.getCurrentlyRunningSession();
        if (running != null) {
            if (running.isManual()) manual++; else auto++;
        }
        return new int[]{auto, manual};
    }

    // Status describes RECORDED OPERATION only - never plant health, and
    // never a specific number of sessions "required", since the history
    // does not contain a defined expected schedule to compare against.
    private String computeFoggingStatus(FoggingReportSummary summary, FoggingReportFilter filter, int totalSessionCount) {
        if (totalSessionCount == 0) return "NO FOGGING RECORDS";
        if (filter.effectiveEndMs <= filter.effectiveStartMs) return "INSUFFICIENT DATA";
        if (isSameManilaDay(filter.effectiveStartMs, filter.effectiveEndMs)) return "NORMAL ACTIVITY";

        long now = System.currentTimeMillis();
        int elapsedDayBuckets = 0;
        int daysWithActivity = 0;
        for (Map.Entry<Long, Long> e : summary.getBucketAggregations().entrySet()) {
            if (e.getKey() > now) continue;
            elapsedDayBuckets++;
            if (e.getValue() != null && e.getValue() > 0) daysWithActivity++;
        }
        if (elapsedDayBuckets == 0) return "INSUFFICIENT DATA";
        double ratio = (double) daysWithActivity / elapsedDayBuckets;
        return ratio >= MIN_CONSISTENT_ACTIVITY_DAY_RATIO ? "NORMAL ACTIVITY" : "CHECK FOGGING ACTIVITY";
    }

    // Plain-language interpretation shown on screen under "What This Means"
    // and printed verbatim as the PDF's interpretation section - both read
    // this one method, so the two can never drift apart.
    //
    // Same shape used for Parameter Reports: what happened, why it matters
    // to the basil, and a soft warning if it continues. Deliberately avoids
    // internal vocabulary (activity-day ratio, observed window, session
    // reconstruction, persisted strategy, source classification) and never
    // claims anything about the plants themselves - only about what the
    // system recorded and what that may practically mean.
    //
    // The auto/manual and status branches below are unchanged in criteria;
    // only the sentences they return are rewritten.
    private String buildFoggingInterpretation(String status, int autoCount, int manualCount) {
        switch (status) {
            case "NO FOGGING RECORDS":
                return "No fogging sessions were recorded for this growth cycle in the selected period. This is not necessarily a problem - fogging does not run while a cycle is paused, and it pauses when the reservoir is low or the system is holding for safety.";
            case "REPORT UNAVAILABLE":
                return "Fogging records could not be loaded. Check your connection and try again.";
            case "CHECK FOGGING ACTIVITY":
                return "Fogging was not recorded as regularly during parts of this period. If this continues, the basil roots may not receive mist as consistently as intended.";
            case "NORMAL ACTIVITY":
                break;
            default:
                return "There is not enough fogging history yet to describe the system's recent activity.";
        }

        int total = autoCount + manualCount;
        if (total == 0) {
            return "Fogging was recorded regularly during this period, helping the system keep delivering nutrient mist to the basil roots.";
        }

        double manualShare = (double) manualCount / total;
        if (manualShare > MANUAL_HEAVY_SHARE_THRESHOLD) {
            return "Fogging was recorded during this period, but several sessions were started manually. If this keeps happening, check whether the automatic fogging system needs attention.";
        }
        return "Fogging was recorded regularly during this period, and most sessions were handled automatically by Basilience. This helps keep mist delivery consistent without needing frequent manual control.";
    }

    // Renders the persisted per-strategy runtime as scannable label/value
    // rows instead of one run-on "Startup 332m · Normal 878m ..." line.
    // Only strategies actually present in the processed data are shown -
    // nothing is inferred or guessed when strategy data is missing.
    private void renderStrategyBreakdown(FoggingReportTotals totals) {
        if (strategyRows == null || strategySection == null) return;
        strategyRows.removeAllViews();

        Map<String, Long> strategyDurations = totals.strategyRuntimeMs;
        String[] order = {"normal", "startup", "hot", "cold"};
        String[] labels = {"Normal", "Startup", "Hot", "Cold"};

        boolean any = false;
        for (int i = 0; i < order.length; i++) {
            Long durationMs = strategyDurations.get(order[i]);
            if (durationMs == null || durationMs <= 0) continue;
            addStrategyRow(labels[i], DurationFormatter.formatRuntime(durationMs), any);
            any = true;
        }

        strategySection.setVisibility(any ? View.VISIBLE : View.GONE);
        if (tvBreakdownAutoDetails != null) {
            tvBreakdownAutoDetails.setVisibility(View.GONE);
        }
    }

    private void addStrategyRow(String label, String value, boolean withTopMargin) {
        Context context = getContext();
        if (context == null || strategyRows == null) return;

        android.widget.LinearLayout row = new android.widget.LinearLayout(context);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams rowParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        if (withTopMargin) {
            rowParams.topMargin = Math.round(6 * getResources().getDisplayMetrics().density);
        }
        row.setLayoutParams(rowParams);

        TextView tvLabel = new TextView(context);
        tvLabel.setText(label);
        tvLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        tvLabel.setTextColor(ContextCompat.getColor(context, R.color.nav_inactive));
        tvLabel.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvValue = new TextView(context);
        tvValue.setText(value);
        tvValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        tvValue.setTextColor(ContextCompat.getColor(context, R.color.text_dark));
        tvValue.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));

        row.addView(tvLabel);
        row.addView(tvValue);
        strategyRows.addView(row);
    }

    // ------------------------------------------------------------------
    // Water Outlook - deliberately independent of the historical report
    // filter/cycle above. Loaded once per screen visit (from onCreateView,
    // via fetchRefillThreshold()'s callbacks) using a fixed rolling window,
    // never re-triggered by switching Entire Cycle/Today/7/30/Custom or by
    // switching cultivation cycles.
    // ------------------------------------------------------------------

    private void loadWaterOutlook() {
        if (!isAdded() || selectedDeviceId == null || selectedDeviceId.isEmpty()) return;
        final long requestGeneration = ++waterOutlookRequestGeneration;

        long nowMs = System.currentTimeMillis();
        long lookbackStartMs = nowMs - WATER_OUTLOOK_LOOKBACK_DAYS * 24L * 60 * 60 * 1000;

        db.collection("devices")
                .document(selectedDeviceId)
                .collection("foggingLogs")
                .whereGreaterThanOrEqualTo("timestamp", lookbackStartMs)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (!isAdded() || requestGeneration != waterOutlookRequestGeneration) return;
                    List<FoggingEvent> events = new ArrayList<>();
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        FoggingEvent event = doc.toObject(FoggingEvent.class);
                        if (event != null) {
                            event.id = doc.getId();
                            events.add(event);
                        }
                    }
                    fetchWaterOutlookBoundaryEvent(requestGeneration, events, lookbackStartMs, nowMs);
                })
                .addOnFailureListener(e -> {
                    if (!isAdded() || requestGeneration != waterOutlookRequestGeneration) return;
                    Log.e("FoggingReports", "Failed to load recent fogging activity for Water Outlook", e);
                    showWaterOutlookUnavailable();
                });
    }

    // Same boundary-lookback technique as the historical report: a session
    // that started before the fixed lookback window and is still running (or
    // ended) inside it must not be discarded as an orphan OFF.
    private void fetchWaterOutlookBoundaryEvent(long requestGeneration, List<FoggingEvent> events, long lookbackStartMs, long nowMs) {
        db.collection("devices")
                .document(selectedDeviceId)
                .collection("foggingLogs")
                .whereLessThan("timestamp", lookbackStartMs)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .addOnSuccessListener(boundarySnapshots -> {
                    if (!isAdded() || requestGeneration != waterOutlookRequestGeneration) return;
                    if (!boundarySnapshots.isEmpty()) {
                        DocumentSnapshot doc = boundarySnapshots.getDocuments().get(0);
                        FoggingEvent boundaryEvent = doc.toObject(FoggingEvent.class);
                        if (boundaryEvent != null && "ON".equalsIgnoreCase(boundaryEvent.event)) {
                            boundaryEvent.id = doc.getId();
                            events.add(boundaryEvent);
                        }
                    }
                    resolveRunningStateForWaterOutlook(requestGeneration, events, lookbackStartMs, nowMs);
                })
                .addOnFailureListener(e -> {
                    if (!isAdded() || requestGeneration != waterOutlookRequestGeneration) return;
                    resolveRunningStateForWaterOutlook(requestGeneration, events, lookbackStartMs, nowMs);
                });
    }

    // Same live-presence gate the historical report uses, applied to Water
    // Outlook's own independent running-state resolution.
    //
    // actuatorStatus/fogger/running is a last-known value that stays true in
    // RTDB after a device drops offline. Trusting it alone let a trailing
    // unmatched ON be treated as live fogging, which added up to
    // MAX_PLAUSIBLE_SESSION_DURATION_MS of phantom runtime into the 7-day
    // usage total - inflating estimated daily water use and understating the
    // time left before a refill. Presence is therefore confirmed first, and
    // the actuator flag is only read once the device is genuinely reachable.
    //
    // This gates ONLY whether a trailing open session counts as live. The
    // lookback window, denominator, and every step of the estimate below are
    // untouched.
    private void resolveRunningStateForWaterOutlook(long requestGeneration, List<FoggingEvent> events, long lookbackStartMs, long nowMs) {
        FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app")
                .getReference("devices").child(selectedDeviceId).child("status")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (!isAdded() || requestGeneration != waterOutlookRequestGeneration) return;

                        Boolean backendOnline = snapshot.child("online").getValue(Boolean.class);
                        Long lastServerSeen = snapshot.child("lastServerSeen").getValue(Long.class);
                        // Reuses the project's single authoritative presence
                        // rule - no second freshness threshold is defined.
                        boolean deviceLive = DeviceConnectionManager.resolveState(
                                backendOnline, lastServerSeen, System.currentTimeMillis())
                                == DeviceConnectivityState.ONLINE;

                        if (!deviceLive) {
                            computeWaterOutlookUsage(requestGeneration, events, lookbackStartMs, nowMs, false);
                            return;
                        }
                        resolveActuatorRunningForWaterOutlook(requestGeneration, events, lookbackStartMs, nowMs);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        // Fail closed: presence could not be established, so
                        // no session may be treated as currently running.
                        if (!isAdded() || requestGeneration != waterOutlookRequestGeneration) return;
                        Log.w("FoggingReports", "Unable to read device presence for Water Outlook; not treating any session as running", error.toException());
                        computeWaterOutlookUsage(requestGeneration, events, lookbackStartMs, nowMs, false);
                    }
                });
    }

    private void resolveActuatorRunningForWaterOutlook(long requestGeneration, List<FoggingEvent> events, long lookbackStartMs, long nowMs) {
        FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app")
                .getReference("devices").child(selectedDeviceId).child("actuatorStatus").child("fogger").child("running")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (!isAdded() || requestGeneration != waterOutlookRequestGeneration) return;
                        boolean isRunning = snapshot.exists() && Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                        computeWaterOutlookUsage(requestGeneration, events, lookbackStartMs, nowMs, isRunning);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        if (!isAdded() || requestGeneration != waterOutlookRequestGeneration) return;
                        computeWaterOutlookUsage(requestGeneration, events, lookbackStartMs, nowMs, false);
                    }
                });
    }

    private void computeWaterOutlookUsage(long requestGeneration, List<FoggingEvent> events, long lookbackStartMs, long nowMs, boolean isRunning) {
        // Bucket size is irrelevant here (Water Outlook renders no chart);
        // FoggingReportProcessor is reused purely for its session
        // reconstruction, boundary-clipping and anomaly exclusion - never
        // for its getObservedDays(), which anchors to the earliest event
        // found inside the window and so is unsuitable here (see
        // observationDays below).
        FoggingReportSummary summary = FoggingReportProcessor.process(events, lookbackStartMs, nowMs, 24L * 60 * 60 * 1000, isRunning);
        long recentTotalFoggingDurationMs = summary.getTotalDurationMs();

        // observationDays = the full fixed lookback window (7 days), not the
        // elapsed time since the earliest fogging event found inside it. A
        // device that fogged nothing for the first 5 of 7 days and then ran
        // 4 hours on days 6-7 has 7 real days of observation, not 2 - those
        // 5 zero-activity days are genuine data, not missing data, so
        // excluding them would inflate the estimated daily consumption.
        //
        // There is no authoritative "device/history start" timestamp
        // anywhere in this app's schema (checked: no createdAt/claimedAt on
        // the device document, no persisted first-ever-record marker), and
        // this task explicitly does not add one. Inferring device age from
        // "earliest fogging event in the window" would just reintroduce the
        // same bug under a different name, since a device that simply
        // hasn't fogged yet today looks identical to a device that didn't
        // exist yet. So every device is treated as established and the
        // denominator is always the full window; a device with truly no
        // recent fogging evidence falls through to "Insufficient recent
        // usage data" below via recentTotalFoggingDurationMs == 0, rather
        // than via a guessed-at age.
        int observationDays = WATER_OUTLOOK_LOOKBACK_DAYS;

        fetchCurrentWaterLevelForOutlook(requestGeneration, recentTotalFoggingDurationMs, observationDays);
    }

    // Kept independent of the historical report filter - always the single
    // latest parameterLogs reading for this device, exactly as before.
    private void fetchCurrentWaterLevelForOutlook(long requestGeneration, long recentTotalFoggingDurationMs, int observationDays) {
        db.collection("devices").document(selectedDeviceId)
                .collection("parameterLogs")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .addOnSuccessListener(snapshots -> {
                    if (!isAdded() || requestGeneration != waterOutlookRequestGeneration) return;
                    Double waterLevelPct = null;
                    Long latestTimestampMs = null;
                    if (!snapshots.isEmpty()) {
                        DocumentSnapshot latest = snapshots.getDocuments().get(0);
                        waterLevelPct = latest.getDouble("water_level");
                        if (waterLevelPct == null) {
                            waterLevelPct = latest.getDouble("waterLevel");
                        }
                        latestTimestampMs = latest.getLong("timestamp");
                    }
                    renderWaterOutlook(waterLevelPct, latestTimestampMs, recentTotalFoggingDurationMs, observationDays);
                })
                .addOnFailureListener(e -> {
                    if (!isAdded() || requestGeneration != waterOutlookRequestGeneration) return;
                    Log.e("FoggingReports", "Failed to load current water level for Water Outlook", e);
                    renderWaterOutlook(null, null, recentTotalFoggingDurationMs, observationDays);
                });
    }

    private boolean isValidWaterLevelReading(Double waterLevelPct) {
        if (waterLevelPct == null) return false;
        if (waterLevelPct.isNaN() || waterLevelPct.isInfinite()) return false;
        return waterLevelPct >= 0 && waterLevelPct <= 100;
    }

    private void renderWaterOutlook(Double waterLevelPct, Long latestTimestampMs, long recentTotalFoggingDurationMs, int observationDays) {
        if (!isAdded()) return;

        // Missing, non-finite, or out-of-physical-range readings are never
        // fabricated into a display value or a prediction.
        if (!isValidWaterLevelReading(waterLevelPct)) {
            showWaterOutlookMessageState("Water level unavailable",
                    "A current refill estimate is not available.");
            return;
        }

        // A valid-looking value whose recency can't be established (missing
        // timestamp, corrupt future timestamp) or that is simply too old is
        // shown distinctly from "unavailable": the number exists, it just
        // can't be trusted as the CURRENT reservoir state.
        long nowMs = System.currentTimeMillis();
        boolean freshnessKnown = latestTimestampMs != null && latestTimestampMs <= nowMs;
        boolean fresh = freshnessKnown && (nowMs - latestTimestampMs) <= WATER_OUTLOOK_FRESHNESS_THRESHOLD_MS;
        if (!fresh) {
            showWaterOutlookMessageState("Water-level data is outdated",
                    "A current refill estimate is not available.");
            return;
        }

        // Valid and fresh: show the reservoir value, and decide below
        // whether a refill estimate can honestly be offered alongside it.
        showWaterOutlookValueState();
        tvWaterLevel.setText(String.format(Locale.getDefault(), "%.0f%%", waterLevelPct));

        if (observationDays <= 0) observationDays = 1;
        float hoursFogged = recentTotalFoggingDurationMs / (1000f * 60f * 60f);
        float avgHoursPerDay = hoursFogged / observationDays;
        float estimatedDailyConsumptionLiters = avgHoursPerDay * WATER_OUTLOOK_CONSUMPTION_RATE_L_PER_HOUR;

        float currentLiters = WATER_OUTLOOK_TANK_CAPACITY_LITERS * (waterLevelPct.floatValue() / 100f);
        float thresholdLiters = WATER_OUTLOOK_TANK_CAPACITY_LITERS * ((float) refillStartLevel / 100f);
        float consumableLiters = currentLiters - thresholdLiters;

        if (consumableLiters <= 0) {
            tvRefillTime.setText("Refill overdue");
            tvRefillTime.setTextColor(ContextCompat.getColor(tvRefillTime.getContext(), R.color.alert_orange));
        } else if (estimatedDailyConsumptionLiters > 0) {
            float daysRemaining = consumableLiters / estimatedDailyConsumptionLiters;
            tvRefillTime.setTextColor(ContextCompat.getColor(tvRefillTime.getContext(), R.color.text_dark));
            if (daysRemaining >= 1.0f) {
                tvRefillTime.setText(String.format(Locale.getDefault(), "~%.1f days", daysRemaining));
            } else {
                float hours = daysRemaining * 24f;
                tvRefillTime.setText(String.format(Locale.getDefault(), "~%.1f hours", hours));
            }
        } else {
            // No usable recent fogging duration in the fixed lookback window -
            // never show 0 days / an infinite estimate / NaN. The reservoir
            // reading above is still valid, so it stays; only the estimate
            // column is replaced by an explanation.
            if (refillColumn != null) refillColumn.setVisibility(View.GONE);
            if (tvWaterOutlookMessage != null) {
                tvWaterOutlookMessage.setVisibility(View.VISIBLE);
                tvWaterOutlookMessage.setText("Not enough recent fogging activity to estimate refill time.");
            }
        }
    }

    // Reservoir value and refill estimate both shown.
    private void showWaterOutlookValueState() {
        if (waterOutlookValues != null) waterOutlookValues.setVisibility(View.VISIBLE);
        if (waterLevelColumn != null) waterLevelColumn.setVisibility(View.VISIBLE);
        if (refillColumn != null) refillColumn.setVisibility(View.VISIBLE);
        if (tvWaterOutlookMessage != null) tvWaterOutlookMessage.setVisibility(View.GONE);
    }

    // No trustworthy current reading: show a short explanation instead of a
    // large numeric value that would imply a live measurement exists.
    private void showWaterOutlookMessageState(String headline, String detail) {
        if (waterOutlookValues != null) waterOutlookValues.setVisibility(View.GONE);
        if (tvWaterOutlookMessage != null) {
            tvWaterOutlookMessage.setVisibility(View.VISIBLE);
            tvWaterOutlookMessage.setText(headline + "\n" + detail);
        }
    }

    private void showWaterOutlookUnavailable() {
        if (!isAdded()) return;
        showWaterOutlookMessageState("Water level unavailable",
                "A current refill estimate is not available.");
    }

    // ------------------------------------------------------------------
    // Export - reuses the exact frozen filter/summary/sessions/chart that
    // produced the visible report; never re-queries a different range.
    // ------------------------------------------------------------------

    private void exportPdf() {
        if (!isAdded() || getContext() == null) return;
        // Both currentFilter and currentSummary are cleared together
        // whenever there's no trustworthy currently-loaded report (see
        // loadData()/showReportUnavailable()), so either being null means
        // export must be blocked rather than reusing stale data.
        if (currentFilter == null || currentSummary == null || currentTotals == null) {
            Toast.makeText(getContext(), "Load a fogging report before exporting.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (processedSessions == null || processedSessions.isEmpty()) {
            Toast.makeText(getContext(), "No fogging data available to export.", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(getContext(), "Generating PDF...", Toast.LENGTH_SHORT).show();

        final FoggingReportFilter filter = currentFilter;
        final List<FoggingSession> sessions = new ArrayList<>(processedSessions);
        final String status = currentStatus;
        final String interpretation = currentInterpretation;
        // The exact totals object the visible report was rendered from. It is
        // immutable plain data, so handing it to the background thread is
        // safe and it cannot drift from what is on screen.
        final FoggingReportTotals totals = currentTotals;
        // getChartBitmap() captures the chart exactly as drawn, so a marker
        // left over from a tap would otherwise be baked into the PDF. The
        // static export should show clean bars and no value labels.
        //
        // The export must always reflect the full selected report range, not
        // whatever the farmer happens to be zoomed into on screen - so both
        // the viewport and the adaptive label spacing are reset to the full
        // range before capture and restored afterward (by re-deriving from
        // the restored viewport's own visible range, rather than caching a
        // separate saved value that could drift out of sync), without
        // requerying or altering any data.
        barChart.highlightValue(null);
        android.graphics.Matrix savedMatrix = new android.graphics.Matrix(barChart.getViewPortHandler().getMatrixTouch());
        if (foggingXFormatter != null) {
            float fullSpanMinutes = (filter.effectiveEndMs - filter.effectiveStartMs) / 60000f;
            foggingXFormatter.updateVisibleRange(0f, fullSpanMinutes);
            applyFoggingAdaptiveXAxis(barChart.getXAxis());
        }
        barChart.fitScreen();
        final Bitmap chartBitmap = barChart.getChartBitmap();
        barChart.getViewPortHandler().refresh(savedMatrix, barChart, true);
        if (foggingXFormatter != null) {
            foggingXFormatter.updateVisibleRange(barChart.getLowestVisibleX(), barChart.getHighestVisibleX());
            applyFoggingAdaptiveXAxis(barChart.getXAxis());
        }
        barChart.invalidate();
        // Snapshot everything Fragment-bound on the UI thread before
        // starting background work, so the worker thread never calls
        // getContext()/requireContext() itself and can't be affected by the
        // Fragment detaching mid-export.
        final Context appContext = requireContext().getApplicationContext();
        final String packageName = requireContext().getPackageName();
        final String userName = requireContext()
                .getSharedPreferences("basilience_prefs", Context.MODE_PRIVATE)
                .getString("user_name", "User");
        final androidx.fragment.app.FragmentActivity hostActivity = requireActivity();

        new Thread(() -> {
            File pdfFile = null;
            Exception failure = null;
            try {
                CycleReportGenerator generator = new CycleReportGenerator(appContext);
                pdfFile = generator.generateFoggingReportPdf(filter, chartBitmap, sessions, status,
                        totals, interpretation, userName);
            } catch (IOException e) {
                failure = e;
            }

            final File result = pdfFile;
            final Exception error = failure;
            hostActivity.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (error != null || result == null) {
                    Log.e("FoggingReports", "Error generating PDF", error);
                    NotificationHelper.showError(getContext(), "We couldn't generate the PDF report. Please try again.");
                    return;
                }
                try {
                    Uri contentUri = FileProvider.getUriForFile(appContext, packageName + ".fileprovider", result);
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(contentUri, "application/pdf");
                    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent, "Open PDF Report"));
                } catch (Exception e) {
                    Log.e("FoggingReports", "Error opening PDF", e);
                    NotificationHelper.showError(getContext(), "We couldn't open the PDF report.");
                }
            });
        }).start();
    }

    private void showInfoDialog() {
        if (getContext() == null) return;
        // Same scrollable, left-aligned guide used by Parameter Reports.
        String[][] sections = {
                {"What does this page do?",
                        "This page shows how the fogging system worked during a cultivation cycle - how often mist was delivered, how long it ran, and whether it was started automatically or by hand."},
                {"Why is this useful?",
                        "It helps you confirm the basil roots were getting mist regularly, spot days with little or no fogging, and see roughly when the water tank will need a refill."},
                {"What the numbers mean",
                        "• Fogging Sessions - how many separate times the fogger ran. One session is one continuous run, from the moment the fogger switches on until it switches off.\n"
                                + "• Total Fogging Time - all of those sessions added together.\n"
                                + "• Average Session - the total time divided by the number of sessions.\n"
                                + "• Automatic - fogging the system started on its own.\n"
                                + "• Manual - fogging someone started by hand.\n"
                                + "• Fogging Strategy - the different fogging patterns the system used, such as Normal or Startup, and how long each one ran."},
                {"Fogging Time Per Day",
                        "Each bar is the total fogging time recorded in one day - or in one hour when you pick Today. A bar of zero means no fogging was recorded then. Tap any bar to see its exact total."},
                {"Water Outlook",
                        "A rough estimate of how much water is left and roughly how long it may last, worked out from the last 7 days of fogging and a typical tank size and fogger usage rate. Basilience does not measure water use directly, so treat this as a guide rather than a reading. It is separate from the period you picked above, so it does not change when you switch cycles or periods."},
                {"Recent Fogging Activity",
                        "The most recent fogging sessions, newest first. A session still in progress shows as running now. If the system restarted in the middle of a session, its length cannot be trusted and it is marked as an incomplete record."},
                {"What this report cannot tell you",
                        "\u2022 It records equipment activity, not how much water the plants took up. The Water Outlook figures are a rough estimate from typical use, not a measurement.\n"
                                + "\u2022 Fogging that happens while the device is offline is not added to this history afterwards, so a long connection outage can leave a gap.\n"
                                + "\u2022 A session that was interrupted by a restart is shown as an incomplete record and is left out of the totals."},
                {"How to use it",
                        "• Pick a cultivation cycle from the dropdown - both in-progress and finished cycles are available.\n"
                                + "• Use the period buttons (Entire, Today, 7D, 30D, Custom) to narrow the range - it always stays inside the selected cycle's dates.\n"
                                + "• Read 'What This Means' for a plain-language summary.\n"
                                + "• Use the export button at the top to save the report as a PDF."}
        };
        NotificationHelper.showGuideDialog(requireContext(), "How to use Fogging Reports",
                sections, "Got it");
    }
}
