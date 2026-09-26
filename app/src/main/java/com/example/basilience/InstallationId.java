package com.example.basilience;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * A random ID for this app installation, created once and kept for the life
 * of the installation. It names this phone's push-token record, so several
 * phones signed in as the same user each keep their own.
 *
 * It lives in its own preferences file on purpose: logging out clears the
 * session preferences, but the installation is still the same phone. A
 * reinstall or "clear data" starts a new installation with a new ID (backup
 * is off, so the ID is never restored onto another phone).
 */
final class InstallationId {

    private static final String PREFS = "basilience_installation";
    private static final String KEY = "installation_id";

    private InstallationId() {}

    static synchronized String get(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY, null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY, id).apply();
        }
        return id;
    }
}
