package com.example.basilience;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

public class Database_Helper {

    private final FirebaseAuth auth;
    private final FirebaseFirestore db;

    private String targetUid;

    public Database_Helper() {
        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
    }

    public void setTargetUid(String uid) {
        this.targetUid = uid;
    }

    private String getEffectiveUid() {
        return targetUid != null ? targetUid : getCurrentUid();
    }

    /**
     * Resolves the UID that should be used for data operations (Admin's UID).
     * If current user is a farmer, returns their ownerAdminUid.
     */
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
    // AUTH
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

    // --------------------
    // FIRESTORE: USERS (profiles)
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

    // (Optional) admin-side list of all user profiles (top-level)
    public Task<QuerySnapshot> getAllUsers() {
        return db.collection("users").get();
    }

    public ListenerRegistration listenToUsers(EventListener<QuerySnapshot> listener) {
        return db.collection("users").addSnapshotListener(listener);
    }

    public Task<QuerySnapshot> getUsersByRole(String role) {
        return db.collection("users")
                .whereEqualTo("role", role)
                .get();
    }

    // --------------------
    // FIRESTORE: PERSONNEL (subcollection under current admin)
    // Path: users/{adminUid}/personnel/{personnelId}
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

        return db.collection("users")
                .document(adminUid)
                .collection("personnel")
                .document() // auto-id
                .set(p);
    }

    public Task<QuerySnapshot> getMyPersonnelByRole(String role) {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("users")
                .document(adminUid)
                .collection("personnel")
                .whereEqualTo("role", role)
                .get();
    }

    public Task<QuerySnapshot> getAllMyPersonnel() {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("users")
                .document(adminUid)
                .collection("personnel")
                .get();
    }

    // --------------------
    // CREATE FARMER LOGIN (Auth) + save to Firestore, without logging out admin
    // Uses secondary FirebaseApp (initialized in MyApp)
    // --------------------
    public Task<Void> createFarmerAccountAndAssignToCurrentAdmin(
            String name, String email, String phone, String password
    ) {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        FirebaseAuth secondaryAuth = FirebaseAuth.getInstance(FirebaseApp.getInstance("secondary"));

        return secondaryAuth.createUserWithEmailAndPassword(email, password)
                .continueWithTask(task -> {
                    if (!task.isSuccessful()) throw task.getException();

                    if (task.getResult() == null || task.getResult().getUser() == null) {
                        throw new Exception("User creation failed");
                    }

                    String farmerUid = task.getResult().getUser().getUid();

                    // top-level farmer profile (used when farmer logs in)
                    Map<String, Object> farmerProfile = new HashMap<>();
                    farmerProfile.put("name", name);
                    farmerProfile.put("email", email);
                    farmerProfile.put("phone", phone);
                    farmerProfile.put("role", "farmer");
                    farmerProfile.put("createdAt", System.currentTimeMillis());
                    farmerProfile.put("ownerAdminUid", adminUid);

                    // admin subcollection record
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
                                    .document(farmerUid) // use farmer uid as doc id
                                    .set(personnel))
                            .addOnCompleteListener(done -> secondaryAuth.signOut());
                });
    }
    public Task<Void> updatePersonnelForCurrentAdmin(String personnelId, Map<String, Object> updates) {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        Task<Void> t1 = db.collection("users")
                .document(adminUid)
                .collection("personnel")
                .document(personnelId)
                .update(updates);

        // Also sync to top-level user profile if it's a farmer
        Task<Void> t2 = db.collection("users")
                .document(personnelId)
                .update(updates)
                .continueWithTask(task -> Tasks.forResult(null)); // Ignore if user doc doesn't exist

        return Tasks.whenAll(t1, t2);
    }

    public Task<Void> deletePersonnelForCurrentAdmin(String personnelId) {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        Task<Void> t1 = db.collection("users")
                .document(adminUid)
                .collection("personnel")
                .document(personnelId)
                .delete();

        // Delete top-level profile to prevent login
        Task<Void> t2 = db.collection("users")
                .document(personnelId)
                .delete()
                .continueWithTask(task -> Tasks.forResult(null));

        return Tasks.whenAll(t1, t2);
    }
    public Task<DocumentSnapshot> getPersonnelForCurrentAdmin(String personnelId) {
        String adminUid = getCurrentUid();
        if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("users")
                .document(adminUid)
                .collection("personnel")
                .document(personnelId)
                .get();
    }

    // --------------------
    // MONITORING & ACTUATORS
    // --------------------
    public ListenerRegistration listenToSystemStatus(EventListener<DocumentSnapshot> listener) {
        String uid = getEffectiveUid();
        if (uid == null) return null;

        // Path: users/{uid}/system/status
        return db.collection("users")
                .document(uid)
                .collection("system")
                .document("status")
                .addSnapshotListener(listener);
    }

    public Task<Void> updateActuatorState(String actuatorName, boolean isOn) {
        String uid = getEffectiveUid();
        if (uid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("users")
                .document(uid)
                .collection("system")
                .document("status")
                .update("actuators." + actuatorName, isOn)
                .addOnFailureListener(e -> {
                    Map<String, Object> actuators = new HashMap<>();
                    actuators.put(actuatorName, isOn);
                    Map<String, Object> data = new HashMap<>();
                    data.put("actuators", actuators);
                    db.collection("users").document(uid).collection("system").document("status")
                            .set(data, SetOptions.merge());
                });
    }

    public Task<Void> updateManualMode(boolean isManual) {
        String uid = getEffectiveUid();
        if (uid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("users")
                .document(uid)
                .collection("system")
                .document("status")
                .update("manualMode", isManual)
                .addOnFailureListener(e -> {
                    Map<String, Object> data = new HashMap<>();
                    data.put("manualMode", isManual);
                    db.collection("users").document(uid).collection("system").document("status")
                            .set(data, SetOptions.merge());
                });
    }

    // --------------------
    // CYCLES
    // --------------------
    public Task<Void> addCycle(Cycle cycle) {
        String uid = getEffectiveUid();
        if (uid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("users")
                .document(uid)
                .collection("cycles")
                .document("cycle_" + cycle.getCycleNo())
                .set(cycle);
    }

    public ListenerRegistration listenToCycles(EventListener<QuerySnapshot> listener) {
        String uid = getEffectiveUid();
        if (uid == null) return null;

        return db.collection("users")
                .document(uid)
                .collection("cycles")
                .orderBy("cycleNo")
                .addSnapshotListener(listener);
    }

    // --------------------
    // SYSTEM LOGS (Parameters)
    // --------------------
    public Task<QuerySnapshot> getParameterLogs(String parameter, long startTime, long endTime) {
        String uid = getEffectiveUid();
        if (uid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("users")
                .document(uid)
                .collection("logs")
                .whereEqualTo("parameter", parameter)
                .whereGreaterThanOrEqualTo("timestamp", startTime)
                .whereLessThanOrEqualTo("timestamp", endTime)
                .orderBy("timestamp")
                .get();
    }

    // --------------------
    // HARVEST LOGS
    // --------------------
    public Task<Void> addHarvestEntry(int cycleNo, Map<String, Object> harvestEntry) {
        String uid = getEffectiveUid();
        if (uid == null) return Tasks.forException(new Exception("Not logged in"));

        return db.collection("users")
                .document(uid)
                .collection("cycles")
                .document("cycle_" + cycleNo)
                .collection("harvests")
                .document() // auto-id
                .set(harvestEntry);
    }

    public ListenerRegistration listenToHarvestEntries(int cycleNo, EventListener<QuerySnapshot> listener) {
        String uid = getEffectiveUid();
        if (uid == null) return null;

        return db.collection("users")
                .document(uid)
                .collection("cycles")
                .document("cycle_" + cycleNo)
                .collection("harvests")
                .orderBy("date")
                .addSnapshotListener(listener);
    }

    // --------------------
    // NOTIFICATIONS
    // --------------------
    public ListenerRegistration listenToNotifications(EventListener<QuerySnapshot> listener) {
        String uid = getEffectiveUid();
        if (uid == null) return null;

        return db.collection("users")
                .document(uid)
                .collection("notifications")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .addSnapshotListener(listener);
    }
}