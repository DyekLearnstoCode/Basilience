package com.example.basilience;

import com.google.firebase.firestore.IgnoreExtraProperties;

@IgnoreExtraProperties
public class Cycle {
    private int cycleNo;
    private String startDate;
    private String endDate;

    // Required for Firestore
    public Cycle() {}

    public Cycle(int cycleNo, String startDate, String endDate) {
        this.cycleNo = cycleNo;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public int getCycleNo() { return cycleNo; }
    public void setCycleNo(int cycleNo) { this.cycleNo = cycleNo; }

    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }

    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }
}
