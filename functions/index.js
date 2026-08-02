const {setGlobalOptions} = require("firebase-functions");
const {onValueUpdated} = require("firebase-functions/v2/database");
const {onDocumentCreated} = require("firebase-functions/v2/firestore");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");

admin.initializeApp();

setGlobalOptions({ maxInstances: 10, region: "asia-southeast1" });

exports.onAlertUpdated = onValueUpdated({
    ref: "/devices/{deviceId}/alerts",
    instance: "basilience-database-default-rtdb"
}, async (event) => {
    const alertsBefore = event.data.before.val() || {};
    const alertsAfter = event.data.after.val() || {};
    const deviceId = event.params.deviceId;

    const db = admin.firestore();
    const alertKeys = [
        { key: "lowWater", message: "Water level is critically low.", type: "parameter" },
        { key: "ecLow", message: "EC level is below the safe range.", type: "parameter" },
        { key: "phOutOfRange", message: "pH level is outside the safe range.", type: "parameter" },
        { key: "waterTempOutOfRange", message: "Water temperature is outside the safe range.", type: "parameter" },
        { key: "highTemperature", message: "Air temperature is above safe limits.", type: "parameter" },
        { key: "sensorFault", message: "One or more sensors are not responding.", type: "hardware" }
    ];

    for (const alert of alertKeys) {
        const wasActive = alertsBefore[alert.key] === true;
        const isActive = alertsAfter[alert.key] === true;

        if (isActive && !wasActive) {
            // Write a notification log document to Firestore under devices/{deviceId}/notifications
            const notificationRef = db.collection("devices")
                .doc(deviceId)
                .collection("notifications");

            await notificationRef.add({
                message: alert.message,
                type: alert.type,
                timestamp: Date.now()
            });
            
            logger.info(`Alert generated and saved to Firestore for ${deviceId}: ${alert.key}`);

            // Fetch users assigned to this device
            const assignmentsSnapshot = await db.collection("deviceAssignments")
                .where("deviceId", "==", deviceId)
                .get();
                
            const userTokens = [];
            for (const doc of assignmentsSnapshot.docs) {
                const userUid = doc.data().userUid;
                if (userUid) {
                    const userDoc = await db.collection("users").doc(userUid).get();
                    if (userDoc.exists) {
                        const fcmToken = userDoc.data().fcmToken;
                        if (fcmToken) {
                            userTokens.push(fcmToken);
                        }
                    }
                }
            }

            if (userTokens.length > 0) {
                const message = {
                    notification: {
                        title: `Alert: Device ${deviceId}`,
                        body: alert.message
                    },
                    tokens: userTokens
                };

                try {
                    const response = await admin.messaging().sendEachForMulticast(message);
                    logger.info(`${response.successCount} messages were sent successfully`);
                } catch (error) {
                    logger.error("Error sending FCM messages:", error);
                }
            }
        }
    }
});

exports.onDeviceOffline = onValueUpdated({
    ref: "/devices/{deviceId}/status/online",
    instance: "basilience-database-default-rtdb"
}, async (event) => {
    const wasOnline = event.data.before.val();
    const isOnline = event.data.after.val();
    const deviceId = event.params.deviceId;

    if (wasOnline === true && isOnline === false) {
        const db = admin.firestore();
        logger.info(`Device went offline: ${deviceId}`);

        // Write a notification log document to Firestore
        const notificationRef = db.collection("devices")
            .doc(deviceId)
            .collection("notifications");

        await notificationRef.add({
            message: `Connection to device ${deviceId} was lost.`,
            type: "hardware",
            timestamp: Date.now()
        });

        // Fetch users assigned to this device
        const assignmentsSnapshot = await db.collection("deviceAssignments")
            .where("deviceId", "==", deviceId)
            .get();
            
        const userTokens = [];
        for (const doc of assignmentsSnapshot.docs) {
            const userUid = doc.data().userUid;
            if (userUid) {
                const userDoc = await db.collection("users").doc(userUid).get();
                if (userDoc.exists) {
                    const fcmToken = userDoc.data().fcmToken;
                    if (fcmToken) {
                        userTokens.push(fcmToken);
                    }
                }
            }
        }

        if (userTokens.length > 0) {
            const message = {
                notification: {
                    title: `Device Offline`,
                    body: `Connection to device ${deviceId} was lost.`
                },
                android: {
                    priority: "high",
                    notification: {
                        sound: "default",
                        channelId: "alerts"
                    }
                },
                tokens: userTokens
            };

            try {
                await admin.messaging().sendEachForMulticast(message);
                logger.info(`Offline notification sent to ${userTokens.length} users.`);
            } catch (error) {
                logger.error("Error sending offline FCM messages:", error);
            }
        }
    }
});

exports.onHarvestCreated = onDocumentCreated("devices/{deviceId}/cycles/{cycleId}/harvestLogs/{harvestId}", async (event) => {
    const snapshot = event.data;
    if (!snapshot) {
        return;
    }
    
    const harvestData = snapshot.data();
    const deviceId = event.params.deviceId;
    const db = admin.firestore();
    
    logger.info(`New harvest recorded for device ${deviceId}: ${harvestData.weight}g`);
    
    // Notify users assigned to this device who have the "ADMIN" role
    const assignmentsSnapshot = await db.collection("deviceAssignments")
        .where("deviceId", "==", deviceId)
        .get();
        
    const userTokens = [];
    for (const doc of assignmentsSnapshot.docs) {
        const userUid = doc.data().userUid;
        if (userUid) {
            const userDoc = await db.collection("users").doc(userUid).get();
            if (userDoc.exists) {
                // Ensure they are an admin
                if (userDoc.data().role === "ADMIN") {
                    const fcmToken = userDoc.data().fcmToken;
                    if (fcmToken) {
                        userTokens.push(fcmToken);
                    }
                }
            }
        }
    }

    if (userTokens.length > 0) {
        const message = {
            notification: {
                title: `New Harvest Log`,
                body: `${harvestData.createdBy || "A user"} recorded a harvest of ${harvestData.weight}g.`
            },
            tokens: userTokens
        };

        try {
            await admin.messaging().sendEachForMulticast(message);
        } catch (error) {
            logger.error("Error sending harvest FCM messages:", error);
        }
    }
});

// =============================================================================
// SENSOR LOGGING: RTDB → Firestore parameterLogs
//
// Flow:
//   ESP32 overwrites /devices/{deviceId}/sensors every 10 seconds (UPLOAD_INTERVAL).
//   This function fires on every write but only commits a new Firestore document
//   once every LOG_INTERVAL_MS (5 minutes) to keep document counts manageable.
//
// Field mapping (firmware name → Android app name):
//   airTemperature   → air_temp
//   humidity         → humidity
//   waterTemperature → water_temp
//   waterLevel       → water_level
//   ph               → ph
//   ec               → ec
//
// Throttle mechanism:
//   A lightweight metadata document at devices/{deviceId}/meta/sensorLogMeta
//   stores the `lastLoggedAt` timestamp. The function skips writing if fewer
//   than LOG_INTERVAL_MS milliseconds have elapsed since the last log entry.
//   Using a dedicated metadata doc avoids a full collection query on every trigger.
// =============================================================================

const LOG_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

exports.logSensorData = onValueUpdated({
    ref: "/devices/{deviceId}/sensors",
    instance: "basilience-database-default-rtdb"
}, async (event) => {
    const deviceId = event.params.deviceId;
    const sensors = event.data.after.val();

    // Guard: skip if the RTDB node was cleared or is empty
    if (!sensors || typeof sensors !== "object") {
        logger.warn(`logSensorData: No sensor payload for device ${deviceId}. Skipping.`);
        return;
    }

    const db = admin.firestore();

    // -------------------------------------------------------------------------
    // Throttle check
    // Read the metadata document to determine when we last wrote a log entry.
    // -------------------------------------------------------------------------
    const metaRef = db.collection("devices").doc(deviceId)
        .collection("meta").doc("sensorLogMeta");

    const metaSnap = await metaRef.get();
    const now = Date.now();

    if (metaSnap.exists) {
        const lastLoggedAt = metaSnap.data().lastLoggedAt || 0;
        if (now - lastLoggedAt < LOG_INTERVAL_MS) {
            // Not enough time has passed — skip silently
            return;
        }
    }

    // -------------------------------------------------------------------------
    // Field mapping: firmware field names → Android app field names
    // Only include values that are present and are finite numbers.
    // NaN and Infinity are not valid JSON and would corrupt the document.
    // -------------------------------------------------------------------------
    const logEntry = { timestamp: now };

    const fieldMap = {
        airTemperature:   "air_temp",
        humidity:         "humidity",
        waterTemperature: "water_temp",
        waterLevel:       "water_level",
        ph:               "ph",
        ec:               "ec"
    };

    for (const [firmwareKey, appKey] of Object.entries(fieldMap)) {
        const value = sensors[firmwareKey];
        if (value !== null && value !== undefined && typeof value === "number" && isFinite(value)) {
            logEntry[appKey] = value;
        }
    }

    // Guard: if no valid sensor values were mapped, don't write a useless doc
    if (Object.keys(logEntry).length <= 1) {
        logger.warn(`logSensorData: No valid numeric sensor fields for device ${deviceId}. Skipping log entry.`);
        return;
    }

    // -------------------------------------------------------------------------
    // Atomic batch write: log entry + metadata update commit together.
    // This ensures the throttle timestamp is never advanced without the
    // corresponding parameterLogs document being committed.
    // -------------------------------------------------------------------------
    const batch = db.batch();

    const logRef = db.collection("devices").doc(deviceId)
        .collection("parameterLogs").doc(); // Firestore auto-ID

    batch.set(logRef, logEntry);
    batch.set(metaRef, { lastLoggedAt: now }, { merge: true });

    await batch.commit();

    logger.info(`logSensorData: Logged sensor snapshot for device ${deviceId} at ${new Date(now).toISOString()}`);
});

// =============================================================================
// FOGGER LOGGING: RTDB → Firestore foggingLogs
//
// Flow:
//   Listens to /devices/{deviceId}/actuatorStatus/fogger
//   Checks the `running` boolean to detect ON/OFF transitions.
//   Logs discrete events (ON or OFF) to Firestore for historical charting.
// =============================================================================

exports.logFoggerActivity = onValueUpdated({
    ref: "/devices/{deviceId}/actuatorStatus/fogger",
    instance: "basilience-database-default-rtdb"
}, async (event) => {
    const before = event.data.before.val() || {};
    const after = event.data.after.val() || {};
    const deviceId = event.params.deviceId;

    const wasRunning = before.running === true;
    const isRunning = after.running === true;

    // Only log when there's an actual state transition
    if (wasRunning === isRunning) {
        return;
    }

    const eventType = isRunning ? "ON" : "OFF";
    const db = admin.firestore();
    const logRef = db.collection("devices").doc(deviceId).collection("foggingLogs").doc();

    await logRef.set({
        event: eventType,
        timestamp: Date.now(),
        source: after.source || "UNKNOWN",
        reason: after.reason || null
    });

    logger.info(`logFoggerActivity: Fogger turned ${eventType} for device ${deviceId}`);
});
