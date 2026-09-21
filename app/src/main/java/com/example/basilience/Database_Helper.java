package com.example.basilience;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FieldPath;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class Database_Helper {

    private final FirebaseAuth auth;
    private final FirebaseFirestore db;
    private final FirebaseDatabase rtdb;

    private static boolean isConnectedListenerRegistered = false;

    // Must match firestore.rules' isNotDuplicateHarvest() guard window exactly -
    // see addHarvestTransaction()'s duplicate-submission check.
    private static final long HARVEST_DUPLICATE_GUARD_MS = 60_000L;

    // A scale reading must not remain reusable indefinitely - see
    // findUnconsumedHarvestScaleReading(). 30 minutes.
    private static final long HARVEST_SCALE_MAX_READING_AGE_MS = 30 * 60 * 1000L;

    // How many of the most recent RTDB harvests/ entries to consider as
    // candidates - bounded on purpose (never an unbounded/full-history
    // read), and chosen to exactly match Firestore's own whereIn() cap of
    // 10 values, so the consumption check below is a single query rather
    // than one read per candidate.
    private static final int HARVEST_SCALE_CANDIDATE_WINDOW = 10;

    private String selectedDeviceId;
    private String cachedRole;

    private DatabaseReference deviceRef;
    private DatabaseReference sensorsRef;
    private DatabaseReference actuatorsRef;
    private DatabaseReference statusRef;
    private DatabaseReference commandsRef;

    public Database_Helper() {
        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        // RTDB Initialization
        rtdb = FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app");
        deviceRef = rtdb.getReference("devices");

        // Connectivity Monitoring
        if (!isConnectedListenerRegistered) {
            DatabaseReference connectedRef = rtdb.getReference(".info/connected");
            connectedRef.addValueEventListener(new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snapshot) {
                    Boolean connected = snapshot.getValue(Boolean.class);
                    if (Boolean.TRUE.equals(connected)) {
                        Log.d("SensorDebug", "RTDB Connected");
                    } else {
                        Log.d("SensorDebug", "RTDB Disconnected");
                    }
                }

                @Override
                public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) {
                    Log.e("SensorDebug", "Connectivity listener cancelled: " + error.getMessage());
                }
            });
            isConnectedListenerRegistered = true;
        }
    }

    public void setSelectedDeviceId(String deviceId) {

        this.selectedDeviceId = deviceId;

        deviceRef = rtdb
                .getReference("devices")
                .child(deviceId);

        sensorsRef = deviceRef.child("sensors");
        actuatorsRef = deviceRef.child("actuators");
        statusRef = deviceRef.child("status");
        commandsRef = deviceRef.child("commands");
    }

    public String getSelectedDeviceId() {
        return this.selectedDeviceId;
    }

    public interface EmailVerificationCallback {
        void onSuccess();
        void onFailure(String errorMessage);
    }

    // --------------------
    // AUTHENTICATION
    // --------------------
    public Task<AuthResult> registerAuth(String email, String password) {
        return auth.createUserWithEmailAndPassword(email, password);
    }

    public Task<AuthResult> loginAuth(String email, String password) {
        return auth.signInWithEmailAndPassword(email, password);
    }

    public Task<Void> sendPasswordResetEmail(String email) {
        return auth.sendPasswordResetEmail(email);
    }

    public Task<Void> logout() {
        String uid = getCurrentUid();
        if (uid == null) {
            auth.signOut();
            cachedRole = null;
            return Tasks.forResult(null);
        }

        FirebaseMessaging messaging = FirebaseMessaging.getInstance();
        Task<Void> cleanup = messaging.getToken().continueWithTask(tokenTask -> {
            Task<Void> firestoreCleanup;
            if (tokenTask.isSuccessful() && tokenTask.getResult() != null) {
                String installationToken = tokenTask.getResult();
                DocumentReference userRef = db.collection("users").document(uid);
                firestoreCleanup = db.runTransaction(transaction -> {
                    DocumentSnapshot user = transaction.get(userRef);
                    if (installationToken.equals(user.getString("fcmToken"))) {
                        transaction.update(userRef, "fcmToken", FieldValue.delete());
                    }
                    return null;
                });
            } else {
                Log.w(TAG, "Unable to read installation token during logout", tokenTask.getException());
                firestoreCleanup = Tasks.forResult(null);
            }

            return firestoreCleanup
                    .addOnFailureListener(error -> Log.w(TAG,
                            "Unable to clear FCM token during logout", error))
                    .continueWithTask(ignored -> messaging.deleteToken());
        }).addOnFailureListener(error -> Log.w(TAG,
                "Unable to invalidate FCM token during logout", error));

        TaskCompletionSource<Void> completion = new TaskCompletionSource<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable finishSignOut = () -> {
            if (!finished.compareAndSet(false, true)) return;
            auth.signOut();
            cachedRole = null;
            completion.setResult(null);
        };

        cleanup.addOnCompleteListener(ignored -> finishSignOut.run());
        new Handler(Looper.getMainLooper()).postDelayed(finishSignOut, 5000L);
        return completion.getTask();
    }

    public String getCurrentUid() {
        return auth.getCurrentUser() != null ? auth.getCurrentUser().getUid() : null;
    }

    /**
     * Helper to verify if the current user has ADMIN privileges.
     * Uses memory cache if available, otherwise fetches from Firestore.
     */
    private Task<Void> checkAdminTask() {
        if (RoleConstants.ROLE_ADMIN.equalsIgnoreCase(cachedRole)) {
            return Tasks.forResult(null);
        }

        String uid = getCurrentUid();
        if (uid == null) return Tasks.forException(new Exception("User not authenticated"));

        return getUserProfile(uid).onSuccessTask(doc -> {
            if (doc.exists()) {
                cachedRole = doc.getString("role");
                if (RoleConstants.ROLE_ADMIN.equalsIgnoreCase(cachedRole)) {
                    return Tasks.forResult(null);
                }
            }
            return Tasks.forException(new FirebaseFirestoreException(
                    "Permission Denied: Admin role required for this operation.",
                    FirebaseFirestoreException.Code.PERMISSION_DENIED));
        });
    }

    public void sendEmailVerification(EmailVerificationCallback callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user != null) {
            user.sendEmailVerification()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            Log.d(TAG, "Email verification sent successfully.");
                            callback.onSuccess();
                        } else {
                            String error = task.getException() != null ? task.getException().getMessage() : "Unknown error";
                            Log.e(TAG, "Failed to send verification email: " + error);
                            callback.onFailure(error);
                        }
                    });
        } else {
            Log.e(TAG, "No user is currently logged in.");
            callback.onFailure("No user is currently logged in.");
        }
    }

    // --------------------
    // FIRESTORE: USERS (Profiles)
    // --------------------
    public Task<Void> createUserProfile(String uid, String fullName, String email, String phone, String role, String ownerAdminUid) {
        Map<String, Object> user = new HashMap<>();
        user.put("fullName", fullName);
        user.put("email", email);
        user.put("phone", phone);
        user.put("role", role);
        user.put("ownerAdminUid", ownerAdminUid);
        user.put("isActive", true);
        user.put("emailVerified", false);
        user.put("createdAt", System.currentTimeMillis());
        return db.collection("users").document(uid).set(user);
    }

    public Task<DocumentSnapshot> getUserProfile(String uid) {
        return db.collection("users").document(uid).get();
    }

    public Task<Void> updateUserProfile(String uid, Map<String, Object> updates) {
        return db.collection("users").document(uid).update(updates);
    }

    public Task<QuerySnapshot> getAllUsers() {
        return db.collection("users").get();
    }

    public ListenerRegistration listenToUsers(EventListener<QuerySnapshot> listener) {
        return db.collection("users").addSnapshotListener(listener);
    }



    public Task<QuerySnapshot> getUsersByRole(String role) {
        return db.collection("users").whereEqualTo("role", role).get();
    }

    // --------------------
    // FIRESTORE: PERSONNEL (Now part of the top-level users collection)
    // --------------------
    public Task<QuerySnapshot> getAllMyPersonnel() {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("users")
                .whereEqualTo("ownerAdminUid", adminUid)
                .get();
    }

    public Task<QuerySnapshot> getMyPersonnelByRole(String role) {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("users")
                .whereEqualTo("ownerAdminUid", adminUid)
                .whereEqualTo("role", role.toUpperCase())
                .get();
    }

    public Task<Void> createFarmerAccountAndAssignToCurrentAdmin(String name, String email, String phone, String password) {
        return checkAdminTask().onSuccessTask(aVoid -> {
            String adminUid = getCurrentUid();
            if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

            FirebaseAuth secondaryAuth = FirebaseAuth.getInstance(FirebaseApp.getInstance("secondary"));

            return secondaryAuth.createUserWithEmailAndPassword(email, password)
                    .continueWithTask(task -> {
                        if (!task.isSuccessful()) throw task.getException();
                        if (task.getResult() == null || task.getResult().getUser() == null)
                            throw new Exception("User creation failed");

                        String farmerUid = task.getResult().getUser().getUid();

                        Map<String, Object> farmerProfile = new HashMap<>();
                        farmerProfile.put("fullName", name);
                        farmerProfile.put("email", email);
                        farmerProfile.put("phone", phone);
                        farmerProfile.put("role", RoleConstants.ROLE_FARMER); // Standardized to uppercase
                        farmerProfile.put("createdAt", System.currentTimeMillis());
                        farmerProfile.put("ownerAdminUid", adminUid);
                        farmerProfile.put("isActive", true);
                        farmerProfile.put("emailVerified", false);

                        return db.collection("users").document(farmerUid).set(farmerProfile)
                                .continueWithTask(t3 -> task.getResult().getUser().sendEmailVerification())
                                .addOnCompleteListener(done -> secondaryAuth.signOut());
                    });
        });
    }

    public Task<Void> updatePersonnelForCurrentAdmin(String personnelId, Map<String, Object> updates) {
        return checkAdminTask().onSuccessTask(aVoid -> db.collection("users").document(personnelId).update(updates));
    }

    public Task<Void> deletePersonnelForCurrentAdmin(String personnelId) {
        return checkAdminTask().onSuccessTask(aVoid -> {
            String adminUid = getCurrentUid();
            if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));
            DocumentReference personnelRef = db.collection("users").document(personnelId);
            return personnelRef.get().continueWithTask(profileTask -> {
                if (!profileTask.isSuccessful()) throw profileTask.getException();
                DocumentSnapshot profile = profileTask.getResult();
                if (!profile.exists() || !adminUid.equals(profile.getString("ownerAdminUid"))) {
                    throw new FirebaseFirestoreException("This personnel is not linked to your account.", FirebaseFirestoreException.Code.PERMISSION_DENIED);
                }
                return db.collection("deviceAssignments").whereEqualTo("userUid", personnelId).get();
            }).continueWithTask(assignmentsTask -> {
                if (!assignmentsTask.isSuccessful()) throw assignmentsTask.getException();
                com.google.firebase.firestore.WriteBatch batch = db.batch();
                batch.update(personnelRef, "ownerAdminUid", null);
                for (DocumentSnapshot assignment : assignmentsTask.getResult()) batch.delete(assignment.getReference());
                return batch.commit();
            });
        });
    }

    public Task<Void> linkExistingPersonnelByEmail(String email) {
        return checkAdminTask().onSuccessTask(aVoid -> {
            String adminUid = getCurrentUid();
            if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));
            return db.collection("users").whereEqualTo("email", email.trim()).limit(1).get().continueWithTask(queryTask -> {
                if (!queryTask.isSuccessful()) throw queryTask.getException();
                if (queryTask.getResult().isEmpty()) throw new FirebaseFirestoreException("No eligible personnel account was found.", FirebaseFirestoreException.Code.NOT_FOUND);
                DocumentSnapshot queriedProfile = queryTask.getResult().getDocuments().get(0);
                DocumentReference personnelRef = queriedProfile.getReference();

                // Fast-path check using the document this query already
                // fetched, instead of always paying for a THIRD sequential
                // round trip (checkAdminTask, this query, then a transaction
                // read) just to re-check the exact same two fields on the
                // exact same document. This is what made "already linked" -
                // easily the most common outcome, since an Admin usually
                // knows the personnel exists - visibly slow to report. The
                // transaction below still re-validates before writing, so a
                // genuine race (someone else linking this personnel in the
                // instant between this query and the write) is still caught
                // correctly; this only short-circuits the read-only outcomes
                // that don't need transactional consistency at all.
                if (!RoleConstants.ROLE_FARMER.equalsIgnoreCase(queriedProfile.getString("role"))) {
                    throw new FirebaseFirestoreException("No eligible personnel account was found.", FirebaseFirestoreException.Code.PERMISSION_DENIED);
                }
                String queriedLinkedAdminUid = queriedProfile.getString("ownerAdminUid");
                if (adminUid.equals(queriedLinkedAdminUid)) throw new FirebaseFirestoreException("This personnel is already linked to your account.", FirebaseFirestoreException.Code.ALREADY_EXISTS);
                if (queriedLinkedAdminUid != null && !queriedLinkedAdminUid.isEmpty()) throw new FirebaseFirestoreException("This personnel account cannot be linked to this Admin.", FirebaseFirestoreException.Code.PERMISSION_DENIED);

                return db.runTransaction(transaction -> {
                    DocumentSnapshot profile = transaction.get(personnelRef);
                    if (!RoleConstants.ROLE_FARMER.equalsIgnoreCase(profile.getString("role"))) {
                        throw new FirebaseFirestoreException("No eligible personnel account was found.", FirebaseFirestoreException.Code.PERMISSION_DENIED);
                    }
                    String linkedAdminUid = profile.getString("ownerAdminUid");
                    if (adminUid.equals(linkedAdminUid)) throw new FirebaseFirestoreException("This personnel is already linked to your account.", FirebaseFirestoreException.Code.ALREADY_EXISTS);
                    if (linkedAdminUid != null && !linkedAdminUid.isEmpty()) throw new FirebaseFirestoreException("This personnel account cannot be linked to this Admin.", FirebaseFirestoreException.Code.PERMISSION_DENIED);
                    transaction.update(personnelRef, "ownerAdminUid", adminUid);
                    return null;
                });
            });
        });
    }

    public Task<DocumentSnapshot> getPersonnelForCurrentAdmin(String personnelId) {
        return db.collection("users").document(personnelId).get();
    }

    public Task<Void> changePersonnelPassword(String personnelId, String newPassword) {
        Map<String, Object> data = new HashMap<>();
        data.put("personnelUid", personnelId);
        data.put("newPassword", newPassword);
        return FirebaseFunctions.getInstance("asia-southeast1")
                .getHttpsCallable("changePersonnelPassword")
                .call(data)
                .onSuccessTask(result -> Tasks.forResult(null));
    }

    // --------------------
    // REALTIME DATABASE & ACTUATORS
    // --------------------
    public DatabaseReference getDeviceReference() {
        return deviceRef;
    }

    public DatabaseReference getStatusReference() {
        return statusRef;
    }

    public DatabaseReference getSensorsReference() {
        return sensorsRef;
    }

    /**
     * Pairs (scaleDeviceId non-empty) or unpairs (null/empty) a Basilience
     * Harvest Scale with this grow-chamber device. Each physical scale is
     * its own separately provisioned unit dedicated to one device when
     * reproduced across multiple installs - never a single shared/global
     * scale - so this is per-device data on the devices/{deviceId}
     * document, not a fixed app-wide value.
     *
     * Goes through the pairHarvestScale callable rather than a raw Firestore
     * write - the server-side trigger that used to mirror this pairing onto
     * the scale (deviceAccess/parentDeviceId) was found to silently drop
     * every event via Eventarc, so a plain write here could show success
     * while nothing ever actually synced. The callable does the write and
     * the mirror in the same request, so success here means it's really
     * done. Admin/ownership checks happen server-side in the callable now,
     * not via checkAdminTask() here.
     */
    /**
     * Renames a claimed device (the friendly deviceName shown throughout
     * the app, not the immutable deviceId/document ID). Same Admin-gated,
     * ownerUid-unchanged update path as setHarvestScaleId() below - the
     * Firestore rule for devices/{deviceId} already allows the owning
     * Admin to update any field other than ownerUid, so no rules change is
     * needed here. Unlike the harvest scale pairing, a blank name is
     * rejected rather than treated as "clear it" - every device must have
     * a name.
     */
    public Task<Void> renameDevice(String deviceId, String newName) {
        if (deviceId == null) return Tasks.forException(new Exception("No device selected"));
        String trimmed = newName == null ? "" : newName.trim();
        if (trimmed.isEmpty()) {
            return Tasks.forException(new IllegalArgumentException("Device name cannot be empty"));
        }

        return checkAdminTask().onSuccessTask(aVoid ->
                db.collection("devices").document(deviceId).update("deviceName", trimmed));
    }

    public Task<Void> setHarvestScaleId(String deviceId, String scaleDeviceId) {
        if (deviceId == null) return Tasks.forException(new Exception("No device selected"));
        String trimmed = scaleDeviceId == null ? null : scaleDeviceId.trim();
        String normalized = (trimmed == null || trimmed.isEmpty()) ? null : trimmed;

        Map<String, Object> data = new HashMap<>();
        data.put("deviceId", deviceId);
        data.put("harvestScaleId", normalized);
        return FirebaseFunctions.getInstance("asia-southeast1")
                .getHttpsCallable("pairHarvestScale")
                .call(data)
                .onSuccessTask(result -> Tasks.forResult(null));
    }

    /**
     * Reads the harvestScaleId paired with a device (see
     * setHarvestScaleId()), or null if unpaired. Resolves to null rather
     * than failing on a read error too - callers use this only to decide
     * whether to offer the "Read from Harvest Scale" control, so a
     * transient failure should just hide that control, not surface an
     * error for a secondary, non-essential lookup.
     */
    public Task<String> getHarvestScaleId(String deviceId) {
        if (deviceId == null) return Tasks.forResult(null);
        return db.collection("devices").document(deviceId).get()
                .continueWith(task -> {
                    if (!task.isSuccessful() || task.getResult() == null || !task.getResult().exists()) {
                        return null;
                    }
                    return task.getResult().getString("harvestScaleId");
                });
    }

    // Firestore document IDs cannot contain '/'. scaleDeviceId and RTDB push
    // keys are both expected to already be safe (device IDs are
    // alphanumeric+hyphen; push keys use only [0-9A-Za-z_-]), but this is a
    // defensive normalization against a malformed/legacy value ever
    // producing an invalid Firestore document ID.
    private static String harvestScaleConsumptionDocId(String scaleDeviceId, String measurementId) {
        return (scaleDeviceId + "_" + measurementId).replace("/", "_");
    }

    /**
     * Finds the newest UNCONSUMED, non-stale, valid reading under
     * devices/{scaleDeviceId}/harvestScale/harvests - the standalone
     * BasilienceHarvestScale firmware's stability-CONFIRMED measurements
     * (see stagePendingWeight()/syncPendingWeight() in that firmware).
     * Deliberately NOT harvestScale/liveWeight, which the scale overwrites
     * every 5 seconds unconditionally - a raw in-flux reading, not a
     * trustworthy one to log a harvest with.
     *
     * Replaces the old readLatestHarvestScaleReading(), which returned a
     * bare Double: that discarded the measurement's identity (the RTDB push
     * key) and timestamps entirely, so the app could not tell a genuinely
     * new reading apart from one already used for an earlier Harvest, or
     * from a stale reading from hours/days ago. This method:
     *   1. Reads a BOUNDED window of the most recent entries (never the
     *      full history) - HARVEST_SCALE_CANDIDATE_WINDOW.
     *   2. Discards any entry with no verifiable capturedAt (legacy, or the
     *      scale could not establish/reconstruct when it was captured - see
     *      HarvestScaleReading.effectiveTimestampEpochSec(), which never
     *      falls back to syncedAt for this) or older than
     *      HARVEST_SCALE_MAX_READING_AGE_MS. If the single newest entry in
     *      the window is exactly this unverifiable case, that's surfaced as
     *      its own specific error rather than the generic "no reading"
     *      message - see newestEntryUnverifiedCaptureTime below.
     *   3. Checks harvestScaleConsumptions/{scaleDeviceId_measurementId} for
     *      every surviving candidate in ONE bounded Firestore whereIn()
     *      query (capped at 10, which is exactly this method's own
     *      candidate window - never N sequential per-candidate reads).
     *   4. Returns the newest candidate with no consumption record. Never
     *      falls back to an already-consumed measurement.
     *
     * RTDB is the source of truth for the measurement itself; Firestore
     * (harvestScaleConsumptions) is the source of truth for whether it's
     * already been turned into a Harvest Log - the two systems can't share
     * one atomic transaction, so idempotency lives entirely in Firestore
     * (see addHarvestTransaction(String, Harvest, HarvestScaleReading)).
     *
     * A separate deviceId parameter (not this class's own
     * selectedDeviceId-scoped grow-chamber references elsewhere) because
     * the scale is its own device, not one of the grow-chamber devices this
     * class is normally scoped to.
     */
    public Task<HarvestScaleReading> findUnconsumedHarvestScaleReading(String scaleDeviceId) {
        TaskCompletionSource<HarvestScaleReading> completion = new TaskCompletionSource<>();

        // addListenerForSingleValueEvent has no built-in timeout: if this
        // phone has no path to Firebase at all (airplane mode, no data
        // signal), neither onDataChange nor onCancelled ever fires, and the
        // caller's button would stay disabled indefinitely with no error
        // shown. This bounded fallback guarantees the caller always hears
        // back one way or another.
        Handler timeoutHandler = new Handler(Looper.getMainLooper());
        Runnable timeoutRunnable = () -> completion.setException(
                new java.util.concurrent.TimeoutException("Timed out waiting for harvest scale reading"));
        timeoutHandler.postDelayed(timeoutRunnable, 10000);

        final String noNewReadingMessage =
                "No new Harvest Scale measurement is available. Place basil on the scale and wait until the reading is saved.";

        rtdb.getReference("devices")
                .child(scaleDeviceId)
                .child("harvestScale")
                .child("harvests")
                .orderByKey()
                .limitToLast(HARVEST_SCALE_CANDIDATE_WINDOW)
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        timeoutHandler.removeCallbacks(timeoutRunnable);
                        if (completion.getTask().isComplete()) return; // already timed out

                        if (!snapshot.exists() || !snapshot.hasChildren()) {
                            completion.setException(new IllegalStateException(noNewReadingMessage));
                            return;
                        }

                        // RTDB returns children in ascending key order;
                        // newest-first matches the "return the NEWEST
                        // unconsumed measurement" requirement.
                        List<DataSnapshot> children = new ArrayList<>();
                        for (DataSnapshot child : snapshot.getChildren()) children.add(child);
                        Collections.reverse(children);

                        long nowMs = System.currentTimeMillis();
                        List<HarvestScaleReading> candidates = new ArrayList<>();
                        List<String> candidateDocIds = new ArrayList<>();
                        // The newest entry in the window that actually has a
                        // grams value, checked once, regardless of whether it
                        // ends up a usable candidate - used below only if no
                        // usable candidate is found, to tell "nothing new was
                        // weighed" apart from "the most recent weighing
                        // synced, but its capture time is unverifiable" (see
                        // HarvestScaleReading.effectiveTimestampEpochSec()).
                        boolean checkedNewestEntry = false;
                        boolean newestEntryUnverifiedCaptureTime = false;

                        for (DataSnapshot child : children) {
                            Double grams = child.child("grams").getValue(Double.class);
                            if (grams == null || child.getKey() == null) continue;

                            // Read as Double, not Long: the firmware writes
                            // these via FirebaseJson as doubles (to sidestep
                            // an unverified integer-overload set on that
                            // library), so RTDB may hand them back in a
                            // float-shaped representation that a direct
                            // getValue(Long.class) coercion isn't guaranteed
                            // to accept. Double.class accepts any JSON
                            // number regardless of shape.
                            Double capturedAtRaw = child.child("capturedAt").getValue(Double.class);
                            Double syncedAtRaw = child.child("syncedAt").getValue(Double.class);
                            long capturedAtSec = capturedAtRaw != null ? Math.round(capturedAtRaw) : 0L;
                            long syncedAtSec = syncedAtRaw != null ? Math.round(syncedAtRaw) : 0L;

                            HarvestScaleReading reading = new HarvestScaleReading(
                                    child.getKey(), scaleDeviceId, grams, capturedAtSec, syncedAtSec);

                            if (!checkedNewestEntry) {
                                checkedNewestEntry = true;
                                newestEntryUnverifiedCaptureTime = capturedAtSec <= 0;
                            }

                            long effectiveSec = reading.effectiveTimestampEpochSec();
                            if (effectiveSec <= 0) {
                                // Legacy entry (pre-dates capturedAt/syncedAt),
                                // or the scale genuinely could not establish
                                // (or reconstruct) when this was captured -
                                // unverifiable age, must not masquerade as a
                                // fresh reading by falling back to syncedAt.
                                continue;
                            }
                            long ageMs = nowMs - effectiveSec * 1000L;
                            if (ageMs < 0 || ageMs > HARVEST_SCALE_MAX_READING_AGE_MS) {
                                // Too old, or a clock-skew value that looks
                                // like it's from the future - equally
                                // untrustworthy either way.
                                continue;
                            }

                            candidates.add(reading);
                            candidateDocIds.add(harvestScaleConsumptionDocId(scaleDeviceId, reading.getMeasurementId()));
                        }

                        if (candidates.isEmpty()) {
                            // Distinguish "nothing new was weighed at all"
                            // from "the most recent weighing synced, but the
                            // scale could not verify when it was actually
                            // captured" (e.g. it rebooted between capture and
                            // sync - see BasilienceHarvestScale.ino) - the
                            // latter must not be silently treated the same as
                            // no reading, since a real synced measurement DID
                            // arrive and the farmer needs to know why it
                            // can't be used automatically.
                            if (newestEntryUnverifiedCaptureTime) {
                                completion.setException(new IllegalStateException(
                                        "This scale measurement synced successfully, but its capture time could not be verified. Please weigh the harvest again."));
                            } else {
                                completion.setException(new IllegalStateException(noNewReadingMessage));
                            }
                            return;
                        }

                        // ONE bounded query - whereIn() caps at 10 values,
                        // which is exactly HARVEST_SCALE_CANDIDATE_WINDOW -
                        // instead of a sequential read per candidate.
                        db.collection("harvestScaleConsumptions")
                                .whereIn(FieldPath.documentId(), candidateDocIds)
                                .get()
                                .addOnSuccessListener(consumedSnap -> {
                                    if (completion.getTask().isComplete()) return;

                                    Set<String> consumedDocIds = new HashSet<>();
                                    for (DocumentSnapshot doc : consumedSnap.getDocuments()) {
                                        consumedDocIds.add(doc.getId());
                                    }

                                    for (HarvestScaleReading candidate : candidates) {
                                        String docId = harvestScaleConsumptionDocId(scaleDeviceId, candidate.getMeasurementId());
                                        if (!consumedDocIds.contains(docId)) {
                                            completion.setResult(candidate);
                                            return;
                                        }
                                    }

                                    // Every candidate in the window is
                                    // already consumed - distinct from "no
                                    // candidates at all" so the UI can tell
                                    // the user what actually happened.
                                    completion.setException(new IllegalStateException(
                                            "This Harvest Scale measurement has already been recorded. Please weigh the new harvest before trying again."));
                                })
                                .addOnFailureListener(e -> {
                                    if (!completion.getTask().isComplete()) completion.setException(e);
                                });
                    }

                    @Override
                    public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) {
                        timeoutHandler.removeCallbacks(timeoutRunnable);
                        if (completion.getTask().isComplete()) return; // already timed out
                        completion.setException(error.toException());
                    }
                });

        return completion.getTask();
    }

    public Task<Void> updateActuatorState(String actuatorName, boolean isOn) {
        return updateActuatorState(actuatorName, isOn, false);
    }

    /**
     * @param overrideRequested true only when the user explicitly confirmed a
     *                          Continue prompt raised by {@link ManualOverrideAdvisor}
     *                          for THIS command. One-shot: firmware (see
     *                          ActuatorManager::validateCommand) applies it only
     *                          to the command it arrives on and never persists
     *                          it as a standing setting.
     */
    public Task<Void> updateActuatorState(String actuatorName, boolean isOn, boolean overrideRequested) {
        return updateActuatorState(actuatorName, isOn, overrideRequested, null);
    }

    /**
     * @param speedPercent 0-100 PWM duty for a variable-speed actuator (Canopy
     *                     Fan / Reservoir Fan); null omits the field entirely
     *                     so firmware falls back to its own default (100) -
     *                     see FirebaseManager::consumeActuatorCommandSnapshot.
     */
    public Task<Void> updateActuatorState(String actuatorName, boolean isOn, boolean overrideRequested, Integer speedPercent) {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty())
            return Tasks.forException(new Exception("No device selected"));

        final String deviceIdAtCallTime = selectedDeviceId;
        Log.d(TAG, "[MANUAL-APP] updateActuatorState actuator=" + actuatorName + " target=" + isOn
                + " override=" + overrideRequested + " speed=" + speedPercent
                + " uid=" + getCurrentUid() + " cachedRole=" + cachedRole + " deviceId=" + deviceIdAtCallTime);

        return checkAdminOrGrantTask()
                .addOnFailureListener(e -> Log.e(TAG, "[MANUAL-APP] Admin/grant authorization failed actuator=" + actuatorName
                        + " cachedRole=" + cachedRole, e))
                .onSuccessTask(aVoid -> {
                    Log.d(TAG, "[MANUAL-APP] Admin/grant authorization passed actuator=" + actuatorName);
                    return rtdb.getReference("devices").child(deviceIdAtCallTime).child("commands").child("manualMode").get();
                })
                .onSuccessTask(snapshot -> {
                    Boolean isManual = snapshot.getValue(Boolean.class);
                    Log.d(TAG, "[MANUAL-APP] manualMode=" + isManual + " actuator=" + actuatorName
                            + " deviceId=" + deviceIdAtCallTime);
                    if (isManual != null && isManual) {
                        Map<String, Object> commandData = new HashMap<>();
                        commandData.put("state", isOn);
                        commandData.put("source", "manual");
                        commandData.put("timestamp", ServerValue.TIMESTAMP);
                        commandData.put("overrideRequested", overrideRequested);
                        if (speedPercent != null) {
                            commandData.put("speed", speedPercent);
                        }

                        String path = "devices/" + deviceIdAtCallTime + "/commands/" + actuatorName;
                        DatabaseReference commandRef = rtdb.getReference(path);
                        return commandRef.setValue(commandData)
                                .addOnSuccessListener(unused -> {
                                    Log.d(TAG, "[MANUAL-APP] Command write success actuator=" + actuatorName);
                                    // Read-back is diagnostic only - never gates the write's own
                                    // success/failure result returned to the caller.
                                    commandRef.get()
                                            .addOnSuccessListener(readBack -> Log.d(TAG,
                                                    "[MANUAL-APP] Stored state=" + readBack.child("state").getValue(Boolean.class)
                                                            + " source=" + readBack.child("source").getValue(String.class)
                                                            + " timestamp=" + readBack.child("timestamp").getValue(Long.class)
                                                            + " actuator=" + actuatorName))
                                            .addOnFailureListener(e -> Log.w(TAG,
                                                    "[MANUAL-APP] Read-back failed (write already succeeded) actuator=" + actuatorName, e));
                                })
                                .addOnFailureListener(e -> Log.e(TAG, "[MANUAL-APP] Command write failed actuator=" + actuatorName
                                        + " deviceId=" + deviceIdAtCallTime, e));
                    } else {
                        Log.e(TAG, "[MANUAL-APP] Rejected: manual mode not enabled actuator=" + actuatorName);
                        // IllegalStateException specifically (not a bare Exception)
                        // so the caller can reliably tell this expected business-
                        // rule rejection apart from an actual Firebase/network
                        // failure by type, and surface this message directly
                        // instead of a generic "command failed, try again" that
                        // doesn't explain why (see Parameters_Monitoring_Fragment).
                        return Tasks.forException(new IllegalStateException("Manual mode must be enabled to control actuators."));
                    }
                });
    }

    public Task<Void> updateManualMode(boolean isManual) {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty())
            return Tasks.forException(new Exception("No device selected"));

        return checkAdminTask().onSuccessTask(aVoid -> {
            DatabaseReference deviceRef = rtdb.getReference("devices").child(selectedDeviceId);
            if (!isManual) {
                Map<String, Object> turnOffCmd = new HashMap<>();
                turnOffCmd.put("state", false);
                turnOffCmd.put("source", "android");
                turnOffCmd.put("timestamp", ServerValue.TIMESTAMP);

                Map<String, Object> turnOffStatus = new HashMap<>();
                turnOffStatus.put("state", 0);
                turnOffStatus.put("source", "android");
                turnOffStatus.put("timestamp", System.currentTimeMillis());

                Map<String, Object> updates = new HashMap<>();
                updates.put("commands/manualMode", false);
                updates.put("commands/solenoid", turnOffCmd);
                updates.put("commands/canopyFan", turnOffCmd);
                updates.put("commands/growLight", turnOffCmd);
                updates.put("commands/phUpPump", turnOffCmd);
                updates.put("commands/phDownPump", turnOffCmd);
                updates.put("commands/growPump", turnOffCmd);
                updates.put("commands/bloomPump", turnOffCmd);
                updates.put("commands/fogger", turnOffCmd);
                updates.put("commands/blower", turnOffCmd);
                updates.put("commands/peltier", turnOffCmd);

                return deviceRef.updateChildren(updates);
            } else {
                // manualModeEnabledAt marks the start of this Manual Mode
                // "session" - the manualControlGrants security rule requires
                // a Farmer's grant.resolvedAt to be >= this value, which is
                // what invalidates a stale grant from an earlier session
                // without needing any explicit cleanup when Manual Mode is
                // turned off (see resolveManualControlGrant()'s own comment).
                Map<String, Object> updates = new HashMap<>();
                updates.put("commands/manualMode", true);
                updates.put("commands/manualModeEnabledAt", ServerValue.TIMESTAMP);
                return deviceRef.updateChildren(updates);
            }
        });
    }

    /** Farmer requests manual-control access (any authenticated user with this device selected may call this - the RTDB rule is the real gate, not a client-side admin check). */
    public Task<Void> requestManualControlAccess() {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty())
            return Tasks.forException(new Exception("No device selected"));
        String uid = getCurrentUid();
        if (uid == null) return Tasks.forException(new Exception("Not logged in"));

        Map<String, Object> request = new HashMap<>();
        request.put("status", "PENDING");
        request.put("requestedAt", ServerValue.TIMESTAMP);

        return rtdb.getReference("devices").child(selectedDeviceId)
                .child("manualControlGrants").child(uid).setValue(request);
    }

    /**
     * Admin resolves a Farmer's manual-control request ("APPROVED", "DENIED",
     * or "REVOKED" for ending an already-approved session early).
     *
     * <p>Approving while Manual Mode is currently off is what turns Manual
     * Mode on for this session too - done as a single atomic multi-path
     * update together with the grant fields and a fresh manualModeEnabledAt,
     * so both ServerValue.TIMESTAMP writes resolve to the identical server
     * value in one call (the security rule requires grant.resolvedAt &gt;=
     * commands.manualModeEnabledAt; two separate sequential writes could
     * land in the wrong order and permanently lock the farmer out). If
     * Manual Mode is already on, manualModeEnabledAt is left untouched -
     * the new grant's resolvedAt will still naturally satisfy the rule
     * against the existing session start.
     */
    public Task<Void> resolveManualControlGrant(String farmerUid, String status) {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty())
            return Tasks.forException(new Exception("No device selected"));

        return checkAdminTask().onSuccessTask(aVoid -> {
            String adminUid = getCurrentUid();
            if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));
            DatabaseReference deviceRef = rtdb.getReference("devices").child(selectedDeviceId);

            if (!"APPROVED".equals(status)) {
                Map<String, Object> updates = new HashMap<>();
                updates.put("status", status);
                updates.put("resolvedByUid", adminUid);
                updates.put("resolvedAt", ServerValue.TIMESTAMP);
                return deviceRef.child("manualControlGrants").child(farmerUid).updateChildren(updates);
            }

            return deviceRef.child("commands").child("manualMode").get().onSuccessTask(snapshot -> {
                boolean alreadyOn = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                Map<String, Object> updates = new HashMap<>();
                updates.put("manualControlGrants/" + farmerUid + "/status", "APPROVED");
                updates.put("manualControlGrants/" + farmerUid + "/resolvedByUid", adminUid);
                updates.put("manualControlGrants/" + farmerUid + "/resolvedAt", ServerValue.TIMESTAMP);
                if (!alreadyOn) {
                    updates.put("commands/manualMode", true);
                    updates.put("commands/manualModeEnabledAt", ServerValue.TIMESTAMP);
                }
                return deviceRef.updateChildren(updates);
            });
        });
    }

    /**
     * Admin OR an active manual-control grant holder. Falls back to reading
     * the caller's own manualControlGrants entry and comparing it against
     * the current session start, purely for a fast client-side pre-check
     * and a precise error message - the RTDB rule (commands/$actuatorKey)
     * is the actual enforcement either way, so this cannot itself be a
     * security hole even if this check were skipped entirely.
     */
    private Task<Void> checkAdminOrGrantTask() {
        return checkAdminTask().continueWithTask(adminTask -> {
            if (adminTask.isSuccessful()) return Tasks.forResult(null);

            String uid = getCurrentUid();
            if (uid == null || selectedDeviceId == null || selectedDeviceId.isEmpty()) {
                Log.e(TAG, "[GRANT-CHECK] not logged in / no device selected");
                return Tasks.forException(new Exception("Not logged in"));
            }
            DatabaseReference deviceRef = rtdb.getReference("devices").child(selectedDeviceId);

            return deviceRef.child("manualControlGrants").child(uid).get().continueWithTask(grantTask -> {
                if (!grantTask.isSuccessful() || grantTask.getResult() == null) {
                    Log.e(TAG, "[GRANT-CHECK] grant read failed uid=" + uid, grantTask.getException());
                    return Tasks.forException(new IllegalStateException(
                            "You do not have manual control access. Request access first."));
                }
                DataSnapshot grant = grantTask.getResult();
                String grantStatus = grant.child("status").getValue(String.class);
                Long resolvedAt = grant.child("resolvedAt").getValue(Long.class);
                Log.d(TAG, "[GRANT-CHECK] uid=" + uid + " grantStatus=" + grantStatus + " resolvedAt=" + resolvedAt);
                if (!"APPROVED".equals(grantStatus) || resolvedAt == null) {
                    Log.e(TAG, "[GRANT-CHECK] rejected: not APPROVED or no resolvedAt");
                    return Tasks.forException(new IllegalStateException(
                            "You do not have manual control access. Request access first."));
                }
                return deviceRef.child("commands").child("manualModeEnabledAt").get().continueWithTask(sessionTask -> {
                    if (!sessionTask.isSuccessful()) {
                        Log.e(TAG, "[GRANT-CHECK] manualModeEnabledAt read failed", sessionTask.getException());
                        return Tasks.forException(new IllegalStateException(
                                "Your manual control access has expired. Please request again."));
                    }
                    DataSnapshot sessionSnapshot = sessionTask.getResult();
                    Long sessionStart = sessionSnapshot != null ? sessionSnapshot.getValue(Long.class) : null;
                    Log.d(TAG, "[GRANT-CHECK] manualModeEnabledAt=" + sessionStart + " (exists="
                            + (sessionSnapshot != null && sessionSnapshot.exists()) + ") resolvedAt=" + resolvedAt);
                    // A missing manualModeEnabledAt means Manual Mode has
                    // been on since before this field was ever stamped (e.g.
                    // a session that predates this feature) - treat that as
                    // no known session start, so any APPROVED grant counts,
                    // matching the RTDB rule's own !exists() OR clause.
                    if (sessionStart != null && resolvedAt < sessionStart) {
                        Log.e(TAG, "[GRANT-CHECK] rejected: resolvedAt < manualModeEnabledAt (stale session)");
                        return Tasks.forException(new IllegalStateException(
                                "Your manual control access has expired. Please request again."));
                    }
                    Log.d(TAG, "[GRANT-CHECK] passed");
                    return Tasks.forResult(null);
                });
            });
        });
    }

    /**
     * Ends the current Manual Mode session by writing commands/manualMode =
     * false only - unlike updateManualMode(false) (Admin's own switch),
     * this does not also reset every individual actuator command, since the
     * RTDB rule only ever lets a Farmer write manualMode to false (never
     * true) and only as a single-path write. Firmware ignores the commands
     * node entirely once manualMode is false and resumes its own automatic
     * control loop, so nothing is left "stuck on" by skipping that reset
     * here. Gated by checkAdminOrGrantTask() - callable by Admin too, but
     * Admin's own switch already covers that case; this exists for a
     * Farmer with an active grant to end their own session without waiting
     * on an Admin.
     */
    public Task<Void> endManualModeSession() {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty())
            return Tasks.forException(new Exception("No device selected"));

        return checkAdminOrGrantTask()
                .addOnFailureListener(e -> Log.e(TAG, "[GRANT-CHECK] endManualModeSession blocked client-side", e))
                .onSuccessTask(aVoid ->
                        rtdb.getReference("devices").child(selectedDeviceId)
                                .child("commands").child("manualMode").setValue(false)
                                .addOnFailureListener(e -> Log.e(TAG, "[GRANT-CHECK] manualMode=false write rejected by RTDB rule", e)));
    }

    public DatabaseReference getOperationsCurrentReference() {
        if (selectedDeviceId == null) return null;
        return rtdb.getReference("devices").child(selectedDeviceId).child("operations").child("current");
    }

    /**
     * Sends an OperationRequest to the firmware via RTDB devices/{deviceId}/commands/current.
     * Uses SharedPreferences to persist an incrementing requestId.
     */
    public Task<Integer> sendOperationRequest(String operation, String action) {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty())
            return Tasks.forException(new Exception("No device selected"));

        // Using SharedPreferences to maintain a simple incrementing requestId
        Context context = FirebaseApp.getInstance().getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("Basilience_Prefs", Context.MODE_PRIVATE);
        int lastId = prefs.getInt("last_request_id", 0);
        int nextId = lastId + 1;
        if (nextId > 32767) {
            nextId = 1;
        }

        // Persist the new ID
        prefs.edit().putInt("last_request_id", nextId).apply();

        // Freeze into an effectively-final value for lambda capture.
        // All mutation of nextId is complete at this point.
        final int requestId = nextId;

        long timestamp = System.currentTimeMillis() / 1000L; // Unix timestamp in seconds

        OperationRequest request = new OperationRequest(
                requestId,
                operation,
                action,
                timestamp,
                1 // protocolVersion
        );

        return checkAdminTask().onSuccessTask(aVoid ->
                rtdb.getReference("devices").child(selectedDeviceId).child("commands").child("current").setValue(request)
        ).continueWith(task -> {
            if (!task.isSuccessful()) throw task.getException();
            return requestId;
        });
    }

    // --------------------
    // FIRESTORE: SYSTEM STATUS & CYCLES
    // --------------------
    public ListenerRegistration listenToSystemStatus(EventListener<DocumentSnapshot> listener) {
        if (selectedDeviceId == null) return null;

        // Summary status (isOnline, firmwareVersion, etc.) is now in the device document
        return db.collection("devices")
                .document(selectedDeviceId)
                .addSnapshotListener(listener);
    }

    /**
     * Authorises the cultivation-operator actions on a growth cycle: starting
     * one and completing one.
     *
     * Running a cultivation cycle is day-to-day farm work, not account
     * administration, so an Admin or a Farmer assigned to this specific device
     * may do both. Assignment is what bounds it: a Farmer can only act on a
     * device they actually work on, never on someone else's.
     *
     * Mirrors the Firestore rules for the same operations - this exists so the
     * app fails early with a clear message, not as the security boundary.
     */
    public Task<Void> checkCycleOperatorPermission(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return Tasks.forException(new Exception("No device selected"));
        }
        if (RoleConstants.ROLE_ADMIN.equalsIgnoreCase(cachedRole)) {
            return Tasks.forResult(null);
        }

        String uid = getCurrentUid();
        if (uid == null) return Tasks.forException(new Exception("User not authenticated"));

        return getUserProfile(uid).onSuccessTask(doc -> {
            if (doc.exists()) {
                cachedRole = doc.getString("role");
                if (RoleConstants.ROLE_ADMIN.equalsIgnoreCase(cachedRole)) {
                    return Tasks.forResult(null);
                }
            }

            // Deterministic assignment id, same convention assignDeviceToPersonnel
            // writes and the Firestore rule checks.
            return db.collection("deviceAssignments")
                    .document(uid + "_" + deviceId)
                    .get()
                    .onSuccessTask(assignment -> {
                        if (assignment.exists()) {
                            return Tasks.<Void>forResult(null);
                        }
                        return Tasks.<Void>forException(new FirebaseFirestoreException(
                                "Permission Denied: You are not assigned to this device.",
                                FirebaseFirestoreException.Code.PERMISSION_DENIED));
                    });
        });
    }

    public Task<Void> addCycle(Cycle cycle) {
        if (selectedDeviceId == null) return Tasks.forException(new Exception("No device selected"));

        return checkCycleOperatorPermission(selectedDeviceId).onSuccessTask(aVoid -> {
            // Record who actually started the cycle. Previously left unset, so a
            // cycle had no creator at all; now that Farmers can create one it
            // has to reflect the real user rather than being assumed to be an
            // Admin.
            if (cycle.getCreatedBy() == null || cycle.getCreatedBy().isEmpty()) {
                cycle.setCreatedBy(getCurrentUid());
            }

            // Capture the target ranges in force right now, then write them with
            // the cycle in ONE set() - so a cycle can never briefly exist without
            // the ranges its report will be judged against.
            return buildTargetRangeSnapshot(selectedDeviceId).onSuccessTask(ranges -> {
                cycle.setTargetRanges(ranges);

                return db.collection("devices")
                        .document(selectedDeviceId)
                        .collection("cycles")
                        .document(cycle.getCycleId())
                        .set(cycle);
            });
        });
    }

    /**
     * Resolves the twelve canonical target-range values for a cycle snapshot.
     *
     * Only acceptable growing ranges are captured. Actuator control settings
     * (refillStartLevel/refillStopLevel, coolerOffTemp, airTempRelease,
     * humidityRelease) are deliberately excluded - they say when equipment
     * switches, which is not what a report evaluates a reading against.
     *
     * Never fails: a settings read that errors, or a field that has never been
     * written, falls back to the same canonical default Android and the firmware
     * both compile in. Cycle creation must not depend on a network-only fetch
     * when a correct default is already known.
     */
    private Task<Map<String, Object>> buildTargetRangeSnapshot(String deviceId) {
        return getDeviceSettings(deviceId).get().continueWith(task -> {
            DataSnapshot snapshot = task.isSuccessful() ? task.getResult() : null;
            if (!task.isSuccessful()) {
                Log.w(TAG, "Unable to read settings for cycle target-range snapshot; using defaults",
                        task.getException());
            }

            Map<String, Object> ranges = new HashMap<>();
            for (ParameterTargetRanges parameter : ParameterTargetRanges.values()) {
                ranges.put(parameter.minKey, readSettingValue(snapshot, parameter.minKey, parameter.defaultMin));
                ranges.put(parameter.maxKey, readSettingValue(snapshot, parameter.maxKey, parameter.defaultMax));
            }
            return ranges;
        });
    }

    private double readSettingValue(DataSnapshot snapshot, String key, float fallback) {
        if (snapshot != null) {
            Object value = snapshot.child(key).getValue();
            if (value instanceof Number) {
                double parsed = ((Number) value).doubleValue();
                if (!Double.isNaN(parsed) && !Double.isInfinite(parsed)) return parsed;
            }
        }
        return fallback;
    }

    public ListenerRegistration listenToCycles(EventListener<QuerySnapshot> listener) {
        if (selectedDeviceId == null) return null;

        return db.collection("devices")
                .document(selectedDeviceId)
                .collection("cycles")
                .orderBy("cycleNumber")
                .addSnapshotListener(listener);
    }

    /**
     * Reads every cycle for a device so the caller can decide which one (if any)
     * is active via {@link CycleStatus}.
     *
     * This deliberately does not filter on status server-side. The cycles.status
     * field override in firestore.indexes.json declares COLLECTION_GROUP scopes
     * only - which is what the Cloud Functions collection-group query needs, but
     * it replaces Firestore's default single-field indexing and so leaves no
     * COLLECTION-scoped index for a per-device status filter. Ordering by
     * cycleNumber uses the same index the cycles listener already relies on.
     */
    public Task<QuerySnapshot> getCycles(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return Tasks.forException(new Exception("No device selected"));
        }

        return db.collection("devices")
                .document(deviceId)
                .collection("cycles")
                .orderBy("cycleNumber")
                .get();
    }

    public Task<Void> completeCycle(String cycleId) {
        if (selectedDeviceId == null)
            return Tasks.forException(new Exception("Device not selected"));

        // Completing a cycle ends a cultivation period - operator work, same
        // authority as starting one. The write set below is exactly what the
        // Firestore completion rule permits.
        return checkCycleOperatorPermission(selectedDeviceId).onSuccessTask(aVoid -> {
            Map<String, Object> updates = new HashMap<>();
            updates.put("status", "COMPLETED");
            updates.put("endDate", com.google.firebase.Timestamp.now());
            updates.put("nextHarvestDate", null);
            // Who ended this cultivation period. Mirrors createdBy: the real
            // authenticated user, never an assumed role. The Firestore rule
            // requires this to equal the caller's own UID.
            updates.put("completedBy", getCurrentUid());

            return db.collection("devices").document(selectedDeviceId)
                    .collection("cycles").document(cycleId)
                    .update(updates);
        });
    }

    /**
     * Reference to the device's existing RTDB settings node - the single place
     * target ranges, control thresholds and the light schedule all live.
     */
    public DatabaseReference getDeviceSettings(String deviceId) {
        return rtdb.getReference("devices").child(deviceId).child("settings");
    }

    /**
     * Writes the parameter target ranges as one atomic update.
     *
     * updateChildren() applies every key or none, so the device can never read
     * a half-applied range (a new minimum against an old maximum). Only the
     * keys passed in are touched - control/hysteresis settings and the light
     * schedule in the same node are left alone.
     *
     * Authority is enforced server-side: the RTDB rule on
     * devices/$deviceId/settings already restricts writes to an ADMIN with
     * device access, so a non-admin request fails here regardless of the UI.
     */
    public Task<Void> saveTargetRanges(String deviceId, Map<String, Object> updates) {
        if (deviceId == null || deviceId.isEmpty()) {
            return Tasks.forException(new Exception("No device selected"));
        }
        if (updates == null || updates.isEmpty()) {
            return Tasks.forResult(null);
        }
        return getDeviceSettings(deviceId).updateChildren(updates);
    }

    public Task<QuerySnapshot> getParameterLogs(long startTime, long endTime) {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) {
            return Tasks.forException(new Exception("No active device selected"));
        }

        return db.collection("devices")
                .document(selectedDeviceId)
                .collection("parameterLogs")
                .whereGreaterThanOrEqualTo("timestamp", startTime)
                .whereLessThanOrEqualTo("timestamp", endTime)
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .get();
    }

    public Task<QuerySnapshot> getFoggingLogs(long startTime, long endTime) {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) {
            return Tasks.forException(new Exception("No active device selected"));
        }

        return db.collection("devices")
                .document(selectedDeviceId)
                .collection("foggingLogs")
                .whereGreaterThanOrEqualTo("timestamp", startTime)
                .whereLessThanOrEqualTo("timestamp", endTime)
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .get();
    }

    // --------------------
    // HARVEST LOGS & NOTIFICATIONS
    // --------------------
    public Task<Void> updateHarvestFrequency(String cycleId, int newFrequency) {
        if (selectedDeviceId == null || cycleId == null) {
            return com.google.android.gms.tasks.Tasks.forException(new Exception("No device/cycle selected"));
        }

        return checkAdminTask().onSuccessTask(aVoid -> {
            com.google.firebase.firestore.DocumentReference cycleRef = db.collection("devices")
                    .document(selectedDeviceId)
                    .collection("cycles")
                    .document(cycleId);

            return db.runTransaction(transaction -> {
                DocumentSnapshot snapshot = transaction.get(cycleRef);
                if (!snapshot.exists())
                    throw new FirebaseFirestoreException("Cycle not found", FirebaseFirestoreException.Code.NOT_FOUND);

                String status = snapshot.getString("status");
                if (status != null && !"ACTIVE".equalsIgnoreCase(status)) {
                    throw new FirebaseFirestoreException("Cannot edit frequency of a completed cycle", FirebaseFirestoreException.Code.FAILED_PRECONDITION);
                }

                Timestamp lastHarvest = snapshot.getTimestamp("lastHarvestDate");
                Timestamp startDate = snapshot.getTimestamp("startDate");
                Timestamp baseDate = (lastHarvest != null) ? lastHarvest : startDate;

                if (baseDate == null) baseDate = Timestamp.now();

                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(baseDate.toDate());
                cal.add(java.util.Calendar.DAY_OF_YEAR, newFrequency);
                Timestamp newNextHarvest = new Timestamp(cal.getTime());

                transaction.update(cycleRef, "harvestFrequencyDays", newFrequency);
                transaction.update(cycleRef, "nextHarvestDate", newNextHarvest);

                return null;
            });
        });
    }

    /** Manual-entry path - unchanged behavior, no scale measurement involved. */
    public Task<Void> addHarvestTransaction(String cycleId, Harvest harvest) {
        return addHarvestTransactionInternal(cycleId, harvest, null);
    }

    /**
     * SCALE-sourced path: atomically consumes the given HarvestScaleReading
     * in the SAME Firestore transaction that creates the Harvest, via a
     * deterministic harvestScaleConsumptions/{scaleDeviceId_measurementId}
     * document (see harvestScaleConsumptionDocId()). RTDB and Firestore
     * cannot share one atomic transaction, so this is where measurement-
     * level idempotency actually lives - reading that consumption document
     * INSIDE the transaction (before any writes) means Firestore's own
     * conflict detection guarantees exactly one concurrent attempt to
     * consume the SAME physical measurement can ever win, regardless of
     * whether the collision is a double tap, a retry after an uncertain
     * network response, a second phone, a second app session, or even a
     * different day.
     */
    public Task<Void> addHarvestTransaction(String cycleId, Harvest harvest, HarvestScaleReading scaleReading) {
        if (scaleReading == null) {
            return Tasks.forException(new IllegalArgumentException(
                    "A scale measurement is required for a SCALE-sourced harvest."));
        }
        return addHarvestTransactionInternal(cycleId, harvest, scaleReading);
    }

    private Task<Void> addHarvestTransactionInternal(String cycleId, Harvest harvest, @Nullable HarvestScaleReading scaleReading) {
        if (selectedDeviceId == null) return Tasks.forException(new Exception("No device selected"));
        if (harvest == null || !Double.isFinite(harvest.getWeight()) || harvest.getWeight() <= 0.0
                || harvest.getHarvestDate() == null) {
            return Tasks.forException(new IllegalArgumentException(
                    "Harvest requires a positive weight and valid timestamp."));
        }

        DocumentReference cycleRef = db.collection("devices").document(selectedDeviceId)
                .collection("cycles").document(cycleId);
        DocumentReference harvestRef = cycleRef.collection("harvestLogs").document();

        harvest.setId(harvestRef.getId());
        if (scaleReading != null) {
            harvest.setScaleDeviceId(scaleReading.getScaleDeviceId());
            harvest.setScaleMeasurementId(scaleReading.getMeasurementId());
            harvest.setScaleCapturedAt(scaleReading.getCapturedAt());
        }

        String adminUid = getCurrentUid();
        DocumentReference consumptionRef = scaleReading != null
                ? db.collection("harvestScaleConsumptions").document(
                        harvestScaleConsumptionDocId(scaleReading.getScaleDeviceId(), scaleReading.getMeasurementId()))
                : null;

        return db.runTransaction(transaction -> {
            DocumentSnapshot cycleSnap = transaction.get(cycleRef);
            // Read BEFORE any write in this transaction - see the method
            // javadoc above for why this specific ordering is what makes
            // the idempotency guarantee real, not just a hopeful check.
            DocumentSnapshot consumptionSnap = consumptionRef != null ? transaction.get(consumptionRef) : null;

            // Validation: Freeze check
            String status = cycleSnap.getString("status");
            if (status == null) status = "ACTIVE"; // Legacy support
            if (!"ACTIVE".equalsIgnoreCase(status)) {
                throw new FirebaseFirestoreException("Cycle is completed and can no longer be modified.",
                        FirebaseFirestoreException.Code.ABORTED);
            }

            if (consumptionSnap != null && consumptionSnap.exists()) {
                throw new FirebaseFirestoreException(
                        "This Harvest Scale measurement has already been recorded. Please weigh the new harvest before trying again.",
                        FirebaseFirestoreException.Code.ALREADY_EXISTS);
            }

            // Duplicate-submission guard: nothing else here (or in
            // firestore.rules' window check) stops two harvest entries from
            // being created back to back - an Admin override has no window
            // restriction at all, and even a Farmer's UI double-tap guard
            // (isHarvestSubmitting) is only a client-side flag that a retry,
            // a second device, or a second app session never sees. Reject
            // outright if this cycle's own lastHarvestDate is within the
            // same short guard window firestore.rules' isNotDuplicateHarvest()
            // enforces, so a stray double-submission fails fast with a clear
            // reason instead of quietly creating two harvest logs (or, absent
            // this check, being rejected by the rule anyway but surfaced as a
            // generic permission error).
            Timestamp lastHarvest = cycleSnap.getTimestamp("lastHarvestDate");
            if (lastHarvest != null) {
                long deltaMs = harvest.getHarvestDate().toDate().getTime() - lastHarvest.toDate().getTime();
                if (deltaMs < HARVEST_DUPLICATE_GUARD_MS) {
                    throw new FirebaseFirestoreException(
                            "A harvest was just recorded for this cycle. Please wait a moment before logging another.",
                            FirebaseFirestoreException.Code.ABORTED);
                }
            }

            double currentWeight = 0;
            if (cycleSnap.contains("totalHarvestWeight") && cycleSnap.get("totalHarvestWeight") != null) {
                currentWeight = cycleSnap.getDouble("totalHarvestWeight");
            }

            long currentCount = 0;
            if (cycleSnap.contains("totalHarvestCount") && cycleSnap.get("totalHarvestCount") != null) {
                currentCount = cycleSnap.getLong("totalHarvestCount");
            }

            int frequency = 5;
            if (cycleSnap.contains("harvestFrequencyDays") && cycleSnap.get("harvestFrequencyDays") != null) {
                frequency = cycleSnap.getLong("harvestFrequencyDays").intValue();
            }

            // Calculate nextHarvestDate based on this harvest's date + frequency
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTime(harvest.getHarvestDate().toDate());
            cal.add(java.util.Calendar.DAY_OF_YEAR, frequency);
            Timestamp nextHarvest = new Timestamp(cal.getTime());

            transaction.set(harvestRef, harvest);
            transaction.update(cycleRef,
                    "totalHarvestWeight", currentWeight + harvest.getWeight(),
                    "totalHarvestCount", currentCount + 1,
                    "lastHarvestDate", harvest.getHarvestDate(),
                    "nextHarvestDate", nextHarvest
            );

            if (consumptionRef != null) {
                Map<String, Object> consumption = new HashMap<>();
                consumption.put("scaleDeviceId", scaleReading.getScaleDeviceId());
                consumption.put("measurementId", scaleReading.getMeasurementId());
                consumption.put("deviceId", selectedDeviceId);
                consumption.put("cycleId", cycleId);
                consumption.put("harvestId", harvestRef.getId());
                consumption.put("consumedByUid", adminUid);
                consumption.put("consumedAt", FieldValue.serverTimestamp());
                transaction.set(consumptionRef, consumption);
            }

            return null;
        });
    }

    public Task<Void> updateHarvestTransaction(String cycleId, String harvestId, double oldWeight, double newWeight, Map<String, Object> updates) {
        if (selectedDeviceId == null) return Tasks.forException(new Exception("No device selected"));

        return checkAdminTask().onSuccessTask(aVoid -> {
            DocumentReference cycleRef = db.collection("devices").document(selectedDeviceId)
                    .collection("cycles").document(cycleId);
            DocumentReference harvestRef = cycleRef.collection("harvestLogs").document(harvestId);
            boolean harvestDateChanged = updates.containsKey("harvestDate");

            // The harvestDate edit + weight/count adjustment above must be
            // atomic, so it stays inside the transaction. Recomputing
            // lastHarvestDate/nextHarvestDate needs a query the Firestore
            // transaction API can't track; it used to run here via a blocking
            // Tasks.await() inline, which could hang this transaction
            // indefinitely under poor connectivity (and Firestore retries
            // transactions on contention, compounding the wait). It now runs
            // as a best-effort follow-up after this transaction commits, the
            // same pattern deleteHarvestTransaction/recomputeHarvestSchedulingMetadata
            // already use below.
            return db.runTransaction(transaction -> {
                DocumentSnapshot cycleSnap = transaction.get(cycleRef);

                // Validation: Freeze check
                String status = cycleSnap.getString("status");
                if (status == null) status = "ACTIVE"; // Legacy support
                if (!"ACTIVE".equalsIgnoreCase(status)) {
                    throw new FirebaseFirestoreException("Cycle is completed and can no longer be modified.",
                            FirebaseFirestoreException.Code.ABORTED);
                }

                double currentTotalWeight = 0;
                if (cycleSnap.contains("totalHarvestWeight") && cycleSnap.get("totalHarvestWeight") != null) {
                    currentTotalWeight = cycleSnap.getDouble("totalHarvestWeight");
                }

                transaction.update(harvestRef, updates);
                transaction.update(cycleRef, "totalHarvestWeight", currentTotalWeight - oldWeight + newWeight);
                return null;
            }).continueWithTask(task -> {
                if (!task.isSuccessful()) {
                    return Tasks.forException(task.getException());
                }
                // Best-effort: the harvestDate edit and weight decrement above
                // already committed and must not be rolled back by a failure
                // here.
                if (harvestDateChanged) {
                    recomputeHarvestSchedulingMetadataAfterUpdate(cycleRef, harvestId);
                }
                return Tasks.forResult(null);
            });
        });
    }

    public Task<Void> deleteHarvestTransaction(String cycleId, String harvestId, double weight) {
        if (selectedDeviceId == null) return Tasks.forException(new Exception("No device selected"));

        DocumentReference cycleRef = db.collection("devices").document(selectedDeviceId)
                .collection("cycles").document(cycleId);
        DocumentReference harvestRef = cycleRef.collection("harvestLogs").document(harvestId);

        return checkAdminTask().onSuccessTask(aVoid ->
                // The delete + totalHarvestWeight/totalHarvestCount decrement must be
                // atomic, so it stays inside the transaction. Recomputing
                // lastHarvestDate/nextHarvestDate needs a query the Firestore
                // transaction API can't track (a plain get() awaited inline), so it
                // used to run here too - meaning any transient failure of that
                // secondary, non-essential query aborted the whole deletion,
                // including the otherwise-valid weight/count decrement. It now runs
                // as a best-effort follow-up after this transaction commits.
                db.runTransaction(transaction -> {
                    DocumentSnapshot cycleSnap = transaction.get(cycleRef);

                    // Validation: Freeze check
                    String status = cycleSnap.getString("status");
                    if (status == null) status = "ACTIVE"; // Legacy support
                    if (!"ACTIVE".equalsIgnoreCase(status)) {
                        throw new FirebaseFirestoreException("Cycle is completed and can no longer be modified.",
                                FirebaseFirestoreException.Code.ABORTED);
                    }

                    double currentWeight = 0;
                    if (cycleSnap.contains("totalHarvestWeight") && cycleSnap.get("totalHarvestWeight") != null) {
                        currentWeight = cycleSnap.getDouble("totalHarvestWeight");
                    }

                    long currentCount = 0;
                    if (cycleSnap.contains("totalHarvestCount") && cycleSnap.get("totalHarvestCount") != null) {
                        currentCount = cycleSnap.getLong("totalHarvestCount");
                    }

                    transaction.delete(harvestRef);
                    transaction.update(cycleRef,
                            "totalHarvestWeight", Math.max(0, currentWeight - weight),
                            "totalHarvestCount", Math.max(0, currentCount - 1)
                    );
                    return null;
                }).continueWithTask(task -> {
                    if (!task.isSuccessful()) {
                        return Tasks.forException(task.getException());
                    }
                    // Best-effort: the harvest deletion and weight/count decrement
                    // above already succeeded and must not be rolled back by a
                    // failure here.
                    recomputeHarvestSchedulingMetadata(cycleRef, harvestId);
                    return Tasks.forResult(null);
                })
        );
    }

    // Recomputes lastHarvestDate/nextHarvestDate from the harvestLogs that
    // remain after a deletion. Runs outside any transaction: it is a fresh
    // recompute from an authoritative query snapshot (not an accumulated
    // delta), so a plain read-then-update is sufficient and avoids blocking
    // a Firestore transaction on a non-transactional network call.
    private void recomputeHarvestSchedulingMetadata(DocumentReference cycleRef, String deletedHarvestId) {
        cycleRef.get().addOnSuccessListener(cycleSnap -> {
            int frequency = 5;
            if (cycleSnap.contains("harvestFrequencyDays") && cycleSnap.get("harvestFrequencyDays") != null) {
                frequency = cycleSnap.getLong("harvestFrequencyDays").intValue();
            }
            final int finalFrequency = frequency;

            cycleRef.collection("harvestLogs")
                    .orderBy("harvestDate", Query.Direction.DESCENDING)
                    .limit(1)
                    .get()
                    .addOnSuccessListener(qSnap -> {
                        Timestamp latestDate = null;
                        Timestamp nextHarvestDate = null;
                        if (!qSnap.isEmpty()) {
                            latestDate = qSnap.getDocuments().get(0).getTimestamp("harvestDate");
                            if (latestDate != null) {
                                java.util.Calendar cal = java.util.Calendar.getInstance();
                                cal.setTime(latestDate.toDate());
                                cal.add(java.util.Calendar.DAY_OF_YEAR, finalFrequency);
                                nextHarvestDate = new Timestamp(cal.getTime());
                            }
                        }

                        Map<String, Object> metadataUpdate = new HashMap<>();
                        metadataUpdate.put("lastHarvestDate", latestDate);
                        metadataUpdate.put("nextHarvestDate", nextHarvestDate);
                        cycleRef.update(metadataUpdate).addOnFailureListener(e ->
                                Log.w(TAG, "Harvest " + deletedHarvestId + " deleted and totals updated, but failed to write "
                                        + "recomputed lastHarvestDate/nextHarvestDate for cycle " + cycleRef.getId(), e));
                    })
                    .addOnFailureListener(e -> Log.w(TAG, "Harvest " + deletedHarvestId + " deleted and totals updated, but "
                            + "failed to query remaining harvestLogs to recompute scheduling metadata for cycle "
                            + cycleRef.getId(), e));
        }).addOnFailureListener(e -> Log.w(TAG, "Harvest " + deletedHarvestId + " deleted and totals updated, but failed to "
                + "read cycle " + cycleRef.getId() + " to recompute scheduling metadata", e));
    }

    // Same rationale as recomputeHarvestSchedulingMetadata above (delete
    // path): a fresh recompute from an authoritative query, run after
    // updateHarvestTransaction's transaction commits instead of blocking it
    // on a non-transactional network call. The edited harvest's own new
    // harvestDate is already reflected in this query - transaction.update()
    // on harvestRef has committed by the time this runs - so no special-cased
    // candidate list (comparing the in-flight edit against existing entries)
    // is needed here, unlike the previous in-transaction version of this
    // logic.
    private void recomputeHarvestSchedulingMetadataAfterUpdate(DocumentReference cycleRef, String updatedHarvestId) {
        cycleRef.get().addOnSuccessListener(cycleSnap -> {
            int frequency = 5;
            if (cycleSnap.contains("harvestFrequencyDays") && cycleSnap.get("harvestFrequencyDays") != null) {
                frequency = cycleSnap.getLong("harvestFrequencyDays").intValue();
            }
            final int finalFrequency = frequency;

            cycleRef.collection("harvestLogs")
                    .orderBy("harvestDate", Query.Direction.DESCENDING)
                    .limit(1)
                    .get()
                    .addOnSuccessListener(qSnap -> {
                        Timestamp latestDate = null;
                        Timestamp nextHarvestDate = null;
                        if (!qSnap.isEmpty()) {
                            latestDate = qSnap.getDocuments().get(0).getTimestamp("harvestDate");
                            if (latestDate != null) {
                                java.util.Calendar cal = java.util.Calendar.getInstance();
                                cal.setTime(latestDate.toDate());
                                cal.add(java.util.Calendar.DAY_OF_YEAR, finalFrequency);
                                nextHarvestDate = new Timestamp(cal.getTime());
                            }
                        }

                        Map<String, Object> metadataUpdate = new HashMap<>();
                        metadataUpdate.put("lastHarvestDate", latestDate);
                        metadataUpdate.put("nextHarvestDate", nextHarvestDate);
                        cycleRef.update(metadataUpdate).addOnFailureListener(e ->
                                Log.w(TAG, "Harvest " + updatedHarvestId + " updated and totals adjusted, but failed to write "
                                        + "recomputed lastHarvestDate/nextHarvestDate for cycle " + cycleRef.getId(), e));
                    })
                    .addOnFailureListener(e -> Log.w(TAG, "Harvest " + updatedHarvestId + " updated and totals adjusted, but "
                            + "failed to query harvestLogs to recompute scheduling metadata for cycle "
                            + cycleRef.getId(), e));
        }).addOnFailureListener(e -> Log.w(TAG, "Harvest " + updatedHarvestId + " updated and totals adjusted, but failed to "
                + "read cycle " + cycleRef.getId() + " to recompute scheduling metadata", e));
    }

    public Task<Void> addHarvestEntry(String cycleId, Map<String, Object> harvestEntry) {
        // Deprecated in favor of addHarvestTransaction but kept for basic compatibility if needed
        if (selectedDeviceId == null) return Tasks.forException(new Exception("No device selected"));

        return db.collection("devices")
                .document(selectedDeviceId)
                .collection("cycles")
                .document(cycleId)
                .collection("harvestLogs")
                .document()
                .set(harvestEntry);
    }

    public Task<QuerySnapshot> getHarvestHistoryForChart(String cycleId) {
        if (selectedDeviceId == null) return Tasks.forException(new Exception("No device selected"));

        // harvestDate is the canonical, always-written field for every live
        // add/edit path (Harvest.harvestDate); the redundant "timestamp"
        // property only exists via Harvest's legacy getTimestamp() alias and
        // is not guaranteed present on every document, so ordering by it
        // risked silently excluding a valid harvest from the chart.
        return db.collection("devices")
                .document(selectedDeviceId)
                .collection("cycles")
                .document(cycleId)
                .collection("harvestLogs")
                .orderBy("harvestDate", Query.Direction.ASCENDING)
                .get();
    }

    public ListenerRegistration listenToHarvestEntries(String cycleId, EventListener<QuerySnapshot> listener) {
        if (selectedDeviceId == null) return null;

        return db.collection("devices")
                .document(selectedDeviceId)
                .collection("cycles")
                .document(cycleId)
                .collection("harvestLogs")
                .orderBy("harvestDate", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .addSnapshotListener(listener);
    }

    public ListenerRegistration listenToCycleDetails(String cycleId, EventListener<DocumentSnapshot> listener) {
        if (selectedDeviceId == null) return null;

        return db.collection("devices")
                .document(selectedDeviceId)
                .collection("cycles")
                .document(cycleId)
                .addSnapshotListener(listener);
    }


    // --------------------
    // DEVICES MANAGEMENT
    // --------------------
    public Task<Void> claimDevice(String deviceCode) {
        return checkAdminTask().onSuccessTask(aVoid -> {
            String adminUid = getCurrentUid();
            if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

            DocumentReference deviceRef = db.collection("devices").document(deviceCode);
            DocumentReference assignmentRef = db.collection("deviceAssignments")
                    .document(adminUid + "_" + deviceCode);

            // The ownership update and the owner's deterministic assignment must either both
            // commit or both be rolled back.  The transaction also prevents two admins from
            // successfully claiming the same previously-unclaimed device at once.
            return db.runTransaction(transaction -> {
                DocumentSnapshot document = transaction.get(deviceRef);
                if (!document.exists()) {
                    throw new FirebaseFirestoreException("Invalid Device Token.",
                            FirebaseFirestoreException.Code.NOT_FOUND);
                }

                String currentOwnerUid = document.getString("ownerUid");
                String status = document.getString("status");
                if (currentOwnerUid != null || "CLAIMED".equals(status)) {
                    // Distinguishes "you already claimed this one" from "someone
                    // else owns it" - both used to surface as the same generic
                    // "check token" message client-side (DeviceFragment), which
                    // misled a user re-entering a token for a device they'd
                    // already claimed into thinking the token itself was wrong.
                    String message = adminUid.equals(currentOwnerUid)
                            ? "You have already claimed this device."
                            : "This device is already claimed by another account.";
                    throw new FirebaseFirestoreException(message,
                            FirebaseFirestoreException.Code.ALREADY_EXISTS);
                }

                Map<String, Object> assignment = new HashMap<>();
                assignment.put("deviceId", deviceCode);
                assignment.put("userUid", adminUid);
                assignment.put("role", RoleConstants.ROLE_ADMIN);
                assignment.put("assignedBy", adminUid);
                assignment.put("assignedAt", System.currentTimeMillis());

                transaction.update(deviceRef, "ownerUid", adminUid, "status", "CLAIMED");
                transaction.set(assignmentRef, assignment);
                return null;
            });
        });
    }

    public Task<Void> unclaimDevice(String deviceId) {
        return checkAdminTask().onSuccessTask(aVoid -> {
            String adminUid = getCurrentUid();
            if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

            DocumentReference deviceRef = db.collection("devices").document(deviceId);

            // Read the exact assignments to remove, then commit the ownership transition and
            // every assignment deletion in one batch.  A rules-side owner check makes a stale
            // read fail safely if ownership changes before this batch reaches Firestore.
            return deviceRef.get().continueWithTask(deviceTask -> {
                if (!deviceTask.isSuccessful()) throw deviceTask.getException();

                DocumentSnapshot device = deviceTask.getResult();
                if (!device.exists()) {
                    throw new FirebaseFirestoreException("Device not found.",
                            FirebaseFirestoreException.Code.NOT_FOUND);
                }
                if (!adminUid.equals(device.getString("ownerUid"))) {
                    throw new FirebaseFirestoreException("Only the device owner can unclaim this device.",
                            FirebaseFirestoreException.Code.PERMISSION_DENIED);
                }

                return db.collection("deviceAssignments")
                        .whereEqualTo("deviceId", deviceId)
                        .get();
            }).continueWithTask(assignmentsTask -> {
                if (!assignmentsTask.isSuccessful()) throw assignmentsTask.getException();

                com.google.firebase.firestore.WriteBatch batch = db.batch();
                batch.update(deviceRef, "ownerUid", null, "status", "UNCLAIMED");
                for (DocumentSnapshot assignment : assignmentsTask.getResult()) {
                    batch.delete(assignment.getReference());
                }
                return batch.commit();
            });
        });
    }

    /**
     * Whether the given device currently has a cycle whose status is ACTIVE.
     * Used to warn an Admin before unclaiming a device with cultivation in
     * progress - unclaiming only revokes visibility, it never stops the
     * device's own automation, so an ACTIVE cycle keeps running regardless.
     */
    public Task<Boolean> hasActiveCycle(String deviceId) {
        return db.collection("devices")
                .document(deviceId)
                .collection("cycles")
                .whereEqualTo("status", "ACTIVE")
                .limit(1)
                .get()
                .continueWith(task -> {
                    if (!task.isSuccessful()) throw task.getException();
                    return !task.getResult().isEmpty();
                });
    }

    public Task<QuerySnapshot> getMyDevices() {
        String uid = getCurrentUid();
        if (uid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("deviceAssignments").whereEqualTo("userUid", uid).get()
                .continueWithTask(assignmentTask -> {
                    if (!assignmentTask.isSuccessful() || assignmentTask.getResult().isEmpty()) {
                        // Return empty query snapshot or failure?
                        // Using a dummy query that returns nothing to keep types consistent
                        return db.collection("devices").whereEqualTo("deviceId", "NONE").get();
                    }

                    List<String> deviceIds = new ArrayList<>();
                    for (DocumentSnapshot doc : assignmentTask.getResult()) {
                        deviceIds.add(doc.getString("deviceId"));
                    }

                    return db.collection("devices").whereIn(FieldPath.documentId(), deviceIds).get();
                });
    }

    // --------------------
    // FIRESTORE: DEVICE ASSIGNMENTS
    // --------------------
    public Task<Void> assignDeviceToUser(String deviceId, String userUid, String role) {
        return checkAdminTask().onSuccessTask(aVoid -> {
            String adminUid = getCurrentUid();
            if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

            Map<String, Object> assignment = new HashMap<>();
            assignment.put("deviceId", deviceId);
            assignment.put("userUid", userUid);
            assignment.put("role", role.toUpperCase());
            assignment.put("assignedBy", adminUid);
            assignment.put("assignedAt", System.currentTimeMillis());

            String assignmentId = userUid + "_" + deviceId;
            DocumentReference assignmentRef = db.collection("deviceAssignments").document(assignmentId);
            // A deterministic ID avoids duplicate documents; the transaction also avoids
            // treating an existing assignment as a successful new assignment.
            return db.runTransaction(transaction -> {
                if (transaction.get(assignmentRef).exists()) {
                    throw new FirebaseFirestoreException("Device is already assigned to this personnel.",
                            FirebaseFirestoreException.Code.ALREADY_EXISTS);
                }
                transaction.set(assignmentRef, assignment);
                return null;
            });
        });
    }

    public Task<DocumentSnapshot> getDeviceDocument(String deviceId) {
        return db.collection("devices").document(deviceId).get();
    }

    public Task<QuerySnapshot> getAssignmentsForUser(String userUid) {
        return db.collection("deviceAssignments")
                .whereEqualTo("userUid", userUid)
                .get();
    }

    public Task<Void> removeAssignment(String deviceId, String userUid) {
        return checkAdminTask().onSuccessTask(aVoid -> {
            String assignmentId = userUid + "_" + deviceId;
            return db.collection("deviceAssignments").document(assignmentId).delete();
        });
    }

    public Task<QuerySnapshot> getAssignedDevices() {
        String uid = getCurrentUid();
        if (uid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("deviceAssignments").whereEqualTo("userUid", uid).get();
    }

    /**
     * Migrates existing deviceAssignments for a specific user to use deterministic IDs (uid_deviceId).
     * Scoped to the current user to satisfy security rules and improve performance.
     * Uses a Write Batch to ensure atomicity for each assignment migration.
     */
    public Task<Void> migrateDeviceAssignments(String uid) {
        if (uid == null) return Tasks.forResult(null);

        return db.collection("deviceAssignments")
                .whereEqualTo("userUid", uid)
                .get()
                .continueWithTask(task -> {
                    if (!task.isSuccessful()) throw task.getException();

                    com.google.firebase.firestore.WriteBatch batch = db.batch();
                    boolean hasChanges = false;

                    for (DocumentSnapshot doc : task.getResult()) {
                        String currentId = doc.getId();
                        String deviceId = doc.getString("deviceId");

                        if (deviceId != null) {
                            String expectedId = uid + "_" + deviceId;
                            if (!currentId.equals(expectedId)) {
                                // Create the new document and delete the old one in one atomic batch
                                DocumentReference newRef = db.collection("deviceAssignments").document(expectedId);
                                DocumentReference oldRef = doc.getReference();

                                batch.set(newRef, doc.getData());
                                batch.delete(oldRef);
                                hasChanges = true;
                            }
                        }
                    }

                    if (hasChanges) {
                        return batch.commit();
                    } else {
                        return Tasks.forResult(null);
                    }
                });
    }


    // --------------------
    // NOTIFICATIONS (Firestore: devices/{deviceId}/notifications)
    // --------------------

    /**
     * Writes a new notification document to Firestore under devices/{deviceId}/notifications.
     * Called by AlertManager when an alert transitions from false → true.
     */
    // Live window size for listenToNotifications() below - kept small
    // deliberately, since every listener callback re-reads this many
    // documents from the server on each (re)connect. Older history beyond
    // this window is fetched on demand via loadOlderNotifications() instead
    // of being kept live-synced, which is what makes browsing further back
    // cheap regardless of how much total history a device has accumulated.
    public static final int NOTIFICATIONS_LIVE_PAGE_SIZE = 50;
    public static final int NOTIFICATIONS_OLDER_PAGE_SIZE = 50;

    /**
     * Attaches a real-time Firestore listener to devices/{deviceId}/notifications,
     * ordered by timestamp descending, limited to the most recent
     * {@link #NOTIFICATIONS_LIVE_PAGE_SIZE} entries - new notifications and
     * read-state changes within this window appear immediately. Older
     * history is not part of this live window at all; see
     * loadOlderNotifications() for fetching it on demand.
     */
    public ListenerRegistration listenToNotifications(EventListener<QuerySnapshot> listener) {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) return null;

        return db.collection("devices")
                .document(selectedDeviceId)
                .collection("notifications")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(NOTIFICATIONS_LIVE_PAGE_SIZE)
                .addSnapshotListener(listener);
    }

    /**
     * One-time (not live) fetch of the next {@link #NOTIFICATIONS_OLDER_PAGE_SIZE}
     * notifications strictly older than startAfterDoc, for "Load Older
     * Notifications" pagination. startAfterDoc must be the DocumentSnapshot
     * of the oldest notification currently loaded (from either the live
     * listener's own snapshot or a previous call to this method) - Firestore
     * cursors are anchored to a real document position, not an offset, so
     * this stays correct even while the live page above keeps shifting as
     * new notifications arrive.
     */
    public Task<QuerySnapshot> loadOlderNotifications(DocumentSnapshot startAfterDoc) {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty() || startAfterDoc == null) {
            return Tasks.forException(new Exception("No device selected or no pagination cursor"));
        }
        return db.collection("devices")
                .document(selectedDeviceId)
                .collection("notifications")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .startAfter(startAfterDoc)
                .limit(NOTIFICATIONS_OLDER_PAGE_SIZE)
                .get();
    }

    public Task<Void> markNotificationsRead(String deviceId, List<String> notificationIds) {
        if (deviceId == null || deviceId.isEmpty() || notificationIds == null || notificationIds.isEmpty()) return Tasks.forResult(null);
        String uid = getCurrentUid();
        if (uid == null) return Tasks.forException(new Exception("User not authenticated"));

        com.google.firebase.firestore.WriteBatch batch = db.batch();
        for (String notificationId : notificationIds) {
            if (notificationId != null && !notificationId.isEmpty()) {
                // One key per document - readBy.<uid> - so another user's entry
                // is never touched. The legacy document-wide isRead field is
                // deliberately left alone: writing it was what let one person
                // clear the unread badge for everyone on the device.
                batch.update(
                        db.collection("devices").document(deviceId)
                                .collection("notifications").document(notificationId),
                        FieldPath.of("readBy", uid), FieldValue.serverTimestamp());
            }
        }
        return batch.commit();
    }

}

