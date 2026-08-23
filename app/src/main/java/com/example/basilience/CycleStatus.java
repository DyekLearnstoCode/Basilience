package com.example.basilience;

import androidx.annotation.Nullable;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;

/**
 * Single source of truth for what "the active cycle" means.
 *
 * The active-cycle test used to live inside a Firestore query
 * ({@code whereEqualTo("status", "ACTIVE")}). That made a normal, valid
 * lifecycle state ("this device has no active cycle right now") depend on a
 * server-side single-field index, and it disagreed with the legacy-status
 * handling used everywhere else in {@link Database_Helper}. Both problems go
 * away by reading the cycles collection with an already-indexed ordering and
 * deciding here, on the client, which document is active.
 */
public final class CycleStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String COMPLETED = "COMPLETED";

    private CycleStatus() {}

    /**
     * A cycle counts as active when it is explicitly marked ACTIVE, or when it
     * predates the status field entirely and was never completed.
     *
     * The null-status branch matches the "Legacy support" convention already
     * applied by the harvest transactions in {@code Database_Helper}; the
     * additional endDate check keeps a legacy cycle that was finished before
     * the status field existed from blocking new cycles forever, because
     * completion has always written an endDate.
     */
    public static boolean isActive(@Nullable DocumentSnapshot doc) {
        if (doc == null || !doc.exists()) return false;
        String status = doc.getString("status");
        if (status == null) return doc.getTimestamp("endDate") == null;
        return ACTIVE.equalsIgnoreCase(status);
    }

    /**
     * @return the active cycle document, or {@code null} when the device has no
     *         active cycle. A null return is a valid state, not a failure.
     */
    @Nullable
    public static DocumentSnapshot findActive(@Nullable QuerySnapshot cycles) {
        if (cycles == null) return null;
        for (DocumentSnapshot doc : cycles.getDocuments()) {
            if (isActive(doc)) return doc;
        }
        return null;
    }

    /** Convenience for callers that only need the yes/no answer. */
    public static boolean hasActive(@Nullable QuerySnapshot cycles) {
        return findActive(cycles) != null;
    }
}
