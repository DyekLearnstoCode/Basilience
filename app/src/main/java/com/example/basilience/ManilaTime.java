package com.example.basilience;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * The one definition of the farm's business day: Asia/Manila.
 *
 * Firebase timestamps are absolute instants and are never changed. What
 * belongs here is the calendar arithmetic around them (which day a date the
 * user picked means, and "N days after" a harvest), so it does not shift with
 * the phone's own timezone or daylight-saving rules. Plain Java on purpose.
 */
final class ManilaTime {

    static final String ZONE_ID = "Asia/Manila";
    static final TimeZone ZONE = TimeZone.getTimeZone(ZONE_ID);

    private ManilaTime() {}

    /** The instant of 00:00:00.000 Manila time on the given calendar day (month is 0-based, as in Calendar). */
    static long startOfDay(int year, int month, int dayOfMonth) {
        Calendar cal = Calendar.getInstance(ZONE);
        cal.clear();
        cal.set(year, month, dayOfMonth, 0, 0, 0);
        return cal.getTimeInMillis();
    }

    /** The instant that is the given number of Manila calendar days after epochMs, at the same Manila time of day. */
    static long addDays(long epochMs, int days) {
        Calendar cal = Calendar.getInstance(ZONE);
        cal.setTimeInMillis(epochMs);
        cal.add(Calendar.DAY_OF_YEAR, days);
        return cal.getTimeInMillis();
    }

    /** Manila's calendar today as {year, month (0-based), dayOfMonth}. */
    static int[] today(long nowMs) {
        Calendar cal = Calendar.getInstance(ZONE);
        cal.setTimeInMillis(nowMs);
        return new int[]{cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)};
    }
}
