package com.example.basilience;

public class RoleConstants {
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_FARMER = "FARMER";

    /** Display-only Title Case ("FARMER" -&gt; "Farmer"). Stored role values/permissions are untouched. */
    public static String displayName(String role) {
        if (role == null || role.trim().isEmpty()) return "—";
        String trimmed = role.trim();
        return trimmed.substring(0, 1).toUpperCase() + trimmed.substring(1).toLowerCase();
    }
}
