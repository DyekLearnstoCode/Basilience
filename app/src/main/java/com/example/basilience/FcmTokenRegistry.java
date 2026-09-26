package com.example.basilience;

import android.content.Context;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * One push-token record per app installation:
 * users/{uid}/fcmTokens/{installationId} = { token, updatedAt, platform }.
 *
 * Every write and delete here touches only the record named by this
 * installation's own ID, so a second phone signed in as the same user can
 * neither replace nor remove this phone's token.
 */
final class FcmTokenRegistry {

    static final String COLLECTION = "fcmTokens";
    static final String PLATFORM = "android";

    private FcmTokenRegistry() {}

    static DocumentReference installationRef(FirebaseFirestore db, String uid, String installationId) {
        return db.collection("users").document(uid).collection(COLLECTION).document(installationId);
    }

    /** Creates or refreshes this installation's record. */
    static Task<Void> register(FirebaseFirestore db, String uid, String installationId, String token) {
        Map<String, Object> record = new HashMap<>();
        record.put("token", token);
        record.put("updatedAt", FieldValue.serverTimestamp());
        record.put("platform", PLATFORM);
        return installationRef(db, uid, installationId).set(record);
    }

    /** Deletes this installation's record only. */
    static Task<Void> unregister(FirebaseFirestore db, String uid, String installationId) {
        return installationRef(db, uid, installationId).delete();
    }

    /** Registers this installation for whoever is signed in; does nothing when nobody is. */
    static Task<Void> registerCurrentInstallation(Context context, String token) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return Tasks.forResult(null);
        return register(FirebaseFirestore.getInstance(), uid, InstallationId.get(context), token);
    }
}
