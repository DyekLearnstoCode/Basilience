package com.example.basilience;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.os.Environment;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import com.google.firebase.Timestamp;
import com.example.basilience.models.FoggingReportFilter;
import com.example.basilience.models.FoggingReportTotals;
import com.example.basilience.models.FoggingSession;
import com.example.basilience.models.ParameterReportFilter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CycleReportGenerator {

    private final Context context;
    private static final int PAGE_WIDTH = 595; // A4 width in points
    private static final int PAGE_HEIGHT = 842; // A4 height in points
    private static final int MARGIN = 50;
    private static final int ROWS_PER_PAGE = 22;

    // ------------------------------------------------------------------
    // Parameter Report print styling.
    //
    // Contrast: body copy is a dark gray that stays legible on a phone,
    // zoomed out, and in print. Only genuinely secondary metadata uses the
    // lighter tone - nothing that carries report content does.
    // ------------------------------------------------------------------
    private static final int PDF_BRAND = Color.parseColor("#116F59");   // Basilience green
    private static final int PDF_HEADING = Color.parseColor("#0B3D33"); // near-black green
    private static final int PDF_BODY = Color.parseColor("#333333");    // dark gray
    private static final int PDF_META = Color.parseColor("#6B6B6B");    // medium gray
    private static final int PDF_RULE = Color.parseColor("#C8C8C8");

    private static final float PDF_SIZE_BRAND = 19f;
    private static final float PDF_SIZE_REPORT_TITLE = 16f;
    private static final float PDF_SIZE_PARAM_TITLE = 16f;
    private static final float PDF_SIZE_SECTION = 13f;
    private static final float PDF_SIZE_BODY = 11f;
    private static final float PDF_SIZE_META = 10f;

    private static final int PDF_GAP_SECTION = 20;
    private static final int PDF_GAP_AFTER_HEADING = 7;
    private static final int PDF_FOOTER_SAFE_Y = PAGE_HEIGHT - 60;

    // Display order/labels for the persisted fogging strategies, matching
    // what the Fogging Report screen lists.
    private static final String[] FOGGING_STRATEGY_ORDER = {"normal", "startup", "hot", "cold"};
    private static final String[] FOGGING_STRATEGY_LABELS = {"Normal", "Startup", "Hot", "Cold"};

    public CycleReportGenerator(Context context) {
        this.context = context;
    }

    public File generateCycleSummaryPdf(String deviceId, Cycle cycle, Bitmap chartBitmap, List<Harvest> harvestHistory, String userName) throws IOException {
        PdfDocument document = new PdfDocument();
        int pageNumber = 1;

        // --- PAGE 1: SUMMARY & CHART ---
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber++).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();
        Paint paint = new Paint();

        int x = MARGIN;
        int contentWidth = PAGE_WIDTH - (2 * MARGIN);
        String cycleStatus = cycle.getStatus() != null ? cycle.getStatus().toUpperCase() : "ACTIVE";
        boolean cycleCompleted = "COMPLETED".equalsIgnoreCase(cycleStatus);

        // y is the TOP of the next block throughout; every helper returns the
        // next free top, so no block can land on top of another and no paint
        // state leaks between sections.
        int y = drawModernReportHeader(canvas, paint, x, 50, "Harvest Report", userName, cycleStatus);

        // --- CYCLE DETAILS ---
        applyBodyPaint(paint);
        y = drawTextBlock(canvas, paint, "Cycle: Cycle #" + cycle.getCycleNumber(), x, y);
        y = drawTextBlock(canvas, paint, "Status: " + cycleStatus, x, y);
        y = drawTextBlock(canvas, paint, "Cycle Range: " + DateUtils.formatDate(cycle.getStartDate())
                + " - " + (cycleCompleted ? DateUtils.formatDate(cycle.getEndDate()) : "Present"), x, y);
        if (!cycleCompleted) {
            y = drawTextBlock(canvas, paint, "Harvest Frequency: Every "
                    + cycle.getHarvestFrequencyDays() + " Days", x, y);
        }
        y += PDF_GAP_SECTION;

        // --- PRODUCTION SUMMARY ---
        // Printed straight from the cycle document's transactionally
        // maintained totals - never re-derived by summing harvest records.
        y = drawSectionHeading(canvas, paint, "Production Summary", x, y);
        applyBodyPaint(paint);
        y = drawTextBlock(canvas, paint, "Total Harvested: "
                + HarvestFormatter.formatWeight(cycle.getTotalHarvestWeight()), x, y);
        y = drawTextBlock(canvas, paint, "Harvest Entries: " + cycle.getTotalHarvestCount(), x, y);
        y += PDF_GAP_SECTION;

        // --- CHART ---
        // The chart keeps a generous fixed size: harvest history flows onto
        // its own pages below, so nothing here needs to be squeezed to fit.
        if (chartBitmap != null) {
            y = drawSectionHeading(canvas, paint, "Accumulated Harvest", x, y);

            int targetWidth = contentWidth;
            float aspectRatio = (float) chartBitmap.getWidth() / chartBitmap.getHeight();
            int targetHeight = (int) (targetWidth / aspectRatio);
            int maxHeight = 250;
            if (targetHeight > maxHeight) {
                targetHeight = maxHeight;
                targetWidth = (int) (targetHeight * aspectRatio);
            }
            Rect destRect = new Rect(x, y, x + targetWidth, y + targetHeight);
            canvas.drawBitmap(chartBitmap, null, destRect, paint);
            y += targetHeight + PDF_GAP_SECTION;
        }

        // --- INTERPRETATION ---
        // Same helper the screen renders, so the two always read alike.
        y = drawSectionHeading(canvas, paint, "What This Means", x, y);
        drawWrappedBlock(canvas, HarvestFormatter.buildProductionSummary(cycleStatus,
                        cycle.getTotalHarvestWeight(), cycle.getTotalHarvestCount()),
                x, y, contentWidth, PDF_SIZE_BODY, PDF_BODY);

        drawModernReportFooter(canvas, paint, pageNumber - 1);
        document.finishPage(page);

        // --- PAGE 2+ : HARVEST HISTORY TABLE ---
        if (harvestHistory != null && !harvestHistory.isEmpty()) {
            int currentHarvestIndex = 0;
            while (currentHarvestIndex < harvestHistory.size()) {
                pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber++).create();
                page = document.startPage(pageInfo);
                canvas = page.getCanvas();

                y = drawModernReportHeader(canvas, paint, x, 50, "Harvest Report", userName, cycleStatus);

                paint.setColor(PDF_HEADING);
                paint.setTextSize(PDF_SIZE_PARAM_TITLE);
                paint.setFakeBoldText(true);
                y = drawTextBlock(canvas, paint, "Harvest History", x, y);
                y += 14;

                paint.setTextSize(PDF_SIZE_BODY);
                paint.setColor(PDF_BRAND);
                canvas.drawRect(x, y, PAGE_WIDTH - MARGIN, y + 22, paint);
                paint.setColor(Color.WHITE);
                paint.setFakeBoldText(true);
                Paint.FontMetrics headerFm = paint.getFontMetrics();
                float headerBaseline = y + 11 - (headerFm.ascent + headerFm.descent) / 2f;
                canvas.drawText("Date", x + 5, headerBaseline, paint);
                canvas.drawText("Weight", x + 130, headerBaseline, paint);
                canvas.drawText("Source", x + 220, headerBaseline, paint);
                canvas.drawText("Recorded By", x + 310, headerBaseline, paint);
                y += 22;

                paint.setFakeBoldText(false);
                int count = 0;
                int startY = y - 22;
                while (count < ROWS_PER_PAGE && currentHarvestIndex < harvestHistory.size()) {
                    Harvest h = harvestHistory.get(currentHarvestIndex);

                    // A note adds a wrapped second line, so each row's height
                    // is measured rather than assumed.
                    String note = h.getNotes() != null ? h.getNotes().trim() : "";
                    int noteHeight = note.isEmpty() ? 0
                            : measureWrappedHeight(note, contentWidth - 10, PDF_SIZE_META) + 3;
                    int rowHeight = 22 + noteHeight;

                    // Rows are no longer a fixed height, so the page bound is
                    // checked rather than assumed: a long note pushes the
                    // remaining records onto the next page instead of running
                    // over the footer. Always allow at least one row so an
                    // unusually long note can never stall the loop.
                    if (count > 0 && y + rowHeight > PDF_FOOTER_SAFE_Y) {
                        break;
                    }

                    if (count % 2 == 0) {
                        paint.setColor(Color.parseColor("#F5F5F5"));
                        canvas.drawRect(x, y, PAGE_WIDTH - MARGIN, y + rowHeight, paint);
                    }

                    paint.setColor(PDF_BODY);
                    paint.setTextSize(PDF_SIZE_BODY);
                    Paint.FontMetrics rowFm = paint.getFontMetrics();
                    float rowBaseline = y + 11 - (rowFm.ascent + rowFm.descent) / 2f;
                    canvas.drawText(DateUtils.formatDate(h.getHarvestDate()), x + 5, rowBaseline, paint);
                    canvas.drawText(HarvestFormatter.formatWeight(h.getWeight()), x + 130, rowBaseline, paint);
                    canvas.drawText(h.getSource() != null && !h.getSource().isEmpty()
                            ? h.getSource() : "Unknown", x + 220, rowBaseline, paint);
                    canvas.drawText(h.getRecordedByName() != null && !h.getRecordedByName().isEmpty()
                            ? h.getRecordedByName() : "Unknown", x + 310, rowBaseline, paint);

                    if (!note.isEmpty()) {
                        drawWrappedBlock(canvas, note, x + 5, y + 22, contentWidth - 10,
                                PDF_SIZE_META, PDF_META);
                    }

                    y += rowHeight;
                    paint.setColor(PDF_RULE);
                    paint.setStrokeWidth(0.5f);
                    canvas.drawLine(x, y, PAGE_WIDTH - MARGIN, y, paint);

                    currentHarvestIndex++;
                    count++;
                }

                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(PDF_RULE);
                paint.setStrokeWidth(1f);
                canvas.drawRect(x, startY, PAGE_WIDTH - MARGIN, y, paint);
                paint.setStyle(Paint.Style.FILL);

                drawModernReportFooter(canvas, paint, pageNumber - 1);
                document.finishPage(page);
            }
        }

        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir != null && !dir.exists()) dir.mkdirs();

        // Includes deviceId and second-precision time alongside cycleNumber so
        // that exporting the same day-numbered cycle for two different
        // devices (or exporting the same cycle twice in one day) can never
        // silently overwrite a previous report.
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "Basilience_Report_" + sanitizeForFilename(deviceId) + "_Cycle" + cycle.getCycleNumber() + "_" + timeStamp + ".pdf";
        File file = new File(dir, fileName);

        FileOutputStream fos = new FileOutputStream(file);
        document.writeTo(fos);
        document.close();
        fos.close();

        return file;
    }

    public File generateSensorReportPdf(ParameterReportFilter filter, Bitmap chartBitmap,
                                         List<com.github.mikephil.charting.data.Entry> entries,
                                         float avg, float high, float low, String unit,
                                         String status, String targetRangeText, String evidenceText,
                                         String interpretation, String userName) throws IOException {
        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();
        Paint paint = new Paint();

        int x = MARGIN;
        int contentWidth = PAGE_WIDTH - (2 * MARGIN);

        // y is the TOP of the next block throughout; every helper returns the
        // next free top, so no block can land on top of another.
        int y = drawModernReportHeader(canvas, paint, x, 50, "Parameter Report", userName, filter.cycleStatus);

        // --- PARAMETER TITLE ---
        paint.setColor(PDF_HEADING);
        paint.setTextSize(PDF_SIZE_PARAM_TITLE);
        paint.setFakeBoldText(true);
        y = drawTextBlock(canvas, paint, filter.displayParameter + " Report", x, y);
        y += 14;

        // --- DEVICE / CYCLE DETAILS ---
        applyBodyPaint(paint);
        y = drawTextBlock(canvas, paint, "Device: " + filter.deviceId, x, y);
        y = drawTextBlock(canvas, paint, "Cycle: " + filter.cycleLabel, x, y);
        y = drawTextBlock(canvas, paint, "Cycle Status: " + filter.cycleStatus, x, y);
        y = drawTextBlock(canvas, paint, "Cycle Range: " + DateUtils.formatDate(filter.cycleStartMs) + " - "
                + ("COMPLETED".equalsIgnoreCase(filter.cycleStatus)
                    ? DateUtils.formatDate(filter.cycleEndMs) : "Present"), x, y);
        y = drawTextBlock(canvas, paint, "Report Period: " + filter.periodLabel + " ("
                + DateUtils.formatDate(filter.effectiveStartMs) + " - "
                + DateUtils.formatDate(filter.effectiveEndMs) + ")", x, y);
        y += PDF_GAP_SECTION;

        // --- SETTINGS USED ---
        // targetRangeText already carries its own label ("Acceptable range:",
        // "Upper limit:", "Low-water threshold:") matching what's shown on
        // screen, so it isn't wrapped in another "Target Range:" prefix here.
        y = drawSectionHeading(canvas, paint, "Settings Used", x, y);
        y = drawWrappedBlock(canvas, targetRangeText, x, y, contentWidth, PDF_SIZE_BODY, PDF_BODY);
        y += PDF_GAP_SECTION;

        // --- SUMMARY ---
        y = drawSectionHeading(canvas, paint, "Summary", x, y);
        applyBodyPaint(paint);
        y = drawTextBlock(canvas, paint, "Status: " + status, x, y);
        y = drawTextBlock(canvas, paint,
                String.format(Locale.getDefault(), "Average: %.2f%s", avg, unit), x, y);
        y = drawTextBlock(canvas, paint,
                String.format(Locale.getDefault(), "Minimum: %.2f%s", low, unit), x, y);
        y = drawTextBlock(canvas, paint,
                String.format(Locale.getDefault(), "Maximum: %.2f%s", high, unit), x, y);
        y = drawTextBlock(canvas, paint, "Readings: " + evidenceText, x, y);
        y += PDF_GAP_SECTION;

        // --- TREND CHART ---
        // The chart is the one elastic block on the page, so it absorbs the
        // squeeze: measure what the interpretation below will actually need,
        // then give the chart whatever room is left above the footer. A long
        // interpretation shrinks the chart rather than running off the page.
        if (chartBitmap != null) {
            y = drawSectionHeading(canvas, paint, "Trend", x, y);

            int interpretationHeight = measureWrappedHeight(interpretation, contentWidth,
                    PDF_SIZE_BODY);
            int reservedBelowChart = PDF_GAP_SECTION
                    + (int) Math.ceil(PDF_SIZE_SECTION * 1.4f) + PDF_GAP_AFTER_HEADING
                    + interpretationHeight;
            int availableForChart = PDF_FOOTER_SAFE_Y - y - reservedBelowChart;

            int targetWidth = contentWidth;
            float aspectRatio = (float) chartBitmap.getWidth() / chartBitmap.getHeight();
            int targetHeight = (int) (targetWidth / aspectRatio);
            int maxHeight = Math.min(190, availableForChart);
            if (maxHeight > 60 && targetHeight > maxHeight) {
                targetHeight = maxHeight;
                targetWidth = (int) (targetHeight * aspectRatio);
            }
            Rect destRect = new Rect(x, y, x + targetWidth, y + targetHeight);
            canvas.drawBitmap(chartBitmap, null, destRect, paint);
            y += targetHeight + PDF_GAP_SECTION;
        }

        // --- INTERPRETATION ---
        y = drawSectionHeading(canvas, paint, "Interpretation", x, y);
        drawWrappedBlock(canvas, interpretation, x, y, contentWidth, PDF_SIZE_BODY, PDF_BODY);

        drawModernReportFooter(canvas, paint, 1);
        document.finishPage(page);

        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir != null && !dir.exists()) dir.mkdirs();

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "Basilience_ParamReport_" + sanitizeForFilename(filter.deviceId) + "_"
                + sanitizeForFilename(filter.cycleLabel) + "_" + filter.canonicalParameter.replace(" ", "")
                + "_" + timeStamp + ".pdf";
        File file = new File(dir, fileName);
        FileOutputStream fos = new FileOutputStream(file);
        document.writeTo(fos);
        document.close();
        fos.close();

        return file;
    }

    /**
     * Renders the Fogging Report PDF.
     *
     * <p>All aggregate figures come from {@code totals}, the frozen totals
     * the visible report was rendered from. This method deliberately does
     * NOT sum {@code sessions} to derive its own totals: those are raw
     * unclipped start-to-end durations, so summing them would undo the
     * processor's boundary clipping, drop the running session's
     * contribution, and add excluded anomalous sessions back in - which is
     * exactly how the export used to disagree with the screen.
     *
     * <p>{@code sessions} is used only for the per-session records table,
     * which is a history listing and serves a different purpose from the
     * aggregate summary.
     */
    public File generateFoggingReportPdf(FoggingReportFilter filter, Bitmap chartBitmap, List<FoggingSession> sessions,
                                          String status, FoggingReportTotals totals,
                                          String interpretation, String userName) throws IOException {
        PdfDocument document = new PdfDocument();
        int pageNumber = 1;

        // --- PAGE 1: SUMMARY & CHART ---
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber++).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();
        Paint paint = new Paint();

        int x = MARGIN;
        int contentWidth = PAGE_WIDTH - (2 * MARGIN);

        // y is the TOP of the next block throughout; every helper returns the
        // next free top, so no block can land on top of another and no paint
        // state leaks between sections.
        int y = drawModernReportHeader(canvas, paint, x, 50, "Fogging Report", userName, filter.cycleStatus);

        // --- DEVICE / CYCLE DETAILS ---
        applyBodyPaint(paint);
        y = drawTextBlock(canvas, paint, "Device: " + filter.deviceId, x, y);
        y = drawTextBlock(canvas, paint, "Cycle: " + filter.cycleLabel, x, y);
        y = drawTextBlock(canvas, paint, "Cycle Status: " + filter.cycleStatus, x, y);
        y = drawTextBlock(canvas, paint, "Cycle Range: " + DateUtils.formatDate(filter.cycleStartMs) + " - "
                + ("COMPLETED".equalsIgnoreCase(filter.cycleStatus)
                    ? DateUtils.formatDate(filter.cycleEndMs) : "Present"), x, y);
        y = drawTextBlock(canvas, paint, "Report Period: " + filter.periodLabel + " ("
                + DateUtils.formatDate(filter.effectiveStartMs) + " - "
                + DateUtils.formatDate(filter.effectiveEndMs) + ")", x, y);
        y += PDF_GAP_SECTION;

        // --- FOGGING SUMMARY ---
        // Every figure here is printed straight from the frozen totals the
        // screen displayed; only the duration formatting is applied.
        y = drawSectionHeading(canvas, paint, "Fogging Summary", x, y);
        applyBodyPaint(paint);
        y = drawTextBlock(canvas, paint, "Status: " + status, x, y);
        y = drawTextBlock(canvas, paint, "Total Sessions: " + totals.totalSessionCount, x, y);
        y = drawTextBlock(canvas, paint, "Total Fogging Runtime: "
                + DurationFormatter.formatRuntime(totals.totalRuntimeMs), x, y);
        y = drawTextBlock(canvas, paint, "Average Session: "
                + DurationFormatter.formatSession(totals.averageSessionDurationMs), x, y);
        y = drawTextBlock(canvas, paint, "Automatic: " + totals.automaticSessionCount + " sessions ("
                + DurationFormatter.formatRuntime(totals.automaticRuntimeMs) + ")", x, y);
        y = drawTextBlock(canvas, paint, "Manual: " + totals.manualSessionCount + " sessions ("
                + DurationFormatter.formatRuntime(totals.manualRuntimeMs) + ")", x, y);
        y += PDF_GAP_SECTION;

        // --- TREND CHART ---
        // Elastic block: measure the interpretation and strategy rows that
        // follow, then give the chart whatever room remains above the footer.
        if (chartBitmap != null) {
            y = drawSectionHeading(canvas, paint, "Daily Fogging Runtime", x, y);

            int strategyRowCount = 0;
            for (String key : FOGGING_STRATEGY_ORDER) {
                Long durationMs = totals.strategyRuntimeMs.get(key);
                if (durationMs != null && durationMs > 0) strategyRowCount++;
            }
            int reservedBelowChart = PDF_GAP_SECTION
                    + (strategyRowCount > 0
                        ? (int) Math.ceil(PDF_SIZE_SECTION * 1.4f) + PDF_GAP_AFTER_HEADING
                            + strategyRowCount * (int) Math.ceil(PDF_SIZE_BODY * 1.4f) + PDF_GAP_SECTION
                        : 0)
                    + (int) Math.ceil(PDF_SIZE_SECTION * 1.4f) + PDF_GAP_AFTER_HEADING
                    + measureWrappedHeight(interpretation, contentWidth, PDF_SIZE_BODY);
            int availableForChart = PDF_FOOTER_SAFE_Y - y - reservedBelowChart;

            int targetWidth = contentWidth;
            float aspectRatio = (float) chartBitmap.getWidth() / chartBitmap.getHeight();
            int targetHeight = (int) (targetWidth / aspectRatio);
            int maxHeight = Math.min(190, availableForChart);
            if (maxHeight > 60 && targetHeight > maxHeight) {
                targetHeight = maxHeight;
                targetWidth = (int) (targetHeight * aspectRatio);
            }
            Rect destRect = new Rect(x, y, x + targetWidth, y + targetHeight);
            canvas.drawBitmap(chartBitmap, null, destRect, paint);
            y += targetHeight + PDF_GAP_SECTION;
        }

        // --- FOGGING STRATEGY ---
        // Same already-processed per-strategy runtimes the screen lists.
        // Only strategies actually present are shown; nothing is inferred,
        // and nothing is recalculated from raw session durations.
        List<String> strategyLines = new ArrayList<>();
        for (int i = 0; i < FOGGING_STRATEGY_ORDER.length; i++) {
            Long durationMs = totals.strategyRuntimeMs.get(FOGGING_STRATEGY_ORDER[i]);
            if (durationMs != null && durationMs > 0) {
                strategyLines.add(FOGGING_STRATEGY_LABELS[i] + ": "
                        + DurationFormatter.formatRuntime(durationMs));
            }
        }
        if (!strategyLines.isEmpty()) {
            y = drawSectionHeading(canvas, paint, "Fogging Strategy", x, y);
            applyBodyPaint(paint);
            for (String line : strategyLines) {
                y = drawTextBlock(canvas, paint, line, x, y);
            }
            y += PDF_GAP_SECTION;
        }

        // --- INTERPRETATION ---
        y = drawSectionHeading(canvas, paint, "What This Means", x, y);
        drawWrappedBlock(canvas, interpretation, x, y, contentWidth, PDF_SIZE_BODY, PDF_BODY);

        drawModernReportFooter(canvas, paint, pageNumber - 1);
        document.finishPage(page);

        // --- PAGE 2+: SESSION RECORDS ---
        if (sessions != null && !sessions.isEmpty()) {
            int currentEventIndex = 0;
            while (currentEventIndex < sessions.size()) {
                pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber++).create();
                page = document.startPage(pageInfo);
                canvas = page.getCanvas();

                y = drawModernReportHeader(canvas, paint, x, 50, "Fogging Report", userName, filter.cycleStatus);

                paint.setColor(PDF_HEADING);
                paint.setTextSize(PDF_SIZE_PARAM_TITLE);
                paint.setFakeBoldText(true);
                y = drawTextBlock(canvas, paint, "Fogging Session Records", x, y);
                y += 30;

                paint.setTextSize(PDF_SIZE_BODY);
                paint.setColor(PDF_BRAND);
                canvas.drawRect(x, y - 15, PAGE_WIDTH - MARGIN, y + 10, paint);
                paint.setColor(Color.WHITE);
                paint.setFakeBoldText(true);
                canvas.drawText("Date & Time", x + 5, y, paint);
                canvas.drawText("Duration", x + 180, y, paint);
                canvas.drawText("Mode", x + 320, y, paint);
                y += 25;

                paint.setFakeBoldText(false);
                int count = 0;
                int startY = y - 25 - 15;

                while (count < ROWS_PER_PAGE && currentEventIndex < sessions.size()) {
                    FoggingSession session = sessions.get(currentEventIndex);

                    if (count % 2 == 0) {
                        paint.setColor(Color.parseColor("#F5F5F5"));
                        canvas.drawRect(x, y - 15, PAGE_WIDTH - MARGIN, y + 10, paint);
                    }

                    paint.setColor(PDF_BODY);
                    canvas.drawText(DateUtils.formatDateTime(new Timestamp(session.getStartEvent().timestamp / 1000, 0)), x + 5, y, paint);
                    canvas.drawText(session.isAnomalous() ? "Incomplete record"
                            : DurationFormatter.formatSession(session.getDurationMs()), x + 180, y, paint);
                    canvas.drawText(session.getDisplayType(), x + 320, y, paint);

                    paint.setColor(PDF_RULE);
                    paint.setStrokeWidth(0.5f);
                    canvas.drawLine(x, y + 10, PAGE_WIDTH - MARGIN, y + 10, paint);

                    y += 25;
                    currentEventIndex++;
                    count++;
                }

                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(PDF_RULE);
                canvas.drawRect(x, startY, PAGE_WIDTH - MARGIN, y - 15, paint);
                paint.setStyle(Paint.Style.FILL);

                drawModernReportFooter(canvas, paint, pageNumber - 1);
                document.finishPage(page);
            }
        }

        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir != null && !dir.exists()) dir.mkdirs();

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "Basilience_FoggingReport_" + sanitizeForFilename(filter.deviceId) + "_"
                + sanitizeForFilename(filter.cycleLabel) + "_" + timeStamp + ".pdf";
        File file = new File(dir, fileName);
        FileOutputStream fos = new FileOutputStream(file);
        document.writeTo(fos);
        document.close();
        fos.close();

        return file;
    }

    static String sanitizeForFilename(String value) {
        if (value == null || value.isEmpty()) return "UnknownDevice";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ------------------------------------------------------------------
    // Cursor-based drawing helpers (all report PDFs).
    //
    // The older code mixed baseline-positioned drawText() calls with
    // hardcoded "y += 85"-style jumps that had to be kept in sync with what
    // each helper actually drew. When they drifted apart, blocks collided -
    // the parameter title landed on top of the header divider because the
    // caller assumed the header ended 10pt lower than it really did.
    //
    // These helpers instead treat y as the TOP of the next block and return
    // the next free top, deriving each block's real height from the paint's
    // own font metrics. Nothing has to be manually kept in sync, so the
    // layout can't overlap regardless of the text sizes chosen.
    // ------------------------------------------------------------------

    /** Draws one line of text from its top edge; returns the next free top. */
    private int drawTextBlock(Canvas canvas, Paint paint, String text, int x, int topY) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawText(text, x, topY - fm.ascent, paint);
        return (int) Math.ceil(topY + (fm.descent - fm.ascent));
    }

    /** Draws wrapped text from its top edge; returns the next free top. */
    private int drawWrappedBlock(Canvas canvas, String text, int x, int topY,
                                 int width, float textSize, int color) {
        TextPaint textPaint = new TextPaint();
        textPaint.setAntiAlias(true);
        textPaint.setTextSize(textSize);
        textPaint.setColor(color);
        StaticLayout layout = new StaticLayout(text, textPaint, width,
                Layout.Alignment.ALIGN_NORMAL, 1.15f, 0f, false);
        canvas.save();
        canvas.translate(x, topY);
        layout.draw(canvas);
        canvas.restore();
        return topY + layout.getHeight();
    }

    /** Height wrapped text will occupy, without drawing it. */
    private int measureWrappedHeight(String text, int width, float textSize) {
        TextPaint textPaint = new TextPaint();
        textPaint.setAntiAlias(true);
        textPaint.setTextSize(textSize);
        return new StaticLayout(text, textPaint, width,
                Layout.Alignment.ALIGN_NORMAL, 1.15f, 0f, false).getHeight();
    }

    /** Section heading in the shared print hierarchy; returns next free top. */
    private int drawSectionHeading(Canvas canvas, Paint paint, String text, int x, int topY) {
        paint.setColor(PDF_HEADING);
        paint.setTextSize(PDF_SIZE_SECTION);
        paint.setFakeBoldText(true);
        int next = drawTextBlock(canvas, paint, text, x, topY);
        return next + PDF_GAP_AFTER_HEADING;
    }

    /** Resets paint to normal body copy so no earlier state can leak in. */
    private void applyBodyPaint(Paint paint) {
        paint.setColor(PDF_BODY);
        paint.setTextSize(PDF_SIZE_BODY);
        paint.setFakeBoldText(false);
        paint.setStrokeWidth(0f);
    }

    /**
     * Shared header for every report PDF (Parameter, Fogging, Harvest).
     * Returns the next free top edge, so the caller never has to guess how
     * tall the header was, and resets paint to a known body state so no
     * colour/stroke can leak into the caller's next block.
     *
     * <p>This replaced an earlier shared header that hardcoded a "Production
     * Summary Report" subtitle for all reports and left paint on LTGRAY -
     * the cause of both the washed-out body copy and the title/divider
     * collision seen in the old exports.
     */
    private int drawModernReportHeader(Canvas canvas, Paint paint, int x, int topY,
                                       String reportTitle, String userName, String status) {
        paint.setAntiAlias(true);

        paint.setColor(PDF_BRAND);
        paint.setTextSize(PDF_SIZE_BRAND);
        paint.setFakeBoldText(true);
        int y = drawTextBlock(canvas, paint, "BASILIENCE", x, topY);

        paint.setColor(PDF_HEADING);
        paint.setTextSize(PDF_SIZE_REPORT_TITLE);
        paint.setFakeBoldText(true);
        int titleTop = y + 2;
        int afterTitle = drawTextBlock(canvas, paint, reportTitle, x, titleTop);

        // Status badge, vertically centred on the report-title line.
        if (status != null && !status.isEmpty()) {
            paint.setTextSize(PDF_SIZE_META);
            paint.setFakeBoldText(true);
            float statusWidth = paint.measureText(status);
            int badgeColor = "COMPLETED".equalsIgnoreCase(status)
                    ? Color.parseColor("#1976D2") : Color.parseColor("#2E7D32");
            paint.setColor(badgeColor);
            RectF badge = new RectF(PAGE_WIDTH - MARGIN - statusWidth - 20, titleTop,
                    PAGE_WIDTH - MARGIN, afterTitle);
            canvas.drawRoundRect(badge, 5, 5, paint);

            paint.setColor(Color.WHITE);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float badgeBaseline = badge.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(status, PAGE_WIDTH - MARGIN - statusWidth - 10, badgeBaseline, paint);
        }
        y = afterTitle + 8;

        paint.setColor(PDF_META);
        paint.setTextSize(PDF_SIZE_META);
        paint.setFakeBoldText(false);
        int metaTop = y;
        y = drawTextBlock(canvas, paint, "Generated by: " + userName, x, metaTop);
        Paint.FontMetrics metaFm = paint.getFontMetrics();
        canvas.drawText("Generated on: " + DateUtils.formatDateTime(Timestamp.now()),
                PAGE_WIDTH - MARGIN - 190, metaTop - metaFm.ascent, paint);

        y += 10;
        paint.setColor(PDF_RULE);
        paint.setStrokeWidth(1f);
        canvas.drawLine(x, y, PAGE_WIDTH - MARGIN, y, paint);

        // Leave the paint in a known body state so nothing downstream can
        // inherit the rule's colour/stroke - the cause of the washed-out
        // body text in the previous export.
        applyBodyPaint(paint);
        return y + 24;
    }

    /**
     * Footer for every report PDF. The previous shared footer carried a
     * "Confidential Document" boilerplate line that had no backing
     * requirement anywhere in the project; it is gone now that all three
     * reports use this one.
     */
    private void drawModernReportFooter(Canvas canvas, Paint paint, int pageNum) {
        int y = PAGE_HEIGHT - 40;
        paint.setTextSize(PDF_SIZE_META);
        paint.setColor(PDF_META);
        paint.setFakeBoldText(false);
        paint.setStrokeWidth(0f);
        canvas.drawText("© " + new SimpleDateFormat("yyyy", Locale.getDefault()).format(new Date())
                + " Basilience", MARGIN, y, paint);
        canvas.drawText("Page " + pageNum, PAGE_WIDTH - MARGIN - 40, y, paint);
    }

}
