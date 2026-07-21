package com.example.basilience;

import static android.content.ContentValues.TAG;

import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DatabaseReference;

import java.util.HashMap;
import java.util.Map;

public class Database_Helper {

    private final FirebaseAuth auth;
    private final FirebaseFirestore db;
    private final FirebaseDatabase rtdb;
    private final DatabaseReference deviceRef;

    private String targetUid; // Naglalaman ng Active/Claimed Device ID (E.g., BSLN-9X2A-K47P)

    public Database_Helper() {
        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        // RTDB Initialization
        rtdb = FirebaseDatabase.getInstance("https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app");
        deviceRef = rtdb.getReference("device");
    }

    public interface EmailVerificationCallback {
        void onSuccess();
        void onFailure(String errorMessage);
    }

    public void setTargetUid(String uid) {
        this.targetUid = uid;
    }

    public String getTargetUid() {
        return this.targetUid;
    }

    private String getEffectiveUid() {
        return targetUid != null ? targetUid : getCurrentUid();
    }

    public Task<String> resolveDataUid() {
        String uid = getCurrentUid();
        if (uid == null) return Tasks.forResult(null);

        return getUserProfile(uid).continueWith(task -> {
            if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                DocumentSnapshot doc = task.getResult();
                String role = doc.getString("role");
                if ("farmer".equals(role)) {
                    String owner = doc.getString("ownerAdminUid");
                    if (owner != null) return owner;
                }
            }
            return uid;
        });
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

    public void logout() {
        auth.signOut();
    }

    public String getCurrentUid() {
        return auth.getCurrentUser() != null ? auth.getCurrentUser().getUid() : null;
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
    public Task<Void> createUserProfile(String uid, String name, String email, String role) {
        Map<String, Object> user = new HashMap<>();
        user.put("name", name);
        user.put("email", email);
        user.put("role", role);
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
    // FIRESTORE: PERSONNEL (Subcollection under Admin)
    // --------------------
    public Task<Void> addPersonnelToCurrentAdmin(String name, String role, String email, String phone) {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        Map<String, Object> p = new HashMap<>();
        p.put("name", name);
        p.put("role", role);
        p.put("email", email);
        p.put("phone", phone);
        p.put("createdAt", System.currentTimeMillis());

        return db.collection("users").document(adminUid).collection("personnel").document().set(p);
    }

    public Task<QuerySnapshot> getMyPersonnelByRole(String role) {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("users").document(adminUid).collection("personnel").whereEqualTo("role", role).get();
    }

    public Task<QuerySnapshot> getAllMyPersonnel() {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("users").document(adminUid).collection("personnel").get();
    }

    public Task<Void> createFarmerAccountAndAssignToCurrentAdmin(String name, String email, String phone, String password) {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        FirebaseAuth secondaryAuth = FirebaseAuth.getInstance(FirebaseApp.getInstance("secondary"));

        return secondaryAuth.createUserWithEmailAndPassword(email, password)
                .continueWithTask(task -> {
                    if (!task.isSuccessful()) throw task.getException();
                    if (task.getResult() == null || task.getResult().getUser() == null) throw new Exception("User creation failed");

                    String farmerUid = task.getResult().getUser().getUid();

                    Map<String, Object> farmerProfile = new HashMap<>();
                    farmerProfile.put("name", name);
                    farmerProfile.put("email", email);
                    farmerProfile.put("phone", phone);
                    farmerProfile.put("role", "farmer");
                    farmerProfile.put("createdAt", System.currentTimeMillis());
                    farmerProfile.put("ownerAdminUid", adminUid);

                    Map<String, Object> personnel = new HashMap<>();
                    personnel.put("userId", farmerUid);
                    personnel.put("name", name);
                    personnel.put("email", email);
                    personnel.put("phone", phone);
                    personnel.put("role", "farmer");
                    personnel.put("createdAt", System.currentTimeMillis());

                    return db.collection("users").document(farmerUid).set(farmerProfile)
                            .continueWithTask(t2 -> db.collection("users")
                                    .document(adminUid)
                                    .collection("personnel")
                                    .document(farmerUid)
                                    .set(personnel))
                            .addOnCompleteListener(done -> secondaryAuth.signOut());
                });
    }

    public Task<Void> updatePersonnelForCurrentAdmin(String personnelId, Map<String, Object> updates) {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        Task<Void> t1 = db.collection("users").document(adminUid).collection("personnel").document(personnelId).update(updates);
        Task<Void> t2 = db.collection("users").document(personnelId).update(updates).continueWithTask(task -> Tasks.forResult(null));

        return Tasks.whenAll(t1, t2);
    }

    public Task<Void> deletePersonnelForCurrentAdmin(String personnelId) {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        Task<Void> t1 = db.collection("users").document(adminUid).collection("personnel").document(personnelId).delete();
        Task<Void> t2 = db.collection("users").document(personnelId).delete().continueWithTask(task -> Tasks.forResult(null));

        return Tasks.whenAll(t1, t2);
    }

    public Task<DocumentSnapshot> getPersonnelForCurrentAdmin(String personnelId) {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("users").document(adminUid).collection("personnel").document(personnelId).get();
    }

    // --------------------
    // REALTIME DATABASE & ACTUATORS
    // --------------------
    public DatabaseReference getSensorsReference() {
        return rtdb.getReference("device").child("sensors");
    }

    public void updateActuatorState(String actuatorName, boolean isOn) {
        String path = "device/command/" + actuatorName;
        rtdb.getReference(path).setValue(isOn)
                .addOnSuccessListener(aVoid -> Log.d("ACTUATOR_DEBUG", "SUCCESS"))
                .addOnFailureListener(e -> Log.e("ACTUATOR_DEBUG", "FAILED", e));
    }

    public void updateManualMode(boolean isManual) {
        deviceRef.child("command").child("manualMode").setValue(isManual)
                .addOnSuccessListener(aVoid -> Log.d("MANUAL_DEBUG", "RTDB write success"))
                .addOnFailureListener(e -> Log.e("MANUAL_DEBUG", "RTDB write failed", e));
    }

    // --------------------
    // FIRESTORE: SYSTEM STATUS & CYCLES
    // --------------------
    public ListenerRegistration listenToSystemStatus(EventListener<DocumentSnapshot> listener) {
        String uid = getEffectiveUid();
        if (uid == null) return null;

        return db.collection("users").document(uid).collection("system").document("status").addSnapshotListener(listener);
    }

    public Task<Void> addCycle(Cycle cycle) {
        String uid = getEffectiveUid();
        if (uid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("users").document(uid).collection("cycles").document("cycle_" + cycle.getCycleNo()).set(cycle);
    }

    public ListenerRegistration listenToCycles(EventListener<QuerySnapshot> listener) {
        String uid = getEffectiveUid();
        if (uid == null) return null;

        return db.collection("users").document(uid).collection("cycles").orderBy("cycleNo").addSnapshotListener(listener);
    }

    // --------------------
    // 🔥 FIRESTORE: DEVICE LOGS (Para sa Graphs)
    // Path: devices/{targetUid}/logs/{timestamp}
    // --------------------
    public Task<QuerySnapshot> getParameterLogs(long startTime, long endTime) {
        if (targetUid == null || targetUid.isEmpty()) {
            return Tasks.forException(new Exception("No active device selected"));
        }

        return db.collection("devices")
                .document(targetUid)
                .collection("logs")
                .whereGreaterThanOrEqualTo("timestamp", startTime)
                .whereLessThanOrEqualTo("timestamp", endTime)
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .get();
    }

    public Task<Void> addTestLog(long timestamp, double airTemp, double humidity, double waterTemp, double waterLevel, double ph, double ec) {
        if (targetUid == null || targetUid.isEmpty()) {
            return Tasks.forException(new Exception("No active device to seed"));
        }

        Map<String, Object> log = new HashMap<>();
        log.put("timestamp", timestamp);
        log.put("air_temp", airTemp);
        log.put("humidity", humidity);
        log.put("water_temp", waterTemp);
        log.put("water_level", waterLevel);
        log.put("ph", ph);
        log.put("ec", ec);

        return db.collection("devices")
                .document(targetUid)
                .collection("logs")
                .document(String.valueOf(timestamp))
                .set(log, SetOptions.merge());
    }

    // --------------------
    // HARVEST LOGS & NOTIFICATIONS
    // --------------------
    public Task<Void> addHarvestEntry(int cycleNo, Map<String, Object> harvestEntry) {
        String uid = getEffectiveUid();
        if (uid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("users").document(uid).collection("cycles").document("cycle_" + cycleNo).collection("harvests").document().set(harvestEntry);
    }

    public ListenerRegistration listenToHarvestEntries(int cycleNo, EventListener<QuerySnapshot> listener) {
        String uid = getEffectiveUid();
        if (uid == null) return null;

        return db.collection("users").document(uid).collection("cycles").document("cycle_" + cycleNo).collection("harvests").orderBy("date").addSnapshotListener(listener);
    }

    public ListenerRegistration listenToNotifications(EventListener<QuerySnapshot> listener) {
        String uid = getEffectiveUid();
        if (uid == null) return null;

        return db.collection("users").document(uid).collection("notifications").orderBy("timestamp", Query.Direction.DESCENDING).addSnapshotListener(listener);
    }

    public Task<Void> addNotification(String message, String type) {
        String uid = getEffectiveUid();
        if (uid == null) return Tasks.forException(new Exception("User not logged in"));

        Map<String, Object> notification = new HashMap<>();
        notification.put("message", message);
        notification.put("timestamp", System.currentTimeMillis());
        notification.put("type", type);

        return db.collection("users").document(uid).collection("notifications").document().set(notification);
    }

    // --------------------
    // DEVICES MANAGEMENT
    // --------------------
    public Task<Void> claimDevice(String deviceCode) {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        DocumentReference deviceRef = db.collection("devices").document(deviceCode);

        return deviceRef.get().continueWithTask(task -> {
            if (!task.isSuccessful()) throw task.getException();

            DocumentSnapshot document = task.getResult();
            if (!document.exists()) throw new Exception("Invalid Device Token Code.");

            String ownerId = document.getString("owner_id");
            if (ownerId != null && !ownerId.isEmpty()) throw new Exception("This device is already registered to another user.");

            Map<String, Object> updates = new HashMap<>();
            updates.put("owner_id", adminUid);
            updates.put("status", "claimed");

            return deviceRef.update(updates);
        });
    }
    // 🔥 IN-UPDATE: Naghahanap na sa Firestore gamit ang device_name at owner_id
    public Task<Void> unclaimDevice(String deviceName) {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("devices")
                .whereEqualTo("device_name", deviceName)
                .whereEqualTo("owner_id", adminUid)
                .get()
                .continueWithTask(task -> {
                    if (!task.isSuccessful()) throw task.getException();

                    QuerySnapshot snapshot = task.getResult();
                    if (snapshot == null || snapshot.isEmpty()) {
                        throw new Exception("Device not found for: " + deviceName);
                    }

                    // Kunin ang nahanap na document (kahit ano man ang Document ID / Token)
                    DocumentSnapshot doc = snapshot.getDocuments().get(0);

                    Map<String, Object> updates = new HashMap<>();
                    updates.put("owner_id", null);
                    updates.put("status", "unclaimed");

                    return doc.getReference().update(updates);
                });
    }
    public Task<QuerySnapshot> getMyDevices() {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("devices").whereEqualTo("owner_id", adminUid).get();
    }

}
