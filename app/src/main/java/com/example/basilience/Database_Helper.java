package com.example.basilience;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
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
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DatabaseReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Database_Helper {

    private final FirebaseAuth auth;
    private final FirebaseFirestore db;
    private final FirebaseDatabase rtdb;

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
        DatabaseReference connectedRef = rtdb.getReference(".info/connected");
        connectedRef.addValueEventListener(new com.google.firebase.database.ValueEventListener() {
            @Override
            public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snapshot) {
                boolean connected = snapshot.getValue(Boolean.class);
                if (connected) {
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

    public void logout() {
        auth.signOut();
        cachedRole = null;
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
            // Just unlinking for now by removing the ownerAdminUid
            Map<String, Object> updates = new HashMap<>();
            updates.put("ownerAdminUid", null);
            return db.collection("users").document(personnelId).update(updates);
        });
    }

    public Task<DocumentSnapshot> getPersonnelForCurrentAdmin(String personnelId) {
        return db.collection("users").document(personnelId).get();
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
        if (selectedDeviceId == null || selectedDeviceId.isEmpty())
            return Tasks.forException(new Exception("No device selected"));

        return checkAdminTask().onSuccessTask(aVoid ->
                rtdb.getReference("devices").child(selectedDeviceId).child("commands").child("manualMode").get()
        ).onSuccessTask(snapshot -> {
            Boolean isManual = snapshot.getValue(Boolean.class);
            if (isManual != null && isManual) {
                Map<String, Object> commandData = new HashMap<>();
                commandData.put("state", isOn);
                commandData.put("source", "android");
                commandData.put("timestamp", System.currentTimeMillis());
                
                String path = "devices/" + selectedDeviceId + "/commands/" + actuatorName;
                return rtdb.getReference(path).setValue(commandData);
            } else {
                return Tasks.forException(new Exception("Manual mode must be enabled to control actuators."));
            }
        });
    }

    public Task<Void> updateManualMode(boolean isManual) {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty())
            return Tasks.forException(new Exception("No device selected"));

        return checkAdminTask().onSuccessTask(aVoid -> {
            DatabaseReference cmdRef = rtdb.getReference("devices").child(selectedDeviceId).child("commands");
            if (!isManual) {
                Map<String, Object> turnOffData = new HashMap<>();
                turnOffData.put("state", false);
                turnOffData.put("source", "android");
                turnOffData.put("timestamp", System.currentTimeMillis());

                Map<String, Object> updates = new HashMap<>();
                updates.put("manualMode", false);
                updates.put("solenoid", turnOffData);
                updates.put("canopyFan", turnOffData);
                updates.put("growLight", turnOffData);
                updates.put("phUpPump", turnOffData);
                updates.put("phDownPump", turnOffData);
                updates.put("growPump", turnOffData);
                updates.put("bloomPump", turnOffData);
                updates.put("fogger", turnOffData);
                updates.put("blower", turnOffData);
                updates.put("peltier", turnOffData);
                return cmdRef.updateChildren(updates);
            } else {
                return cmdRef.child("manualMode").setValue(true);
            }
        });
    }

    /**
     * Sends an OperationRequest to the firmware via RTDB devices/{deviceId}/commands/current.
     * Uses SharedPreferences to persist an incrementing requestId.
     */
    public Task<Void> sendOperationRequest(String operation, String action) {
        if (selectedDeviceId == null || selectedDeviceId.isEmpty())
            return Tasks.forException(new Exception("No device selected"));

        // Using SharedPreferences to maintain a simple incrementing requestId
        Context context = FirebaseApp.getInstance().getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("Basilience_Prefs", Context.MODE_PRIVATE);
        int lastId = prefs.getInt("last_request_id", 0);
        int nextId = lastId + 1;

        // Persist the new ID
        prefs.edit().putInt("last_request_id", nextId).apply();

        long timestamp = System.currentTimeMillis() / 1000L; // Unix timestamp in seconds

        OperationRequest request = new OperationRequest(
                nextId,
                operation,
                action,
                timestamp,
                1 // protocolVersion
        );

        return checkAdminTask().onSuccessTask(aVoid ->
                rtdb.getReference("devices").child(selectedDeviceId).child("commands").child("current").setValue(request)
        );
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

    public Task<Void> addCycle(Cycle cycle) {
        if (selectedDeviceId == null) return Tasks.forException(new Exception("No device selected"));

        return checkAdminTask().onSuccessTask(aVoid ->
                db.collection("devices")
                        .document(selectedDeviceId)
                        .collection("cycles")
                        .document(cycle.getCycleId())
                        .set(cycle)
        );
    }

    public ListenerRegistration listenToCycles(EventListener<QuerySnapshot> listener) {
        if (selectedDeviceId == null) return null;

        return db.collection("devices")
                .document(selectedDeviceId)
                .collection("cycles")
                .orderBy("cycleNumber")
                .addSnapshotListener(listener);
    }

    public Task<QuerySnapshot> getActiveCycle(String deviceId) {
        return db.collection("devices")
                .document(deviceId)
                .collection("cycles")
                .whereEqualTo("status", "ACTIVE")
                .limit(1)
                .get();
    }

    public Task<Void> completeCycle(String cycleId) {
        if (selectedDeviceId == null)
            return Tasks.forException(new Exception("Device not selected"));

        return checkAdminTask().onSuccessTask(aVoid -> {
            Map<String, Object> updates = new HashMap<>();
            updates.put("status", "COMPLETED");
            updates.put("endDate", com.google.firebase.Timestamp.now());
            updates.put("nextHarvestDate", null);

            return db.collection("devices").document(selectedDeviceId)
                    .collection("cycles").document(cycleId)
                    .update(updates);
        });
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

                double currentWeight = 0;
                if (cycleSnap.contains("totalHarvestWeight") && cycleSnap.get("totalHarvestWeight") != null) {
                    currentWeight = cycleSnap.getDouble("totalHarvestWeight");
                }

                long currentCount = 0;
                if (cycleSnap.contains("totalHarvestCount") && cycleSnap.get("totalHarvestCount") != null) {
                    currentCount = cycleSnap.getLong("totalHarvestCount");
                }

                transaction.delete(harvestRef);

                // We need to re-evaluate lastHarvestDate and nextHarvestDate after deletion
                try {
                    QuerySnapshot qSnap = Tasks.await(cycleRef.collection("harvestLogs")
                            .orderBy("harvestDate", Query.Direction.DESCENDING)
                            .limit(2)
                            .get());

                    List<Timestamp> candidates = new ArrayList<>();
                    if (qSnap != null) {
                        for (DocumentSnapshot doc : qSnap.getDocuments()) {
                            if (!doc.getId().equals(harvestId)) {
                                candidates.add(doc.getTimestamp("harvestDate"));
                            }
                        }
                    }

                    Timestamp latestDate = null;
                    Timestamp nextHarvestDate = null;

                    if (!candidates.isEmpty()) {
                        latestDate = Collections.max(candidates);

                        if (latestDate != null) {
                            int frequency = 5;
                            if (cycleSnap.contains("harvestFrequencyDays") && cycleSnap.get("harvestFrequencyDays") != null) {
                                frequency = cycleSnap.getLong("harvestFrequencyDays").intValue();
                            }
                            java.util.Calendar cal = java.util.Calendar.getInstance();
                            cal.setTime(latestDate.toDate());
                            cal.add(java.util.Calendar.DAY_OF_YEAR, frequency);
                            nextHarvestDate = new Timestamp(cal.getTime());
                        }
                    }

                    transaction.update(cycleRef,
                            "totalHarvestWeight", Math.max(0, currentWeight - weight),
                            "totalHarvestCount", Math.max(0, currentCount - 1),
                            "lastHarvestDate", latestDate,
                            "nextHarvestDate", nextHarvestDate
                    );
                } catch (Exception e) {
                    throw new RuntimeException("Failed to update cycle summary after deletion", e);
                }
                return null;
            });
        });
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

        return db.collection("devices")
                .document(selectedDeviceId)
                .collection("cycles")
                .document(cycleId)
                .collection("harvestLogs")
                .orderBy("timestamp", Query.Direction.ASCENDING)
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

    public ListenerRegistration listenToNotifications(EventListener<QuerySnapshot> listener) {
        if (selectedDeviceId == null) return null;

        return db.collection("devices")
                .document(selectedDeviceId)
                .collection("notifications")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .addSnapshotListener(listener);
    }

    public Task<Void> addNotification(String message, String type) {
        if (selectedDeviceId == null) return Tasks.forException(new Exception("No device selected"));

        Map<String, Object> notification = new HashMap<>();
        notification.put("message", message);
        notification.put("timestamp", System.currentTimeMillis());
        notification.put("type", type);

        return db.collection("devices")
                .document(selectedDeviceId)
                .collection("notifications")
                .document()
                .set(notification);
    }

    // --------------------
    // DEVICES MANAGEMENT
    // --------------------
    public Task<Void> claimDevice(String deviceCode) {
        return checkAdminTask().onSuccessTask(aVoid -> {
            String adminUid = getCurrentUid();
            if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

            DocumentReference deviceRef = db.collection("devices").document(deviceCode);

            return deviceRef.get().continueWithTask(task -> {
                if (!task.isSuccessful()) throw task.getException();

                DocumentSnapshot document = task.getResult();
                if (!document.exists()) throw new Exception("Invalid Device Token.");

                String status = document.getString("status");
                if ("CLAIMED".equals(status)) throw new Exception("Device already claimed.");

                Map<String, Object> updates = new HashMap<>();
                updates.put("ownerUid", adminUid);
                updates.put("status", "CLAIMED");

                return deviceRef.update(updates).continueWithTask(t2 -> {
                    if (!t2.isSuccessful()) throw t2.getException();

                    // Create Assignment for Admin (Deterministic ID for Security Rules)
                    Map<String, Object> assignment = new HashMap<>();
                    assignment.put("deviceId", deviceCode);
                    assignment.put("userUid", adminUid);
                    assignment.put("role", RoleConstants.ROLE_ADMIN);
                    assignment.put("assignedBy", adminUid);
                    assignment.put("assignedAt", System.currentTimeMillis());

                    String assignmentId = adminUid + "_" + deviceCode;
                    return db.collection("deviceAssignments").document(assignmentId).set(assignment);
                });
            });
        });
    }

    public Task<Void> unclaimDevice(String deviceId) {
        return checkAdminTask().onSuccessTask(aVoid -> {
            String adminUid = getCurrentUid();
            if (adminUid == null) return Tasks.forException(new Exception("Not logged in"));

            DocumentReference dRef = db.collection("devices").document(deviceId);

            Map<String, Object> updates = new HashMap<>();
            updates.put("ownerUid", null);
            updates.put("status", "UNCLAIMED");

            return dRef.update(updates).continueWithTask(task -> {
                // Delete assignments for this device
                return db.collection("deviceAssignments")
                        .whereEqualTo("deviceId", deviceId)
                        .get()
                        .continueWithTask(qTask -> {
                            if (!qTask.isSuccessful()) return Tasks.forResult(null);
                            List<Task<Void>> deleteTasks = new ArrayList<>();
                            for (DocumentSnapshot doc : qTask.getResult()) {
                                deleteTasks.add(doc.getReference().delete());
                            }
                            return Tasks.whenAll(deleteTasks);
                        });
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
            return db.collection("deviceAssignments").document(assignmentId).set(assignment);
        });
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

}
