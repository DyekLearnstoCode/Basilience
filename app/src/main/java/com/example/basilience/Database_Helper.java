package com.example.basilience;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class Database_Helper {

    private final FirebaseAuth auth;
    private final FirebaseFirestore db;
    private final FirebaseDatabase rtdb;

    private static boolean isConnectedListenerRegistered = false;

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
                DocumentReference personnelRef = queryTask.getResult().getDocuments().get(0).getReference();
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

        return checkAdminTask()
                .addOnFailureListener(e -> Log.e(TAG, "[MANUAL-APP] Admin authorization failed actuator=" + actuatorName
                        + " cachedRole=" + cachedRole, e))
                .onSuccessTask(aVoid -> {
                    Log.d(TAG, "[MANUAL-APP] Admin authorization passed actuator=" + actuatorName);
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
                        return Tasks.forException(new Exception("Manual mode must be enabled to control actuators."));
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
                return deviceRef.child("commands").child("manualMode").setValue(true);
            }
        });
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

    public Task<Void> addHarvestTransaction(String cycleId, Harvest harvest) {
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

        return db.runTransaction(transaction -> {
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
            return null;
        });
    }

    public Task<Void> updateHarvestTransaction(String cycleId, String harvestId, double oldWeight, double newWeight, Map<String, Object> updates) {
        if (selectedDeviceId == null) return Tasks.forException(new Exception("No device selected"));

        return checkAdminTask().onSuccessTask(aVoid -> {
            DocumentReference cycleRef = db.collection("devices").document(selectedDeviceId)
                    .collection("cycles").document(cycleId);
            DocumentReference harvestRef = cycleRef.collection("harvestLogs").document(harvestId);

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

                Map<String, Object> cycleUpdates = new HashMap<>();
                cycleUpdates.put("totalHarvestWeight", currentTotalWeight - oldWeight + newWeight);

                // If the harvestDate is being updated, we need to update lastHarvestDate and nextHarvestDate
                if (updates.containsKey("harvestDate")) {
                    try {
                        QuerySnapshot qSnap = Tasks.await(cycleRef.collection("harvestLogs")
                                .orderBy("harvestDate", Query.Direction.DESCENDING)
                                .limit(2)
                                .get());

                        List<Timestamp> candidates = new ArrayList<>();
                        candidates.add((Timestamp) updates.get("harvestDate"));

                        if (qSnap != null) {
                            for (DocumentSnapshot doc : qSnap.getDocuments()) {
                                if (!doc.getId().equals(harvestId)) {
                                    candidates.add(doc.getTimestamp("harvestDate"));
                                }
                            }
                        }

                        Timestamp latestDate = Collections.max(candidates);
                        cycleUpdates.put("lastHarvestDate", latestDate);

                        // Recalculate nextHarvestDate
                        int frequency = 5;
                        if (cycleSnap.contains("harvestFrequencyDays") && cycleSnap.get("harvestFrequencyDays") != null) {
                            frequency = cycleSnap.getLong("harvestFrequencyDays").intValue();
                        }

                        if (latestDate != null) {
                            java.util.Calendar cal = java.util.Calendar.getInstance();
                            cal.setTime(latestDate.toDate());
                            cal.add(java.util.Calendar.DAY_OF_YEAR, frequency);
                            cycleUpdates.put("nextHarvestDate", new Timestamp(cal.getTime()));
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to update cycle summary", e);
                    }
                }

                transaction.update(cycleRef, cycleUpdates);
                return null;
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
                    throw new FirebaseFirestoreException("Device is already claimed.",
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
    /**
     * Attaches a real-time Firestore listener to devices/{deviceId}/notifications,
     * ordered by timestamp descending, limited to the most recent 50 entries.
     */
    public ListenerRegistration listenToNotifications(EventListener<QuerySnapshot> listener) {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty()) return null;

        return db.collection("devices")
                .document(selectedDeviceId)
                .collection("notifications")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(50)
                .addSnapshotListener(listener);
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

