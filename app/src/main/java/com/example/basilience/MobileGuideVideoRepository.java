package com.example.basilience;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Owns the single, GLOBAL (not per-device) Firestore document backing the
 * Mobile App Video Tutorial - {@code appConfig/mobileGuideVideo}. The Mobile
 * Guide itself stays fully static (MobileGuideContent.java); only this one
 * video's title/description/URL/thumbnail is remotely editable, so adding
 * the real tutorial later never requires a new APK. No existing global
 * (non-per-device) config collection was found elsewhere in this project, so
 * this introduces the smallest one needed rather than reusing the per-device
 * hardwareGuide architecture, which would incorrectly scope an app-wide
 * tutorial to a single device.
 */
public class MobileGuideVideoRepository {

    private static final String DOC_PATH_COLLECTION = "appConfig";
    private static final String DOC_ID = "mobileGuideVideo";

    public interface VideoListener {
        void onVideo(@Nullable String videoTitle, @Nullable String videoDescription,
                     @Nullable String videoUrl, @Nullable String videoThumbnailUrl);
    }

    public interface ErrorListener {
        void onError(Exception e);
    }

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    private DocumentReference doc() {
        return db.collection(DOC_PATH_COLLECTION).document(DOC_ID);
    }

    /**
     * Live value of the configured video, including Firestore's own offline
     * cache - a document that's absent, or unreachable (errorListener),
     * simply reports no override, and MobileGuideFragment keeps its bundled
     * "Coming Soon" default in either case.
     */
    public ListenerRegistration observe(VideoListener listener, ErrorListener errorListener) {
        return doc().addSnapshotListener((snapshot, error) -> {
            if (error != null) {
                errorListener.onError(error);
                return;
            }
            if (snapshot == null || !snapshot.exists()) {
                listener.onVideo(null, null, null, null);
                return;
            }
            listener.onVideo(snapshot.getString("videoTitle"), snapshot.getString("videoDescription"),
                    snapshot.getString("videoUrl"), snapshot.getString("videoThumbnailUrl"));
        });
    }

    /** One-shot fetch for the editor dialog to pre-fill its form. */
    public Task<DocumentSnapshot> getOnce() {
        return doc().get();
    }

    /** Blank fields are stored as null so a cleared URL correctly falls back to "Coming Soon." */
    public Task<Void> save(@Nullable String videoTitle, @Nullable String videoDescription,
                            @Nullable String videoUrl, @Nullable String videoThumbnailUrl) {
        Map<String, Object> data = new HashMap<>();
        data.put("videoTitle", blankToNull(videoTitle));
        data.put("videoDescription", blankToNull(videoDescription));
        data.put("videoUrl", blankToNull(videoUrl));
        data.put("videoThumbnailUrl", blankToNull(videoThumbnailUrl));
        data.put("updatedBy", FirebaseAuth.getInstance().getUid());
        data.put("updatedAt", FieldValue.serverTimestamp());
        return doc().set(data, SetOptions.merge());
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
