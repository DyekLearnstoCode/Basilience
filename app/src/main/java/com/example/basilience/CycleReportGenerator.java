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

import com.example.basilience.models.FoggingEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CycleReportGenerator {

    private final Context context;
    private static final int PAGE_WIDTH = 595; // A4 width in points
    private static final int PAGE_HEIGHT = 842; // A4 height in points
    private static final int MARGIN = 50;
    private static final int ROWS_PER_PAGE = 22;

    public CycleReportGenerator(Context context) {
        this.context = context;
    }

    public File generateCycleSummaryPdf(Cycle cycle, Bitmap chartBitmap, List<Harvest> harvestHistory, String userName) throws IOException {
        PdfDocument document = new PdfDocument();
        int pageNumber = 1;

        // --- PAGE 1: SUMMARY & CHART ---
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber++).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();
        Paint paint = new Paint();

        int x = MARGIN;
        int y = 60;

        // --- HEADER ---
        drawHeader(canvas, paint, x, y, userName, (cycle.getStatus() != null ? cycle.getStatus().toUpperCase() : "ACTIVE"));
        y += 85;

        // --- CYCLE INFORMATION ---
        paint.setTextSize(16f);
        paint.setFakeBoldText(true);
        canvas.drawText("Cycle Information", x, y, paint);
        y += 30;
        paint.setTextSize(12f);
        paint.setFakeBoldText(false);
        
        drawLabelValue(canvas, paint, "Cycle Number:", "Cycle #" + cycle.getCycleNumber(), x, y);
        y += 20;
        drawLabelValue(canvas, paint, "Status:", (cycle.getStatus() != null ? cycle.getStatus().toUpperCase() : "ACTIVE"), x, y);
        y += 20;
        drawLabelValue(canvas, paint, "Start Date:", DateUtils.formatDate(cycle.getStartDate()), x, y);
        y += 20;
        drawLabelValue(canvas, paint, "End Date:", DateUtils.formatDate(cycle.getEndDate()), x, y);
        y += 20;
        drawLabelValue(canvas, paint, "Harvest Frequency:", "Every " + cycle.getHarvestFrequencyDays() + " Days", x, y);

        // --- PRODUCTION SUMMARY ---
        y += 50;
        paint.setTextSize(16f);
        paint.setFakeBoldText(true);
        canvas.drawText("Production Summary", x, y, paint);
        y += 30;
        paint.setTextSize(12f);
        paint.setFakeBoldText(false);

        drawLabelValue(canvas, paint, "Total Weight:", String.format(Locale.getDefault(), "%.2fg", cycle.getTotalHarvestWeight()), x, y);
        y += 20;
        drawLabelValue(canvas, paint, "Total Harvests:", String.valueOf(cycle.getTotalHarvestCount()), x, y);
        y += 20;
        double avg = cycle.getTotalHarvestCount() > 0 ? cycle.getTotalHarvestWeight() / cycle.getTotalHarvestCount() : 0;
        drawLabelValue(canvas, paint, "Average Weight:", String.format(Locale.getDefault(), "%.2fg per harvest", avg), x, y);

        // --- CHART BITMAP ---
        if (chartBitmap != null) {
            y += 40;
            paint.setTextSize(14f);
            paint.setFakeBoldText(true);
            paint.setColor(Color.BLACK);
            canvas.drawText("Harvest Trend Analysis", x, y, paint);
            y += 20;
            
            int targetWidth = PAGE_WIDTH - (2 * MARGIN);
            float aspectRatio = (float) chartBitmap.getWidth() / chartBitmap.getHeight();
            int targetHeight = (int) (targetWidth / aspectRatio);

            // Constraint: Don't let chart take more than 1/3 of the page height to avoid pushing content off
            int maxHeight = 250;
            if (targetHeight > maxHeight) {
                targetHeight = maxHeight;
                targetWidth = (int) (targetHeight * aspectRatio);
            }

            Rect destRect = new Rect(x, y, x + targetWidth, y + targetHeight);
            canvas.drawBitmap(chartBitmap, null, destRect, paint);
            y += targetHeight + 30;
        }

        drawFooter(canvas, paint, pageNumber - 1);
        document.finishPage(page);

        // --- PAGE 2+ : HARVEST HISTORY TABLE ---
        if (harvestHistory != null && !harvestHistory.isEmpty()) {
            int currentHarvestIndex = 0;
            while (currentHarvestIndex < harvestHistory.size()) {
                pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber++).create();
                page = document.startPage(pageInfo);
                canvas = page.getCanvas();
                y = 60;
                
                drawHeader(canvas, paint, x, y, userName, (cycle.getStatus() != null ? cycle.getStatus().toUpperCase() : "ACTIVE"));
                y += 85;
                
                paint.setTextSize(16f);
                paint.setFakeBoldText(true);
                canvas.drawText("Harvest History", x, y, paint);
                y += 30;
                
                // Table Header
                paint.setTextSize(12f);
                paint.setColor(Color.parseColor("#2E7D32")); // Dark green header
                canvas.drawRect(x, y - 15, PAGE_WIDTH - MARGIN, y + 10, paint);
                paint.setColor(Color.WHITE);
                paint.setFakeBoldText(true);
                canvas.drawText("Date", x + 5, y, paint);
                canvas.drawText("Weight (g)", x + 120, y, paint);
                canvas.drawText("Source", x + 220, y, paint);
                canvas.drawText("Recorded By", x + 320, y, paint);
                y += 25;
                
                paint.setFakeBoldText(false);
                int count = 0;
                int startY = y - 25 - 15;
                while (count < ROWS_PER_PAGE && currentHarvestIndex < harvestHistory.size()) {
                    Harvest h = harvestHistory.get(currentHarvestIndex);
                    
                    // Alternating row colors
                    if (count % 2 == 0) {
                        paint.setColor(Color.parseColor("#F5F5F5"));
                        canvas.drawRect(x, y - 15, PAGE_WIDTH - MARGIN, y + 10, paint);
                    }
                    
                    paint.setColor(Color.BLACK);
                    canvas.drawText(DateUtils.formatDate(h.getHarvestDate()), x + 5, y, paint);
                    canvas.drawText(String.format(Locale.getDefault(), "%.1f", h.getWeight()), x + 120, y, paint);
                    canvas.drawText(h.getSource(), x + 220, y, paint);
                    canvas.drawText(h.getRecordedByName(), x + 320, y, paint);
                    
                    // Draw bottom border for the row
                    paint.setColor(Color.LTGRAY);
                    paint.setStrokeWidth(0.5f);
                    canvas.drawLine(x, y + 10, PAGE_WIDTH - MARGIN, y + 10, paint);
                    
                    y += 25;
                    currentHarvestIndex++;
                    count++;
                }
                
                // Draw outer border for the table
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(Color.LTGRAY);
                canvas.drawRect(x, startY, PAGE_WIDTH - MARGIN, y - 15, paint);
                paint.setStyle(Paint.Style.FILL);
                
                drawFooter(canvas, paint, pageNumber - 1);
                document.finishPage(page);
            }
        }

        // Save file
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir != null && !dir.exists()) dir.mkdirs();

        String timeStamp = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        String fileName = "Basilience_Report_Cycle" + cycle.getCycleNumber() + "_" + timeStamp + ".pdf";
        File file = new File(dir, fileName);

        FileOutputStream fos = new FileOutputStream(file);
        document.writeTo(fos);
        document.close();
        fos.close();

        return file;
    }

    private void drawLabelValue(
            Canvas canvas,
            Paint paint,
            String label,
            String value,
            int x,
            int y)
    {
        canvas.drawText(label + " " + value, x, y, paint);
    }
    private void drawHeader(Canvas canvas, Paint paint, int x, int y, String userName, String status) {
        // App Title
        paint.setColor(Color.parseColor("#2E7D32")); // Dark Green
        paint.setTextSize(26f);
        paint.setFakeBoldText(true);
        canvas.drawText("BASILIENCE", x, y, paint);

        // Subtitle
        y += 25;
        paint.setColor(Color.BLACK);
        paint.setTextSize(14f);
        paint.setFakeBoldText(true);
        canvas.drawText("Production Summary Report", x, y, paint);

        // Status Chip Replacement (Visual representation)
        int statusColor = status.equalsIgnoreCase("COMPLETED") ? Color.parseColor("#1976D2") : Color.parseColor("#4CAF50");
        paint.setColor(statusColor);
        paint.setFakeBoldText(true);
        float statusWidth = paint.measureText(status);
        
        RectF statusRect = new RectF(PAGE_WIDTH - MARGIN - statusWidth - 20, y - 20, PAGE_WIDTH - MARGIN, y + 5);
        canvas.drawRoundRect(statusRect, 5, 5, paint);
        
        paint.setColor(Color.WHITE);
        canvas.drawText(status, PAGE_WIDTH - MARGIN - statusWidth - 10, y, paint);

        // Metadata
        y += 35;
        paint.setTextSize(10f);
        paint.setFakeBoldText(false);
        paint.setColor(Color.GRAY);
        canvas.drawText("Generated By: " + userName, x, y, paint);
        canvas.drawText("Generated On: " + DateUtils.formatDateTime(Timestamp.now()), PAGE_WIDTH - MARGIN - 180, y, paint);

        y += 15;
        paint.setColor(Color.LTGRAY);
        paint.setStrokeWidth(1f);
        canvas.drawLine(x, y, PAGE_WIDTH - MARGIN, y, paint);
    }

    private void drawFooter(Canvas canvas, Paint paint, int pageNum) {
        int y = PAGE_HEIGHT - 40;
        paint.setTextSize(10f);
        paint.setColor(Color.GRAY);
        paint.setFakeBoldText(false);
        canvas.drawText("© " + new SimpleDateFormat("yyyy", Locale.getDefault()).format(new Date()) + " Basilience. Confidential Document.", MARGIN, y, paint);
        canvas.drawText("Page " + pageNum, PAGE_WIDTH - MARGIN - 40, y, paint);
    }

    public File generateSensorReportPdf(String deviceId, String parameter, String filter, List<com.github.mikephil.charting.data.Entry> entries, float avg, float high, float low, String userName) throws IOException {
        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();
        Paint paint = new Paint();

        int x = MARGIN;
        int y = 60;

        // Header
        drawHeader(canvas, paint, x, y, userName, "SENSOR LOG");
        y += 85;

        // Report Title
        paint.setTextSize(18f);
        paint.setFakeBoldText(true);
        canvas.drawText(parameter + " Analysis (" + filter + ")", x, y, paint);
        y += 35;

        // Device Info
        paint.setTextSize(12f);
        paint.setFakeBoldText(false);
        drawLabelValue(canvas, paint, "Device ID:", deviceId, x, y);
        y += 25;

        // Statistics
        paint.setFakeBoldText(true);
        canvas.drawText("Statistical Summary", x, y, paint);
        y += 25;
        paint.setFakeBoldText(false);
        drawLabelValue(canvas, paint, "Average Value:", String.format(Locale.getDefault(), "%.2f", avg), x, y);
        y += 20;
        drawLabelValue(canvas, paint, "Peak Value:", String.format(Locale.getDefault(), "%.2f", high), x, y);
        y += 20;
        drawLabelValue(canvas, paint, "Lowest Value:", String.format(Locale.getDefault(), "%.2f", low), x, y);
        y += 20;
        drawLabelValue(canvas, paint, "Total Readings:", String.valueOf(entries.size()), x, y);

        y += 40;
        paint.setFakeBoldText(true);
        canvas.drawText("Interpretation", x, y, paint);
        y += 25;
        paint.setFakeBoldText(false);
        String interpretation = getInterpretation(parameter, avg);
        
        // Simple text wrap for interpretation
        TextPaint textPaint = new TextPaint();
        textPaint.setTextSize(11f);
        textPaint.setColor(Color.DKGRAY);
        StaticLayout staticLayout = new StaticLayout(interpretation, textPaint, PAGE_WIDTH - (2 * MARGIN), Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
        canvas.save();
        canvas.translate(x, y);
        staticLayout.draw(canvas);
        canvas.restore();

        drawFooter(canvas, paint, 1);
        document.finishPage(page);

        // Save file
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir != null && !dir.exists()) dir.mkdirs();

        String fileName = "SensorReport_" + parameter.replace(" ", "") + "_" + System.currentTimeMillis() + ".pdf";
        File file = new File(dir, fileName);
        FileOutputStream fos = new FileOutputStream(file);
        document.writeTo(fos);
        document.close();
        fos.close();

        return file;
    }

    private String getInterpretation(String parameter, float avg) {
        if (parameter.contains("pH")) {
            if (avg < 5.5) return "Warning: pH level is too acidic. Nutrient lockout may occur. Add pH Up.";
            if (avg > 6.5) return "Warning: pH level is too alkaline. Nutrient lockout may occur. Add pH Down.";
            return "Status: pH level is optimal (5.5 - 6.5) for hydroponic growth.";
        }
        if (parameter.contains("EC")) {
            if (avg < 1.0) return "Warning: EC level is low. Plants may be underfed. Increase nutrient concentration.";
            if (avg > 2.5) return "Warning: EC level is high. Risk of nutrient burn. Dilute with fresh water.";
            return "Status: EC level is within the healthy range for most vegetative stages.";
        }
        if (parameter.contains("Temperature")) {
            if (avg < 18) return "Warning: Temperatures are too low. Growth may slow down.";
            if (avg > 30) return "Warning: Temperatures are too high. Risk of root rot and bolting.";
            return "Status: Temperature is stable and ideal for cultivation.";
        }
        return "The system is maintaining parameters according to the pre-defined environmental profile.";
    }

    public File generateFoggingReportPdf(String deviceId, String filter, List<FoggingEvent> events, String userName) throws IOException {
        PdfDocument document = new PdfDocument();
        int pageNumber = 1;
        int currentEventIndex = 0;
        
        List<FoggingEvent> chronoEvents = new java.util.ArrayList<>(events);
        java.util.Collections.reverse(chronoEvents);

        List<String[]> rows = new java.util.ArrayList<>();
        long totalDurationMs = 0;
        long lastOnTime = -1;

        for (FoggingEvent e : chronoEvents) {
            if ("ON".equals(e.event)) {
                lastOnTime = e.timestamp;
            } else if ("OFF".equals(e.event)) {
                if (lastOnTime != -1 && e.timestamp > lastOnTime) {
                    long duration = e.timestamp - lastOnTime;
                    totalDurationMs += duration;
                    long durMins = duration / (1000 * 60);
                    
                    rows.add(new String[]{
                        DateUtils.formatDateTime(new Timestamp(e.timestamp / 1000, 0)),
                        durMins + " mins",
                        e.isManual ? "Manual" : "Automatic"
                    });
                    lastOnTime = -1;
                }
            }
        }
        
        // Reverse rows so newest is first
        java.util.Collections.reverse(rows);
        long totalMins = totalDurationMs / (1000 * 60);

        if (rows.isEmpty()) {
            rows.add(new String[]{"No fogging events found", "-", "-"});
        }

        while (currentEventIndex < rows.size()) {
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber++).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            Paint paint = new Paint();

            int x = MARGIN;
            int y = 60;

            drawHeader(canvas, paint, x, y, userName, "FOGGING LOG");
            y += 85;

            paint.setTextSize(18f);
            paint.setFakeBoldText(true);
            canvas.drawText("Fogging Duration Analysis (" + filter + ")", x, y, paint);
            y += 35;

            paint.setTextSize(12f);
            paint.setFakeBoldText(false);
            drawLabelValue(canvas, paint, "Device ID:", deviceId, x, y);
            y += 20;
            drawLabelValue(canvas, paint, "Total Fogging Events:", String.valueOf(rows.size()), x, y);
            y += 20;
            drawLabelValue(canvas, paint, "Total Duration:", totalMins + " mins", x, y);
            y += 35;

            // Table Header
            paint.setTextSize(12f);
            paint.setColor(Color.parseColor("#2E7D32")); 
            canvas.drawRect(x, y - 15, PAGE_WIDTH - MARGIN, y + 10, paint);
            paint.setColor(Color.WHITE);
            paint.setFakeBoldText(true);
            canvas.drawText("Date & Time", x + 5, y, paint);
            canvas.drawText("Duration", x + 180, y, paint);
            canvas.drawText("Trigger Mode", x + 320, y, paint);
            y += 25;
            
            paint.setFakeBoldText(false);
            int count = 0;
            int startY = y - 25 - 15;

            while (count < ROWS_PER_PAGE && currentEventIndex < rows.size()) {
                String[] r = rows.get(currentEventIndex);
                
                if (count % 2 == 0) {
                    paint.setColor(Color.parseColor("#F5F5F5"));
                    canvas.drawRect(x, y - 15, PAGE_WIDTH - MARGIN, y + 10, paint);
                }
                
                paint.setColor(Color.BLACK);
                canvas.drawText(r[0], x + 5, y, paint);
                canvas.drawText(r[1], x + 180, y, paint);
                canvas.drawText(r[2], x + 320, y, paint);
                
                paint.setColor(Color.LTGRAY);
                paint.setStrokeWidth(0.5f);
                canvas.drawLine(x, y + 10, PAGE_WIDTH - MARGIN, y + 10, paint);
                
                y += 25;
                currentEventIndex++;
                count++;
            }
            
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.LTGRAY);
            canvas.drawRect(x, startY, PAGE_WIDTH - MARGIN, y - 15, paint);
            paint.setStyle(Paint.Style.FILL);

            drawFooter(canvas, paint, pageNumber - 1);
            document.finishPage(page);
        }

        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir != null && !dir.exists()) dir.mkdirs();

        String fileName = "FoggingReport_" + System.currentTimeMillis() + ".pdf";
        File file = new File(dir, fileName);
        FileOutputStream fos = new FileOutputStream(file);
        document.writeTo(fos);
        document.close();
        fos.close();

        return file;
    }
}
