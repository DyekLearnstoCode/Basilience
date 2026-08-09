const {setGlobalOptions} = require("firebase-functions");
const {onValueUpdated, onValueWritten} = require("firebase-functions/v2/database");
const {onDocumentCreated} = require("firebase-functions/v2/firestore");
const {onTaskDispatched} = require("firebase-functions/v2/tasks");
const {onSchedule} = require("firebase-functions/v2/scheduler");
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");
const {getFunctions} = require("firebase-admin/functions");

admin.initializeApp({
    databaseURL: "https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app"
});

setGlobalOptions({ maxInstances: 10, region: "asia-southeast1" });

const OFFLINE_CHECK_DELAY_SECONDS = 20;
const OFFLINE_TIMEOUT_MS = 15000;
const MANILA_TIME_ZONE = "Asia/Manila";

exports.changePersonnelPassword = onCall(async (request) => {
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "Authentication is required.");
    }

    const authTimeSeconds = Number(request.auth.token.auth_time);
    const authenticationAgeSeconds = Math.floor(Date.now() / 1000) - authTimeSeconds;
    if (!Number.isFinite(authTimeSeconds) || authenticationAgeSeconds < 0
            || authenticationAgeSeconds > 300) {
        throw new HttpsError("failed-precondition", "Recent Admin authentication is required.");
    }

    const personnelUid = typeof request.data?.personnelUid === "string"
        ? request.data.personnelUid.trim() : "";
    const newPassword = typeof request.data?.newPassword === "string"
        ? request.data.newPassword : "";

    if (!personnelUid || personnelUid === request.auth.uid) {
        throw new HttpsError("invalid-argument", "A valid Personnel account is required.");
    }
    if (newPassword.length < 6 || newPassword.length > 4096) {
        throw new HttpsError("invalid-argument", "The new password does not meet requirements.");
    }

    const db = admin.firestore();
    const [adminProfile, personnelProfile] = await Promise.all([
        db.collection("users").doc(request.auth.uid).get(),
        db.collection("users").doc(personnelUid).get()
    ]);

    if (!adminProfile.exists || String(adminProfile.data().role || "").toUpperCase() !== "ADMIN") {
        throw new HttpsError("permission-denied", "Only an authenticated Admin may perform this action.");
    }
    if (!personnelProfile.exists) {
        throw new HttpsError("not-found", "Personnel account not found.");
    }

    const personnel = personnelProfile.data();
    const personnelRole = String(personnel.role || "").toUpperCase();
    const authorizedRole = personnelRole === "FARMER" || personnelRole === "PERSONNEL";
    if (!authorizedRole || personnel.ownerAdminUid !== request.auth.uid) {
        throw new HttpsError("permission-denied", "This Personnel account is not linked to the current Admin.");
    }

    try {
        await admin.auth().updateUser(personnelUid, {password: newPassword});
        logger.info("Personnel password updated by authorized Admin", {
            adminUid: request.auth.uid,
            personnelUid
        });
        return {success: true};
    } catch (error) {
        logger.error("Unable to update Personnel password", {
            adminUid: request.auth.uid,
            personnelUid,
            code: error.code
        });
        throw new HttpsError("internal", "Unable to update the Personnel password.");
    }
});

async function getDeviceUserTokens(db, deviceId) {
    const tokensSet = new Set();
    const recipients = [];

    try {
        if (deviceId) {
            const deviceDoc = await db.collection("devices").doc(deviceId).get();
            let ownerUid = null;
            if (deviceDoc.exists) {
                const data = deviceDoc.data();
                ownerUid = data.ownerUid || data.ownerAdminUid;
                if (ownerUid) {
                    const ownerUserDoc = await db.collection("users").doc(ownerUid).get();
                    if (ownerUserDoc.exists) {
                        const owner = ownerUserDoc.data();
                        const hasToken = Boolean(owner.fcmToken);
                        recipients.push({uid: ownerUid, role: owner.role || "UNKNOWN", assignmentStatus: "owner", tokenCount: hasToken ? 1 : 0});
                        if (hasToken) tokensSet.add(owner.fcmToken);
                    }
                }
            }

            const assignmentsSnapshot = await db.collection("deviceAssignments")
                .where("deviceId", "==", deviceId)
                .get();

            for (const doc of assignmentsSnapshot.docs) {
                const userUid = doc.data().userUid;
                if (userUid && userUid !== ownerUid) {
                    const userDoc = await db.collection("users").doc(userUid).get();
                    if (userDoc.exists) {
                        const user = userDoc.data();
                        // An assignment only grants reminder/alert access while the
                        // personnel profile remains linked to this device's owner.
                        if ((user.role === "FARMER" || user.role === "PERSONNEL")
                                && user.ownerAdminUid === ownerUid && user.fcmToken) {
                            tokensSet.add(user.fcmToken);
                        }
                        recipients.push({
                            uid: userUid,
                            role: user.role || "UNKNOWN",
                            assignmentStatus: user.ownerAdminUid === ownerUid ? "assigned-linked" : "assignment-not-linked",
                            tokenCount: user.fcmToken ? 1 : 0
                        });
                    }
                }
            }
        }

        logger.info("Resolved device notification recipients", {deviceId, recipients});

        if (tokensSet.size === 0) {
            logger.warn(`No owner or assigned-user FCM tokens found for device ${deviceId}; push skipped.`);
        }
    } catch (e) {
        logger.error(`Error retrieving user tokens for device ${deviceId}:`, e);
    }

    return Array.from(tokensSet);
}

function dateKeyInManila(epochMs) {
    const parts = new Intl.DateTimeFormat("en-CA", {
        timeZone: MANILA_TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit"
    }).formatToParts(new Date(epochMs));
    const value = {};
    for (const part of parts) value[part.type] = part.value;
    return `${value.year}-${value.month}-${value.day}`;
}

function shiftDateKey(dateKey, days) {
    const date = new Date(`${dateKey}T00:00:00.000Z`);
    date.setUTCDate(date.getUTCDate() + days);
    return date.toISOString().slice(0, 10);
}

function harvestDateEpochMillis(value) {
    if (value && typeof value.toMillis === "function") {
        const epochMs = value.toMillis();
        return Number.isFinite(epochMs) ? {epochMs, valueType: "firestore_timestamp"} : null;
    }
    if (typeof value === "number" && Number.isFinite(value)) {
        return {epochMs: value, valueType: "epoch_milliseconds"};
    }
    return null;
}

async function createHarvestReminder(db, deviceId, cycleId, nextHarvestDate, reminderType) {
    const scheduledEpochMs = nextHarvestDate;
    const scheduledDateKey = dateKeyInManila(scheduledEpochMs);
    const notificationId = `harvest_${scheduledDateKey}_${reminderType}`;
    const isTomorrow = reminderType === "tomorrow";
    const title = isTomorrow ? "Harvest Scheduled Tomorrow" : "Harvest Ready Today";
    const message = isTomorrow
        ? "The next harvest for this device is scheduled for tomorrow."
        : "The scheduled harvest is available today.";
    const notificationRef = db.collection("devices").doc(deviceId)
        .collection("notifications").doc(notificationId);

    try {
        // create(), rather than set(), guarantees a repeated scheduler run cannot
        // duplicate either history or the corresponding FCM event.
        await notificationRef.create({
            title,
            message,
            type: "harvest",
            timestamp: Date.now(),
            isRead: false,
            eventId: notificationId,
            cycleId,
            nextHarvestDate: scheduledEpochMs,
            reminderType
        });
    } catch (error) {
        if (error && (error.code === 6 || error.code === "already-exists")) {
            logger.info("Harvest reminder already exists; skipping duplicate", {deviceId, notificationId});
            return;
        }
        throw error;
    }

    const userTokens = await getDeviceUserTokens(db, deviceId);
    if (userTokens.length === 0) return;
    try {
        const response = await admin.messaging().sendEachForMulticast({
            notification: {title, body: message},
            data: {title, body: message, type: "HARVEST_REMINDER", deviceId, notificationId},
            tokens: userTokens
        });
        logger.info("Harvest FCM send result", {
            deviceId, cycleId, notificationId, tokenCount: userTokens.length,
            successCount: response.successCount, failureCount: response.failureCount
        });
        response.responses.forEach((result, index) => {
            if (!result.success) {
                logger.error("Harvest FCM recipient failure", {
                    deviceId, notificationId, recipientIndex: index,
                    errorCode: result.error && result.error.code,
                    errorMessage: result.error && result.error.message
                });
            }
        });
    } catch (error) {
        logger.error("Harvest FCM multicast failed", {deviceId, cycleId, notificationId, error});
        throw error;
    }
}

// The function evaluates calendar dates in the app's Philippine operating timezone.
// Deterministic notification IDs make hourly runs harmless and allow a new
// nextHarvestDate to begin a new reminder cycle.
exports.evaluateHarvestReminders = onSchedule({
    schedule: "every 60 minutes",
    timeZone: MANILA_TIME_ZONE,
    region: "asia-southeast1"
}, async () => {
    const db = admin.firestore();
    const todayKey = dateKeyInManila(Date.now());
    const activeCycles = await db.collectionGroup("cycles").where("status", "==", "ACTIVE").get();
    logger.info("Harvest reminder active cycle enumeration", {
        activeCycleCount: activeCycles.size,
        todayManilaDate: todayKey
    });

    for (const cycleDoc of activeCycles.docs) {
        const cycle = cycleDoc.data();
        const deviceRef = cycleDoc.ref.parent.parent;
        const deviceId = deviceRef && deviceRef.id;
        const parsedHarvestDate = harvestDateEpochMillis(cycle.nextHarvestDate);
        if (!deviceId || !parsedHarvestDate) {
            logger.warn("Harvest reminder malformed cycle date", {
                deviceId: deviceId || null,
                cycleId: cycleDoc.id,
                nextHarvestDateType: cycle.nextHarvestDate === null ? "null" : typeof cycle.nextHarvestDate
            });
            continue;
        }
        const scheduledDateKey = dateKeyInManila(parsedHarvestDate.epochMs);
        const qualification = todayKey === scheduledDateKey ? "today"
            : todayKey === shiftDateKey(scheduledDateKey, -1) ? "tomorrow" : "neither";
        logger.info("Harvest reminder date evaluation", {
            deviceId,
            cycleId: cycleDoc.id,
            nextHarvestDate: parsedHarvestDate.epochMs,
            nextHarvestDateType: parsedHarvestDate.valueType,
            manilaDate: scheduledDateKey,
            todayManilaDate: todayKey,
            qualification
        });

        try {
            if (todayKey === scheduledDateKey) {
                await createHarvestReminder(db, deviceId, cycleDoc.id, parsedHarvestDate.epochMs, "today");
            } else if (todayKey === shiftDateKey(scheduledDateKey, -1)) {
                await createHarvestReminder(db, deviceId, cycleDoc.id, parsedHarvestDate.epochMs, "tomorrow");
            }
        } catch (error) {
            logger.error("Harvest reminder evaluation failed for cycle", {
                deviceId: deviceRef.id, cycleId: cycleDoc.id, error
            });
        }
    }
});

exports.onAlertUpdated = onValueUpdated({
    ref: "/devices/{deviceId}/alerts",
    instance: "basilience-database-default-rtdb"
}, async (event) => {
    const alertsBefore = event.data.before.val() || {};
    const alertsAfter = event.data.after.val() || {};
    const deviceId = event.params.deviceId;

    const db = admin.firestore();
    const alertKeys = [
        { key: "lowWater", title: "Low Water Level", message: "The reservoir water level is below the configured refill threshold.", type: "parameter" },
        { key: "ecLow", title: "Low EC Level", message: "EC level is below the safe range.", type: "parameter" },
        { key: "phOutOfRange", title: "pH Out of Range", message: "pH level is outside the safe range.", type: "parameter" },
        { key: "waterTempOutOfRange", title: "Water Temperature Alert", message: "Water temperature is outside the safe range.", type: "parameter" },
        { key: "highTemperature", title: "High Air Temperature", message: "Air temperature is above safe limits.", type: "parameter" },
        { key: "sensorFault", title: "Sensor Fault", message: "One or more sensors are not responding.", type: "hardware" }
    ];

    for (const alert of alertKeys) {
        const wasActive = alertsBefore[alert.key] === true;
        const isActive = alertsAfter[alert.key] === true;

        if (isActive && !wasActive) {
            const notificationId = `${event.id}_${alert.key}`;
            const notificationRef = db.collection("devices")
                .doc(deviceId)
                .collection("notifications");

            await notificationRef.doc(notificationId).set({
                title: alert.title,
                message: alert.message,
                type: alert.type,
                timestamp: Date.now(),
                isRead: false,
                eventId: notificationId
            });
            
            logger.info(`Alert generated and saved to Firestore for ${deviceId}: ${alert.key}`);

            const userTokens = await getDeviceUserTokens(db, deviceId);

            if (userTokens.length > 0) {
                const message = {
                    notification: {
                        title: alert.title,
                        body: alert.message
                    },
                    data: {
                        title: alert.title,
                        body: alert.message,
                        type: alert.key,
                        deviceId: deviceId || "",
                        notificationId
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

async function handleDeviceOffline(eventId, deviceId, wasOnline, isOnline) {
    logger.info(`handleDeviceOffline for ${deviceId}: wasOnline=${wasOnline}, isOnline=${isOnline}`);

    if (wasOnline !== false && isOnline === false) {
        const notificationId = `${eventId}_offline`;
        const db = admin.firestore();
        logger.info(`Device went offline: ${deviceId}`);

        const notificationRef = db.collection("devices")
            .doc(deviceId)
            .collection("notifications");

        await notificationRef.doc(notificationId).set({
            title: "Device Offline",
            message: `Connection to device ${deviceId} was lost.`,
            type: "hardware",
            timestamp: Date.now(),
            isRead: false,
            eventId: notificationId
        });

        const userTokens = await getDeviceUserTokens(db, deviceId);
        logger.info(`Found ${userTokens.length} user tokens for FCM push notification.`);

        if (userTokens.length > 0) {
            const message = {
                notification: {
                    title: `Device Offline`,
                    body: `Connection to device ${deviceId} was lost.`
                },
                data: {
                    title: `Device Offline`,
                    body: `Connection to device ${deviceId} was lost.`,
                    type: "OFFLINE_ALERT",
                    deviceId: deviceId || "",
                    notificationId
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
                const response = await admin.messaging().sendEachForMulticast(message);
                logger.info(`Offline notification sent successfully to ${response.successCount} tokens.`);
            } catch (error) {
                logger.error("Error sending offline FCM messages:", error);
            }
        } else {
            logger.warn(`No FCM tokens found for device ${deviceId}.`);
        }
    }
}

exports.onDeviceOffline = onValueUpdated({
    ref: "/devices/{deviceId}/status/online",
    instance: "basilience-database-default-rtdb"
}, async (event) => {
    await handleDeviceOffline(event.id, event.params.deviceId, event.data.before.val(), event.data.after.val());
});

exports.onHarvestCreated = onDocumentCreated("devices/{deviceId}/cycles/{cycleId}/harvestLogs/{harvestId}", async (event) => {
    const snapshot = event.data;
    if (!snapshot) {
        return;
    }
    
    const harvestData = snapshot.data();
    const deviceId = event.params.deviceId;
    const cycleId = event.params.cycleId;
    const harvestId = event.params.harvestId;
    const db = admin.firestore();
    const notificationId = `harvest_recorded_${harvestId}`;
    const title = "Harvest Recorded";
    const message = `New harvest of ${harvestData.weight}g recorded for device ${deviceId}.`;

    logger.info(`New harvest recorded for device ${deviceId}: ${harvestData.weight}g`);

    const notificationRef = db.collection("devices").doc(deviceId)
        .collection("notifications").doc(notificationId);
    try {
        await notificationRef.create({
            title,
            message,
            type: "harvest",
            timestamp: Date.now(),
            isRead: false,
            eventId: notificationId,
            cycleId,
            harvestId
        });
    } catch (error) {
        if (error && (error.code === 6 || error.code === "already-exists")) {
            logger.info("Harvest recorded notification already exists; skipping duplicate", {
                deviceId, cycleId, harvestId, notificationId
            });
            return;
        }
        throw error;
    }

    const userTokens = await getDeviceUserTokens(db, deviceId);
    if (userTokens.length > 0) {
        const fcmMessage = {
            notification: {title, body: message},
            data: {
                title,
                body: message,
                type: "HARVEST_RECORDED",
                deviceId,
                notificationId,
                cycleId,
                harvestId
            },
            tokens: userTokens
        };

        try {
            const response = await admin.messaging().sendEachForMulticast(fcmMessage);
            logger.info("Harvest recorded FCM send result", {
                deviceId, cycleId, harvestId, notificationId,
                successCount: response.successCount,
                failureCount: response.failureCount
            });
        } catch (error) {
            logger.error("Harvest recorded FCM send failed", {
                deviceId, cycleId, harvestId, notificationId, error
            });
            throw error;
        }
    }
});

// Canonical parameter/sensor history collection: devices/{deviceId}/parameterLogs
// Schema (must match Android readers in Database_Helper.getParameterLogs /
// SystemReportsFragment / FoggingReportsFragment.calculatePrediction):
//   timestamp: number (epoch ms, server time)
//   ph: number, ec: number
//   air_temp: number, humidity: number, water_temp: number, water_level: number
// Source RTDB fields (written by FirebaseManager::writeSensors) are camelCase
// and use device-uptime millis() for their own timestamp, so we re-stamp with
// Date.now() here rather than trusting the RTDB value.
const SENSOR_LOG_INTERVAL_MS = 5 * 60 * 1000;

exports.logSensorData = onValueWritten({
    ref: "/devices/{deviceId}/sensors",
    instance: "basilience-database-default-rtdb"
}, async (event) => {
    const data = event.data.after.val();
    const deviceId = event.params.deviceId;

    if (!data) return;

    const now = Date.now();
    const logEntry = {timestamp: now};
    const addValid = (field, value, minimum, maximum, invalidSentinel) => {
        if (typeof value !== "number" || !Number.isFinite(value)) return;
        if (invalidSentinel !== undefined && Math.abs(value - invalidSentinel) < 0.0001) return;
        if (value < minimum || value > maximum) return;
        logEntry[field] = value;
    };
    addValid("ph", data.ph, 0, 14);
    addValid("ec", data.ec, 0, Number.MAX_VALUE);
    addValid("air_temp", data.airTemperature, -40, 80);
    addValid("humidity", data.humidity, 0, 100);
    addValid("water_temp", data.waterTemperature, -55, 125, -127);
    addValid("water_level", data.waterLevel, 0, 100);
    if (Object.keys(logEntry).length === 1) return;

    const db = admin.firestore();
    const deviceRef = db.collection("devices").doc(deviceId);
    const metaRef = deviceRef.collection("meta").doc("sensorLogMeta");
    const logRef = deviceRef.collection("parameterLogs").doc();
    try {
        await db.runTransaction(async (transaction) => {
            const metaSnapshot = await transaction.get(metaRef);
            const lastLoggedAt = metaSnapshot.exists ? Number(metaSnapshot.get("lastLoggedAt")) : 0;
            if (Number.isFinite(lastLoggedAt) && now - lastLoggedAt < SENSOR_LOG_INTERVAL_MS) return;
            transaction.set(logRef, logEntry);
            transaction.set(metaRef, {lastLoggedAt: now}, {merge: true});
        });
    } catch (error) {
        logger.error(`Error logging sensor data for ${deviceId}:`, error);
    }
});

// Canonical fogging history collection: devices/{deviceId}/foggingLogs
// Schema (must match Android readers in FoggingReportsFragment /
// models.FoggingEvent / FoggingReportProcessor):
//   event: "ON" | "OFF", timestamp: number (epoch ms, server time)
//   isManual: boolean, source: string|null, strategy: string|null, reason: string|null
// Firmware (FirebaseManager::writeActuators) never writes /actuators/FOGGER —
// it writes the confirmed relay state to /actuatorStatus/fogger/running (bool),
// alongside /actuatorStatus/fogger/source ("automatic" | "manual" | "android")
// and optional /actuatorStatus/fogger/strategy ("startup" | "normal" | "hot" | "cold").
// That is the only place an ON/OFF transition can be observed, so the trigger
// watches the whole actuatorStatus/fogger node (not just /running) to read
// source/reason atomically with the transition.
exports.logFoggerActivity = onValueWritten({
    ref: "/devices/{deviceId}/actuatorStatus/fogger",
    instance: "basilience-database-default-rtdb"
}, async (event) => {
    const before = event.data.before.val() || {};
    const after = event.data.after.val() || {};
    const deviceId = event.params.deviceId;

    const wasRunning = before.running === true;
    const isRunning = after.running === true;

    if (wasRunning === isRunning) return;

    const db = admin.firestore();
    const eventType = isRunning ? "ON" : "OFF";
    const source = after.source || null;
    const isManual = source === "manual" || source === "android";
    const strategy = source === "automatic" ? (after.strategy || null) : null;

    try {
        await db.collection("devices")
            .doc(deviceId)
            .collection("foggingLogs")
            .add({
                event: eventType,
                timestamp: Date.now(),
                isManual: isManual,
                source: source,
                strategy: strategy,
                reason: after.reason || null
            });
    } catch (error) {
        logger.error(`Error logging fogger activity for ${deviceId}:`, error);
    }

    logger.info(`logFoggerActivity: Fogger turned ${eventType} for device ${deviceId} (source=${source})`);
});

exports.trackDeviceHeartbeat = onValueWritten({
    ref: "/devices/{deviceId}/sensors",
    instance: "basilience-database-default-rtdb"
}, async (event) => {
    // Only treat existing data as a heartbeat. If node is deleted, ignore.
    if (!event.data.after.exists()) {
        logger.info("trackDeviceHeartbeat: sensors node missing, heartbeat ignored", {
            deviceId: event.params.deviceId
        });
        return;
    }

    const deviceId = event.params.deviceId;
    const db = admin.database();
    const heartbeatAt = Date.now();

    try {
        const updates = {};
        // Use the Cloud Function server clock, not firmware millis(), for presence.
        updates[`/devices/${deviceId}/status/lastServerSeen`] = heartbeatAt;
        updates[`/devices/${deviceId}/status/online`] = true;

        await db.ref().update(updates);

        const queue = getFunctions().taskQueue("locations/asia-southeast1/functions/delayedOfflineCheck");
        await queue.enqueue({
            deviceId,
            expectedLastServerSeen: heartbeatAt
        }, {
            scheduleDelaySeconds: OFFLINE_CHECK_DELAY_SECONDS,
            dispatchDeadlineSeconds: 60
        });

        logger.info("trackDeviceHeartbeat: heartbeat recorded and delayed offline check scheduled", {
            deviceId,
            heartbeatAt,
            offlineCheckDelaySeconds: OFFLINE_CHECK_DELAY_SECONDS
        });
    } catch (error) {
        logger.error(`Error updating heartbeat for ${deviceId}:`, error);
    }
});

exports.delayedOfflineCheck = onTaskDispatched({
    retryConfig: {
        maxAttempts: 3,
        minBackoffSeconds: 5,
        maxBackoffSeconds: 30
    },
    rateLimits: {
        maxConcurrentDispatches: 50
    }
}, async (request) => {
    const db = admin.database();
    const deviceId = request.data && request.data.deviceId;
    const expectedLastServerSeen = Number(request.data && request.data.expectedLastServerSeen);

    if (!deviceId || !Number.isFinite(expectedLastServerSeen)) {
        logger.warn("delayedOfflineCheck: invalid task payload", {
            deviceId,
            expectedLastServerSeen: request.data && request.data.expectedLastServerSeen
        });
        return;
    }

    const startedAt = Date.now();
    const statusRef = db.ref(`/devices/${deviceId}/status`);

    try {
        const initialSnapshot = await statusRef.once("value");
        if (!initialSnapshot.exists()) {
            logger.info("delayedOfflineCheck: status node missing, exiting", {
                deviceId,
                expectedLastServerSeen
            });
            return;
        }

        let transactionAttempts = 0;
        const result = await statusRef.transaction((currentStatus) => {
            transactionAttempts += 1;
            // The Admin SDK may invoke the callback once with an empty local cache.
            // Returning an object allows it to retry with server state instead of aborting.
            if (currentStatus === null) {
                return {};
            }

            const currentLastServerSeen = Number(currentStatus.lastServerSeen);
            const ageMs = Date.now() - currentLastServerSeen;

            if (currentStatus.online === true
                    && Number.isFinite(currentLastServerSeen)
                    && currentLastServerSeen === expectedLastServerSeen
                    && ageMs >= OFFLINE_TIMEOUT_MS) {
                currentStatus.online = false;
                return currentStatus;
            }

            return undefined;
        });

        const resultingStatus = result.snapshot.val() || {};
        const resultingLastServerSeen = Number(resultingStatus.lastServerSeen);
        logger.info("delayedOfflineCheck: transaction result", {
            deviceId,
            expectedLastServerSeen,
            committed: result.committed,
            transactionAttempts,
            resultingOnline: resultingStatus.online,
            resultingLastServerSeen: resultingStatus.lastServerSeen,
            resultingAgeMs: Number.isFinite(resultingLastServerSeen) ? Date.now() - resultingLastServerSeen : null,
            elapsedSinceExpectedMs: startedAt - expectedLastServerSeen
        });
    } catch (error) {
        logger.error("delayedOfflineCheck: failed", {
            deviceId,
            expectedLastServerSeen,
            error
        });
        throw error;
    }
});

exports.onStatusUpdated = onValueUpdated({
    ref: "/devices/{deviceId}/status",
    instance: "basilience-database-default-rtdb"
}, async (event) => {
    const statusBefore = event.data.before.val() || {};
    const statusAfter = event.data.after.val() || {};
    const deviceId = event.params.deviceId;

    const db = admin.firestore();
    const statusKeys = [
        { key: "safetyLock", message: "The system has stopped unsafe operations. Resolve the fault before resetting Safety Lock.", type: "hardware", title: "Safety Lock Activated" }
    ];

    for (const status of statusKeys) {
        const wasActive = statusBefore[status.key] === true;
        const isActive = statusAfter[status.key] === true;

        if (isActive && !wasActive) {
            const notificationId = `${event.id}_${status.key}`;
            const notificationRef = db.collection("devices")
                .doc(deviceId)
                .collection("notifications");

            await notificationRef.doc(notificationId).set({
                title: status.title,
                message: status.message,
                type: status.type,
                timestamp: Date.now(),
                isRead: false,
                eventId: notificationId
            });
            
            logger.info(`Status alert generated and saved to Firestore for ${deviceId}: ${status.key}`);

            const userTokens = await getDeviceUserTokens(db, deviceId);

            if (userTokens.length > 0) {
                const message = {
                    notification: {
                        title: status.title,
                        body: status.message
                    },
                    data: {
                        title: status.title,
                        body: status.message,
                        type: status.key,
                        deviceId: deviceId || "",
                        notificationId
                    },
                    tokens: userTokens
                };

                try {
                    const response = await admin.messaging().sendEachForMulticast(message);
                    logger.info(`Status FCM: ${response.successCount} messages were sent successfully`);
                } catch (error) {
                    logger.error("Error sending Status FCM messages:", error);
                }
            }
        }
    }
});
