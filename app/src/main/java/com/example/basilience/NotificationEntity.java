package com.example.basilience;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

/**
 * Local cache row for one notification, scoped to exactly one (user, device).
 * Firestore's devices/{deviceId}/notifications/{notificationId} stays the
 * source of truth for content and read state (readBy.{uid}); this table is
 * a read-model the Notifications screen pages through, kept in sync by
 * NotificationRepository.
 *
 * <p>The composite primary key is deliberate: it lets rows for different
 * accounts or different devices coexist in the same local database without
 * ever colliding or leaking into each other's queries - every DAO query
 * filters on (uid, deviceId) explicitly, so a stale row from a previous
 * signed-in account on a shared device simply never matches anything the
 * current session runs, with no separate cleanup required for correctness
 * (though NotificationRepository opportunistically prunes other-uid rows on
 * login to bound local storage growth).
 */
@Entity(tableName = "notifications",
        primaryKeys = {"uid", "deviceId", "notificationId"},
        indices = {
                // Backs the main paged sort/query (ORDER BY timestamp DESC
                // within a user+device scope) - every filter variant
                // (All/Unread/Read/Category) still sorts by timestamp first.
                @Index({"uid", "deviceId", "timestamp"}),
                @Index({"uid", "deviceId", "isRead"}),
                @Index({"uid", "deviceId", "category"})
        })
public class NotificationEntity {
    public static final String TYPE_PARAMETER = "parameter";
    public static final String TYPE_HARVEST = "harvest";
    public static final String TYPE_HARDWARE = "hardware";
    public static final String TYPE_CONNECTIVITY_OFFLINE = "connectivity_offline";
    public static final String TYPE_CONNECTIVITY_RECOVERY = "connectivity_recovery";
    public static final String TYPE_INFO = "info";

    @NonNull public String uid;
    @NonNull public String deviceId;
    @NonNull public String notificationId;

    /** Raw Firestore "type" field - one of the TYPE_* constants above. */
    public String type;
    /** Canonical parameter key (see NotificationParameterKeys), or null for non-parameter types. */
    public String parameterKey;
    /**
     * Derived, queryable category bucket - one of the canonical parameter
     * keys, "UNSPECIFIED", or "SYSTEM_OTHER" - computed once at upsert time
     * (same classification NotificationFragment.classifyItem() already
     * does) so the category filter is a plain indexed WHERE clause instead
     * of a Java-side scan over everything cached.
     */
    public String category;

    public String message;
    /** Insertion-monotonic; the sort key. Never backdated - see functions/onNotificationQueued. */
    public long timestamp;
    /** True device-observed time, display-only. Never used for sort/pagination. */
    public Long occurredAtMs;

    /** This user's readBy.{uid} presence - never another user's, never the legacy shared isRead. */
    public boolean isRead;

    public boolean offlineRecorded;
    public boolean smsFallbackUsed;
    public String recorderName;
    public String recorderUid;
}
