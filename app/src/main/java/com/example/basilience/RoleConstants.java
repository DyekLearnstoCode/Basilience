package com.example.basilience;

public class RoleConstants {
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_FARMER = "FARMER";
    public static final String PREF_DEVELOPER_TESTER = "developer_tester";
    public static final String PREF_DEVELOPER_MODE_DEVICE_ID = "developer_mode_device_id";

    public static boolean isDeveloperTester(android.content.SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(PREF_DEVELOPER_TESTER, false);
    }

    /** Display-only Title Case ("FARMER" -&gt; "Farmer"). Stored role values/permissions are untouched. */
    public static String displayName(String role) {
        if (role == null || role.trim().isEmpty()) return "—";
        String trimmed = role.trim();
        return trimmed.substring(0, 1).toUpperCase() + trimmed.substring(1).toLowerCase();
    }
}
