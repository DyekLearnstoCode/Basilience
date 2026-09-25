package com.example.basilience.models;

/**
 * One row of the Parameter Report's combined readings table: every parameter's
 * value at a single logged moment, already formatted for display, plus
 * whether each value fell outside its configured target range.
 */
public class ParameterTableRow {
    public final String dateTimeText;
    public final String ph;
    public final boolean phOutOfRange;
    public final String ec;
    public final boolean ecOutOfRange;
    public final String airTemp;
    public final boolean airTempOutOfRange;
    public final String humidity;
    public final boolean humidityOutOfRange;
    public final String waterTemp;
    public final boolean waterTempOutOfRange;
    public final String waterLevel;
    public final boolean waterLevelOutOfRange;

    public ParameterTableRow(String dateTimeText,
                              String ph, boolean phOutOfRange,
                              String ec, boolean ecOutOfRange,
                              String airTemp, boolean airTempOutOfRange,
                              String humidity, boolean humidityOutOfRange,
                              String waterTemp, boolean waterTempOutOfRange,
                              String waterLevel, boolean waterLevelOutOfRange) {
        this.dateTimeText = dateTimeText;
        this.ph = ph;
        this.phOutOfRange = phOutOfRange;
        this.ec = ec;
        this.ecOutOfRange = ecOutOfRange;
        this.airTemp = airTemp;
        this.airTempOutOfRange = airTempOutOfRange;
        this.humidity = humidity;
        this.humidityOutOfRange = humidityOutOfRange;
        this.waterTemp = waterTemp;
        this.waterTempOutOfRange = waterTempOutOfRange;
        this.waterLevel = waterLevel;
        this.waterLevelOutOfRange = waterLevelOutOfRange;
    }
}
