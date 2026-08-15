package com.example.basilience.models;

public class SensorData {

    // Boxed values preserve Firebase null/missing fields instead of fabricating 0.0.
    public Double airTemperature;
    public Double humidity;
    public Double waterTemperature;
    public Double waterLevel;
    public Double ec;
    public Double tds;
    public Double ph;
    public Long timestamp;

    public SensorData() {}
}
