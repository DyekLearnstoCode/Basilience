package com.example.basilience;

import com.google.firebase.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class DateUtils {

    private static final String DEFAULT_NULL = "---";

    // SimpleDateFormat isn't thread-safe, so each pattern gets its own
    // ThreadLocal instance instead of a bare static one - reused across
    // calls on the same thread (mainly UI-thread RecyclerView binds, where
    // reconstructing one per row per bind was measurable overhead on a
    // freshly-rendered/re-filtered list) without risking cross-thread
    // corruption if a background callback ever formats a date too.
    private static final ThreadLocal<SimpleDateFormat> DATE_TIME_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("MMM dd, yyyy • h:mm a", Locale.getDefault()));
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()));
    private static final ThreadLocal<SimpleDateFormat> DATE_SLASH_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("MM/dd/yy", Locale.getDefault()));
    private static final ThreadLocal<SimpleDateFormat> SHORT_DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("MMM dd", Locale.getDefault()));
    private static final ThreadLocal<SimpleDateFormat> TIME_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("h:mm a", Locale.getDefault()));

    /**
     * Formats a Timestamp to: MMM dd, yyyy • h:mm a
     * Example: Jul 22, 2026 • 4:15 PM
     */
    public static String formatDateTime(Timestamp timestamp) {
        if (timestamp == null) return DEFAULT_NULL;
        return formatDateTime(timestamp.toDate().getTime());
    }

    public static String formatDateTime(long milliseconds) {
        return DATE_TIME_FORMAT.get().format(new java.util.Date(milliseconds));
    }

    public static String formatDate(Timestamp timestamp) {
        if (timestamp == null) return DEFAULT_NULL;
        return formatDate(timestamp.toDate().getTime());
    }

    public static String formatDate(long milliseconds) {
        return DATE_FORMAT.get().format(new java.util.Date(milliseconds));
    }

    public static String formatShortDate(Timestamp timestamp) {
        if (timestamp == null) return DEFAULT_NULL;
        return formatShortDate(timestamp.toDate().getTime());
    }

    public static String formatShortDate(long milliseconds) {
        return SHORT_DATE_FORMAT.get().format(new java.util.Date(milliseconds));
    }

    public static String formatTime(Timestamp timestamp) {
        if (timestamp == null) return DEFAULT_NULL;
        return formatTime(timestamp.toDate().getTime());
    }

    public static String formatTime(long milliseconds) {
        return TIME_FORMAT.get().format(new java.util.Date(milliseconds));
    }

    /**
     * Formats a moment onto two lines for a narrow table cell: MM/dd/yy on
     * top, h:mm a underneath. Example: "09/22/26\n12:28 PM".
     */
    public static String formatDateTimeCompact(long milliseconds) {
        java.util.Date date = new java.util.Date(milliseconds);
        return DATE_SLASH_FORMAT.get().format(date) + "\n" + TIME_FORMAT.get().format(date);
    }
}
