package com.example.basilience;

import com.google.firebase.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class DateUtils {

    private static final String DEFAULT_NULL = "---";

    /**
     * Formats a Timestamp to: MMM dd, yyyy • h:mm a
     * Example: Jul 22, 2026 • 4:15 PM
     */
    public static String formatDateTime(Timestamp timestamp) {
        if (timestamp == null) return DEFAULT_NULL;
        return formatDateTime(timestamp.toDate().getTime());
    }

    public static String formatDateTime(long milliseconds) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy • h:mm a", Locale.getDefault());
        return sdf.format(new java.util.Date(milliseconds));
    }

    public static String formatDate(Timestamp timestamp) {
        if (timestamp == null) return DEFAULT_NULL;
        return formatDate(timestamp.toDate().getTime());
    }

    public static String formatDate(long milliseconds) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
        return sdf.format(new java.util.Date(milliseconds));
    }

    public static String formatShortDate(Timestamp timestamp) {
        if (timestamp == null) return DEFAULT_NULL;
        return formatShortDate(timestamp.toDate().getTime());
    }

    public static String formatShortDate(long milliseconds) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd", Locale.getDefault());
        return sdf.format(new java.util.Date(milliseconds));
    }

    public static String formatTime(Timestamp timestamp) {
        if (timestamp == null) return DEFAULT_NULL;
        return formatTime(timestamp.toDate().getTime());
    }

    public static String formatTime(long milliseconds) {
        SimpleDateFormat sdf = new SimpleDateFormat("h:mm a", Locale.getDefault());
        return sdf.format(new java.util.Date(milliseconds));
    }
}
