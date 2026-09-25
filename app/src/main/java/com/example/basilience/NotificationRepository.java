package com.example.basilience;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import androidx.paging.PagingLiveData;
import androidx.paging.PagingSource;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldPath;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.functions.FirebaseFunctions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Coordinates Firestore (the only source of truth for notification content
 * and read state) and Room (the Notifications screen's local cache/read
 * model). The screen itself never touches Firestore documents or holds a
 * full in-memory list any more - it only ever observes Room via Paging 3 and
 * calls into this repository for writes.
 */
public class NotificationRepository {

    public enum ReadFilter { ALL, UNREAD, READ }

    private static final String TAG = "NotificationRepository";
    private static final int PAGE_SIZE = 50;

    private final NotificationDao dao;
    private final Database_Helper dbHelper;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private ListenerRegistration liveListener;
    // Firestore-side "Load Older" pagination state, deliberately kept here
    // rather than read off PagingDataAdapter's own append LoadState - Room's
    // PagingSource only knows what's ALREADY cached locally, it has no way
    // to know whether more remote history exists in Firestore to go fetch.
    private DocumentSnapshot oldestLoadedDoc;
    private boolean paginationStarted;
    private final MutableLiveData<Boolean> loadingOlder = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> hasMoreOlder = new MutableLiveData<>(false);

    public NotificationRepository(Context context) {
        this.dao = AppDatabase.getInstance(context).notificationDao();
        this.dbHelper = new Database_Helper();
    }

    public LiveData<Boolean> loadingOlderState() { return loadingOlder; }
    public LiveData<Boolean> hasMoreOlderState() { return hasMoreOlder; }

    public LiveData<List<NotificationDao.CategoryCount>> unreadCountsByCategory(String uid, String deviceId) {
        return dao.unreadCountsByCategory(uid, deviceId);
    }

    public LiveData<Boolean> hasUnspecifiedCategory(String uid, String deviceId) {
        return dao.hasUnspecifiedCategory(uid, deviceId);
    }

    /**
     * category is one of NotificationParameterKeys' canonical keys,
     * "UNSPECIFIED", "SYSTEM_OTHER", or null for "All Categories" - the same
     * string space NotificationEntity.category is written in at sync time.
     */
    public LiveData<PagingData<NotificationEntity>> observe(String uid, String deviceId,
                                                             ReadFilter filter, @Nullable String category) {
        Pager<Integer, NotificationEntity> pager = new Pager<>(
                new PagingConfig(PAGE_SIZE, PAGE_SIZE, false),
                () -> pagingSourceFor(uid, deviceId, filter, category));
        return PagingLiveData.getLiveData(pager);
    }

    private PagingSource<Integer, NotificationEntity> pagingSourceFor(String uid, String deviceId,
                                                                       ReadFilter filter, @Nullable String category) {
        if (category != null) {
            switch (filter) {
                case UNREAD: return dao.pagingSourceUnreadCategory(uid, deviceId, category);
                case READ: return dao.pagingSourceReadCategory(uid, deviceId, category);
                default: return dao.pagingSourceCategory(uid, deviceId, category);
            }
        }
        switch (filter) {
            case UNREAD: return dao.pagingSourceUnread(uid, deviceId);
            case READ: return dao.pagingSourceRead(uid, deviceId);
            default: return dao.pagingSourceAll(uid, deviceId);
        }
    }

    /**
     * Attaches the live Firestore listener (unchanged query from
     * Database_Helper) and upserts every snapshot into Room - the screen
     * observes Room, never this listener's results directly.
     */
    public void startSync(String uid, String deviceId, @Nullable OnFailureListener listenerErrorHandler) {
        stopSync();
        paginationStarted = false;
        oldestLoadedDoc = null;
        hasMoreOlder.setValue(false);
        loadingOlder.setValue(false);

        ioExecutor.execute(() -> dao.pruneOtherUsers(uid));

        // listenToNotifications()/loadOlderNotifications() read Database_Helper's
        // own selectedDeviceId field rather than taking it as a parameter -
        // this repository owns a private Database_Helper instance, so it must
        // be set here before either is called.
        dbHelper.setSelectedDeviceId(deviceId);

        liveListener = dbHelper.listenToNotifications((value, error) -> {
            if (error != null) {
                Log.e(TAG, "Notification listener failed", error);
                if (listenerErrorHandler != null) listenerErrorHandler.onFailure(error);
                return;
            }
            if (value == null) return;

            List<QueryDocumentSnapshot> docs = new ArrayList<>();
            for (QueryDocumentSnapshot doc : value) docs.add(doc);

            List<NotificationEntity> entities = new ArrayList<>();
            for (QueryDocumentSnapshot doc : docs) {
                NotificationEntity entity = toEntity(doc, uid, deviceId);
                if (entity != null) entities.add(entity);
            }

            if (!paginationStarted) {
                oldestLoadedDoc = docs.isEmpty() ? null : docs.get(docs.size() - 1);
                hasMoreOlder.postValue(docs.size() >= Database_Helper.NOTIFICATIONS_LIVE_PAGE_SIZE);
            }

            ioExecutor.execute(() -> dao.upsertAll(entities));
        });
    }

    public void stopSync() {
        if (liveListener != null) {
            liveListener.remove();
            liveListener = null;
        }
    }

    public void loadOlderPage(String uid, String deviceId, @Nullable OnFailureListener onFailure) {
        if (Boolean.TRUE.equals(loadingOlder.getValue()) || oldestLoadedDoc == null) return;
        paginationStarted = true;
        loadingOlder.setValue(true);

        dbHelper.loadOlderNotifications(oldestLoadedDoc)
                .addOnSuccessListener(snapshot -> {
                    List<QueryDocumentSnapshot> docs = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : snapshot) docs.add(doc);

                    List<NotificationEntity> entities = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : docs) {
                        NotificationEntity entity = toEntity(doc, uid, deviceId);
                        if (entity != null) entities.add(entity);
                    }
                    if (!docs.isEmpty()) oldestLoadedDoc = docs.get(docs.size() - 1);
                    hasMoreOlder.postValue(docs.size() >= Database_Helper.NOTIFICATIONS_OLDER_PAGE_SIZE);

                    ioExecutor.execute(() -> {
                        dao.upsertAll(entities);
                        loadingOlder.postValue(false);
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to load older notifications", e);
                    loadingOlder.postValue(false);
                    if (onFailure != null) onFailure.onFailure(e);
                });
    }

    /**
     * Optimistic Room update, then the same Firestore readBy write as
     * before, reverting the Room row on failure - the exact shape the old
     * in-memory-list version used, just against Room instead of a field.
     */
    public void markRead(String uid, String deviceId, String notificationId, @Nullable OnFailureListener onFailure) {
        List<String> ids = Collections.singletonList(notificationId);
        ioExecutor.execute(() -> dao.markReadLocally(uid, deviceId, ids));

        FirebaseFirestore.getInstance()
                .collection("devices").document(deviceId)
                .collection("notifications").document(notificationId)
                .update(FieldPath.of("readBy", uid), FieldValue.serverTimestamp())
                .addOnFailureListener(e -> {
                    ioExecutor.execute(() -> dao.markUnreadLocally(uid, deviceId, ids));
                    if (onFailure != null) onFailure.onFailure(e);
                });
    }

    public void markSelectedRead(String uid, String deviceId, List<String> notificationIds,
                                  @Nullable OnSuccessListener<Void> onSuccess, @Nullable OnFailureListener onFailure) {
        ioExecutor.execute(() -> dao.markReadLocally(uid, deviceId, notificationIds));
        dbHelper.markNotificationsRead(deviceId, notificationIds)
                .addOnSuccessListener(unused -> { if (onSuccess != null) onSuccess.onSuccess(null); })
                .addOnFailureListener(e -> {
                    ioExecutor.execute(() -> dao.markUnreadLocally(uid, deviceId, notificationIds));
                    if (onFailure != null) onFailure.onFailure(e);
                });
    }

    /**
     * The server-side callable stays the sole authority for the full-history
     * operation (see functions/index.js's markAllNotificationsReadForDevice
     * and the bulkReadUids-based counter design) - this only updates
     * whatever's currently cached in Room on success. Anything not yet
     * paginated into Room simply arrives already-marked-read the next time
     * it's fetched, since the server has already updated readBy by then.
     */
    public void markAllRead(String uid, String deviceId, @Nullable OnSuccessListener<Object> onSuccess,
                            @Nullable OnFailureListener onFailure) {
        Map<String, Object> data = new HashMap<>();
        data.put("deviceId", deviceId);
        // Every Cloud Function in this project is deployed to asia-southeast1
        // (see functions/index.js's setGlobalOptions) - FirebaseFunctions.getInstance()
        // with no region defaults to us-central1, where this callable doesn't
        // exist, and fails with NOT_FOUND (caught by on-device testing - see
        // Database_Helper's other callable invocations for the same pattern).
        FirebaseFunctions.getInstance("asia-southeast1").getHttpsCallable("markAllNotificationsReadForDevice")
                .call(data)
                .addOnSuccessListener(result -> {
                    ioExecutor.execute(() -> dao.markAllReadLocally(uid, deviceId));
                    if (onSuccess != null) onSuccess.onSuccess(result.getData());
                })
                .addOnFailureListener(e -> { if (onFailure != null) onFailure.onFailure(e); });
    }

    @Nullable
    private NotificationEntity toEntity(QueryDocumentSnapshot doc, String uid, String deviceId) {
        try {
            String notificationId = doc.getId();
            String message = doc.getString("message");
            String type = doc.getString("type");
            Long timestamp = readTimestampMillis(doc);
            if (message == null || type == null || timestamp == null) {
                Log.w(TAG, "Skipping malformed notification document: " + doc.getReference().getPath());
                return null;
            }

            NotificationEntity entity = new NotificationEntity();
            entity.uid = uid;
            entity.deviceId = deviceId;
            entity.notificationId = notificationId;
            entity.type = type;
            entity.message = message;
            entity.timestamp = timestamp;
            entity.occurredAtMs = doc.getLong("occurredAtMs");
            // Per-user read state, derived the same way the old in-memory
            // parser did: a document with no readBy entry for this user -
            // including every historical one - is unread. The legacy
            // document-wide isRead field is never consulted.
            entity.isRead = doc.get(FieldPath.of("readBy", uid)) != null;
            entity.recorderName = doc.getString("recorderName");
            entity.recorderUid = doc.getString("recorderUid");
            Boolean offlineRecorded = doc.getBoolean("offlineRecorded");
            Boolean smsFallbackUsed = doc.getBoolean("smsFallbackUsed");
            entity.offlineRecorded = offlineRecorded != null && offlineRecorded;
            entity.smsFallbackUsed = smsFallbackUsed != null && smsFallbackUsed;
            entity.parameterKey = NotificationParameterKeys.resolve(type, doc.getString("parameterKey"), notificationId);
            entity.category = classifyCategory(type, entity.parameterKey);
            return entity;
        } catch (Exception e) {
            Log.w(TAG, "Skipping malformed notification document: " + doc.getReference().getPath(), e);
            return null;
        }
    }

    /** Same classification NotificationFragment's category filter/dropdown queries by. */
    static String classifyCategory(String type, @Nullable String parameterKey) {
        if (!NotificationEntity.TYPE_PARAMETER.equals(type)) return "SYSTEM_OTHER";
        if (parameterKey == null) return "UNSPECIFIED";
        return parameterKey;
    }

    private static Long readTimestampMillis(QueryDocumentSnapshot doc) {
        Object raw = doc.get("timestamp");
        if (raw instanceof Number) return ((Number) raw).longValue();
        if (raw instanceof Timestamp) return ((Timestamp) raw).toDate().getTime();
        return null;
    }
}
