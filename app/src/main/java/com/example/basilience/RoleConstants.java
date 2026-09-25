package com.example.basilience;

public class RoleConstants {
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_FARMER = "FARMER";
    public static final String PREF_DEVELOPER_TESTER = "developer_tester";
    public static final String PREF_DEVELOPER_MODE_DEVICE_ID = "developer_mode_device_id";

    /**
     * The canonical role for a stored or remote role value: ROLE_ADMIN or
     * ROLE_FARMER (ignoring case and surrounding whitespace, since older
     * profiles were not always stored in uppercase), or null for anything
     * else - missing, blank, malformed or unknown. Callers must treat null as
     * "no valid role" and never as Admin.
     */
    public static String normalize(String role) {
        if (role == null) return null;
        String trimmed = role.trim();
        if (ROLE_ADMIN.equalsIgnoreCase(trimmed)) return ROLE_ADMIN;
        if (ROLE_FARMER.equalsIgnoreCase(trimmed)) return ROLE_FARMER;
        return null;
    }

    /** True only when the locally cached role is a valid ADMIN. A missing or invalid cached role is never Admin. */
    public static boolean isAdmin(android.content.SharedPreferences prefs) {
        return prefs != null && ROLE_ADMIN.equals(normalize(prefs.getString("user_role", null)));
    }

    public static boolean isDeveloperTester(android.content.SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(PREF_DEVELOPER_TESTER, false);
    }

    /** Display-only Title Case ("FARMER" -&gt; "Farmer"). Stored role values/permissions are untouched. */
    public static String displayName(String role) {
        if (role == null || role.trim().isEmpty()) return "--";
        String trimmed = role.trim();
        return trimmed.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + trimmed.substring(1).toLowerCase(java.util.Locale.ROOT);
    }
}
