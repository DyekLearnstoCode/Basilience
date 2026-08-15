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

// Firmware publishes sensors every 10 seconds and now spends up to 20 seconds
// restoring Wi-Fi. Forty seconds covers three missed heartbeats plus ordinary
// write/jitter latency; the task runs after that threshold has safely elapsed.
const OFFLINE_CHECK_DELAY_SECONDS = 45;
const OFFLINE_TIMEOUT_MS = 40000;
const CONNECTIVITY_FCM_TTL_MS = 3 * 60 * 1000;
// Manual setup commonly requires switching the phone to the ESP32 AP, entering
// credentials, and waiting for a restart. Ten minutes avoids false alarms during
// that workflow while still allowing a forgotten/dead setup session to expire.
const PROVISIONING_GRACE_MS = 10 * 60 * 1000;
const MANILA_TIME_ZONE = "Asia/Manila";

function isInvalidFcmTokenError(error) {
    const code = error && error.code;
    return code === "messaging/registration-token-not-registered"
        || code === "messaging/invalid-registration-token";
}

async function cleanupInvalidFcmTokens(db, tokens, response, context) {
    const invalidTokens = new Set();
    response.responses.forEach((result, index) => {
        if (!result.success && isInvalidFcmTokenError(result.error) && tokens[index]) {
            invalidTokens.add(tokens[index]);
        }
    });

    for (const token of invalidTokens) {
        const users = await db.collection("users").where("fcmToken", "==", token).get();
        for (const userDoc of users.docs) {
            await db.runTransaction(async transaction => {
                const current = await transaction.get(userDoc.ref);
                if (current.exists && current.get("fcmToken") === token) {
                    transaction.update(userDoc.ref, {
                        fcmToken: admin.firestore.FieldValue.delete()
                    });
                }
            });
        }
        logger.info("Removed invalid FCM token", {context, matchingUsers: users.size});
    }
}

async function sendMulticastWithCleanup(db, message, context) {
    const response = await admin.messaging().sendEachForMulticast(message);
    await cleanupInvalidFcmTokens(db, message.tokens || [], response, context);
    return response;
}

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
        const response = await sendMulticastWithCleanup(db, {
            notification: {title, body: message},
            data: {title, body: message, type: "HARVEST_REMINDER", deviceId, notificationId},
            tokens: userTokens
        }, "harvest-reminder");
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
        { key: "phLow", title: "pH Low", message: "pH level is below the configured safe range.", type: "parameter" },
        { key: "phHigh", title: "pH High", message: "pH level is above the configured safe range.", type: "parameter" },
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

            const document = notificationRef.doc(notificationId);
            try {
                await document.create({
                title: alert.title,
                message: alert.message,
                type: alert.type,
                timestamp: Date.now(),
                isRead: false,
                eventId: notificationId
                });
            } catch (error) {
                if (error && (error.code === 6 || error.code === "already-exists")) {
                    logger.info("Alert event already handled", {deviceId, notificationId});
                    continue;
                }
                throw error;
            }
            
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
                    const response = await sendMulticastWithCleanup(db, message, "parameter-alert");
                    logger.info(`${response.successCount} messages were sent successfully`);
                } catch (error) {
                    await document.delete();
                    throw error;
                }
            }
        }
    }
});

async function handleDeviceConnectivityTransition(eventId, deviceId, wasOnline, isOnline) {
    const wentOffline = wasOnline === true && isOnline === false;
    const cameOnline = wasOnline === false && isOnline === true;
    if (!wentOffline && !cameOnline) return;

    logger.info(`[PRESENCE] ${deviceId} ${wentOffline ? "ONLINE -> OFFLINE" : "OFFLINE -> ONLINE"}`);

    const suffix = wentOffline ? "unreachable" : "online";
    const notificationId = `${eventId}_${suffix}`;
    const title = wentOffline ? "Basilience Device Unreachable" : "Basilience Device Back Online";
    const body = wentOffline
        ? "Basilience can no longer communicate with the device. Check the device power or network connection. Local automation may still be running if the device has power."
        : `${deviceId} is back online and communicating normally.`;
    const notificationType = wentOffline ? "OFFLINE_ALERT" : "ONLINE_RECOVERY";
    const db = admin.firestore();
    const realtimeDb = admin.database();
    const statusSnapshot = await realtimeDb.ref(`/devices/${deviceId}/status`).once("value");
    const currentStatus = statusSnapshot.val() || {};
    const generatedAt = Date.now();
    const lastServerSeen = Number(currentStatus.lastServerSeen);
    const presenceState = wentOffline ? "OFFLINE" : "ONLINE";
    const historyType = wentOffline ? "connectivity_offline" : "connectivity_recovery";
    const document = db.collection("devices").doc(deviceId)
        .collection("notifications").doc(notificationId);

    // The RTDB event id is stable across Cloud Function retries. Creating this
    // deterministic document is the idempotency gate for both history and FCM.
    try {
        await document.create({
            title,
            message: body,
            type: historyType,
            subtype: historyType,
            timestamp: generatedAt,
            generatedAt,
            lastServerSeen: Number.isFinite(lastServerSeen) ? lastServerSeen : null,
            presenceState,
            isRead: false,
            eventId: notificationId
        });
        if (cameOnline) {
            logger.info("[NOTIFICATION] Device recovery event created", {deviceId, notificationId});
        }
    } catch (error) {
        if (error && (error.code === 6 || error.code === "already-exists")) {
            logger.info("Connectivity event already handled", {deviceId, notificationId});
            return;
        }
        throw error;
    }

    const userTokens = await getDeviceUserTokens(db, deviceId);
    if (userTokens.length === 0) {
        logger.warn(`No FCM tokens found for device ${deviceId}.`);
        return;
    }

    const message = {
        data: {
            title,
            body,
            type: notificationType,
            deviceId: deviceId || "",
            notificationId,
            eventId: notificationId,
            generatedAt: String(generatedAt),
            lastServerSeen: Number.isFinite(lastServerSeen) ? String(lastServerSeen) : "",
            presenceState
        },
        android: {
            priority: "high",
            ttl: CONNECTIVITY_FCM_TTL_MS,
            collapseKey: `device_connectivity_${deviceId}`
        },
        tokens: userTokens
    };

    try {
        const response = await sendMulticastWithCleanup(db, message, "device-connectivity");
        logger.info("Connectivity notification sent", {
            deviceId,
            notificationType,
            successCount: response.successCount
        });
    } catch (error) {
        // A transport-level failure occurred before multicast returned a result.
        // Remove the idempotency gate so the platform retry can safely try again.
        await document.delete();
        throw error;
    }
}

exports.onDeviceOffline = onValueUpdated({
    ref: "/devices/{deviceId}/status/online",
    instance: "basilience-database-default-rtdb"
}, async (event) => {
    await handleDeviceConnectivityTransition(
        event.id,
        event.params.deviceId,
        event.data.before.val(),
        event.data.after.val());
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
            const response = await sendMulticastWithCleanup(db, fcmMessage, "harvest-recorded");
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

        await enqueueOfflineCheck(deviceId, heartbeatAt, OFFLINE_CHECK_DELAY_SECONDS);

        logger.info("trackDeviceHeartbeat: heartbeat recorded and delayed offline check scheduled", {
            deviceId,
            heartbeatAt,
            offlineCheckDelaySeconds: OFFLINE_CHECK_DELAY_SECONDS
        });
    } catch (error) {
        logger.error(`Error updating heartbeat for ${deviceId}:`, error);
    }
});

async function enqueueOfflineCheck(deviceId, expectedLastServerSeen, delaySeconds) {
    const queue = getFunctions().taskQueue(
        "locations/asia-southeast1/functions/delayedOfflineCheck");
    await queue.enqueue({deviceId, expectedLastServerSeen}, {
        scheduleDelaySeconds: Math.max(1, Math.ceil(delaySeconds)),
        dispatchDeadlineSeconds: 60
    });
}

exports.trackProvisioningState = onValueUpdated({
    ref: "/devices/{deviceId}/status/provisioning",
    instance: "basilience-database-default-rtdb"
}, async event => {
    const before = event.data.before.val() === true;
    const after = event.data.after.val() === true;
    if (before === after) return;

    const deviceId = event.params.deviceId;
    const statusRef = admin.database().ref(`/devices/${deviceId}/status`);
    if (after) {
        await statusRef.update({provisioningStartedAt: Date.now()});
        logger.info("Provisioning grace started", {deviceId, graceMs: PROVISIONING_GRACE_MS});
        return;
    }

    await statusRef.child("provisioningStartedAt").remove();
    const status = (await statusRef.once("value")).val() || {};
    const lastServerSeen = Number(status.lastServerSeen);
    if (status.online === true && Number.isFinite(lastServerSeen)) {
        // Restore normal presence evaluation after intentional provisioning. A
        // fresh heartbeat invalidates this task through the expected timestamp.
        await enqueueOfflineCheck(deviceId, lastServerSeen, OFFLINE_CHECK_DELAY_SECONDS);
    }
    logger.info("Provisioning grace ended", {deviceId});
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

        const initialStatus = initialSnapshot.val() || {};
        if (initialStatus.online === true
                && Number(initialStatus.lastServerSeen) === expectedLastServerSeen
                && initialStatus.provisioning === true) {
            const provisioningStartedAt = Number(initialStatus.provisioningStartedAt);
            const elapsedMs = Number.isFinite(provisioningStartedAt)
                ? Date.now() - provisioningStartedAt : 0;
            if (!Number.isFinite(provisioningStartedAt) || elapsedMs < PROVISIONING_GRACE_MS) {
                const remainingMs = Number.isFinite(provisioningStartedAt)
                    ? PROVISIONING_GRACE_MS - Math.max(0, elapsedMs)
                    : PROVISIONING_GRACE_MS;
                await enqueueOfflineCheck(
                    deviceId, expectedLastServerSeen, Math.ceil(remainingMs / 1000));
                logger.info("delayedOfflineCheck: provisioning grace active", {
                    deviceId,
                    expectedLastServerSeen,
                    provisioningStartedAt: Number.isFinite(provisioningStartedAt)
                        ? provisioningStartedAt : null,
                    remainingMs
                });
                return;
            }
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

exports.sendTestOfflineNotification = onValueWritten({
    ref: "/devices/{deviceId}/debug/testNotifications/offline/{testId}",
    instance: "basilience-database-default-rtdb"
}, async event => {
    if (!event.data.after.exists()) return;
    const deviceId = event.params.deviceId;
    const testId = event.params.testId;
    const db = admin.firestore();
    const tokens = await getDeviceUserTokens(db, deviceId);
    try {
        if (tokens.length > 0) {
            await sendMulticastWithCleanup(db, {
                notification: {
                    title: "TEST — Device Unreachable",
                    body: `${deviceId} test notification. Real connectivity was not changed.`
                },
                data: {
                    title: "TEST — Device Unreachable",
                    body: `${deviceId} test notification. Real connectivity was not changed.`,
                    type: "TEST_OFFLINE_ALERT",
                    deviceId,
                    notificationId: `test_offline_${testId}`
                },
                tokens
            }, "test-offline");
        }
    } finally {
        await event.data.after.ref.remove();
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

            const document = notificationRef.doc(notificationId);
            try {
                await document.create({
                title: status.title,
                message: status.message,
                type: status.type,
                timestamp: Date.now(),
                isRead: false,
                eventId: notificationId
                });
            } catch (error) {
                if (error && (error.code === 6 || error.code === "already-exists")) {
                    logger.info("Status event already handled", {deviceId, notificationId});
                    continue;
                }
                throw error;
            }
            
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
                    const response = await sendMulticastWithCleanup(db, message, "status-alert");
                    logger.info(`Status FCM: ${response.successCount} messages were sent successfully`);
                } catch (error) {
                    await document.delete();
                    throw error;
                }
            }
        }
    }
});
