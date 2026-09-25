package com.example.basilience;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Environment;

import com.example.basilience.models.ParameterExportBundle;
import com.example.basilience.models.ParameterReportFilter;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.RegionUtil;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the Parameter Report's comprehensive XLSX export: a formal-report-
 * styled Summary sheet, one worksheet per selected parameter dominated by a
 * large control-chart IMAGE (not a native Excel chart - see the class note
 * on ControlChartRenderer for why), and a plain Raw Data sheet holding every
 * original 5-minute reading so no source data is lost to aggregation -
 * aggregation only ever affects what a chart draws (see ChartAggregation).
 */
public class ExcelReportGenerator {

    private final Context context;

    // Excel sheet names cannot exceed 31 characters or contain \/?*[]: -
    // none of our parameter display names do, but this stays defensive.
    private static final int MAX_SHEET_NAME_LENGTH = 31;

    // Parameter sheet grid: column 0 is wide enough for a label or a
    // date/time value, columns 1-9 are compact data columns. The chart image
    // and section banners span the full width (0..NUM_COLS-1); the compact
    // stats/findings sections only use as many of those columns as they need.
    private static final int NUM_COLS = 10;
    private static final int CHART_ANCHOR_ROWS = 15;
    private static final int MAX_EXCURSIONS_PER_PARAMETER = 5;

    public ExcelReportGenerator(Context context) {
        this.context = context;
    }

    public File generateSensorReportXlsx(ParameterReportFilter filter, List<ParameterExportBundle> bundles,
                                          String userName) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XlsxStyles styles = new XlsxStyles(workbook);

            writeSummarySheet(workbook, styles, filter, bundles, userName);
            for (ParameterExportBundle bundle : bundles) {
                writeParameterSheet(workbook, styles, filter, bundle);
            }
            writeRawDataSheet(workbook, styles, bundles);

            File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir != null && !dir.exists()) dir.mkdirs();

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "Basilience_ParamReport_" + CycleReportGenerator.sanitizeForFilename(filter.deviceId) + "_"
                    + CycleReportGenerator.sanitizeForFilename(filter.cycleLabel) + "_" + timeStamp + ".xlsx";
            File file = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
            return file;
        }
    }

    // ------------------------------------------------------------------
    // Summary sheet - a formal report cover page, not a data table.
    // ------------------------------------------------------------------

    private void writeSummarySheet(XSSFWorkbook workbook, XlsxStyles styles, ParameterReportFilter filter,
                                    List<ParameterExportBundle> bundles, String userName) {
        XSSFSheet sheet = workbook.createSheet("Summary");
        int statsCols = 9;
        int lastStatsCol = statsCols - 1;

        int rowIndex = writeBanner(sheet, styles, 0, "Basilience Parameter Report", lastStatsCol);

        // --- Boxed metadata card ---
        int cardStartRow = rowIndex;
        rowIndex = writeMetadataRow(sheet, styles, rowIndex, "Device", filter.deviceId, lastStatsCol);
        rowIndex = writeMetadataRow(sheet, styles, rowIndex, "Cycle", filter.cycleLabel, lastStatsCol);
        rowIndex = writeMetadataRow(sheet, styles, rowIndex, "Cycle Status", filter.cycleStatus, lastStatsCol);
        rowIndex = writeMetadataRow(sheet, styles, rowIndex, "Cycle Range",
                DateUtils.formatDate(filter.cycleStartMs) + " - " + ("COMPLETED".equalsIgnoreCase(filter.cycleStatus)
                        ? DateUtils.formatDate(filter.cycleEndMs) : "Present"), lastStatsCol);
        rowIndex = writeMetadataRow(sheet, styles, rowIndex, "Report Period", filter.periodLabel + " ("
                + DateUtils.formatDate(filter.effectiveStartMs) + " - " + DateUtils.formatDate(filter.effectiveEndMs) + ")", lastStatsCol);
        rowIndex = writeMetadataRow(sheet, styles, rowIndex, "Generated By", userName, lastStatsCol);
        rowIndex = writeMetadataRow(sheet, styles, rowIndex, "Generated On", DateUtils.formatDateTime(System.currentTimeMillis()), lastStatsCol);
        applyOutlineBorder(sheet, new CellRangeAddress(cardStartRow, rowIndex - 1, 0, lastStatsCol));
        rowIndex++;

        // --- Parameter Summary table ---
        rowIndex = writeSectionHeading(sheet, styles, rowIndex, "Parameter Summary", lastStatsCol);

        String[] headers = {"Parameter", "Unit", "Target Range", "Average", "Minimum", "Maximum",
                "Readings", "Out-of-Range", "Status"};
        int tableHeaderRow = rowIndex;
        XSSFRow headerRow = sheet.createRow(rowIndex++);
        for (int i = 0; i < headers.length; i++) {
            writeCell(headerRow, i, headers[i], styles.tableHeader);
        }

        int firstDataRow = rowIndex;
        for (int b = 0; b < bundles.size(); b++) {
            ParameterExportBundle bundle = bundles.get(b);
            CellStyle cellStyle = (b % 2 == 0) ? styles.tableCell : styles.tableCellAlt;
            XSSFRow row = sheet.createRow(rowIndex++);
            int outOfRangeCount = countOutOfRange(bundle);
            String numberFormat = "%." + bundle.decimals + "f";
            writeCell(row, 0, bundle.displayParameter, cellStyle);
            writeCell(row, 1, bundle.unit.trim(), cellStyle);
            writeCell(row, 2, bundle.targetRangeText, cellStyle);
            writeCell(row, 3, String.format(Locale.getDefault(), numberFormat, bundle.average), cellStyle);
            writeCell(row, 4, String.format(Locale.getDefault(), numberFormat, bundle.low), cellStyle);
            writeCell(row, 5, String.format(Locale.getDefault(), numberFormat, bundle.high), cellStyle);
            writeCell(row, 6, String.valueOf(bundle.readingCount), cellStyle);
            writeCell(row, 7, String.valueOf(outOfRangeCount), cellStyle);
            writeCell(row, 8, bundle.status, cellStyle);
        }
        applyOutlineBorder(sheet, new CellRangeAddress(tableHeaderRow, rowIndex - 1, 0, lastStatsCol));
        sheet.createFreezePane(0, firstDataRow);
        rowIndex++;

        // --- Findings & Interpretation ---
        rowIndex = writeSectionHeading(sheet, styles, rowIndex, "Findings & Interpretation", lastStatsCol);
        for (ParameterExportBundle bundle : bundles) {
            XSSFRow nameRow = sheet.createRow(rowIndex++);
            writeCell(nameRow, 0, bundle.displayParameter, styles.label);
            sheet.addMergedRegion(new CellRangeAddress(nameRow.getRowNum(), nameRow.getRowNum(), 0, lastStatsCol));

            XSSFRow textRow = sheet.createRow(rowIndex++);
            writeCell(textRow, 0, bundle.interpretation, styles.interpretation);
            textRow.setHeightInPoints(estimateWrappedRowHeightPoints(bundle.interpretation, statsCols * 14));
            sheet.addMergedRegion(new CellRangeAddress(textRow.getRowNum(), textRow.getRowNum(), 0, lastStatsCol));
            RegionUtil.setBorderLeft(BorderStyle.MEDIUM, new CellRangeAddress(textRow.getRowNum(), textRow.getRowNum(), 0, lastStatsCol), sheet);
            RegionUtil.setLeftBorderColor(styles.brandColorIndex, new CellRangeAddress(textRow.getRowNum(), textRow.getRowNum(), 0, lastStatsCol), sheet);
        }

        sheet.setColumnWidth(0, 18 * 256);
        sheet.setColumnWidth(2, 34 * 256);
        for (int i = 1; i < headers.length; i++) {
            if (i != 2) sheet.setColumnWidth(i, 13 * 256);
        }
    }

    private int countOutOfRange(ParameterExportBundle bundle) {
        if (bundle.rangeMin == null || bundle.rangeMax == null) return 0;
        int count = 0;
        for (ChartAggregation.Sample s : bundle.samples) {
            if (ThresholdZoneClassifier.classify(s.value, bundle.rangeMin, bundle.rangeMax) == ThresholdZoneClassifier.Zone.RED) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Per-parameter sheet - the control chart is the main focus. Order:
    // title, compact metadata, large chart image, compact values/status,
    // small findings block. No per-bucket table here - the aggregated
    // timeline is the chart itself, and every raw reading already lives in
    // Raw Data, so nothing is duplicated.
    // ------------------------------------------------------------------

    private void writeParameterSheet(XSSFWorkbook workbook, XlsxStyles styles, ParameterReportFilter filter,
                                      ParameterExportBundle bundle) {
        XSSFSheet sheet = workbook.createSheet(safeSheetName(bundle.displayParameter));
        int lastCol = NUM_COLS - 1;

        List<ChartAggregation.Bucket> buckets = ControlChartRenderer.aggregateForChart(bundle,
                filter.effectiveStartMs, filter.effectiveEndMs);

        // 1. Title
        int rowIndex = writeBanner(sheet, styles, 0, bundle.displayParameter + " - Control Chart", lastCol);

        // 2. Compact report metadata
        int cardStartRow = rowIndex;
        rowIndex = writeMetadataRow(sheet, styles, rowIndex, "Unit", bundle.unit.trim().isEmpty() ? "-" : bundle.unit.trim(), lastCol);
        rowIndex = writeMetadataRow(sheet, styles, rowIndex, "Target Range", bundle.targetRangeText, lastCol);
        rowIndex = writeMetadataRow(sheet, styles, rowIndex, "Report Period", filter.periodLabel + " ("
                + DateUtils.formatDate(filter.effectiveStartMs) + " - " + DateUtils.formatDate(filter.effectiveEndMs) + ")", lastCol);
        long bucketSizeMs = ChartAggregation.bucketSizeMsFor(Math.max(1L, filter.effectiveEndMs - filter.effectiveStartMs));
        String intervalDescription = ChartAggregation.describeInterval(bucketSizeMs);
        String intervalText;
        if (buckets.isEmpty()) {
            intervalText = "No readings in this period";
        } else if (bucketSizeMs <= 5 * 60_000L) {
            // Raw resolution: the chart plots every reading directly, nothing was grouped.
            intervalText = intervalDescription + " (" + buckets.size() + " readings plotted directly - see Raw Data sheet)";
        } else {
            intervalText = intervalDescription + " (" + buckets.size() + " points on this chart from " + bundle.readingCount
                    + " raw readings - every reading is in the Raw Data sheet)";
        }
        rowIndex = writeMetadataRow(sheet, styles, rowIndex, "Chart Interval", intervalText, lastCol);
        rowIndex = writeMetadataRow(sheet, styles, rowIndex, "Chart Zones",
                "Green = normal, Yellow = near threshold, Red = out of range (dashed lines mark the Min/Max target)", lastCol);
        applyOutlineBorder(sheet, new CellRangeAddress(cardStartRow, rowIndex - 1, 0, lastCol));
        rowIndex++;

        // Findings are computed here (before the chart) so the chart can
        // annotate the worst one directly on the figure.
        List<ChartAggregation.Excursion> excursions = ChartAggregation.findExcursions(bundle.samples,
                bundle.rangeMin, bundle.rangeMax, MAX_EXCURSIONS_PER_PARAMETER);
        ChartAggregation.Excursion topExcursion = excursions.isEmpty() ? null : excursions.get(0);

        // 3. Control chart image - the main focus of this sheet, but sized
        // to leave real room for the sections below rather than dominating
        // the whole sheet.
        if (!buckets.isEmpty()) {
            int chartTopRow = rowIndex;
            int chartBottomRow = chartTopRow + CHART_ANCHOR_ROWS;
            Bitmap chartBitmap = ControlChartRenderer.render(context, bundle, buckets, 1500, 620, topExcursion);
            if (chartBitmap != null) {
                embedPicture(workbook, sheet, chartBitmap, 0, chartTopRow, NUM_COLS, chartBottomRow);
                chartBitmap.recycle();
            }
            rowIndex = chartBottomRow + 1;
        } else {
            XSSFRow emptyRow = sheet.createRow(rowIndex++);
            writeCell(emptyRow, 0, "No readings for " + bundle.displayParameter + " in the selected period.", styles.label);
            sheet.addMergedRegion(new CellRangeAddress(emptyRow.getRowNum(), emptyRow.getRowNum(), 0, lastCol));
        }

        // 4. Compact values/status section beneath the chart - a single at-a-
        // glance row, not a per-bucket table (every raw reading already lives
        // in Raw Data; the chart itself is the aggregated timeline).
        rowIndex = writeSectionHeading(sheet, styles, rowIndex, "At a Glance", lastCol);
        String[] glanceHeaders = {"Average", "Minimum", "Maximum", "Readings", "Status"};
        int glanceHeaderRow = rowIndex;
        XSSFRow glanceHeaderRowObj = sheet.createRow(rowIndex++);
        for (int i = 0; i < glanceHeaders.length; i++) {
            writeCell(glanceHeaderRowObj, i, glanceHeaders[i], styles.tableHeader);
        }
        String numberFormat = "%." + bundle.decimals + "f";
        XSSFRow glanceRow = sheet.createRow(rowIndex++);
        writeCell(glanceRow, 0, String.format(Locale.getDefault(), numberFormat, bundle.average), styles.tableCell);
        writeCell(glanceRow, 1, String.format(Locale.getDefault(), numberFormat, bundle.low), styles.tableCell);
        writeCell(glanceRow, 2, String.format(Locale.getDefault(), numberFormat, bundle.high), styles.tableCell);
        writeCell(glanceRow, 3, String.valueOf(bundle.readingCount), styles.tableCell);
        writeCell(glanceRow, 4, bundle.status, styles.tableCell);
        applyOutlineBorder(sheet, new CellRangeAddress(glanceHeaderRow, rowIndex - 1, 0, glanceHeaders.length - 1));
        rowIndex++;

        // 5. Small summary/findings block - capped, not a full log.
        rowIndex = writeSectionHeading(sheet, styles, rowIndex, "Out-of-Range Findings", lastCol);
        if (excursions.isEmpty()) {
            XSSFRow noneRow = sheet.createRow(rowIndex++);
            writeCell(noneRow, 0, "No out-of-range readings recorded for this period.", styles.value);
            sheet.addMergedRegion(new CellRangeAddress(noneRow.getRowNum(), noneRow.getRowNum(), 0, lastCol));
        } else {
            String[] findingHeaders = {"Date/Time", "Peak Value", "Duration"};
            int findingHeaderRow = rowIndex;
            XSSFRow findingHeaderRowObj = sheet.createRow(rowIndex++);
            for (int i = 0; i < findingHeaders.length; i++) {
                writeCell(findingHeaderRowObj, i, findingHeaders[i], styles.tableHeader);
            }
            for (ChartAggregation.Excursion excursion : excursions) {
                XSSFRow row = sheet.createRow(rowIndex++);
                String durationText = excursion.endMs > excursion.startMs
                        ? DurationFormatter.formatRuntime(excursion.endMs - excursion.startMs) : "instant";
                writeCell(row, 0, DateUtils.formatDateTime(excursion.startMs), styles.tableCell);
                writeCell(row, 1, String.format(Locale.getDefault(), numberFormat, excursion.peakValue) + bundle.unit, styles.tableCell);
                writeCell(row, 2, durationText, styles.tableCell);
            }
            applyOutlineBorder(sheet, new CellRangeAddress(findingHeaderRow, rowIndex - 1, 0, findingHeaders.length - 1));
        }

        sheet.setColumnWidth(0, 20 * 256);
        for (int i = 1; i < NUM_COLS; i++) {
            sheet.setColumnWidth(i, 13 * 256);
        }
    }

    private void embedPicture(XSSFWorkbook workbook, XSSFSheet sheet, Bitmap bitmap,
                               int fromCol, int fromRow, int toCol, int toRow) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
        int pictureIndex = workbook.addPicture(baos.toByteArray(), Workbook.PICTURE_TYPE_PNG);

        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, fromCol, fromRow, toCol, toRow);
        XSSFPicture picture = drawing.createPicture(anchor, pictureIndex);
        picture.getClientAnchor().setAnchorType(org.apache.poi.ss.usermodel.ClientAnchor.AnchorType.MOVE_AND_RESIZE);
    }

    // ------------------------------------------------------------------
    // Raw Data sheet - every original reading, no aggregation. Deliberately
    // plain: this IS supposed to look like a data table.
    // ------------------------------------------------------------------

    private void writeRawDataSheet(XSSFWorkbook workbook, XlsxStyles styles, List<ParameterExportBundle> bundles) {
        XSSFSheet sheet = workbook.createSheet("Raw Data");

        // Merge every selected parameter's samples by timestamp so each row
        // is one logged moment across all selected parameters, matching the
        // on-screen combined readings table's shape.
        TreeMap<Long, float[]> merged = new TreeMap<>();
        for (int p = 0; p < bundles.size(); p++) {
            for (ChartAggregation.Sample s : bundles.get(p).samples) {
                float[] row = merged.computeIfAbsent(s.timestampMs, k -> {
                    float[] r = new float[bundles.size()];
                    Arrays.fill(r, Float.NaN);
                    return r;
                });
                row[p] = s.value;
            }
        }

        int rowIndex = 0;
        XSSFRow headerRow = sheet.createRow(rowIndex++);
        writeCell(headerRow, 0, "Timestamp", styles.tableHeader);
        for (int p = 0; p < bundles.size(); p++) {
            ParameterExportBundle bundle = bundles.get(p);
            String unitSuffix = bundle.unit.trim().isEmpty() ? "" : " (" + bundle.unit.trim() + ")";
            writeCell(headerRow, p + 1, bundle.displayParameter + unitSuffix, styles.tableHeader);
        }
        sheet.createFreezePane(0, 1);

        for (Map.Entry<Long, float[]> entry : merged.entrySet()) {
            XSSFRow row = sheet.createRow(rowIndex++);
            writeCell(row, 0, DateUtils.formatDateTime(entry.getKey()), styles.tableCell);
            float[] values = entry.getValue();
            for (int p = 0; p < values.length; p++) {
                if (Float.isNaN(values[p])) {
                    writeCell(row, p + 1, "", styles.tableCell);
                } else {
                    Cell cell = row.createCell(p + 1);
                    cell.setCellValue(values[p]);
                    cell.setCellStyle(styles.tableCell);
                }
            }
        }

        sheet.setColumnWidth(0, 22 * 256);
        for (int p = 0; p < bundles.size(); p++) {
            sheet.setColumnWidth(p + 1, 16 * 256);
        }
    }

    // ------------------------------------------------------------------
    // Small shared writing helpers
    // ------------------------------------------------------------------

    /** A full-width shaded title banner. Returns the next free row. */
    private int writeBanner(XSSFSheet sheet, XlsxStyles styles, int rowIndex, String title, int lastCol) {
        XSSFRow row = sheet.createRow(rowIndex);
        row.setHeightInPoints(28f);
        writeCell(row, 0, title, styles.banner);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, lastCol));
        return rowIndex + 1;
    }

    /** A lighter section sub-heading within a sheet (e.g. "Parameter Summary"). Returns the next free row. */
    private int writeSectionHeading(XSSFSheet sheet, XlsxStyles styles, int rowIndex, String title, int lastCol) {
        XSSFRow row = sheet.createRow(rowIndex);
        row.setHeightInPoints(20f);
        writeCell(row, 0, title, styles.sectionHeading);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, lastCol));
        return rowIndex + 1;
    }

    /** One label/value row inside a metadata card - value merged across the remaining width so long text doesn't clip against a populated neighbor. */
    private int writeMetadataRow(XSSFSheet sheet, XlsxStyles styles, int rowIndex, String label, String value, int lastCol) {
        XSSFRow row = sheet.createRow(rowIndex);
        writeCell(row, 0, label, styles.label);
        writeCell(row, 1, value, styles.value);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 1, lastCol));
        return rowIndex + 1;
    }

    private void writeCell(XSSFRow row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /** Applies a plain outline border around a rectangular region without disturbing each cell's own fill/font. */
    private void applyOutlineBorder(XSSFSheet sheet, CellRangeAddress region) {
        RegionUtil.setBorderTop(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderBottom(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderLeft(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderRight(BorderStyle.THIN, region, sheet);
    }

    /** Rough wrapped-line estimate (no font-metrics dependency, which would need java.awt on Android) so a paragraph's row height scales with its actual length instead of a fixed guess. */
    private float estimateWrappedRowHeightPoints(String text, int mergedCharWidth) {
        int charsPerLine = Math.max(20, mergedCharWidth);
        int lines = Math.max(1, (int) Math.ceil(text.length() / (double) charsPerLine));
        return Math.min(400f, lines * 16f + 6f);
    }

    private String safeSheetName(String name) {
        String cleaned = name.replaceAll("[\\\\/*?\\[\\]:]", " ").trim();
        return cleaned.length() > MAX_SHEET_NAME_LENGTH ? cleaned.substring(0, MAX_SHEET_NAME_LENGTH) : cleaned;
    }

    /** Cell styles built once per workbook and reused everywhere - POI styles are expensive to create per-cell. */
    private static final class XlsxStyles {
        final short brandColorIndex;
        final CellStyle banner;
        final CellStyle sectionHeading;
        final CellStyle label;
        final CellStyle value;
        final CellStyle tableHeader;
        final CellStyle tableCell;
        final CellStyle tableCellAlt;
        final CellStyle interpretation;

        XlsxStyles(XSSFWorkbook workbook) {
            brandColorIndex = IndexedColors.DARK_GREEN.getIndex();

            Font bannerFont = workbook.createFont();
            bannerFont.setBold(true);
            bannerFont.setFontHeightInPoints((short) 16);
            bannerFont.setColor(IndexedColors.WHITE.getIndex());
            CellStyle bannerStyle = workbook.createCellStyle();
            bannerStyle.setFont(bannerFont);
            bannerStyle.setFillForegroundColor(brandColorIndex);
            bannerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            bannerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            bannerStyle.setAlignment(HorizontalAlignment.LEFT);
            banner = bannerStyle;

            Font sectionFont = workbook.createFont();
            sectionFont.setBold(true);
            sectionFont.setFontHeightInPoints((short) 12);
            sectionFont.setColor(IndexedColors.DARK_GREEN.getIndex());
            CellStyle sectionStyle = workbook.createCellStyle();
            sectionStyle.setFont(sectionFont);
            sectionStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            sectionStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            sectionStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            sectionHeading = sectionStyle;

            Font labelFont = workbook.createFont();
            labelFont.setBold(true);
            label = workbook.createCellStyle();
            label.setFont(labelFont);
            label.setVerticalAlignment(VerticalAlignment.TOP);

            value = workbook.createCellStyle();
            value.setWrapText(true);
            value.setVerticalAlignment(VerticalAlignment.TOP);

            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            tableHeader = workbook.createCellStyle();
            tableHeader.setFont(headerFont);
            tableHeader.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
            tableHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            tableHeader.setBorderBottom(BorderStyle.THIN);
            tableHeader.setAlignment(HorizontalAlignment.CENTER);
            tableHeader.setWrapText(true);

            tableCell = workbook.createCellStyle();
            tableCell.setBorderBottom(BorderStyle.THIN);
            tableCell.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            tableCell.setWrapText(true);
            tableCell.setVerticalAlignment(VerticalAlignment.TOP);

            tableCellAlt = workbook.createCellStyle();
            tableCellAlt.cloneStyleFrom(tableCell);
            tableCellAlt.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            tableCellAlt.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            interpretation = workbook.createCellStyle();
            interpretation.setWrapText(true);
            interpretation.setVerticalAlignment(VerticalAlignment.TOP);
            interpretation.setIndention((short) 1);
        }
    }
}
