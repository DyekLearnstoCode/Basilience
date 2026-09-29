package com.example.basilience;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.basilience.models.GuideSection;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the Firestore/Storage side of the per-device editable Hardware Guide -
 * {@code devices/{deviceId}/hardwareGuide/{componentKey}} - so
 * HardwareGuideFragment and HardwareGuideEditorFragment stay free of
 * Firebase-specific code. Firestore's own default offline persistence (no
 * special settings needed - see MyApp/Database_Helper, neither disables it)
 * is what makes "saved override -> cached override -> bundled default"
 * work: addSnapshotListener below delivers cached data immediately, before
 * falling back to bundled content for any component with no override at all.
 */
public class HardwareGuideRepository {

    public interface SectionsListener {
        void onSections(List<GuideSection> sections);
    }

    public interface ErrorListener {
        void onError(Exception e);
    }

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final FirebaseStorage storage = FirebaseStorage.getInstance();

    /** Live merge of bundled defaults with this device's saved overrides. Call remove() on the returned registration in onDestroyView. */
    public ListenerRegistration observeSections(String deviceId, SectionsListener listener, ErrorListener errorListener) {
        return hardwareGuideCollection(deviceId).addSnapshotListener((snapshot, error) -> {
            if (error != null) {
                errorListener.onError(error);
                return;
            }
            Map<HardwareComponentKey, QueryDocumentSnapshot> overrides = new EnumMap<>(HardwareComponentKey.class);
            if (snapshot != null) {
                for (QueryDocumentSnapshot doc : snapshot) {
                    HardwareComponentKey key = parseKey(doc.getId());
                    if (key != null) overrides.put(key, doc);
                }
            }
            List<GuideSection> merged = new ArrayList<>();
            for (GuideSection base : HardwareGuideContent.sections()) {
                QueryDocumentSnapshot override = base.getHardwareKey() != null ? overrides.get(base.getHardwareKey()) : null;
                merged.add(override != null ? applyOverride(base, override) : base);
            }
            listener.onSections(merged);
        });
    }

    /** One-shot fetch of a single component's raw override document, for the editor to pre-fill its form. Result may not exist. */
    public Task<DocumentSnapshot> getOverride(String deviceId, HardwareComponentKey key) {
        return overrideDoc(deviceId, key).get();
    }

    /**
     * Writes the full edited guide for one component. When newImageUri is null, the write only
     * touches the five text fields (SetOptions.merge()) so any existing custom image is left
     * exactly as-is; when non-null, the image is uploaded first and the whole document -
     * including the new imagePath/imageUrl - is overwritten in one call.
     */
    public Task<Void> saveOverride(String deviceId, HardwareComponentKey key, String purpose,
                                    List<String> normalOperation, List<String> indicators,
                                    List<String> commonProblems, List<String> troubleshooting,
                                    @Nullable Uri newImageUri) {
        DocumentReference docRef = overrideDoc(deviceId, key);
        if (newImageUri == null) {
            Map<String, Object> data = textFields(key, purpose, normalOperation, indicators, commonProblems, troubleshooting);
            return docRef.set(data, SetOptions.merge());
        }

        String path = "hardwareGuideImages/" + deviceId + "/" + key.name() + "/" + System.currentTimeMillis() + ".jpg";
        StorageReference ref = storage.getReference().child(path);
        return ref.putFile(newImageUri)
                .continueWithTask(uploadTask -> {
                    if (!uploadTask.isSuccessful()) throw taskException(uploadTask);
                    return ref.getDownloadUrl();
                })
                .continueWithTask(urlTask -> {
                    if (!urlTask.isSuccessful()) throw taskException(urlTask);
                    Map<String, Object> data = textFields(key, purpose, normalOperation, indicators, commonProblems, troubleshooting);
                    data.put("imagePath", path);
                    data.put("imageUrl", urlTask.getResult().toString());
                    return docRef.set(data);
                });
    }

    /**
     * Writes just the video card fields for one component (currently only
     * {@link HardwareComponentKey#VIDEO_TUTORIAL}) - separate from
     * {@link #saveOverride} since the video card has no purpose/steps/image
     * of its own and shouldn't force an Admin through that unrelated form.
     * Blank title/description/url/thumbnail are stored as null so the
     * bundled default text is used and {@link com.example.basilience.models.GuideSection#hasVideo()}
     * correctly falls back to "Coming Soon" for a blank URL.
     */
    public Task<Void> saveVideoOverride(String deviceId, HardwareComponentKey key, @Nullable String videoTitle,
                                         @Nullable String videoDescription, @Nullable String videoUrl,
                                         @Nullable String videoThumbnailUrl) {
        Map<String, Object> data = new HashMap<>();
        data.put("componentKey", key.name());
        data.put("videoTitle", blankToNull(videoTitle));
        data.put("videoDescription", blankToNull(videoDescription));
        data.put("videoUrl", blankToNull(videoUrl));
        data.put("videoThumbnailUrl", blankToNull(videoThumbnailUrl));
        data.put("updatedBy", FirebaseAuth.getInstance().getUid());
        data.put("updatedAt", FieldValue.serverTimestamp());
        return overrideDoc(deviceId, key).set(data, SetOptions.merge());
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /** Clears just the custom image, leaving any saved text overrides untouched. A device-cleanup Cloud Function deletes the now-orphaned Storage file. */
    public Task<Void> restoreDefaultImage(String deviceId, HardwareComponentKey key) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("imagePath", FieldValue.delete());
        updates.put("imageUrl", FieldValue.delete());
        return overrideDoc(deviceId, key).set(updates, SetOptions.merge());
    }

    /** Deletes the whole override document, so this component falls back entirely to the bundled default (text and image). */
    public Task<Void> restoreDefaultGuide(String deviceId, HardwareComponentKey key) {
        return overrideDoc(deviceId, key).delete();
    }

    private Map<String, Object> textFields(HardwareComponentKey key, String purpose, List<String> normalOperation,
                                            List<String> indicators, List<String> commonProblems, List<String> troubleshooting) {
        Map<String, Object> data = new HashMap<>();
        data.put("componentKey", key.name());
        data.put("purpose", purpose);
        data.put("normalOperation", normalOperation);
        data.put("indicators", indicators);
        data.put("commonProblems", commonProblems);
        data.put("troubleshooting", troubleshooting);
        data.put("updatedBy", FirebaseAuth.getInstance().getUid());
        data.put("updatedAt", FieldValue.serverTimestamp());
        return data;
    }

    private GuideSection applyOverride(GuideSection base, DocumentSnapshot doc) {
        GuideSection.Builder b = base.toBuilder();
        String purpose = doc.getString("purpose");
        if (purpose != null) b.purpose(purpose);
        List<String> normalOperation = stringList(doc, "normalOperation");
        if (normalOperation != null) b.steps(normalOperation);
        List<String> indicators = stringList(doc, "indicators");
        if (indicators != null) b.indicators(indicators);
        List<String> commonProblems = stringList(doc, "commonProblems");
        if (commonProblems != null) b.commonProblems(commonProblems);
        List<String> troubleshooting = stringList(doc, "troubleshooting");
        if (troubleshooting != null) b.troubleshooting(troubleshooting);
        String imageUrl = doc.getString("imageUrl");
        if (imageUrl != null) b.imageUrl(imageUrl);
        String videoTitle = doc.getString("videoTitle");
        String videoDescription = doc.getString("videoDescription");
        if (videoTitle != null || videoDescription != null) {
            b.video(videoTitle != null ? videoTitle : base.getVideoTitle(),
                    videoDescription != null ? videoDescription : base.getVideoDescription());
        }
        String videoUrl = doc.getString("videoUrl");
        if (videoUrl != null) b.videoUrl(videoUrl);
        String videoThumbnailUrl = doc.getString("videoThumbnailUrl");
        if (videoThumbnailUrl != null) b.videoThumbnailUrl(videoThumbnailUrl);
        return b.build();
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static List<String> stringList(DocumentSnapshot doc, String field) {
        Object raw = doc.get(field);
        return raw instanceof List ? (List<String>) raw : null;
    }

    @Nullable
    private static HardwareComponentKey parseKey(String name) {
        try {
            return HardwareComponentKey.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Exception taskException(@NonNull Task<?> task) {
        Exception e = task.getException();
        return e != null ? e : new Exception("Unknown error");
    }

    private DocumentReference overrideDoc(String deviceId, HardwareComponentKey key) {
        return hardwareGuideCollection(deviceId).document(key.name());
    }

    private com.google.firebase.firestore.CollectionReference hardwareGuideCollection(String deviceId) {
        return db.collection("devices").document(deviceId).collection("hardwareGuide");
    }
}
