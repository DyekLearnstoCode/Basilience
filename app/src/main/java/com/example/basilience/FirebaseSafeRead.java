package com.example.basilience;

import android.util.Log;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.firestore.DocumentSnapshot;

/**
 * Type-checked reads of Firebase values that never throw on malformed data.
 *
 * RTDB's typed getValue(X.class) and Firestore's toObject()/getDouble()/
 * getString()/... throw a RuntimeException when the stored value has a
 * different type than requested, and inside a listener callback that
 * crashes the whole app. Every method here returns null instead, and logs
 * the offending path once per call, so the caller can decide what "no usable
 * value" means at that site (skip the record, keep the last valid UI value,
 * show "unavailable"). A missing value is not malformed and returns null
 * silently.
 *
 * Nothing is coerced: a String "5" is not turned into 5, and a wrong-typed
 * value never becomes 0 or false. Same approach as
 * DeviceConnectionManager.readLongValue().
 */
final class FirebaseSafeRead {

    private static final String TAG = "FirebaseSafeRead";

    private FirebaseSafeRead() {}

    // ---------------- Realtime Database ----------------

    static Boolean bool(DataSnapshot snapshot) {
        Object raw = raw(snapshot);
        if (raw == null) return null;
        if (raw instanceof Boolean) return (Boolean) raw;
        logMismatch(snapshot, "Boolean", raw);
        return null;
    }

    static Double dbl(DataSnapshot snapshot) {
        Object raw = raw(snapshot);
        if (raw == null) return null;
        if (raw instanceof Number) {
            double value = ((Number) raw).doubleValue();
            if (!Double.isNaN(value) && !Double.isInfinite(value)) return value;
        }
        logMismatch(snapshot, "Double", raw);
        return null;
    }

    static Long lng(DataSnapshot snapshot) {
        Object raw = raw(snapshot);
        if (raw == null) return null;
        if (raw instanceof Number) {
            // Whole numbers are accepted in any numeric shape (the firmware
            // writes them as doubles), but a fractional value is not an
            // integer and is not silently truncated.
            double asDouble = ((Number) raw).doubleValue();
            if (!Double.isNaN(asDouble) && !Double.isInfinite(asDouble) && asDouble == Math.rint(asDouble)) {
                return ((Number) raw).longValue();
            }
        }
        logMismatch(snapshot, "Long", raw);
        return null;
    }

    static Integer integer(DataSnapshot snapshot) {
        Long value = lng(snapshot);
        if (value == null) return null;
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            logMismatch(snapshot, "Integer (out of range)", value);
            return null;
        }
        return value.intValue();
    }

    static String str(DataSnapshot snapshot) {
        Object raw = raw(snapshot);
        if (raw == null) return null;
        if (raw instanceof String) return (String) raw;
        logMismatch(snapshot, "String", raw);
        return null;
    }

    private static Object raw(DataSnapshot snapshot) {
        if (snapshot == null) return null;
        try {
            return snapshot.getValue();
        } catch (RuntimeException e) {
            Log.w(TAG, "Unreadable value at " + snapshot.getRef(), e);
            return null;
        }
    }

    private static void logMismatch(DataSnapshot snapshot, String expected, Object raw) {
        Log.w(TAG, "Ignoring malformed value at " + snapshot.getRef()
                + ": expected " + expected + " but found " + raw.getClass().getSimpleName());
    }

    // ---------------- Firestore ----------------

    /** doc.toObject(cls), or null (logged, with the document path) if the document does not map to the model. */
    static <T> T toObject(DocumentSnapshot doc, Class<T> type) {
        if (doc == null) return null;
        try {
            return doc.toObject(type);
        } catch (RuntimeException e) {
            Log.e(TAG, "Skipping malformed document " + doc.getReference().getPath()
                    + " (cannot map to " + type.getSimpleName() + ")", e);
            return null;
        }
    }

    static Double fsDouble(DocumentSnapshot doc, String field) {
        if (doc == null) return null;
        try {
            Double value = doc.getDouble(field);
            if (value != null && (value.isNaN() || value.isInfinite())) {
                Log.w(TAG, "Ignoring non-finite " + field + " in " + doc.getReference().getPath());
                return null;
            }
            return value;
        } catch (RuntimeException e) {
            logFsMismatch(doc, field, "Number", e);
            return null;
        }
    }

    static Long fsLong(DocumentSnapshot doc, String field) {
        if (doc == null) return null;
        try {
            return doc.getLong(field);
        } catch (RuntimeException e) {
            logFsMismatch(doc, field, "Number", e);
            return null;
        }
    }

    static String fsString(DocumentSnapshot doc, String field) {
        if (doc == null) return null;
        try {
            return doc.getString(field);
        } catch (RuntimeException e) {
            logFsMismatch(doc, field, "String", e);
            return null;
        }
    }

    static Boolean fsBoolean(DocumentSnapshot doc, String field) {
        if (doc == null) return null;
        try {
            return doc.getBoolean(field);
        } catch (RuntimeException e) {
            logFsMismatch(doc, field, "Boolean", e);
            return null;
        }
    }

    private static void logFsMismatch(DocumentSnapshot doc, String field, String expected, RuntimeException e) {
        Log.w(TAG, "Ignoring malformed field '" + field + "' in " + doc.getReference().getPath()
                + ": expected " + expected, e);
    }
}
