package com.example.basilience.models;

import com.example.basilience.ChartAggregation;

import java.util.List;

/**
 * Everything one parameter's export output (a PDF summary block, an XLSX
 * parameter sheet, a Raw Data column) needs, computed once in
 * SystemReportsFragment from the same authoritative state the on-screen
 * report already used - so an export can never disagree with what the
 * farmer was looking at, and PDF/XLSX generation never re-derives stats or
 * thresholds a second, potentially different way.
 */
public class ParameterExportBundle {
    public final String canonicalParameter;
    public final String displayParameter;
    public final String unit;
    public final int decimals;
    public final Float rangeMin;
    public final Float rangeMax;
    public final String targetRangeText;
    public final float average;
    public final float high;
    public final float low;
    public final int readingCount;
    public final String status;
    public final String interpretation;
    /** Raw (timestamp, value) samples in x order, the same data currentReadings would hold for a single-parameter export. */
    public final List<ChartAggregation.Sample> samples;

    public ParameterExportBundle(String canonicalParameter, String displayParameter, String unit, int decimals,
                                  Float rangeMin, Float rangeMax, String targetRangeText,
                                  float average, float high, float low, int readingCount,
                                  String status, String interpretation, List<ChartAggregation.Sample> samples) {
        this.canonicalParameter = canonicalParameter;
        this.displayParameter = displayParameter;
        this.unit = unit;
        this.decimals = decimals;
        this.rangeMin = rangeMin;
        this.rangeMax = rangeMax;
        this.targetRangeText = targetRangeText;
        this.average = average;
        this.high = high;
        this.low = low;
        this.readingCount = readingCount;
        this.status = status;
        this.interpretation = interpretation;
        this.samples = samples;
    }
}
