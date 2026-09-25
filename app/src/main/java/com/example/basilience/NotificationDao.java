package com.example.basilience;

import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;
import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Upsert;

import java.util.List;

@Dao
public interface NotificationDao {

    @Query("SELECT * FROM notifications WHERE uid = :uid AND deviceId = :deviceId " +
            "ORDER BY timestamp DESC, notificationId DESC")
    PagingSource<Integer, NotificationEntity> pagingSourceAll(String uid, String deviceId);

    @Query("SELECT * FROM notifications WHERE uid = :uid AND deviceId = :deviceId AND isRead = 0 " +
            "ORDER BY timestamp DESC, notificationId DESC")
    PagingSource<Integer, NotificationEntity> pagingSourceUnread(String uid, String deviceId);

    @Query("SELECT * FROM notifications WHERE uid = :uid AND deviceId = :deviceId AND isRead = 1 " +
            "ORDER BY timestamp DESC, notificationId DESC")
    PagingSource<Integer, NotificationEntity> pagingSourceRead(String uid, String deviceId);

    @Query("SELECT * FROM notifications WHERE uid = :uid AND deviceId = :deviceId AND category = :category " +
            "ORDER BY timestamp DESC, notificationId DESC")
    PagingSource<Integer, NotificationEntity> pagingSourceCategory(String uid, String deviceId, String category);

    @Query("SELECT * FROM notifications WHERE uid = :uid AND deviceId = :deviceId AND isRead = 0 AND category = :category " +
            "ORDER BY timestamp DESC, notificationId DESC")
    PagingSource<Integer, NotificationEntity> pagingSourceUnreadCategory(String uid, String deviceId, String category);

    @Query("SELECT * FROM notifications WHERE uid = :uid AND deviceId = :deviceId AND isRead = 1 AND category = :category " +
            "ORDER BY timestamp DESC, notificationId DESC")
    PagingSource<Integer, NotificationEntity> pagingSourceReadCategory(String uid, String deviceId, String category);

    /** One grouped query for every category's unread count, instead of one LiveData per category. */
    @Query("SELECT category, COUNT(*) as cnt FROM notifications " +
            "WHERE uid = :uid AND deviceId = :deviceId AND isRead = 0 GROUP BY category")
    LiveData<List<CategoryCount>> unreadCountsByCategory(String uid, String deviceId);

    @Query("SELECT EXISTS(SELECT 1 FROM notifications WHERE uid = :uid AND deviceId = :deviceId AND category = 'UNSPECIFIED')")
    LiveData<Boolean> hasUnspecifiedCategory(String uid, String deviceId);

    class CategoryCount {
        public String category;
        public int cnt;
    }

    @Upsert
    void upsertAll(List<NotificationEntity> rows);

    @Query("UPDATE notifications SET isRead = 1 WHERE uid = :uid AND deviceId = :deviceId AND notificationId IN (:notificationIds)")
    void markReadLocally(String uid, String deviceId, List<String> notificationIds);

    @Query("UPDATE notifications SET isRead = 0 WHERE uid = :uid AND deviceId = :deviceId AND notificationId IN (:notificationIds)")
    void markUnreadLocally(String uid, String deviceId, List<String> notificationIds);

    @Query("UPDATE notifications SET isRead = 1 WHERE uid = :uid AND deviceId = :deviceId")
    void markAllReadLocally(String uid, String deviceId);

    /** Bounds local storage growth across account switches on a shared device - see NotificationEntity's own comment. */
    @Query("DELETE FROM notifications WHERE uid != :keepUid")
    void pruneOtherUsers(String keepUid);
}
