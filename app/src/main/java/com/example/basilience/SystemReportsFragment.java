package com.example.basilience;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
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
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.util.Pair;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.basilience.models.ParameterExportBundle;
import com.example.basilience.models.ParameterTableRow;
import com.example.basilience.models.ParameterReportFilter;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.components.LegendEntry;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Calendar;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;

public class SystemReportsFragment extends Fragment {

    private static final String TIMEZONE_ID = "Asia/Manila";
    // Simple, documented cutoff - not a scoring algorithm: a parameter is
    // considered STABLE when at least this share of readings fell inside the
    // configured target range/threshold for the selected period.
    private static final double STABLE_WITHIN_TARGET_RATIO = 0.80;
    // Above this many raw readings, the plotted line is downsampled (see
    // ChartRangeSegmenter.downsample()) so MPAndroidChart stays fast to pan/
    // zoom/tap on a long, frequently-logged report. Stats below (average/
    // high/low/insight) and currentReadings (used by CSV/PDF export) always
    // keep using every raw reading, never this thinned copy.
    private static final int CHART_DOWNSAMPLE_THRESHOLD = 1500;
    private static final int CHART_DOWNSAMPLE_TARGET_POINTS = 750;

    // Canonical parameter keys, in the order every export (parameter
    // selection dialog, XLSX sheet order, PDF section order) presents them.
    private static final String[] EXPORTABLE_PARAMETERS = {
            "pH", "EC", "Air Temperature", "Humidity", "Water Temperature", "Water Level"
    };

    private Spinner spinnerCycle;
    private Spinner spinnerParameter;
    private ThresholdBandLineChart lineChart;
    private TextView tvAverage, tvHigh, tvLow;
    private Database_Helper dbHelper;

    private MaterialButton btnEntireCycle, btnToday, btnWeek, btnMonth, btnCustom;
    // UI-visual only: btnShare is now a compact icon-only MaterialButton in
    // the page header (was a full-width bottom button); only View-level
    // setOnClickListener()/setVisibility() are ever called on it, so this
    // layout change carries no behavior difference.
    private MaterialButton btnShare;
    private String currentSelectedFilter = "Entire Cycle";
    private TextView tvInterpretation, tvInsightStatus, tvTargetRange, tvEvidence, tvEffectiveRange;
    private TextView tvChartTitle;
    private View dotInsightStatus, heroAccentEdge, cardInsightHero;
    private View reportContentContainer, noCyclesEmptyState;
    private TextView tvNoCyclesEmptyState;
    private View periodSelectorRow;
    private RecyclerView recyclerReadingsTable;
    private TextView tvReadingsTableEmpty;
    private final List<ParameterTableRow> readingsTableRows = new ArrayList<>();
    private ParameterTableAdapter readingsTableAdapter;
    // Numeric (not display-string) per-parameter raw samples, rebuilt from
    // the same document snapshot populateReadingsTable() already iterates -
    // export's source of truth for stats/charts/Raw Data, independent of
    // which single parameter is currently charted above.
    private final Map<String, List<ChartAggregation.Sample>> currentParameterSamples = new HashMap<>();
    private CoachMarkTour coachMarkTour;
    private String selectedDeviceId;
    private String userRole = RoleConstants.ROLE_FARMER;
    private long reportRequestGeneration = 0L;
    private long layoutLoadingShownAt;
    // One settings mapping, read once in loadDeviceThresholds() from the
    // device's actual RTDB settings node, and used consistently everywhere
    // a threshold is needed: report compliance (isWithinTarget), the
    // farmer-facing label (getTargetRangeText), the plain-language summary
    // (buildInterpretation), and the PDF export (which reuses the same
    // computed ParameterInsight, never its own copy).
    private Double minPhThreshold;
    private Double maxPhThreshold;
    private Double phTargetMinThreshold;
    private Double phTargetMaxThreshold;

    private Double minEcThreshold;
    private Double maxEcThreshold;
    private Double ecTargetMinThreshold;
    private Double ecTargetMaxThreshold;

    // Canonical target (acceptable) ranges - the same settings the device uses
    // to classify a reading. Distinct from the control/hysteresis values below.
    private Double minAirTempTarget;
    private Double maxAirTempTarget;
    private Double minHumidityTarget;
    private Double maxHumidityTarget;
    private Double minWaterTempTarget;
    private Double maxWaterTempTarget;
    private Double minWaterLevelTarget;
    private Double maxWaterLevelTarget;

    private Double highAirTempThreshold;
    private Double airTempReleaseThreshold;

    private Double highHumidityThreshold;
    private Double humidityReleaseThreshold;

    private Double highWaterTempThreshold;
    private Double coolerOffTempThreshold;
    // Must match firmware Config.h WATER_COOLING_HYSTERESIS. Firmware derives
    // both effective cooling thresholds from maxWaterTemp on every control tick.
    private static final double WATER_COOLING_HYSTERESIS_C = 2.5;

    // Water-depth model (centimeters) - see firmware Config.h's "Water
    // Reservoir Geometry". AUTHORITATIVE refill control thresholds; the
    // legacy refillStartLevel/refillStopLevel percentage fields are no
    // longer read by any firmware control path.
    private Double refillStartThresholdCm;
    private Double refillStopThresholdCm;

    private final List<Cycle> cycles = new ArrayList<>();
    private Cycle selectedCycle;
    private ListenerRegistration cyclesListener;

    private Long customStartMs;
    private Long customEndMs;

    // The single authoritative state for whatever report is currently on
    // screen, frozen at the moment its data finished (or failed to) load.
    // Graph, statistics, farmer summary, PDF export and CSV export all read
    // from this same object/cached readings instead of each recomputing
    // their own range, so an export can never describe a different data
    // subset than what the farmer is looking at.
    private ParameterReportFilter currentFilter;
    private List<ParameterReading> currentReadings = new ArrayList<>();
    private float currentAvg, currentHigh, currentLow;
    private ParameterInsight currentInsight;

    // The X-axis label strategy for the trend chart currently on screen -
    // rebuilt each time renderReport() loads new data, and mutated in place
    // by the chart gesture listener as the farmer zooms/pans, so labels
    // always describe the currently visible span rather than the original
    // full filter range.
    private AdaptiveTimeAxisFormatter adaptiveXFormatter;

    public SystemReportsFragment() { }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.reports_system, container, false);

        dbHelper = new Database_Helper();

        if (getArguments() != null) {
            selectedDeviceId = getArguments().getString("deviceId");
        }

        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) {
            android.content.SharedPreferences prefs = requireContext().getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
            selectedDeviceId = prefs.getString("selected_device_id", null);
        }

        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) {
            Toast.makeText(getContext(), "Please select a device first", Toast.LENGTH_SHORT).show();
        } else {
            dbHelper.setSelectedDeviceId(selectedDeviceId);
            NotificationHelper.bindDeviceLabel(view.findViewById(R.id.tvDeviceScopeLabel), selectedDeviceId);
        }

        spinnerCycle = view.findViewById(R.id.spinnerCycle);
        spinnerParameter = view.findViewById(R.id.spinnerParameter);
        lineChart = view.findViewById(R.id.lineChart);
        setupChartZoomBehavior();
        tvAverage = view.findViewById(R.id.tvAverage);
        tvHigh = view.findViewById(R.id.tvHigh);
        tvLow = view.findViewById(R.id.tvLow);

        tvInterpretation = view.findViewById(R.id.tvInterpretation);
        tvInsightStatus = view.findViewById(R.id.tvInsightStatus);
        dotInsightStatus = view.findViewById(R.id.dotInsightStatus);
        heroAccentEdge = view.findViewById(R.id.heroAccentEdge);
        cardInsightHero = view.findViewById(R.id.cardInsightHero);
        tvTargetRange = view.findViewById(R.id.tvTargetRange);
        tvEvidence = view.findViewById(R.id.tvEvidence);
        tvEffectiveRange = view.findViewById(R.id.tvEffectiveRange);
        tvChartTitle = view.findViewById(R.id.tvChartTitle);
        reportContentContainer = view.findViewById(R.id.reportContentContainer);
        noCyclesEmptyState = view.findViewById(R.id.noCyclesEmptyState);
        tvNoCyclesEmptyState = view.findViewById(R.id.tvNoCyclesEmptyState);

        btnEntireCycle = view.findViewById(R.id.btnEntireCycle);
        btnToday = view.findViewById(R.id.btnToday);
        btnWeek = view.findViewById(R.id.btnWeek);
        btnMonth = view.findViewById(R.id.btnMonth);
        btnCustom = view.findViewById(R.id.btnCustom);
        btnShare = view.findViewById(R.id.btnShare);
        periodSelectorRow = view.findViewById(R.id.periodSelectorRow);
        recyclerReadingsTable = view.findViewById(R.id.recyclerReadingsTable);
        tvReadingsTableEmpty = view.findViewById(R.id.tvReadingsTableEmpty);
        if (recyclerReadingsTable != null) {
            recyclerReadingsTable.setLayoutManager(
                    new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
            readingsTableAdapter = new ParameterTableAdapter(readingsTableRows);
            recyclerReadingsTable.setAdapter(readingsTableAdapter);
        }
        ImageButton btnInfo = view.findViewById(R.id.btnInfo);
        if (btnInfo != null) {
            btnInfo.setOnClickListener(v -> showInfoDialog());
        }

        fetchUserInfo();

        View btnBack = view.findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            NavController navController = NavHostFragment.findNavController(this);
            btnBack.setOnClickListener(v -> navController.popBackStack());
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
                loadReportData();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        spinnerParameter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                loadReportData();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        btnEntireCycle.setOnClickListener(v -> updateFilterSelection("Entire Cycle"));
        btnToday.setOnClickListener(v -> updateFilterSelection("Today"));
        btnWeek.setOnClickListener(v -> updateFilterSelection("7 Days"));
        btnMonth.setOnClickListener(v -> updateFilterSelection("30 Days"));
        btnCustom.setOnClickListener(v -> startCustomRangeSelection());

        if (btnShare != null) {
            btnShare.setOnClickListener(v -> showExportOptions());
        }

        loadDeviceThresholds();
        startListeningToCycles();

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (cyclesListener != null) cyclesListener.remove();
        if (coachMarkTour != null) {
            coachMarkTour.finish();
            coachMarkTour = null;
        }
    }

    // ------------------------------------------------------------------
    // Cycle loading & selection
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
                showNoCyclesState("No cultivation cycles exist for this device yet. Start a cycle to begin tracking parameter reports for it.");
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
            // Default to the active cycle if one exists, otherwise the most recently created cycle.
            preselect = cycles.size() - 1;
            for (int i = 0; i < cycles.size(); i++) {
                if ("ACTIVE".equalsIgnoreCase(normalizeCycleStatus(cycles.get(i).getStatus()))) {
                    preselect = i;
                    break;
                }
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCycle.setAdapter(adapter);
        spinnerCycle.setSelection(preselect);

        // Confirmed live bug: Spinner.setSelection() does not reliably fire
        // onItemSelectedListener when the position matches what the adapter
        // already auto-selected (a well-known AdapterView quirk - most
        // commonly position 0, which preselect resolves to for any device
        // with a single cycle, or whenever the active cycle happens to be
        // first in the list). That left selectedCycle permanently null even
        // though the spinner visually showed a cycle chosen, silently
        // stranding the whole screen on "Select a cycle and parameter..."
        // with every period chip appearing to do nothing. Set it directly
        // here instead of depending solely on the listener; the listener
        // still runs the same logic when the user actually changes the
        // selection interactively, this just guarantees the initial one.
        Cycle newlySelected = cycles.get(preselect);
        boolean cycleChanged = selectedCycle == null
                || !Objects.equals(selectedCycle.getCycleId(), newlySelected.getCycleId());
        selectedCycle = newlySelected;
        if (cycleChanged) {
            customStartMs = null;
            customEndMs = null;
            currentSelectedFilter = "Entire Cycle";
        }
        updatePeriodChipsForSelectedCycle();
        loadReportData();
    }

    private String cycleSpinnerLabel(Cycle c) {
        String name = (c.getCycleName() != null && !c.getCycleName().isEmpty()) ? c.getCycleName() : ("Cycle #" + c.getCycleNumber());
        String status = normalizeCycleStatus(c.getStatus());
        String range = DateUtils.formatDate(c.getStartDate()) + " to "
                + ("COMPLETED".equals(status) ? DateUtils.formatDate(c.getEndDate()) : "Present");
        return name + " • " + ("ACTIVE".equals(status) ? "In Progress" : "Completed") + " • " + range;
    }

    private String normalizeCycleStatus(String rawStatus) {
        return (rawStatus == null || rawStatus.isEmpty()) ? "ACTIVE" : rawStatus.toUpperCase(Locale.US);
    }

    private void showNoCyclesState(String message) {
        if (reportContentContainer != null) reportContentContainer.setVisibility(View.GONE);
        if (noCyclesEmptyState != null) noCyclesEmptyState.setVisibility(View.VISIBLE);
        if (tvNoCyclesEmptyState != null) tvNoCyclesEmptyState.setText(message);
        hideLayoutLoading();
        currentFilter = null;
        currentReadings = new ArrayList<>();
        currentParameterSamples.clear();
    }

    /** Shows the report loading overlay. Shared by loadReportData() and the export actions below. */
    private void showLayoutLoading() {
        View layoutLoading = getView() != null ? getView().findViewById(R.id.layoutLoading) : null;
        layoutLoadingShownAt = SystemClock.elapsedRealtime();
        if (layoutLoading != null) {
            layoutLoading.setVisibility(View.VISIBLE);
            layoutLoading.bringToFront();
        }
    }

    /** Hides the report loading overlay, never sooner than the minimum visible duration. */
    private void hideLayoutLoading() {
        View layoutLoading = getView() != null ? getView().findViewById(R.id.layoutLoading) : null;
        if (layoutLoading == null || layoutLoading.getVisibility() != View.VISIBLE) return;
        NotificationHelper.hideLoaderAfterMinimumDuration(layoutLoadingShownAt, () -> {
            View overlay = getView() != null ? getView().findViewById(R.id.layoutLoading) : null;
            if (overlay != null) overlay.setVisibility(View.GONE);
        });
    }

    private void showReportContent() {
        if (reportContentContainer != null) reportContentContainer.setVisibility(View.VISIBLE);
        if (noCyclesEmptyState != null) noCyclesEmptyState.setVisibility(View.GONE);
    }

    /**
     * Shows the guided Parameter Reports walkthrough once, the first time
     * this screen has actually finished loading a report. Fired from
     * loadReportData()'s success path (after renderReport()), not from
     * showReportContent() - showReportContent() only means the spinners/
     * period row exist, but loadReportData() immediately shows its own
     * layoutLoading overlay for the initial report fetch right after, which
     * raced with and covered the tour the same way Monitoring's
     * sensorStabilizingOverlay did. Shared with FoggingReportsFragment: same
     * "has_seen_reports_detail_tour" flag, since the two report screens are
     * similar enough that seeing this tour once on either is enough.
     */
    private void maybeShowCoachMarkTour() {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences("basilience_prefs", android.content.Context.MODE_PRIVATE);
        if (prefs.getBoolean("has_seen_reports_detail_tour", false)) {
            return;
        }
        prefs.edit().putBoolean("has_seen_reports_detail_tour", true).apply();

        List<CoachMarkTour.Step> steps = Arrays.asList(
                new CoachMarkTour.Step(spinnerCycle, "Choose a cycle",
                        "Pick which growth cycle to look at."),
                new CoachMarkTour.Step(spinnerParameter, "Choose a parameter",
                        "pH, EC, temperature, and more."),
                new CoachMarkTour.Step(periodSelectorRow, "Choose a time range",
                        "Entire cycle, today, this week, this month, or a custom range."));

        coachMarkTour = new CoachMarkTour(requireActivity(), getViewLifecycleOwner(),
                requireActivity().getOnBackPressedDispatcher(), steps, null);
        coachMarkTour.start();
    }

    // ------------------------------------------------------------------
    // Period filter chips
    // ------------------------------------------------------------------

    private void updatePeriodChipsForSelectedCycle() {
        boolean isActive = selectedCycle != null && "ACTIVE".equals(normalizeCycleStatus(selectedCycle.getStatus()));
        // "Today" would be meaningless (and misleading) for an old completed
        // cycle, since it refers to the real current date rather than any
        // date the cycle was actually running.
        btnToday.setVisibility(isActive ? View.VISIBLE : View.GONE);
        if (!isActive && "Today".equals(currentSelectedFilter)) {
            currentSelectedFilter = "Entire Cycle";
        }
        refreshFilterChipHighlight();
    }

    private void updateFilterSelection(String selectedFilter) {
        currentSelectedFilter = selectedFilter;
        refreshFilterChipHighlight();
        loadReportData();
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
    // Custom date range
    // ------------------------------------------------------------------

    /**
     * A single Material date-range calendar replaces the old chained Start
     * Date -&gt; End Date DatePickerDialogs. The picker's own header shows
     * both ends and the range as they're picked, its CalendarConstraints
     * gray out/disable any date outside the cycle so an invalid range can't
     * be tapped in the first place, and customStartMs/customEndMs only
     * commit (reloading the report) when the user taps Save - never on a
     * single date tap.
     *
     * MaterialDatePicker works in UTC calendar days internally (a selection
     * is documented as UTC midnight of that day), while every other date
     * computation in this fragment is anchored to Asia/Manila
     * (startOfDayManila/endOfDayManila). manilaDateToUtcMidnight/
     * utcMidnightToManilaDate convert between the two so the picker only
     * ever sees/returns the Manila calendar day, never a UTC-shifted one.
     */
    private void startCustomRangeSelection() {
        if (selectedCycle == null) {
            // Previously a silent no-op - a user tapping Custom before a
            // cycle finished resolving saw nothing happen with no
            // explanation at all.
            Toast.makeText(getContext(), "Select a cycle first.", Toast.LENGTH_SHORT).show();
            return;
        }
        long cycleStartMs = currentCycleStartMs();
        long cycleEndMs = currentCycleEndMs();

        long constraintStartUtc = manilaDateToUtcMidnight(cycleStartMs);
        long constraintEndUtc = manilaDateToUtcMidnight(cycleEndMs);
        CalendarConstraints constraints = new CalendarConstraints.Builder()
                .setStart(constraintStartUtc)
                .setEnd(constraintEndUtc)
                .build();

        long initialStartUtc = clampUtc(manilaDateToUtcMidnight(customStartMs != null ? customStartMs : cycleStartMs),
                constraintStartUtc, constraintEndUtc);
        long initialEndUtc = clampUtc(manilaDateToUtcMidnight(customEndMs != null ? customEndMs : cycleEndMs),
                constraintStartUtc, constraintEndUtc);
        if (initialEndUtc < initialStartUtc) initialEndUtc = initialStartUtc;

        MaterialDatePicker<Pair<Long, Long>> picker = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText("Select Report Range")
                .setCalendarConstraints(constraints)
                .setSelection(new Pair<>(initialStartUtc, initialEndUtc))
                .build();

        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null || selection.first == null || selection.second == null) return;
            customStartMs = startOfDayManila(utcMidnightToManilaDate(selection.first));
            customEndMs = endOfDayManila(utcMidnightToManilaDate(selection.second));
            updateFilterSelection("Custom");
        });

        picker.show(requireActivity().getSupportFragmentManager(), "custom_report_range");
    }

    /** The UTC-midnight instant representing the same calendar day (year/month/day) that manilaMs falls on in Asia/Manila. */
    private long manilaDateToUtcMidnight(long manilaMs) {
        Calendar manilaCal = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE_ID));
        manilaCal.setTimeInMillis(manilaMs);
        Calendar utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utcCal.clear();
        utcCal.set(manilaCal.get(Calendar.YEAR), manilaCal.get(Calendar.MONTH), manilaCal.get(Calendar.DAY_OF_MONTH));
        return utcCal.getTimeInMillis();
    }

    /** The inverse of manilaDateToUtcMidnight: an Asia/Manila instant (midday, so callers can safely pass it through startOfDayManila/endOfDayManila) on the calendar day the picker's UTC-midnight value represents. */
    private long utcMidnightToManilaDate(long utcMidnightMs) {
        Calendar utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utcCal.setTimeInMillis(utcMidnightMs);
        Calendar manilaCal = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE_ID));
        manilaCal.clear();
        manilaCal.set(utcCal.get(Calendar.YEAR), utcCal.get(Calendar.MONTH), utcCal.get(Calendar.DAY_OF_MONTH), 12, 0, 0);
        return manilaCal.getTimeInMillis();
    }

    private long clampUtc(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
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

    // ------------------------------------------------------------------
    // Filter state construction (Part 5: one authoritative filter state)
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

    private ParameterReportFilter buildCurrentFilter() {
        if (selectedCycle == null || selectedDeviceId == null || selectedDeviceId.isEmpty()) return null;
        if (spinnerParameter.getSelectedItem() == null) return null;

        String displayParameter = String.valueOf(spinnerParameter.getSelectedItem());
        String canonicalParameter = canonicalParameterKey(displayParameter);
        if (canonicalParameter == null) return null;

        long cycleStartMs = currentCycleStartMs();
        long cycleEndMs = currentCycleEndMs();

        // Every period is intersected with the selected cycle's own bounds,
        // so records can never be shown or exported outside the cycle that
        // was actually selected, regardless of which period chip is active.
        long[] requested = getRequestedRangeForPeriod(currentSelectedFilter, cycleStartMs, cycleEndMs);
        long effectiveStart = Math.max(cycleStartMs, requested[0]);
        long effectiveEnd = Math.min(cycleEndMs, requested[1]);
        if (effectiveEnd < effectiveStart) effectiveEnd = effectiveStart;

        return new ParameterReportFilter(selectedDeviceId, selectedCycle.getCycleId(), cycleSpinnerLabel(selectedCycle),
                normalizeCycleStatus(selectedCycle.getStatus()), cycleStartMs, cycleEndMs,
                canonicalParameter, displayParameter, currentSelectedFilter, effectiveStart, effectiveEnd);
    }

    // ------------------------------------------------------------------
    // Data loading & rendering
    // ------------------------------------------------------------------

    private void loadDeviceThresholds() {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) return;
        FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app")
                .getReference("devices").child(selectedDeviceId).child("settings")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (!isAdded()) return;
                        minPhThreshold = numberValue(snapshot.child("minPH"));
                        maxPhThreshold = numberValue(snapshot.child("maxPH"));
                        phTargetMinThreshold = numberValue(snapshot.child("phTargetMin"));
                        phTargetMaxThreshold = numberValue(snapshot.child("phTargetMax"));

                        minEcThreshold = numberValue(snapshot.child("minEC"));
                        maxEcThreshold = numberValue(snapshot.child("maxEC"));
                        ecTargetMinThreshold = numberValue(snapshot.child("ecTargetMin"));
                        ecTargetMaxThreshold = numberValue(snapshot.child("ecTargetMax"));

                        minAirTempTarget = numberValue(snapshot.child("minAirTemp"));
                        maxAirTempTarget = numberValue(snapshot.child("maxAirTemp"));
                        minHumidityTarget = numberValue(snapshot.child("minHumidity"));
                        maxHumidityTarget = numberValue(snapshot.child("maxHumidity"));
                        minWaterTempTarget = numberValue(snapshot.child("minWaterTemp"));
                        maxWaterTempTarget = numberValue(snapshot.child("maxWaterTemp"));
                        minWaterLevelTarget = numberValue(snapshot.child("minWaterLevel"));
                        maxWaterLevelTarget = numberValue(snapshot.child("maxWaterLevel"));

                        highAirTempThreshold = numberValue(snapshot.child("highAirTemp"));
                        airTempReleaseThreshold = numberValue(snapshot.child("airTempRelease"));

                        highHumidityThreshold = numberValue(snapshot.child("highHumidity"));
                        humidityReleaseThreshold = numberValue(snapshot.child("humidityRelease"));

                        // highWaterTemp/coolerOffTemp are legacy mirrored keys
                        // and can lag after maxWaterTemp is edited. Report the
                        // same effective thresholds the controller computes.
                        highWaterTempThreshold = maxWaterTempTarget;
                        coolerOffTempThreshold = maxWaterTempTarget == null
                                ? null : maxWaterTempTarget - WATER_COOLING_HYSTERESIS_C;

                        refillStartThresholdCm = numberValue(snapshot.child("refillStartLevelCm"));
                        refillStopThresholdCm = numberValue(snapshot.child("refillStopLevelCm"));

                        if (selectedCycle != null && spinnerParameter.getSelectedItem() != null) {
                            loadReportData();
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.w("REPORT_THRESHOLDS", "Unable to read configured device thresholds", error.toException());
                        // Previously silent: the report would just sit without
                        // rendering (loadReportData() above is only reached
                        // from onDataChange) and nothing told the user why.
                        // Threshold-based highlighting will be missing from
                        // the report, but the rest of it can still load.
                        if (!isAdded()) return;
                        Toast.makeText(getContext(),
                                "Unable to load configured thresholds for this report.",
                                Toast.LENGTH_SHORT).show();
                        if (selectedCycle != null && spinnerParameter.getSelectedItem() != null) {
                            loadReportData();
                        }
                    }
                });
    }

    private Double numberValue(DataSnapshot snapshot) {
        Object value = snapshot.getValue();
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    private void loadReportData() {
        final long requestGeneration = ++reportRequestGeneration;
        ParameterReportFilter filter = buildCurrentFilter();
        if (filter == null) {
            currentFilter = null;
            currentReadings = new ArrayList<>();
            currentParameterSamples.clear();
            readingsTableRows.clear();
            if (readingsTableAdapter != null) readingsTableAdapter.notifyDataSetChanged();
            if (recyclerReadingsTable != null) recyclerReadingsTable.setVisibility(View.GONE);
            if (tvReadingsTableEmpty != null) tvReadingsTableEmpty.setVisibility(View.VISIBLE);
            showEmptyReportState("Select a cycle and parameter to view a report.");
            return;
        }

        View layoutLoading = getView() != null ? getView().findViewById(R.id.layoutLoading) : null;
        layoutLoadingShownAt = SystemClock.elapsedRealtime();
        if (layoutLoading != null) {
            layoutLoading.setVisibility(View.VISIBLE);
            layoutLoading.bringToFront();
        }

        if (tvEffectiveRange != null) {
            tvEffectiveRange.setText("Showing " + DateUtils.formatDate(filter.effectiveStartMs)
                    + " to " + DateUtils.formatDate(filter.effectiveEndMs));
        }

        dbHelper.getParameterLogs(filter.effectiveStartMs, filter.effectiveEndMs)
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (!isAdded() || requestGeneration != reportRequestGeneration) return;
                    hideLayoutLoading();
                    renderReport(filter, queryDocumentSnapshots);
                    maybeShowCoachMarkTour();
                })
                .addOnFailureListener(e -> {
                    if (requestGeneration != reportRequestGeneration) return;
                    hideLayoutLoading();
                    Log.e("CHART_FETCH_ERROR", "Failed to fetch logs", e);
                    currentFilter = null;
                    currentReadings = new ArrayList<>();
                    showEmptyReportState("Unable to load report data.");
                    NotificationHelper.showError(getContext(), "Unable to load report data. Please try again.");
                });
    }

    // ------------------------------------------------------------------
    // Zoom-aware X axis (Part B): registered once, reused across every
    // report render. Horizontal zoom/pan only - vertical scale isn't
    // meaningful for a fixed-value-axis trend chart. Gesture handling only
    // recomputes label formatting/density and invalidates the chart; it
    // never touches the dataset or requeries anything.
    // ------------------------------------------------------------------

    private void setupChartZoomBehavior() {
        lineChart.setScaleYEnabled(false);
        lineChart.setOnChartGestureListener(new OnChartGestureListener() {
            @Override public void onChartGestureStart(MotionEvent me, ChartTouchListener.ChartGesture lastPerformedGesture) { }
            @Override public void onChartGestureEnd(MotionEvent me, ChartTouchListener.ChartGesture lastPerformedGesture) { }
            @Override public void onChartLongPressed(MotionEvent me) { }
            @Override public void onChartDoubleTapped(MotionEvent me) { }
            @Override public void onChartSingleTapped(MotionEvent me) { }
            @Override public void onChartFling(MotionEvent me1, MotionEvent me2, float velocityX, float velocityY) { }
            @Override public void onChartScale(MotionEvent me, float scaleX, float scaleY) { refreshAdaptiveXAxis(); }
            @Override public void onChartTranslate(MotionEvent me, float dX, float dY) { refreshAdaptiveXAxis(); }
        });
    }

    /** Re-derives label format/density from the chart's current visible range. Cheap: no data requery. */
    private void refreshAdaptiveXAxis() {
        if (adaptiveXFormatter == null || lineChart == null) return;
        adaptiveXFormatter.updateVisibleRange(lineChart.getLowestVisibleX(), lineChart.getHighestVisibleX());
        applyAdaptiveXAxis(lineChart.getXAxis());
        lineChart.invalidate();
    }

    /**
     * Applies the formatter's current granularity/label-count to the axis.
     * Granularity is a hard floor on label spacing (enabled via
     * setGranularityEnabled) - this is what actually prevents label overlap;
     * labelCount is only a soft upper bound on top of it. Previously only
     * labelCount was set, which let MPAndroidChart render more labels than
     * requested at uneven spacing on the Today view.
     */
    private void applyAdaptiveXAxis(XAxis xAxis) {
        if (adaptiveXFormatter == null) return;
        xAxis.setGranularity(adaptiveXFormatter.getGranularityMinutes());
        xAxis.setGranularityEnabled(true);
        xAxis.setLabelCount(adaptiveXFormatter.suggestedLabelCount(), false);
    }

    private void renderReport(ParameterReportFilter filter, QuerySnapshot queryDocumentSnapshots) {
        String canonicalParameter = filter.canonicalParameter;
        String dbFieldName = getFieldNameFromParameter(canonicalParameter);

        // Chart surface title reuses the same display label already shown
        // elsewhere (e.g. CSV headers) - no new data, just a UI label.
        if (tvChartTitle != null) tvChartTitle.setText(filter.displayParameter + " Trend");

        List<Entry> entries = new ArrayList<>();
        List<Float> values = new ArrayList<>();
        List<ParameterReading> readings = new ArrayList<>();
        float sum = 0f;
        float high = Float.NEGATIVE_INFINITY;
        float low = Float.POSITIVE_INFINITY;

        for (QueryDocumentSnapshot doc : queryDocumentSnapshots) {
            Double value = doc.getDouble(dbFieldName);
            Long timestamp = doc.getLong("timestamp");
            if (timestamp != null && isValidParameterValue(canonicalParameter, value)) {
                float val = value.floatValue();
                entries.add(new Entry((timestamp - filter.effectiveStartMs) / 60000f, val));
                values.add(val);
                readings.add(new ParameterReading(timestamp, val));
                sum += val;
                if (val > high) high = val;
                if (val < low) low = val;
            }
        }

        currentFilter = filter;
        currentReadings = readings;

        // Combined readings table: every parameter, not just the one
        // currently charted - independent of canonicalParameter/dbFieldName
        // above, built straight from the same document snapshots.
        populateReadingsTable(queryDocumentSnapshots);

        if (entries.isEmpty()) {
            currentInsight = null;
            showEmptyReportState("No parameter records were available for this period.");
            return;
        }

        int primaryColor = getResources().getColor(R.color.primary);
        int outOfRangeColor = ContextCompat.getColor(requireContext(), R.color.state_critical);

        // Only the stretches outside the configured range are red; everything
        // else keeps the normal series colour. The series is cut into
        // contiguous runs because MPAndroidChart cannot colour part of one
        // dataset - see ChartRangeSegmenter for the crossing interpolation.
        Float rangeMin = configuredRangeMin(canonicalParameter);
        Float rangeMax = configuredRangeMax(canonicalParameter);
        // entries.size() can be checked here, not after: it's still the raw,
        // full-resolution count at this point, before this is applied. The
        // earlier sum/high/low/values/readings above are unaffected - built
        // from the raw loop before this line.
        List<Entry> plottedEntries = entries.size() > CHART_DOWNSAMPLE_THRESHOLD
                ? ChartRangeSegmenter.downsample(entries, CHART_DOWNSAMPLE_TARGET_POINTS)
                : entries;
        List<ChartRangeSegmenter.Segment> segments =
                ChartRangeSegmenter.segment(plottedEntries, rangeMin, rangeMax);

        List<ILineDataSet> dataSets = new ArrayList<>(segments.size());
        boolean anyOutOfRange = false;
        for (ChartRangeSegmenter.Segment segment : segments) {
            int color = segment.outOfRange ? outOfRangeColor : primaryColor;
            anyOutOfRange |= segment.outOfRange;

            LineDataSet dataSet = new LineDataSet(segment.entries, filter.displayParameter);
            dataSet.setColor(color);
            dataSet.setCircleColor(color);
            dataSet.setValueTextColor(Color.BLACK);
            dataSet.setLineWidth(2f);
            dataSet.setDrawCircles(false);
            // Point values are shown on tap via the marker instead of being
            // printed over every point, which made the chart unreadable.
            dataSet.setDrawValues(false);
            dataSet.setHighlightEnabled(true);
            dataSet.setHighLightColor(primaryColor);
            dataSet.setHighlightLineWidth(1f);
            dataSet.setDrawHorizontalHighlightIndicator(false);
            dataSets.add(dataSet);
        }

        // Point markers at a calculated interval - the line above already
        // plots every point in plottedEntries; this overlay only decides
        // which of them also get a visible dot, spaced so a dense period
        // (e.g. 30 Days) doesn't turn into a smear of overlapping circles.
        int markerWidthPx = lineChart.getWidth() > 0 ? lineChart.getWidth() : 720;
        int stride = ControlChartRenderer.markerStride(plottedEntries.size(), markerWidthPx);
        List<Entry> markerEntries = new ArrayList<>();
        List<Integer> markerColors = new ArrayList<>();
        for (int i = 0; i < plottedEntries.size(); i += stride) {
            Entry e = plottedEntries.get(i);
            markerEntries.add(e);
            markerColors.add(ChartRangeSegmenter.isOutOfRange(e.getY(), rangeMin, rangeMax) ? outOfRangeColor : primaryColor);
        }
        int lastIndex = plottedEntries.size() - 1;
        if (lastIndex >= 0 && (lastIndex % stride) != 0) {
            Entry e = plottedEntries.get(lastIndex);
            markerEntries.add(e);
            markerColors.add(ChartRangeSegmenter.isOutOfRange(e.getY(), rangeMin, rangeMax) ? outOfRangeColor : primaryColor);
        }
        LineDataSet markerDataSet = new LineDataSet(markerEntries, "");
        markerDataSet.setColor(Color.TRANSPARENT);
        markerDataSet.setLineWidth(0f);
        markerDataSet.setDrawCircles(true);
        markerDataSet.setCircleRadius(3.2f);
        markerDataSet.setDrawCircleHole(false);
        markerDataSet.setCircleColors(markerColors);
        markerDataSet.setDrawValues(false);
        markerDataSet.setHighlightEnabled(false);
        dataSets.add(markerDataSet);

        lineChart.setData(new LineData(dataSets));

        // A custom legend keeps this at one or two entries no matter how many
        // runs the series was cut into.
        applyChartLegend(primaryColor, outOfRangeColor, anyOutOfRange);
        // Confirmed live crash (IndexOutOfBoundsException inside MPAndroidChart's
        // LegendRenderer.renderLegend, reached from onDraw): setData() above
        // already triggers one legend layout pass sized for whatever the
        // dataset produced, then applyChartLegend()'s setCustom() swaps in a
        // DIFFERENT entry count (1 or 2, depending on anyOutOfRange) without
        // ever re-running that layout pass. MPAndroidChart's word-wrap sizing
        // arrays stay sized for the FIRST pass's entry count, so switching
        // filters between a period with an out-of-range excursion (2 entries)
        // and one without (1 entry) tries to index a slot that no longer
        // exists on whichever draw call catches the mismatch. Forcing a
        // second legend computation here, now that the entries are final,
        // keeps those internal arrays in sync with what's actually rendered.
        lineChart.notifyDataSetChanged();
        lineChart.getDescription().setEnabled(false);
        lineChart.getLegend().setEnabled(true);
        // Without this, MPAndroidChart draws every legend entry starting at
        // the same baseline when they don't all fit on one line - the two
        // entries render on top of each other instead of wrapping. This is
        // a real risk here: "Out of Range" only appears some of the time, so
        // the legend's width isn't constant.
        lineChart.getLegend().setWordWrapEnabled(true);
        lineChart.getLegend().setYOffset(8f);
        lineChart.setExtraBottomOffset(8f);
        lineChart.setHighlightPerTapEnabled(true);
        // Tapping empty chart space clears the highlight, so no marker or
        // value label lingers once a point is deselected.
        lineChart.setHighlightPerDragEnabled(false);

        // Modernized chart chrome (Basilience Design System pilot): lighter
        // grid/axis lines - visual only, the dataset/entries above are
        // untouched. A visible frame around the plot area (matching the
        // export chart's styling) replaces the previous borderless look, so
        // the app and export charts read as the same kind of figure.
        int mutedAxisColor = Color.parseColor("#8A2E4F46");
        int hairlineColor = Color.parseColor("#F0F0F0");
        lineChart.setDrawGridBackground(false);
        lineChart.setDrawBorders(true);
        lineChart.setBorderColor(Color.parseColor("#B0B0B0"));
        lineChart.setBorderWidth(1f);
        lineChart.getLegend().setTextColor(mutedAxisColor);
        lineChart.getLegend().setTextSize(11f);

        final long xAxisBaseMs = filter.effectiveStartMs;
        XAxis xAxis = lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(mutedAxisColor);
        xAxis.setTextSize(11f);
        xAxis.setGridColor(hairlineColor);
        xAxis.setAxisLineColor(hairlineColor);
        xAxis.setAvoidFirstLastClipping(true);

        // Label format/density adapts to the CURRENT VISIBLE range, not just
        // this full load - initialized here to the full effective range so
        // the full view is immediately correct, then kept in sync by
        // refreshAdaptiveXAxis() as the farmer zooms/pans (see
        // setupChartZoomBehavior()).
        adaptiveXFormatter = new AdaptiveTimeAxisFormatter(xAxisBaseMs, TIMEZONE_ID);
        float fullSpanMinutes = (filter.effectiveEndMs - filter.effectiveStartMs) / 60000f;
        adaptiveXFormatter.updateVisibleRange(0f, fullSpanMinutes);
        applyAdaptiveXAxis(xAxis);
        xAxis.setValueFormatter(adaptiveXFormatter);

        String unit = getUnitForParameter(canonicalParameter);
        YAxis axisLeft = lineChart.getAxisLeft();
        // Widen the value axis when needed so configured threshold indicators
        // stay visible for every period and for every reading source.
        applyAxisRangeIncludingIndicators(canonicalParameter, axisLeft, low, high);
        axisLeft.setTextColor(mutedAxisColor);
        axisLeft.setTextSize(11f);
        axisLeft.setGridColor(hairlineColor);
        axisLeft.setAxisLineColor(hairlineColor);
        axisLeft.setLabelCount(5, false);
        axisLeft.setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                return String.format(Locale.getDefault(), "%.1f%s", value, unit);
            }
        });
        lineChart.getAxisRight().setEnabled(false);
        applyTargetLimitLines(canonicalParameter, axisLeft);
        // No shaded band behind the plot any more: the dashed limit lines plus
        // the red out-of-range run carry that meaning without tinting the
        // whole surface.
        lineChart.setThresholdBands(null);

        // Tap marker: reads the same x-axis origin/unit the chart already
        // uses, so it never introduces a second source for a reading.
        ParameterChartMarkerView marker = new ParameterChartMarkerView(requireContext(),
                xAxisBaseMs, markerUnitForParameter(canonicalParameter),
                markerDecimalsForParameter(canonicalParameter), TIMEZONE_ID);
        marker.setChartView(lineChart);
        lineChart.setMarker(marker);
        lineChart.highlightValue(null);

        // A new filter/cycle/parameter selection always starts at its own
        // full range - any zoom left over from a previous selection's
        // viewport must not carry over onto this one.
        lineChart.fitScreen();
        lineChart.invalidate();

        float avg = sum / entries.size();
        tvAverage.setText(formatMetric(avg, unit));
        tvHigh.setText(formatMetric(high, unit));
        tvLow.setText(formatMetric(low, unit));

        currentAvg = avg;
        currentHigh = high;
        currentLow = low;

        ParameterInsight insight = computeInsight(canonicalParameter, values);
        currentInsight = insight;
        renderInsight(insight);
    }

    /**
     * The configured threshold indicators for a parameter, in the order they
     * should be drawn. This is the single source of truth for both the dashed
     * limit lines and the axis range that has to keep them on screen, so the
     * two can never disagree.
     *
     * Only genuinely configured values appear here. Parameters whose control
     * logic has one real bound (see the hysteresis notes below) contribute one
     * indicator - a lower bound is never invented so that every parameter can
     * show a matching pair.
     */
    private List<ThresholdIndicator> configuredIndicatorsFor(String canonicalParameter) {
        // Every parameter now draws from the same canonical target range, so the
        // chart lines, the out-of-range colouring and the summary text can never
        // disagree. A bound that is genuinely unconfigured is simply not drawn -
        // nothing is invented to make a chart look symmetric.
        List<ThresholdIndicator> indicators = new ArrayList<>();

        Float min = configuredRangeMin(canonicalParameter);
        Float max = configuredRangeMax(canonicalParameter);

        if (min != null) indicators.add(new ThresholdIndicator(min, "Min"));
        if (max != null) indicators.add(new ThresholdIndicator(max, "Max"));

        return indicators;
    }

    /**
     * Lower bound of the configured target range, or null when the parameter
     * genuinely has none. Only a real configured minimum is returned - an
     * actuator release/off value is hysteresis, never a reporting bound.
     */
    private Float configuredRangeMin(String canonicalParameter) {
        ParameterTargetRanges parameter = targetRangeFor(canonicalParameter);
        if (parameter == null) return null;

        Float snapshot = cycleSnapshotValue(parameter.minKey);
        if (snapshot != null) return snapshot;

        if (canonicalParameter.equalsIgnoreCase("pH")) return toFloat(minPhThreshold);
        if (canonicalParameter.equalsIgnoreCase("EC")) return toFloat(minEcThreshold);
        if (canonicalParameter.equalsIgnoreCase("Air Temperature")) return toFloat(minAirTempTarget);
        if (canonicalParameter.equalsIgnoreCase("Humidity")) return toFloat(minHumidityTarget);
        if (canonicalParameter.equalsIgnoreCase("Water Temperature")) return toFloat(minWaterTempTarget);
        if (canonicalParameter.equalsIgnoreCase("Water Level")) return toFloat(minWaterLevelTarget);
        return null;
    }

    /**
     * The single range-resolution rule for this screen: a cycle keeps the target
     * ranges it was created under, so a report on a finished cycle stays fixed
     * even after Settings change. Only a cycle created before snapshots existed
     * falls back to the current device settings.
     *
     * Everything that evaluates a reading - the dashed lines, the red
     * out-of-range runs, the compliance figure and the summary text - goes
     * through configuredRangeMin/Max, so no two of them can disagree.
     */
    private Float cycleSnapshotValue(String key) {
        if (selectedCycle == null) return null;

        java.util.Map<String, Object> ranges = selectedCycle.getTargetRanges();
        if (ranges == null) return null;

        Object value = ranges.get(key);
        if (!(value instanceof Number)) return null;

        float parsed = ((Number) value).floatValue();
        if (Float.isNaN(parsed) || Float.isInfinite(parsed)) return null;
        return parsed;
    }

    private ParameterTargetRanges targetRangeFor(String canonicalParameter) {
        for (ParameterTargetRanges parameter : ParameterTargetRanges.values()) {
            if (parameter.displayName.equalsIgnoreCase(canonicalParameter)) return parameter;
        }
        return null;
    }

    /** Upper bound of the configured target range, or null when there is none. */
    private Float configuredRangeMax(String canonicalParameter) {
        ParameterTargetRanges parameter = targetRangeFor(canonicalParameter);
        if (parameter != null) {
            Float snapshot = cycleSnapshotValue(parameter.maxKey);
            if (snapshot != null) return snapshot;
        }

        if (canonicalParameter.equalsIgnoreCase("pH")) return toFloat(maxPhThreshold);
        if (canonicalParameter.equalsIgnoreCase("EC")) return toFloat(maxEcThreshold);
        if (canonicalParameter.equalsIgnoreCase("Air Temperature")) return toFloat(maxAirTempTarget);
        if (canonicalParameter.equalsIgnoreCase("Humidity")) return toFloat(maxHumidityTarget);
        if (canonicalParameter.equalsIgnoreCase("Water Temperature")) return toFloat(maxWaterTempTarget);
        if (canonicalParameter.equalsIgnoreCase("Water Level")) return toFloat(maxWaterLevelTarget);
        return null;
    }

    private Float toFloat(Double value) {
        return value == null ? null : value.floatValue();
    }

    /**
     * One legend entry for the normal series, plus "Out of Range" only when the
     * period actually contains an excursion. The dashed limit lines label
     * themselves on the axis and are deliberately kept out of the legend.
     */
    private void applyChartLegend(int primaryColor, int outOfRangeColor, boolean anyOutOfRange) {
        List<LegendEntry> legendEntries = new ArrayList<>(2);
        legendEntries.add(legendEntry("Sensor Reading", primaryColor));
        if (anyOutOfRange) {
            legendEntries.add(legendEntry("Out of Range", outOfRangeColor));
        }
        lineChart.getLegend().setCustom(legendEntries);
    }

    private LegendEntry legendEntry(String label, int color) {
        LegendEntry entry = new LegendEntry();
        entry.label = label;
        entry.formColor = color;
        entry.form = Legend.LegendForm.LINE;
        return entry;
    }

    private void applyTargetLimitLines(String canonicalParameter, YAxis axisLeft) {
        axisLeft.removeAllLimitLines();
        // One red in the chart: the same danger token the out-of-range runs use.
        int lineColor = ContextCompat.getColor(requireContext(), R.color.state_critical);
        int decimals = markerDecimalsForParameter(canonicalParameter);
        for (ThresholdIndicator indicator : configuredIndicatorsFor(canonicalParameter)) {
            // "Min 5.5" rather than a bare "Min", so the boundary is readable
            // without tracing it back to the axis.
            String label = indicator.label + " "
                    + String.format(Locale.getDefault(), "%." + decimals + "f", indicator.value);
            addLimitLine(axisLeft, indicator.value, label, lineColor);
        }
    }

    /**
     * Keeps every configured threshold indicator inside the value axis.
     *
     * MPAndroidChart scales the value axis to the plotted data only and clips
     * limit lines that fall outside it, so an indicator would silently vanish
     * whenever the readings for the selected period happened not to approach
     * it. That is why the same parameter could show its Max on a 7-day view
     * (wider spread) but nothing on Today, and why injected mock readings -
     * which sit in a deliberately narrow band well inside the configured
     * limits - appeared to "lose" their target range entirely. The thresholds
     * were always loaded; they were simply off-axis.
     *
     * The axis is only ever widened, never narrowed: the full data range still
     * determines the minimum extent, exactly as before.
     */
    private void applyAxisRangeIncludingIndicators(String canonicalParameter, YAxis axisLeft,
                                                   float dataLow, float dataHigh) {
        List<ThresholdIndicator> indicators = configuredIndicatorsFor(canonicalParameter);
        if (indicators.isEmpty()) {
            // Nothing configured to keep on screen - leave MPAndroidChart's
            // own data-driven autoscaling completely untouched.
            axisLeft.resetAxisMinimum();
            axisLeft.resetAxisMaximum();
            return;
        }

        float low = dataLow;
        float high = dataHigh;
        for (ThresholdIndicator indicator : indicators) {
            low = Math.min(low, indicator.value);
            high = Math.max(high, indicator.value);
        }

        // Breathing room so a line sitting at the extreme of the range is not
        // drawn flush against the chart edge, where its label would be cut off.
        float span = high - low;
        float margin = span > 0 ? span * 0.08f : Math.max(Math.abs(high) * 0.05f, 0.5f);
        axisLeft.setAxisMinimum(low - margin);
        axisLeft.setAxisMaximum(high + margin);
    }

    /** A single configured threshold line: the value and the label drawn beside it. */
    private static final class ThresholdIndicator {
        final float value;
        final String label;

        ThresholdIndicator(float value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    private void addLimitLine(YAxis axis, float value, String label, int color) {
        LimitLine line = new LimitLine(value, label);
        line.setLineColor(color);
        line.setLineWidth(1f);
        line.enableDashedLine(6f, 4f, 0f);
        line.setTextColor(color);
        line.setTextSize(9f);
        line.setLabelPosition(LimitLine.LimitLabelPosition.RIGHT_TOP);
        axis.addLimitLine(line);
    }

    // ------------------------------------------------------------------
    // Farmer-readable summary (Part 7)
    // ------------------------------------------------------------------

    private static final class ParameterReading {
        final long timestampMs;
        final float value;
        ParameterReading(long timestampMs, float value) {
            this.timestampMs = timestampMs;
            this.value = value;
        }
    }

    private static final class ParameterInsight {
        final String status;
        final String targetRangeText;
        final String evidenceText;
        final String interpretation;

        ParameterInsight(String status, String targetRangeText, String evidenceText, String interpretation) {
            this.status = status;
            this.targetRangeText = targetRangeText;
            this.evidenceText = evidenceText;
            this.interpretation = interpretation;
        }
    }

    private ParameterInsight computeInsight(String canonicalParameter, List<Float> values) {
        int within = 0;
        int outside = 0;
        for (float v : values) {
            Boolean ok = isWithinTarget(canonicalParameter, v);
            if (ok == null) continue;
            if (ok) within++; else outside++;
        }
        int assessed = within + outside;

        String status;
        Double percentWithinTarget = null;
        if (assessed == 0) {
            status = "INSUFFICIENT DATA";
        } else {
            percentWithinTarget = 100.0 * within / assessed;
            status = percentWithinTarget >= (STABLE_WITHIN_TARGET_RATIO * 100) ? "STABLE" : "NEEDS ATTENTION";
        }

        String targetRangeText = getTargetRangeText(canonicalParameter);
        String evidenceText = buildEvidenceText(values.size(), percentWithinTarget);
        String interpretation = buildInterpretation(canonicalParameter, status);

        return new ParameterInsight(status, targetRangeText, evidenceText, interpretation);
    }

    private void renderInsight(ParameterInsight insight) {
        if (tvInsightStatus == null) return;
        if (cardInsightHero != null) cardInsightHero.setVisibility(View.VISIBLE);
        tvInsightStatus.setVisibility(View.VISIBLE);
        if (dotInsightStatus != null) dotInsightStatus.setVisibility(View.VISIBLE);
        tvInsightStatus.setText(insight.status);
        int color;
        int containerBg;
        switch (insight.status) {
            case "NEEDS ATTENTION":
                color = android.R.color.holo_orange_dark;
                containerBg = R.drawable.ds_status_pill_warning;
                break;
            case "INSUFFICIENT DATA":
                color = android.R.color.darker_gray;
                containerBg = R.drawable.ds_status_pill_neutral;
                break;
            default:
                color = android.R.color.holo_green_dark;
                containerBg = R.drawable.ds_status_pill_success;
                break;
        }
        int resolvedColor = ContextCompat.getColor(tvInsightStatus.getContext(), color);
        tvInsightStatus.setTextColor(resolvedColor);
        // Compact status pill (Basilience Design System pilot), same status
        // string already driving the text/dot color above - purely visual.
        tvInsightStatus.setBackgroundResource(containerBg);
        if (dotInsightStatus != null) {
            dotInsightStatus.setBackgroundTintList(android.content.res.ColorStateList.valueOf(resolvedColor));
        }
        if (heroAccentEdge != null) {
            heroAccentEdge.setBackgroundTintList(android.content.res.ColorStateList.valueOf(resolvedColor));
        }

        if (tvTargetRange != null) tvTargetRange.setText(insight.targetRangeText);
        if (tvEvidence != null) tvEvidence.setText(insight.evidenceText);
        tvInterpretation.setText(insight.interpretation);
        tvInterpretation.setTextColor(ContextCompat.getColor(tvInterpretation.getContext(), R.color.nav_inactive));
    }

    private String buildEvidenceText(int readingCount, Double percentWithinTarget) {
        String base = readingCount + (readingCount == 1 ? " reading" : " readings");
        if (percentWithinTarget == null) return base;
        return base + " • " + Math.round(percentWithinTarget) + "% within target";
    }

    // Returns null when no threshold is configured for this parameter,
    // meaning it can't be assessed. Compliance always uses the wider
    // ACCEPTABLE/ALERT threshold (minPH/maxPH, minEC/maxEC, highAirTemp,
    // highHumidity, highWaterTemp, minWaterLevel/maxWaterLevel) - never the
    // narrower dosing correction targets (phTargetMin/Max, ecTargetMin/Max)
    // or the actuator-release/control hysteresis values (airTempRelease,
    // humidityRelease, coolerOffTemp, refillStartLevelCm/refillStopLevelCm),
    // which describe when automation kicks in/out, not what counts as an
    // acceptable reading for this report.
    private Boolean isWithinTarget(String canonicalParameter, float value) {
        Float min = configuredRangeMin(canonicalParameter);
        Float max = configuredRangeMax(canonicalParameter);

        // Nothing configured on either side means compliance cannot be judged.
        if (min == null && max == null) return null;

        // Inclusive at both bounds, and delegated to the same helper the chart
        // colouring uses, so the compliance figure and the red segments can
        // never disagree about what "out of range" means.
        return !ChartRangeSegmenter.isOutOfRange(value, min, max);
    }

    // The farmer-facing range label. Every parameter now has a canonical target
    // range, so this reads the same values the chart draws and the compliance
    // figure uses - the summary can no longer say "under 28" while the chart
    // shows 20-28. Control/hysteresis values are still shown, but described as
    // when equipment switches rather than presented as bounds.
    /**
     * The bare "Target range: X - Y" (or single-bound / unconfigured)
     * phrasing, with no parameter-specific qualifier appended. Exports use
     * this alone - the correction-target/hysteresis notes below are useful
     * context for a farmer actively tuning dosing on screen, but read as
     * noise in a printed report's metadata, which only needs the actual
     * reporting range.
     */
    private String coreTargetRangeText(Float min, Float max, String unit, int decimals) {
        if (min == null && max == null) {
            return "No configured target range for this parameter.";
        }
        final String number = "%." + decimals + "f";
        if (min != null && max != null) {
            return String.format(Locale.getDefault(), "Target range: " + number + " – " + number + "%s", min, max, unit);
        } else if (max != null) {
            return String.format(Locale.getDefault(), "Upper limit: " + number + "%s", max, unit);
        } else {
            return String.format(Locale.getDefault(), "Lower limit: " + number + "%s", min, unit);
        }
    }

    private String getTargetRangeText(String canonicalParameter) {
        Float min = configuredRangeMin(canonicalParameter);
        Float max = configuredRangeMax(canonicalParameter);
        String text = coreTargetRangeText(min, max, getUnitForParameter(canonicalParameter),
                markerDecimalsForParameter(canonicalParameter));
        if (min == null && max == null) return text;

        if (canonicalParameter.equalsIgnoreCase("pH")
                && phTargetMinThreshold != null && phTargetMaxThreshold != null) {
            text += String.format(Locale.getDefault(),
                    " (correction target: %.2f \u2013 %.2f)", phTargetMinThreshold, phTargetMaxThreshold);
        } else if (canonicalParameter.equalsIgnoreCase("EC")
                && ecTargetMinThreshold != null && ecTargetMaxThreshold != null) {
            text += String.format(Locale.getDefault(),
                    " (correction target: %.2f \u2013 %.2f mS/cm)", ecTargetMinThreshold, ecTargetMaxThreshold);
        } else if (canonicalParameter.equalsIgnoreCase("Air Temperature")
                && highAirTempThreshold != null && airTempReleaseThreshold != null) {
            text += String.format(Locale.getDefault(),
                    " (fan runs above %.1f\u00B0C, off again at %.1f\u00B0C)",
                    highAirTempThreshold, airTempReleaseThreshold);
        } else if (canonicalParameter.equalsIgnoreCase("Humidity")
                && highHumidityThreshold != null && humidityReleaseThreshold != null) {
            text += String.format(Locale.getDefault(),
                    " (fan runs above %.1f%%, off again at %.1f%%)",
                    highHumidityThreshold, humidityReleaseThreshold);
        } else if (canonicalParameter.equalsIgnoreCase("Water Temperature")
                && highWaterTempThreshold != null && coolerOffTempThreshold != null) {
            text += String.format(Locale.getDefault(),
                    " (cooling runs above %.1f\u00B0C, off again at %.1f\u00B0C)",
                    highWaterTempThreshold, coolerOffTempThreshold);
        } else if (canonicalParameter.equalsIgnoreCase("Water Level")
                && refillStartThresholdCm != null && refillStopThresholdCm != null) {
            text += String.format(Locale.getDefault(),
                    " (refill starts at %.1f cm, fills to %.1f cm)",
                    refillStartThresholdCm, refillStopThresholdCm);
        }

        return text;
    }

    // Plain-language interpretation shown on screen under "What This Means"
    // and printed verbatim as the PDF's "Interpretation" section - both read
    // this one method through ParameterInsight, so the two can never drift.
    //
    // Each sentence follows the same shape: what happened, why it matters to
    // the basil, and a soft warning about what may follow if it continues.
    // Wording stays deliberately non-technical (no "configured limit",
    // "root-zone", "regulation release", "correction target"); the exact
    // numbers still appear in the factual settings/details area instead.
    //
    // Claims are kept hedged ("helps", "may", "can", "if this continues") so
    // the report never diagnoses the plant or promises an outcome. The
    // one-sided parameters (air temperature, humidity, water temperature,
    // water level) describe staying on the correct side of the set limit,
    // which is genuinely all the underlying STABLE check establishes.
    private String buildInterpretation(String canonicalParameter, String status) {
        if (canonicalParameter.equalsIgnoreCase("pH")) {
            switch (status) {
                case "STABLE": return "The water's pH stayed in the recommended range most of the time, which helps the basil take in nutrients properly and supports steady growth.";
                case "NEEDS ATTENTION": return "The water's pH went outside the recommended range several times. If this continues, the basil may have more difficulty taking in nutrients, which can affect its growth.";
                default: return "No pH range has been set for this device yet, so the readings cannot be compared against a recommended range.";
            }
        }
        if (canonicalParameter.equalsIgnoreCase("EC")) {
            switch (status) {
                case "STABLE": return "The nutrient strength stayed in the recommended range most of the time, helping the basil receive a balanced amount of nutrients for healthy growth.";
                case "NEEDS ATTENTION": return "The nutrient strength went outside the recommended range several times. If this continues, the basil may receive too little or too much nutrient, which can affect its growth.";
                default: return "No nutrient strength range has been set for this device yet, so the readings cannot be compared against a recommended range.";
            }
        }
        if (canonicalParameter.equalsIgnoreCase("Air Temperature")) {
            switch (status) {
                case "STABLE": return "The air temperature stayed below the set limit most of the time, helping keep the growing area comfortable for the basil.";
                case "NEEDS ATTENTION": return "The air temperature went above the set limit several times. If this continues, the basil may become stressed by the heat and growth may slow down.";
                default: return "No air temperature limit has been set for this device yet, so the readings cannot be compared against a limit.";
            }
        }
        if (canonicalParameter.equalsIgnoreCase("Humidity")) {
            switch (status) {
                case "STABLE": return "Humidity stayed below the set limit most of the time, helping keep the growing area from becoming too damp for the basil.";
                case "NEEDS ATTENTION": return "Humidity went above the set limit several times. If this continues, the growing area may stay too damp, which can make conditions less suitable for the basil.";
                default: return "No humidity limit has been set for this device yet, so the readings cannot be compared against a limit.";
            }
        }
        if (canonicalParameter.equalsIgnoreCase("Water Temperature")) {
            switch (status) {
                case "STABLE": return "The reservoir water stayed below the set temperature limit most of the time, helping keep the mist delivered to the basil at a comfortable temperature.";
                case "NEEDS ATTENTION": return "The reservoir water became too warm several times before being turned into mist. If this continues, the fog reaching the basil may run warmer than ideal, which can place extra stress on the plant.";
                default: return "No water temperature limit has been set for this device yet, so the readings cannot be compared against a limit.";
            }
        }
        if (canonicalParameter.equalsIgnoreCase("Water Level")) {
            switch (status) {
                case "STABLE": return "The reservoir had enough water for most of this period, helping the system continue delivering mist to the basil roots.";
                case "NEEDS ATTENTION": return "The reservoir dropped below the refill level several times. If this continues, there may not be enough water available for consistent misting to the basil roots.";
                default: return "No refill level has been set for this device yet, so the readings cannot be compared against a refill point.";
            }
        }
        return "No target has been set for this reading yet, so it can only be shown as a trend for now.";
    }

    // ------------------------------------------------------------------
    // Export (Part 10-12): reuses the exact filter/data that produced the
    // screen currently being shown - never recomputed independently.
    // ------------------------------------------------------------------

    /**
     * Export → choose PDF (single-page analytical summary) or Excel
     * (comprehensive workbook) → choose all six parameters or a subset.
     * Both formats and every parameter draw from the same currentFilter/
     * currentParameterSamples state the screen is already showing - see
     * buildExportBundles(). CSV export was retired in favor of XLSX, which
     * covers the same "every reading" need without losing the ability to
     * format/chart it.
     */
    private void showExportOptions() {
        if (currentFilter == null) {
            Toast.makeText(getContext(), "Load a report before exporting.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] formatOptions = {"PDF (Summary Report)", "Excel (Comprehensive Data)"};
        NotificationHelper.showSelectionDialog(requireContext(), "Export Report", formatOptions, formatIndex -> {
            boolean isPdf = formatIndex == 0;
            showParameterScopeDialog(isPdf);
        });
    }

    private void showParameterScopeDialog(boolean isPdf) {
        String[] scopeOptions = {"All Parameters", "Select Parameters..."};
        NotificationHelper.showSelectionDialog(requireContext(), "Choose Parameters", scopeOptions, scopeIndex -> {
            if (scopeIndex == 0) {
                performExport(isPdf, Arrays.asList(EXPORTABLE_PARAMETERS));
            } else {
                showMultiSelectParametersDialog(isPdf);
            }
        });
    }

    private void showMultiSelectParametersDialog(boolean isPdf) {
        if (getContext() == null) return;
        boolean[] checked = new boolean[EXPORTABLE_PARAMETERS.length];
        Arrays.fill(checked, true);
        new AlertDialog.Builder(requireContext())
                .setTitle("Select Parameters")
                .setMultiChoiceItems(EXPORTABLE_PARAMETERS, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Export", (dialog, which) -> {
                    List<String> selected = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) selected.add(EXPORTABLE_PARAMETERS[i]);
                    }
                    if (selected.isEmpty()) {
                        Toast.makeText(getContext(), "Select at least one parameter.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    performExport(isPdf, selected);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Builds one ParameterExportBundle per requested parameter from
     * currentParameterSamples/configuredRangeMin/Max/computeInsight - the
     * exact same per-parameter machinery the on-screen chart, stats and
     * insight card already use for whichever single parameter happens to be
     * charted, just run for every selected parameter instead of one. A
     * parameter with no readings in this period still gets a bundle (status
     * "INSUFFICIENT DATA") rather than being silently dropped, since the
     * farmer explicitly chose to include it.
     */
    private List<ParameterExportBundle> buildExportBundles(List<String> canonicalParameters) {
        List<ParameterExportBundle> bundles = new ArrayList<>();
        for (String canonical : canonicalParameters) {
            List<ChartAggregation.Sample> samples = currentParameterSamples.get(canonical);
            if (samples == null) samples = new ArrayList<>();

            List<Float> values = new ArrayList<>(samples.size());
            float sum = 0f;
            float high = Float.NEGATIVE_INFINITY;
            float low = Float.POSITIVE_INFINITY;
            for (ChartAggregation.Sample s : samples) {
                values.add(s.value);
                sum += s.value;
                if (s.value > high) high = s.value;
                if (s.value < low) low = s.value;
            }
            float avg = values.isEmpty() ? 0f : sum / values.size();
            if (values.isEmpty()) {
                high = 0f;
                low = 0f;
            }

            Float rangeMin = configuredRangeMin(canonical);
            Float rangeMax = configuredRangeMax(canonical);
            ParameterInsight insight = computeInsight(canonical, values);
            String exportTargetRangeText = coreTargetRangeText(rangeMin, rangeMax,
                    getUnitForParameter(canonical), markerDecimalsForParameter(canonical));

            bundles.add(new ParameterExportBundle(canonical, canonical, getUnitForParameter(canonical),
                    markerDecimalsForParameter(canonical), rangeMin, rangeMax, exportTargetRangeText,
                    avg, high, low, values.size(), insight.status, insight.interpretation, samples));
        }
        return bundles;
    }

    private void performExport(boolean isPdf, List<String> selectedParameters) {
        if (!isAdded() || getContext() == null) return;
        if (currentFilter == null) {
            Toast.makeText(getContext(), "Load a report before exporting.", Toast.LENGTH_SHORT).show();
            return;
        }

        final ParameterReportFilter filter = currentFilter;

        // Same one-frame-deferred pattern the old export used: showing the
        // overlay and immediately doing the (now heavier, multi-parameter)
        // generation work in the same call never actually lets it paint
        // first.
        showLayoutLoading();
        View root = getView();
        if (root == null) {
            hideLayoutLoading();
            return;
        }
        root.post(() -> {
            if (!isAdded() || getContext() == null) {
                hideLayoutLoading();
                return;
            }
            try {
                List<ParameterExportBundle> bundles = buildExportBundles(selectedParameters);
                if (bundles.isEmpty()) {
                    NotificationHelper.showError(getContext(), "No data available to export.");
                    return;
                }

                String userName = "Basilience User";
                File file;
                String mimeType;
                if (isPdf) {
                    CycleReportGenerator generator = new CycleReportGenerator(requireContext());
                    file = generator.generateMultiParameterSensorReportPdf(filter, bundles, userName);
                    mimeType = "application/pdf";
                } else {
                    ExcelReportGenerator generator = new ExcelReportGenerator(requireContext());
                    file = generator.generateSensorReportXlsx(filter, bundles, userName);
                    mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                }

                Uri contentUri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", file);
                // ACTION_SEND, not ACTION_VIEW - a "share sheet" is meant to
                // hand the file to another app (Drive, email, Messenger,
                // etc.), not just open it in a viewer.
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType(mimeType);
                intent.putExtra(Intent.EXTRA_SUBJECT, "Basilience Parameter Report - " + filter.cycleLabel);
                intent.putExtra(Intent.EXTRA_TEXT, "Attached is the parameter report for "
                        + filter.cycleLabel + " (" + filter.periodLabel + ").");
                intent.putExtra(Intent.EXTRA_STREAM, contentUri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, "Export Report via:"));
            } catch (IOException e) {
                Log.e("EXPORT_ERROR", "Error generating report", e);
                NotificationHelper.showError(getContext(), "We couldn't generate the report. Please try again.");
            } finally {
                hideLayoutLoading();
            }
        });
    }

    // ------------------------------------------------------------------
    // Canonical parameter mapping (preserved from the earlier pH/EC fix)
    // ------------------------------------------------------------------

    // Single canonical mapping from the spinner's user-facing display label
    // (R.array.parameters_array, e.g. "pH Level") to the internal canonical
    // parameter key every other lookup in this fragment keys off of (field
    // name, unit, validity range, insight thresholds). All parameter
    // resolution must go through this one place instead of re-matching
    // spinner label strings, since the display labels and canonical keys
    // are not always identical (pH Level -> pH, EC Level -> EC).
    private String canonicalParameterKey(String displayLabel) {
        if (displayLabel == null) return null;
        if (displayLabel.equalsIgnoreCase("pH Level") || displayLabel.equalsIgnoreCase("pH")) return "pH";
        if (displayLabel.equalsIgnoreCase("EC Level") || displayLabel.equalsIgnoreCase("EC")) return "EC";
        if (displayLabel.equalsIgnoreCase("Air Temperature")) return "Air Temperature";
        if (displayLabel.equalsIgnoreCase("Humidity")) return "Humidity";
        if (displayLabel.equalsIgnoreCase("Water Temperature")) return "Water Temperature";
        if (displayLabel.equalsIgnoreCase("Water Level")) return "Water Level";
        return null;
    }

    private String getFieldNameFromParameter(String canonicalParameter) {
        if (canonicalParameter.equalsIgnoreCase("Air Temperature")) return "air_temp";
        if (canonicalParameter.equalsIgnoreCase("Humidity")) return "humidity";
        if (canonicalParameter.equalsIgnoreCase("Water Temperature")) return "water_temp";
        if (canonicalParameter.equalsIgnoreCase("Water Level")) return "water_level";
        if (canonicalParameter.equalsIgnoreCase("pH")) return "ph";
        if (canonicalParameter.equalsIgnoreCase("EC")) return "ec";
        return null;
    }

    private boolean isValidParameterValue(String parameter, Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) return false;
        if (parameter.equalsIgnoreCase("pH")) return value >= 0 && value <= 14;
        if (parameter.equalsIgnoreCase("EC")) return value >= 0;
        if (parameter.equalsIgnoreCase("Air Temperature")) return value >= -40 && value <= 80;
        if (parameter.equalsIgnoreCase("Humidity")) return value >= 0 && value <= 100;
        if (parameter.equalsIgnoreCase("Water Temperature")) {
            return value >= -55 && value <= 125 && Math.abs(value + 127) > 0.0001;
        }
        if (parameter.equalsIgnoreCase("Water Level")) return value >= 0 && value <= 100;
        return false;
    }

    /**
     * Fills the combined "Readings Table" - every parameter's value at each
     * logged moment, side by side - straight from the same document
     * snapshots the selected parameter's chart already loaded, so no second
     * query is needed. Independent of which parameter is currently charted;
     * reuses the same configuredRangeMin/Max and marker formatting the chart
     * and CSV/PDF exports already use, so a cell's value and status can
     * never disagree with the chart above it.
     */
    private void populateReadingsTable(QuerySnapshot snapshot) {
        if (readingsTableAdapter == null) return;

        Float phMin = configuredRangeMin("pH"), phMax = configuredRangeMax("pH");
        Float ecMin = configuredRangeMin("EC"), ecMax = configuredRangeMax("EC");
        Float airMin = configuredRangeMin("Air Temperature"), airMax = configuredRangeMax("Air Temperature");
        Float humMin = configuredRangeMin("Humidity"), humMax = configuredRangeMax("Humidity");
        Float waterTempMin = configuredRangeMin("Water Temperature"), waterTempMax = configuredRangeMax("Water Temperature");
        Float waterLevelMin = configuredRangeMin("Water Level"), waterLevelMax = configuredRangeMax("Water Level");

        readingsTableRows.clear();
        for (String key : EXPORTABLE_PARAMETERS) {
            currentParameterSamples.put(key, new ArrayList<>());
        }
        for (QueryDocumentSnapshot doc : snapshot) {
            Long timestamp = doc.getLong("timestamp");
            if (timestamp == null) continue;

            Double ph = doc.getDouble("ph");
            Double ec = doc.getDouble("ec");
            Double airTemp = doc.getDouble("air_temp");
            Double humidity = doc.getDouble("humidity");
            Double waterTemp = doc.getDouble("water_temp");
            Double waterLevel = doc.getDouble("water_level");

            addSampleIfValid("pH", timestamp, ph);
            addSampleIfValid("EC", timestamp, ec);
            addSampleIfValid("Air Temperature", timestamp, airTemp);
            addSampleIfValid("Humidity", timestamp, humidity);
            addSampleIfValid("Water Temperature", timestamp, waterTemp);
            addSampleIfValid("Water Level", timestamp, waterLevel);

            readingsTableRows.add(new ParameterTableRow(
                    DateUtils.formatDateTimeCompact(timestamp),
                    formatTableCell(ph, "pH"), cellOutOfRange(ph, "pH", phMin, phMax),
                    formatTableCell(ec, "EC"), cellOutOfRange(ec, "EC", ecMin, ecMax),
                    formatTableCell(airTemp, "Air Temperature"), cellOutOfRange(airTemp, "Air Temperature", airMin, airMax),
                    formatTableCell(humidity, "Humidity"), cellOutOfRange(humidity, "Humidity", humMin, humMax),
                    formatTableCell(waterTemp, "Water Temperature"), cellOutOfRange(waterTemp, "Water Temperature", waterTempMin, waterTempMax),
                    formatTableCell(waterLevel, "Water Level"), cellOutOfRange(waterLevel, "Water Level", waterLevelMin, waterLevelMax)));
        }
        readingsTableAdapter.notifyDataSetChanged();

        if (recyclerReadingsTable != null) {
            recyclerReadingsTable.setVisibility(readingsTableRows.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (tvReadingsTableEmpty != null) {
            tvReadingsTableEmpty.setVisibility(readingsTableRows.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void addSampleIfValid(String canonicalParameter, long timestamp, Double value) {
        if (!isValidParameterValue(canonicalParameter, value)) return;
        List<ChartAggregation.Sample> list = currentParameterSamples.get(canonicalParameter);
        if (list != null) list.add(new ChartAggregation.Sample(timestamp, value.floatValue()));
    }

    private String formatTableCell(Double value, String canonicalParameter) {
        if (!isValidParameterValue(canonicalParameter, value)) return "--";
        int decimals = markerDecimalsForParameter(canonicalParameter);
        return String.format(Locale.getDefault(), "%." + decimals + "f%s", value, markerUnitForParameter(canonicalParameter));
    }

    private boolean cellOutOfRange(Double value, String canonicalParameter, Float min, Float max) {
        if (!isValidParameterValue(canonicalParameter, value)) return false;
        return (min != null && value < min) || (max != null && value > max);
    }

    private String getUnitForParameter(String parameter) {
        if (parameter.contains("Temperature")) return "°C";
        if (parameter.equalsIgnoreCase("Humidity") || parameter.equalsIgnoreCase("Water Level")) return "%";
        if (parameter.equalsIgnoreCase("EC")) return " mS/cm";
        return "";
    }

    // Renders a metric-strip value with its unit shrunk to roughly 55% of
    // the numeric size. The long "mS/cm" EC unit used to wrap onto a second
    // line at the width of one metric column; at the reduced size the whole
    // string fits on one line while the number stays the emphasised part.
    // Formatting of the number itself is unchanged (%.1f, same as before).
    private CharSequence formatMetric(float value, String unit) {
        String number = String.format(Locale.getDefault(), "%.1f", value);
        if (unit.isEmpty()) return number;
        SpannableString styled = new SpannableString(number + unit);
        styled.setSpan(new RelativeSizeSpan(0.55f), number.length(), styled.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }

    // Display-only variants used solely by the chart tap marker. They wrap
    // getUnitForParameter above rather than replacing it, so the units that
    // reach the CSV/PDF exports and the metric strip stay exactly as they
    // were - the marker just needs a readable standalone label ("6.10 pH"
    // rather than a bare "6.10") and pH/EC's finer precision.
    private String markerUnitForParameter(String canonicalParameter) {
        String unit = getUnitForParameter(canonicalParameter);
        if (unit.isEmpty() && canonicalParameter.equalsIgnoreCase("pH")) return " pH";
        return unit;
    }

    private int markerDecimalsForParameter(String canonicalParameter) {
        return (canonicalParameter.equalsIgnoreCase("pH") || canonicalParameter.equalsIgnoreCase("EC"))
                ? 2 : 1;
    }

    // ------------------------------------------------------------------
    // Empty states, role handling, info dialog
    // ------------------------------------------------------------------

    private void fetchUserInfo() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid != null) {
            dbHelper.getUserProfile(uid).addOnSuccessListener(documentSnapshot -> {
                if (documentSnapshot.exists()) {
                    userRole = documentSnapshot.getString("role");
                    updateUIForRole();
                }
            });
        }
    }

    private void updateUIForRole() {
        if (btnShare != null) {
            btnShare.setVisibility(RoleConstants.ROLE_ADMIN.equalsIgnoreCase(userRole) ? View.VISIBLE : View.GONE);
        }
    }

    private void showEmptyReportState(String insightMessage) {
        lineChart.getAxisLeft().removeAllLimitLines();
        lineChart.setThresholdBands(null);
        lineChart.clear();
        lineChart.invalidate();
        // The combined readings table is independent of the currently
        // charted parameter (see populateReadingsTable()'s own comment) -
        // this empty state means only the SELECTED parameter had no valid
        // entries, so the table (already populated earlier in this same
        // renderReport() call, if this was reached from there) is left as
        // is rather than being wiped here too.
        tvAverage.setText("--");
        tvHigh.setText("--");
        tvLow.setText("--");
        if (tvEffectiveRange != null) {
            if (currentFilter != null) {
                tvEffectiveRange.setText("Showing " + DateUtils.formatDate(currentFilter.effectiveStartMs)
                        + " to " + DateUtils.formatDate(currentFilter.effectiveEndMs));
            } else {
                tvEffectiveRange.setText("");
            }
        }
        if (tvInterpretation != null) {
            // The insight hero is a single grouped surface now, so with
            // nothing to show inside it, hide the whole card rather than
            // leaving an empty tinted box with a stray divider.
            if (cardInsightHero != null) cardInsightHero.setVisibility(View.GONE);
            tvInsightStatus.setVisibility(View.GONE);
            if (dotInsightStatus != null) dotInsightStatus.setVisibility(View.GONE);
            if (tvTargetRange != null) tvTargetRange.setText("");
            if (tvEvidence != null) tvEvidence.setText("");
            tvInterpretation.setText(insightMessage);
            tvInterpretation.setTextColor(ContextCompat.getColor(tvInterpretation.getContext(), R.color.nav_inactive));
        }
    }

    private void showInfoDialog() {
        if (getContext() == null) return;
        // Same guide content as before, now structured into heading/body
        // pairs so the dialog can render it left-aligned and scrollable.
        String[][] sections = {
                {"What does this page do?",
                        "This page shows what conditions (Temperature, Humidity, pH, EC, and more) your basil actually experienced during a specific cultivation cycle."},
                {"Why is this useful?",
                        "Instead of raw sensor numbers, you get a plain-language summary of whether conditions stayed on target for that cycle, helping you decide what to adjust next time."},
                {"How to use it?",
                        "• Pick a cultivation cycle from the 'Cultivation Cycle' dropdown - both in-progress and completed cycles are available.\n"
                                + "• Tap the 'Parameter' dropdown to pick what you want to check (like pH or Water Level).\n"
                                + "• Use the period filter (Entire, Today, 7D, 30D, Custom) to narrow the range - it always stays inside the selected cycle's dates.\n"
                                + "• Read the insight summary for a plain-language take on how that parameter behaved.\n"
                                + "• Tap any point on the trend chart to see that exact reading and when it was recorded.\n"
                                + "• The Readings Table below the chart lists every logged reading for all six parameters side by side.\n"
                                + "• Use the share icon to export: PDF for a summarized report, or Excel for a comprehensive workbook with every raw reading - either can include all parameters or just the ones you select."}
        };
        NotificationHelper.showGuideDialog(requireContext(), "How to use Parameter Reports",
                sections, "Got it");
    }
}
