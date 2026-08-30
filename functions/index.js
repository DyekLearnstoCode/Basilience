const {setGlobalOptions} = require("firebase-functions");
const {onValueUpdated, onValueWritten} = require("firebase-functions/v2/database");
const {onDocumentCreated, onDocumentWritten} = require("firebase-functions/v2/firestore");
const {onTaskDispatched} = require("firebase-functions/v2/tasks");
const {onSchedule} = require("firebase-functions/v2/scheduler");
const {onCall, HttpsError, onRequest} = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");
const {getFunctions} = require("firebase-admin/functions");
const crypto = require("crypto");

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
// Cooling always completes within minutes; an open episode marker older than
// this was abandoned (e.g. a missed completion event) and must not be used
// as evidence for a later, unrelated cooling cycle.
const COOLING_EPISODE_MAX_AGE_MS = 60 * 60 * 1000;

function safeEventId(eventId) {
    return crypto.createHash("sha256").update(String(eventId)).digest("hex");
}

const AUTOMATION_LIFECYCLE_OPERATIONS = new Set([
    "PH_UP", "PH_DOWN", "EC_CORRECTION", "REFILL"
]);

function automationEpisodeId(deviceId, requestId, operation) {
    return safeEventId(`automation:${deviceId}:${requestId}:${operation}`);
}

// requestId alone is not unique across ESP32 reboots (the firmware's
// auto-request counter restarts at 32768 on every boot), so the success
// notification identity also folds in the completed operation's own
// completedTimestamp - an immutable field already present on the COMPLETED
// RTDB write. The same write's retries always carry the same
// completedTimestamp (idempotent), while a later reboot that reuses
// requestId=32768 will have a different completedTimestamp and therefore a
// different notification ID.
function automationSuccessNotificationId(deviceId, requestId, operation, completedAt) {
    return `${safeEventId(`automation:${deviceId}:${requestId}:${operation}:${completedAt}`)}_success`;
}

// Reads a numeric target from a previously-fetched /devices/{deviceId}/settings
// snapshot, falling back to the firmware's own compiled default (and logging
// when that happens) if the setting is missing, still unset, or not a finite
// number. Keeps success validation in sync with device-configured targets
// instead of hardcoding them.
function resolveTargetSetting(settingsSnapshot, key, fallback, context) {
    const settings = settingsSnapshot ? settingsSnapshot.val() : null;
    const candidate = settings ? settings[key] : null;
    if (typeof candidate === "number" && Number.isFinite(candidate)) {
        return candidate;
    }
    logger.info("Using firmware-default fallback for missing/invalid target setting", {
        ...context,
        key,
        fallback
    });
    return fallback;
}

function automationSuccessContent(operation, variant) {
    if (operation === "PH_UP") {
        return {
            type: "phCorrectionCompleted",
            title: "pH is back to normal",
            message: "Basilience successfully corrected the pH level."
        };
    }

    if (operation === "PH_DOWN") {
        return {
            type: "phCorrectionCompleted",
            title: "pH is back to normal",
            message: "Basilience successfully corrected the pH level."
        };
    }

    if (operation === "EC_CORRECTION" && variant === "LOW") {
        return {
            type: "ecCorrectionCompleted",
            title: "Nutrient level is back to normal",
            message: "Basilience successfully restored the nutrient level."
        };
    }

    if (operation === "EC_CORRECTION" && variant === "HIGH") {
        return {
            type: "ecCorrectionCompleted",
            title: "Nutrient level is back to normal",
            message: "Basilience successfully restored the nutrient level."
        };
    }

    if (operation === "REFILL") {
        return {
            type: "refillCompleted",
            title: "Reservoir refilled",
            message: "The reservoir has been refilled to the proper level."
        };
    }

    return null;
}

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

async function createAutomationSuccessNotification({
    db,
    deviceId,
    requestId,
    operation,
    variant,
    completedAt
}) {
    const content = automationSuccessContent(operation, variant);
    if (!content) return false;

    const notificationId = automationSuccessNotificationId(deviceId, requestId, operation, completedAt);
    const document = db.collection("devices").doc(deviceId)
        .collection("notifications").doc(notificationId);

    try {
        await document.create({
            title: content.title,
            message: content.message,
            type: content.type,
            timestamp: Date.now(),
            isRead: false,
            eventId: notificationId,
            requestId,
            operation,
            operationVariant: variant,
            source: "AUTOMATIC",
            lifecycle: "SUCCESS"
        });
    } catch (error) {
        if (error && (error.code === 6 || error.code === "already-exists")) {
            logger.info("Automation lifecycle event already handled", {
                deviceId, requestId, operation, lifecycle: "SUCCESS", notificationId
            });
            return false;
        }
        throw error;
    }

    const userTokens = await getDeviceUserTokens(db, deviceId);
    if (userTokens.length === 0) return true;

    try {
        const response = await sendMulticastWithCleanup(db, {
            notification: {
                title: content.title,
                body: content.message
            },
            data: {
                title: content.title,
                body: content.message,
                type: content.type,
                deviceId: deviceId || "",
                notificationId,
                requestId: String(requestId),
                operation,
                lifecycle: "SUCCESS"
            },
            tokens: userTokens
        }, "automation-lifecycle");
        logger.info("Automation lifecycle FCM send result", {
            deviceId,
            requestId,
            operation,
            lifecycle: "SUCCESS",
            notificationId,
            successCount: response.successCount,
            failureCount: response.failureCount
        });
    } catch (error) {
        await document.delete();
        throw error;
    }

    return true;
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

exports.onAutomaticOperationLifecycleUpdated = onValueWritten({
    ref: "/devices/{deviceId}/operations/current",
    instance: "basilience-database-default-rtdb",
    retry: true
}, async (event) => {
    const before = event.data.before.val() || {};
    const after = event.data.after.val() || {};
    const deviceId = event.params.deviceId;
    const operation = String(after.operation || "").toUpperCase();
    const source = String(after.source || "").toUpperCase();
    const stateBefore = String(before.state || "").toUpperCase();
    const stateAfter = String(after.state || "").toUpperCase();
    const requestId = after.requestId;

    if (source !== "AUTOMATIC"
            || !AUTOMATION_LIFECYCLE_OPERATIONS.has(operation)
            || requestId === null || requestId === undefined) {
        return;
    }

    const sameEpisodeBefore = String(before.source || "").toUpperCase() === "AUTOMATIC"
        && String(before.operation || "").toUpperCase() === operation
        && String(before.requestId) === String(requestId);
    const started = stateAfter === "RUNNING"
        && (!sameEpisodeBefore || stateBefore !== "RUNNING");
    const completed = stateAfter === "COMPLETED"
        && sameEpisodeBefore
        && stateBefore !== "COMPLETED";

    if (!started && !completed) return;

    const db = admin.firestore();
    const episodeId = automationEpisodeId(deviceId, requestId, operation);
    const episodeRef = db.collection("devices").doc(deviceId)
        .collection("automationNotificationEpisodes").doc(episodeId);
    let variant = operation === "PH_UP" ? "LOW"
        : operation === "PH_DOWN" ? "HIGH"
        : operation === "REFILL" ? "REFILL"
        : null;

    if (started) {
        if (operation === "EC_CORRECTION") {
            const alertsSnapshot = await admin.database()
                .ref(`/devices/${deviceId}/alerts`).once("value");
            const alerts = alertsSnapshot.val() || {};
            if (alerts.ecLow === true && alerts.ecHigh !== true) {
                variant = "LOW";
            } else if (alerts.ecHigh === true && alerts.ecLow !== true) {
                variant = "HIGH";
            } else {
                logger.warn("EC correction direction could not be classified safely", {
                    deviceId,
                    requestId,
                    ecLow: alerts.ecLow === true,
                    ecHigh: alerts.ecHigh === true
                });
                return;
            }
        }

        // Internal correlation only: RUNNING creates no Notification History and
        // sends no FCM. The marker retains EC direction after its alert clears.
        await episodeRef.set({
            requestId,
            operation,
            operationVariant: variant,
            source: "AUTOMATIC"
        });
        return;
    }

    if (!completed) return;

    const [episodeDocument, sensorsSnapshot, settingsSnapshot, targetSettingsSnapshot] = await Promise.all([
        episodeRef.get(),
        admin.database().ref(`/devices/${deviceId}/sensors`).once("value"),
        // Water-depth model (see firmware Config.h's "Water Reservoir
        // Geometry"): refillStopLevelCm is what firmware's refill control
        // actually stops at now - the legacy refillStopLevel percentage
        // field is no longer read by any firmware control path, so gating
        // this notification on it would desync from real refill completion.
        operation === "REFILL"
            ? admin.database().ref(`/devices/${deviceId}/settings/refillStopLevelCm`).once("value")
            : Promise.resolve(null),
        (operation === "PH_UP" || operation === "PH_DOWN" || operation === "EC_CORRECTION")
            ? admin.database().ref(`/devices/${deviceId}/settings`).once("value")
            : Promise.resolve(null)
    ]);

    if (episodeDocument.exists) {
        const episode = episodeDocument.data() || {};
        if (String(episode.requestId) !== String(requestId)
                || episode.operation !== operation
                || episode.source !== "AUTOMATIC") {
            logger.warn("Automation success episode marker mismatch", {
                deviceId, requestId, operation
            });
            return;
        }
        variant = episode.operationVariant;
    } else if (operation === "EC_CORRECTION") {
        logger.warn("EC success has no matching direction marker", {
            deviceId, requestId, operation
        });
        return;
    }

    const sensors = sensorsSnapshot.val() || {};
    // REFILL reads waterLevelCm (water-depth model, AUTHORITATIVE - see
    // firmware Config.h's "Water Reservoir Geometry"), never the legacy
    // waterLevel percentage, so this stays in sync with what firmware's
    // refill control itself actually compares against.
    const sensorValue = operation === "PH_UP" || operation === "PH_DOWN"
        ? sensors.ph
        : operation === "EC_CORRECTION" ? sensors.ec : sensors.waterLevelCm;
    const sensorValid = typeof sensorValue === "number"
        && Number.isFinite(sensorValue)
        && ((operation === "PH_UP" || operation === "PH_DOWN")
            ? sensorValue >= 0 && sensorValue <= 14
            : operation === "EC_CORRECTION"
                ? sensorValue >= 0
                // Physical depth ceiling, not MAX_WORKING_WATER_CM (6.0) - the
                // live waterLevelCm reading at refill completion can
                // legitimately sit above the 100%-working-capacity ceiling
                // (overfill), and that must not suppress this notification.
                // See the static automation integration audit, part 2.
                : sensorValue >= 0 && sensorValue <= MAX_PHYSICAL_WATER_DEPTH_CM);
    const refillStopLevelCm = settingsSnapshot ? settingsSnapshot.val() : null;
    const targetContext = {deviceId, requestId, operation, variant};
    const phTargetMin = operation === "PH_UP"
        ? resolveTargetSetting(targetSettingsSnapshot, "phTargetMin", 5.8, targetContext)
        : null;
    const phTargetMax = operation === "PH_DOWN"
        ? resolveTargetSetting(targetSettingsSnapshot, "phTargetMax", 6.3, targetContext)
        : null;
    const ecTargetMin = operation === "EC_CORRECTION" && variant === "LOW"
        ? resolveTargetSetting(targetSettingsSnapshot, "ecTargetMin", 1.4, targetContext)
        : null;
    const ecTargetMax = operation === "EC_CORRECTION" && variant === "HIGH"
        ? resolveTargetSetting(targetSettingsSnapshot, "ecTargetMax", 1.8, targetContext)
        : null;
    const targetSatisfied = sensorValid && (
        (operation === "PH_UP" && sensorValue >= phTargetMin)
        || (operation === "PH_DOWN" && sensorValue <= phTargetMax)
        || (operation === "EC_CORRECTION" && variant === "LOW" && sensorValue >= ecTargetMin)
        || (operation === "EC_CORRECTION" && variant === "HIGH" && sensorValue <= ecTargetMax)
        || (operation === "REFILL"
            && typeof refillStopLevelCm === "number"
            && Number.isFinite(refillStopLevelCm)
            && refillStopLevelCm >= 0
            && refillStopLevelCm <= MAX_WORKING_WATER_CM
            && sensorValue >= refillStopLevelCm)
    );

    if (!targetSatisfied) {
        logger.warn("Completed automation did not satisfy notification success target", {
            deviceId,
            requestId,
            operation,
            variant,
            sensorValue: sensorValid ? sensorValue : null,
            refillStopLevelCm: operation === "REFILL" ? refillStopLevelCm : null
        });
        return;
    }

    await createAutomationSuccessNotification({
        db,
        deviceId,
        requestId,
        operation,
        variant,
        completedAt: after.completedTimestamp
    });
});

// Reservoir cooling (circulation pump + Peltier) is intentionally controlled
// directly by AutomationManager::updateCooling() and never creates a
// systemState.operationRequest episode (see architecture audit: putting it on
// the shared OperationRequest object risks it overwriting - or being starved
// by - PH/EC/REFILL). Success tracking is therefore backend-only, correlated
// purely from data the firmware already publishes.
//
// waterTempOutOfRange (highWaterTemp, 25C) and cooling completion
// (coolerOffTemp, 22.5C) are two different thresholds by firmware design -
// the alert clears well before cooling actually finishes, while
// coolingDemandActive/Peltier keep running through that hysteresis gap. So
// episode OPEN is still driven by the alert (onAlertUpdated below), but
// episode CLOSE/success is driven separately by the Peltier actuator's own
// RUNNING -> OFF transition (onCoolingPeltierUpdated), which is what
// firmware actually does once waterTemp <= coolerOffTemp is reached.
async function handleWaterTemperatureCoolingEpisodeStart(db, event, deviceId, alertsBefore, alertsAfter) {
    const wasOutOfRange = alertsBefore.waterTempOutOfRange === true;
    const isOutOfRange = alertsAfter.waterTempOutOfRange === true;
    if (!isOutOfRange || wasOutOfRange) return;

    // Episode start: record proof-of-cooling now, but never send anything
    // here - RUNNING/START stays silent for every automatic correction.
    const realtimeDb = admin.database();
    const actuatorSnapshot = await realtimeDb
        .ref(`/devices/${deviceId}/actuatorStatus`).once("value");
    const actuators = actuatorSnapshot.val() || {};
    const peltierAlreadyRunning = !!(actuators.peltier && actuators.peltier.running === true);
    const coolingObservedAtStart =
        peltierAlreadyRunning
        || (actuators.circulationPump && actuators.circulationPump.running === true);

    const episodeRef = db.collection("devices").doc(deviceId)
        .collection("coolingNotificationEpisodes").doc("current");

    await episodeRef.set({
        deviceId,
        episodeId: safeEventId(event.id),
        startedAt: Date.now(),
        startedEventId: event.id,
        coolingObservedAtStart,
        // Primary completion evidence: refined by onCoolingPeltierUpdated the
        // moment Peltier is actually confirmed running (usually a few ticks
        // after the alert fires, once circulation itself confirms first).
        peltierObserved: peltierAlreadyRunning
    });
}

async function emitWaterTemperatureCoolingSuccess(db, deviceId, notificationId) {
    const title = "Water temperature is back to normal";
    const message = "Basilience successfully restored the water temperature to the proper range.";
    const type = "waterTemperatureCorrectionCompleted";
    const document = db.collection("devices").doc(deviceId)
        .collection("notifications").doc(notificationId);

    try {
        await document.create({
            title,
            message,
            type,
            timestamp: Date.now(),
            isRead: false,
            eventId: notificationId,
            source: "AUTOMATIC",
            lifecycle: "SUCCESS"
        });
    } catch (error) {
        if (error && (error.code === 6 || error.code === "already-exists")) {
            logger.info("Water temperature success already handled", {deviceId, notificationId});
            return;
        }
        throw error;
    }

    const userTokens = await getDeviceUserTokens(db, deviceId);
    if (userTokens.length === 0) return;

    try {
        const response = await sendMulticastWithCleanup(db, {
            notification: {title, body: message},
            data: {
                title,
                body: message,
                type,
                deviceId: deviceId || "",
                notificationId,
                lifecycle: "SUCCESS"
            },
            tokens: userTokens
        }, "automation-lifecycle");
        logger.info("Water temperature success FCM send result", {
            deviceId,
            notificationId,
            successCount: response.successCount,
            failureCount: response.failureCount
        });
    } catch (error) {
        await document.delete();
        throw error;
    }
}

function automationTestSubsystemFromStatus(status) {
    const mode = status && status.automationTestMode;
    if (!mode || mode.enabled !== true || typeof mode.subsystem !== "string") return "OFF";
    return mode.subsystem.trim().toUpperCase();
}

function automationTestAlertPushAllowed(alertKey, status) {
    if (alertKey === "sensorFault") return true;

    const subsystem = automationTestSubsystemFromStatus(status);
    if ((alertKey === "lowWater" || alertKey === "criticalLowWater"
            || alertKey === "waterLevelLow" || alertKey === "waterLevelHigh")
        && status.ignoreWaterLevelAutomation === true) {
        return false;
    }
    if (subsystem === "OFF") return true;

    const allowedBySubsystem = {
        STARTUP: new Set(["lowWater", "criticalLowWater"]),
        REFILL: new Set(["lowWater", "criticalLowWater", "waterLevelLow", "waterLevelHigh"]),
        PH: new Set(["phLow", "phHigh"]),
        EC: new Set(["ecLow", "ecHigh"]),
        COOLING: new Set(["waterTempOutOfRange", "waterTempLow"]),
        FOGGING: new Set(["lowWater", "criticalLowWater", "lowAirTemperature", "highTemperature"]),
        CANOPY: new Set(["lowAirTemperature", "highTemperature", "humidityLow", "humidityHigh"]),
        GROW_LIGHT: new Set([])
    };

    return allowedBySubsystem[subsystem]
        ? allowedBySubsystem[subsystem].has(alertKey)
        : false;
}

function automationTestStatusPushAllowed(statusKey, status) {
    if (statusKey === "safetyLock") return true;

    const subsystem = automationTestSubsystemFromStatus(status);
    if (subsystem === "OFF") return true;

    return (subsystem === "PH" && statusKey === "phSubsystemLocked")
        || (subsystem === "EC" && statusKey === "ecSubsystemLocked")
        || (subsystem === "REFILL" && statusKey === "refillSubsystemLocked")
        || (subsystem === "COOLING" && statusKey === "coolingSubsystemLocked");
}

exports.onAlertUpdated = onValueUpdated({
    ref: "/devices/{deviceId}/alerts",
    instance: "basilience-database-default-rtdb",
    retry: true
}, async (event) => {
    const alertsBefore = event.data.before.val() || {};
    const alertsAfter = event.data.after.val() || {};
    const deviceId = event.params.deviceId;

    const db = admin.firestore();
    const statusSnapshot = await admin.database()
        .ref(`/devices/${deviceId}/status`).once("value");
    const notificationStatus = statusSnapshot.val() || {};

    await handleWaterTemperatureCoolingEpisodeStart(db, event, deviceId, alertsBefore, alertsAfter);

    const alertKeys = [
        { key: "lowWater", title: "Water level is low", message: "The reservoir water level is low. Basilience will refill it automatically when safe.", type: "parameter" },
        // Severity escalation on top of lowWater (water-depth model - see
        // firmware Config.h's "Water Reservoir Geometry" and the static
        // automation integration audit) - fires separately, later, as the
        // level keeps falling past criticalLowWaterCm. No new actuator
        // behavior: the operational block (pH/EC dosing, fogging, cooling)
        // is already fully in effect by the time this can ever fire.
        { key: "criticalLowWater", title: "Critical low water level", message: "The reservoir water level is critically low. Refill is in progress; dependent automation remains paused until it recovers.", type: "parameter" },
        { key: "ecLow", title: "Nutrient level is too low", message: "The nutrient level is below the proper range. Basilience will correct it automatically when safe.", type: "parameter" },
        { key: "ecHigh", title: "Nutrient level is too high", message: "The nutrient level is above the proper range. Basilience will correct it automatically when safe.", type: "parameter" },
        { key: "phLow", title: "pH is too low", message: "The pH level is below the proper range. Basilience will correct it automatically when safe.", type: "parameter" },
        { key: "phHigh", title: "pH is too high", message: "The pH level is above the proper range. Basilience will correct it automatically when safe.", type: "parameter" },
        // waterTempOutOfRange is the HIGH side only - the firmware raises it when
        // water temperature exceeds the configured maximum, and waterTempLow
        // below covers the other direction. The old "outside the safe range"
        // wording predated that split and would now be ambiguous next to its
        // low-side counterpart. The firmware flag name is unchanged.
        { key: "waterTempOutOfRange", title: "High Water Temperature", message: "Water temperature is above the configured maximum range.", type: "parameter" },
        { key: "waterTempLow", title: "Low Water Temperature", message: "Water temperature is below the configured minimum range.", type: "parameter" },
        { key: "lowAirTemperature", title: "Low Air Temperature", message: "Air temperature is below the acceptable range.", type: "parameter" },
        { key: "highTemperature", title: "High Air Temperature", message: "Air temperature is above safe limits.", type: "parameter" },
        // Humidity had no notifications at all before target ranges existed.
        // Reported as a range condition only - Basilience has no humidifier, and
        // the message must not imply one.
        { key: "humidityLow", title: "Low Humidity", message: "Humidity is below the configured minimum range.", type: "parameter" },
        { key: "humidityHigh", title: "High Humidity", message: "Humidity is above the configured maximum range.", type: "parameter" },
        // Target-range counterparts to the operational lowWater alert above.
        // waterLevelHigh promises no corrective action: there is no drain.
        { key: "waterLevelLow", title: "Low Water Level", message: "Reservoir water level is below the configured minimum range.", type: "parameter" },
        { key: "waterLevelHigh", title: "High Water Level", message: "Reservoir water level is above the configured maximum range.", type: "parameter" },
        { key: "sensorFault", title: "Sensor Fault", message: "A sensor reading is unavailable or invalid. Check the affected sensor.", type: "hardware" }
    ];

    for (const alert of alertKeys) {
        const wasActive = alertsBefore[alert.key] === true;
        const isActive = alertsAfter[alert.key] === true;

        // lowWater (refill control threshold, water-depth model - see
        // firmware Config.h's "Water Reservoir Geometry") trips at a higher
        // water level than waterLevelLow (target-range minimum, still
        // percentage-based) by default, and stays true continuously while
        // the level keeps falling - so by the time waterLevelLow's own
        // threshold is crossed, lowWater is already active and this still
        // dedupes the pair into one notification for one physical event.
        // The operational one wins: it already tells the farmer the level
        // is low AND that a refill is coming. waterLevelLow still notifies
        // on its own whenever it is the only one active - which is exactly
        // the case that matters, an admin setting the target minimum above
        // the refill level.
        if (alert.key === "waterLevelLow" && alertsAfter.lowWater === true) {
            continue;
        }

        if (isActive && !wasActive) {
            const notificationId = `${safeEventId(event.id)}_${alert.key}`;
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

            if (!automationTestAlertPushAllowed(alert.key, notificationStatus)) {
                logger.info("Automation Test Mode suppressed unrelated alert push", {
                    deviceId,
                    alertKey: alert.key,
                    subsystem: automationTestSubsystemFromStatus(notificationStatus)
                });
                continue;
            }

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

// Cooling completion (waterTemp <= coolerOffTemp) happens well after the
// waterTempOutOfRange alert has already cleared (see the hysteresis comment
// on handleWaterTemperatureCoolingEpisodeStart above), so it cannot be
// observed from the alert. It is instead observed directly from the Peltier
// actuator's own RUNNING -> OFF transition, which is exactly what
// AutomationManager::updateCooling() does the instant coolingDemandActive
// clears - the same real trigger the firmware itself uses.
exports.onCoolingPeltierUpdated = onValueWritten({
    ref: "/devices/{deviceId}/actuatorStatus/peltier",
    instance: "basilience-database-default-rtdb",
    retry: true
}, async (event) => {
    const before = event.data.before.val() || {};
    const after = event.data.after.val() || {};
    const deviceId = event.params.deviceId;

    const wasRunning = before.running === true;
    const isRunning = after.running === true;
    if (wasRunning === isRunning) return;

    const db = admin.firestore();
    const episodeRef = db.collection("devices").doc(deviceId)
        .collection("coolingNotificationEpisodes").doc("current");

    if (isRunning && !wasRunning) {
        // Peltier just confirmed running. Record this as completion evidence
        // on whatever episode is currently open - never create/overwrite one
        // here, only the alert transition (episode start) does that.
        if (after.source === "automatic") {
            await episodeRef.set({peltierObserved: true}, {merge: true}).catch(() => {});
        }
        return;
    }

    // Peltier just stopped. Only a stop that was itself automatic-sourced
    // (i.e. AutomationManager's own "cooling demand cleared" command, not a
    // manual toggle or the circulation-failure safety interlock leaving a
    // stale "manual"-less source) can represent a real cooling completion.
    if (after.source !== "automatic") {
        return;
    }

    const episodeDocument = await episodeRef.get();
    if (!episodeDocument.exists) {
        logger.info("Peltier stopped with no open water-temperature cooling episode", {deviceId});
        return;
    }
    const episode = episodeDocument.data() || {};

    if (episode.closedAt) {
        // Already closed - a duplicate/replayed actuator write.
        return;
    }

    const startedAt = Number(episode.startedAt);
    if (!Number.isFinite(startedAt) || Date.now() - startedAt > COOLING_EPISODE_MAX_AGE_MS) {
        logger.warn("Water-temperature cooling episode marker is stale; ignoring", {
            deviceId, episodeId: episode.episodeId, startedAt
        });
        return;
    }

    if (episode.peltierObserved !== true) {
        logger.warn("Peltier stopped without confirmed automatic-cooling evidence on the open episode", {
            deviceId, episodeId: episode.episodeId
        });
        return;
    }

    const [sensorsSnapshot, settingsSnapshot] = await Promise.all([
        admin.database().ref(`/devices/${deviceId}/sensors`).once("value"),
        admin.database().ref(`/devices/${deviceId}/settings`).once("value")
    ]);

    const waterTemp = (sensorsSnapshot.val() || {}).waterTemperature;
    const waterTempValid = typeof waterTemp === "number" && Number.isFinite(waterTemp);
    const coolerOffTemp = resolveTargetSetting(settingsSnapshot, "coolerOffTemp", 22.5, {deviceId});
    const targetSatisfied = waterTempValid && waterTemp <= coolerOffTemp;

    if (!targetSatisfied) {
        logger.warn("Peltier stopped but water temperature did not satisfy the cooler-off target", {
            deviceId,
            waterTemp: waterTempValid ? waterTemp : null,
            coolerOffTemp
        });
        return;
    }

    const notificationId = `${safeEventId(event.id)}_waterTempSuccess`;
    await emitWaterTemperatureCoolingSuccess(db, deviceId, notificationId);

    // Close only after a successful attempt so a retry after a mid-flight
    // failure (e.g. FCM error) still finds the episode open and evidenced.
    await episodeRef.update({closedAt: Date.now(), closedEventId: event.id}).catch(() => {});
});

async function handleDeviceConnectivityTransition(eventId, deviceId, wasOnline, isOnline) {
    const wentOffline = wasOnline === true && isOnline === false;
    const cameOnline = wasOnline === false && isOnline === true;
    if (!wentOffline && !cameOnline) return;

    logger.info(`[PRESENCE] ${deviceId} ${wentOffline ? "ONLINE -> OFFLINE" : "OFFLINE -> ONLINE"}`);

    const suffix = wentOffline ? "unreachable" : "online";
    const notificationId = `${safeEventId(eventId)}_${suffix}`;
    const title = wentOffline ? "Basilience Device Unreachable" : "Basilience Device Back Online";
    const body = wentOffline
        ? "Basilience cannot communicate with the device. Check its power or network connection. Local automation may still be running if the device has power."
        : "The device has reconnected and cloud monitoring has resumed.";
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
    instance: "basilience-database-default-rtdb",
    retry: true
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
    // Copy the recorder identity already stored on the harvest record itself
    // (HarvestLogFragment writes both at creation time). recorderName is a
    // display-name snapshot, preferred so history stays accurate even if the
    // recorder's profile name changes later; recorderUid is kept as a
    // fallback for the rare record that lacks a name snapshot. A pre-harvest
    // reminder (createHarvestReminder) never sets either field, which is
    // exactly how the Android client tells the two "harvest" notifications
    // apart for "Recorded by" display purposes.
    const recorderUid = harvestData.recordedBy || null;
    const recorderName = harvestData.recordedByName || null;

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
            harvestId,
            recorderUid,
            recorderName
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
//   water_level_cm: number, water_volume_liters: number (water-depth model -
//   see firmware Config.h's "Water Reservoir Geometry"; absent on records
//   from before the firmware update - Android must never treat a missing
//   water_level_cm as though the legacy water_level percentage on that same
//   record came from the new depth-based formula)
// Source RTDB fields (written by FirebaseManager::writeSensors) are camelCase
// and use device-uptime millis() for their own timestamp, so we re-stamp with
// Date.now() here rather than trusting the RTDB value.
const SENSOR_LOG_INTERVAL_MS = 5 * 60 * 1000;
const MAX_WORKING_WATER_CM = 6.0; // Mirrors firmware Config.h's constant.
// Physical plausibility ceiling for a LOGGED depth reading - the installed
// sensor-to-bottom calibration distance (firmware Config.h's
// WATER_LEVEL_EMPTY_DISTANCE_CM default), not MAX_WORKING_WATER_CM (6.0),
// which is only the 100% WORKING-capacity ceiling for the derived
// percentage. A depth above 6cm is a legitimate overfill reading, not
// invalid data, and must not be silently dropped from history - see the
// static automation integration audit, part 2.
const MAX_PHYSICAL_WATER_DEPTH_CM = 28.67;

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
    addValid("water_level_cm", data.waterLevelCm, 0, MAX_PHYSICAL_WATER_DEPTH_CM);
    addValid("water_volume_liters", data.waterVolumeLiters, 0, Number.MAX_VALUE);
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
// LIVE PATH - legacy/current-firmware compatibility only. Firmware that has
// FoggingEventQueue (see onFoggingEventQueued below) stamps
// lastFoggingEventId onto this same actuatorStatus/fogger write whenever it
// already owns a transition through its own durable queue+replay path; this
// function must then defer, or the same transition would be double-logged.
// Firmware without that field (pre-upgrade devices) is unaffected - this
// path behaves exactly as before, just idempotent now.
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

    if (after.lastFoggingEventId) {
        // New firmware already recorded this transition through
        // onFoggingEventQueued - do not log it a second time here.
        logger.info(`logFoggerActivity: deferring to queue path for device ${deviceId} (eventId=${after.lastFoggingEventId})`);
        return;
    }

    const db = admin.firestore();
    const eventType = isRunning ? "ON" : "OFF";
    const source = after.source || null;
    const isManual = source === "manual" || source === "android";
    const strategy = source === "automatic" ? (after.strategy || null) : null;

    // Deterministic id from THIS trigger invocation's own delivery id
    // (same safeEventId(event.id) pattern already used throughout this
    // file): RTDB triggers are at-least-once, so a retried invocation of the
    // SAME write must not create a second foggingLogs document.
    const docId = `fog_${safeEventId(event.id)}`;

    try {
        await db.collection("devices")
            .doc(deviceId)
            .collection("foggingLogs")
            .doc(docId)
            .create({
                event: eventType,
                timestamp: Date.now(),
                isManual: isManual,
                source: source,
                strategy: strategy,
                reason: after.reason || null
            });
    } catch (error) {
        if (!(error && (error.code === 6 || error.code === "already-exists"))) {
            logger.error(`Error logging fogger activity for ${deviceId}:`, error);
        }
        // already-exists: an earlier retry of this same invocation already
        // produced the document - nothing left to do.
    }

    logger.info(`logFoggerActivity: Fogger turned ${eventType} for device ${deviceId} (source=${source})`);
});

// QUEUE PATH - the append-only history record for firmware running
// FoggingEventQueue. Never reads/writes actuatorStatus/fogger (that node is
// current-state only); mirrors onNotificationQueued's shape exactly:
// idempotent by eventId, ack written back only after Firestore persistence
// actually succeeds.
exports.onFoggingEventQueued = onValueWritten({
    ref: "/devices/{deviceId}/foggingEventQueue/{eventId}",
    instance: "basilience-database-default-rtdb",
    retry: true
}, async (event) => {
    const deviceId = event.params.deviceId;
    const eventId = event.params.eventId;
    const data = event.data.after.val();

    if (!data || data.status === "acked") {
        // Null: entry deleted (firmware already removed it locally after a
        // prior ack). "acked": this write is our own ack below (RTDB
        // triggers on this ref fire for descendant writes too), or a stale
        // re-trigger - either way, already handled.
        return;
    }

    if (data.event !== "ON" && data.event !== "OFF") {
        logger.error(`onFoggingEventQueued: invalid event shape for ${deviceId}/${eventId}`, data);
        return;
    }

    const source = data.source || null;
    // Same derivation the live path (logFoggerActivity) uses, so a
    // transition logs identically regardless of which path recorded it.
    const isManual = source === "manual" || source === "android";
    const strategy = source === "automatic" ? (data.strategy || null) : null;

    const db = admin.firestore();
    const logRef = db.collection("devices").doc(deviceId).collection("foggingLogs").doc(eventId);

    try {
        await logRef.create({
            event: data.event,
            // Original device-observed time is preserved, never replaced by
            // ingest/server time - only falls back to now() if the firmware
            // itself never had a valid RTC timestamp to offer (see
            // timestampValid in the task report's timestamp-behavior notes).
            timestamp: (data.timestampValid && data.occurredAt) ? data.occurredAt * 1000 : Date.now(),
            isManual: isManual,
            source: source,
            strategy: strategy,
            reason: data.reason || null
        });
        logger.info(`onFoggingEventQueued: recorded ${data.event} for device ${deviceId} (eventId=${eventId})`);
    } catch (error) {
        if (!(error && (error.code === 6 || error.code === "already-exists"))) {
            logger.error(`Failed to persist queued fogging event ${eventId} for device ${deviceId}`, error);
            return; // leave unacked; firmware will resubmit later
        }
        // already-exists: a previous invocation already created it - still
        // safe/correct to ack below so the firmware can drop it locally.
    }

    await admin.database().ref(`/devices/${deviceId}/foggingEventQueue/${eventId}/status`).set("acked");
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
    instance: "basilience-database-default-rtdb",
    retry: true
}, async (event) => {
    const statusBefore = event.data.before.val() || {};
    const statusAfter = event.data.after.val() || {};
    const deviceId = event.params.deviceId;

    const db = admin.firestore();
    const statusKeys = [
        { key: "safetyLock", message: "The system has stopped unsafe operations. Resolve the fault before resetting Safety Lock.", type: "hardware", title: "Safety Lock Activated" },
        { key: "phSubsystemLocked", title: "pH correction needs attention", message: "Basilience could not bring the pH back to normal. Please check the dosing system.", type: "hardware" },
        { key: "ecSubsystemLocked", title: "Nutrient correction needs attention", message: "Basilience could not restore the nutrient level automatically. Please check the reservoir and dosing system.", type: "hardware" },
        { key: "refillSubsystemLocked", title: "Reservoir refill needs attention", message: "Basilience could not refill the reservoir normally. Please check the water supply and refill system.", type: "hardware" },
        { key: "coolingSubsystemLocked", title: "Cooling System Stopped", message: "Water cooling was stopped because the cooling requirements could not be safely maintained.", type: "hardware" }
    ];

    for (const status of statusKeys) {
        const wasActive = statusBefore[status.key] === true;
        const isActive = statusAfter[status.key] === true;

        if (isActive && !wasActive) {
            const notificationId = `${safeEventId(event.id)}_${status.key}`;
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

            if (!automationTestStatusPushAllowed(status.key, statusAfter)) {
                logger.info("Automation Test Mode suppressed unrelated subsystem push", {
                    deviceId,
                    statusKey: status.key,
                    subsystem: automationTestSubsystemFromStatus(statusAfter)
                });
                continue;
            }

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

//==================================================================
// Offline GSM notification pipeline: RTDB projections the ESP32 (RTDB-only,
// no Firestore access) reads while online, plus the transport bridge that
// lets a firmware-queued offline event replay into the SAME Firestore
// notification history every other producer already writes to.
//==================================================================

// Structural-only Philippine mobile normalization - mirrors the Android
// PhoneNumberUtils.normalizePhilippineMobile algorithm exactly (same
// accepted input styles, same canonical +639XXXXXXXXX output, no
// carrier-prefix table). Legacy Firestore values (0917..., 917..., 639...)
// are normalized defensively here rather than assumed already-canonical.
function normalizePhilippineMobile(input) {
    if (typeof input !== "string") return null;
    const cleaned = input.trim().replace(/[\s-]+/g, "");
    if (!cleaned) return null;

    const hasPlus = cleaned.startsWith("+");
    const digits = hasPlus ? cleaned.slice(1) : cleaned;
    if (!digits || !/^\d+$/.test(digits)) return null;

    let subscriber;
    if (digits.startsWith("63") && digits.length === 12) {
        subscriber = digits.slice(2);
    } else if (!hasPlus && digits.startsWith("0") && digits.length === 11) {
        subscriber = digits.slice(1);
    } else if (!hasPlus && digits.length === 10) {
        subscriber = digits;
    } else {
        return null;
    }

    if (subscriber.length !== 10 || subscriber[0] !== "9") return null;
    return "+63" + subscriber;
}

// Recomputes both the /devices/{deviceId}/smsRecipients AND
// /deviceAccess/{deviceId} RTDB projections in one pass, from the SAME
// authoritative Firestore relationship getDeviceUserTokens() already uses
// (devices.ownerUid + deviceAssignments, filtered by role/ownerAdminUid
// linkage) - no second ownership model. smsRecipients carries only
// phone/enabled/role (no email/password/other profile data); deviceAccess
// carries only role plus the server-projected developerTester entitlement,
// used exclusively as an RTDB-rules lookup table (Android never reads it
// directly). One invalid/empty phone is skipped, never
// blocking the rest of either projection. Both projections are written with
// a single atomic .set() each - a fully recomputed snapshot with no
// remaining members clears stale entries automatically (RTDB has no way to
// persist an "empty object", so .set({}) removes the node), and nothing is
// written unless every read above succeeded (a failed read throws before any
// write is attempted, so a partial/stale overwrite cannot happen).
async function regenerateSmsRecipients(db, deviceId) {
    if (!deviceId) return;

    const deviceDoc = await db.collection("devices").doc(deviceId).get();
    const ownerUid = deviceDoc.exists ? (deviceDoc.data().ownerUid || deviceDoc.data().ownerAdminUid) : null;

    const recipients = {};
    const access = {};

    if (ownerUid) {
        const ownerDoc = await db.collection("users").doc(ownerUid).get();
        if (ownerDoc.exists) {
            // Owner maps to ADMIN - canonical RoleConstants.ROLE_ADMIN value,
            // never a third/invented role.
            access[ownerUid] = {
                role: "ADMIN",
                developerTester: ownerDoc.data().developerTester === true
            };
            const phone = normalizePhilippineMobile(ownerDoc.data().phone);
            if (phone) recipients[ownerUid] = {phone, enabled: true, role: "ADMIN"};
        }
    }

    const assignmentsSnapshot = await db.collection("deviceAssignments")
        .where("deviceId", "==", deviceId)
        .get();

    for (const doc of assignmentsSnapshot.docs) {
        const userUid = doc.data().userUid;
        if (!userUid || userUid === ownerUid) continue;

        const userDoc = await db.collection("users").doc(userUid).get();
        if (!userDoc.exists) continue;

        const user = userDoc.data();
        // Same eligibility rule as getDeviceUserTokens(): an assignment only
        // grants access while the personnel profile remains linked to this
        // device's owner.
        if ((user.role === "FARMER" || user.role === "PERSONNEL") && user.ownerAdminUid === ownerUid) {
            // Personnel assignment maps to FARMER - canonical
            // RoleConstants.ROLE_FARMER value, regardless of whether the
            // source profile says "FARMER" or the defensive legacy
            // "PERSONNEL" string.
            access[userUid] = {
                role: "FARMER",
                developerTester: user.developerTester === true
            };
            const phone = normalizePhilippineMobile(user.phone);
            if (phone) recipients[userUid] = {phone, enabled: true, role: user.role};
        }
    }

    const rtdb = admin.database();
    await Promise.all([
        rtdb.ref(`/devices/${deviceId}/smsRecipients`).set(recipients),
        rtdb.ref(`/deviceAccess/${deviceId}`).set(access)
    ]);
    logger.info(`Projections updated for device ${deviceId}: ${Object.keys(recipients).length} SMS-eligible, ${Object.keys(access).length} authorized`);
}

exports.onDeviceAssignmentWrittenUpdateSmsRecipients = onDocumentWritten(
    "deviceAssignments/{assignmentId}",
    async (event) => {
        const before = event.data.before.exists ? event.data.before.data() : null;
        const after = event.data.after.exists ? event.data.after.data() : null;
        const deviceId = (after && after.deviceId) || (before && before.deviceId);
        if (deviceId) await regenerateSmsRecipients(admin.firestore(), deviceId);
    }
);

exports.onUserWrittenUpdateSmsRecipients = onDocumentWritten(
    "users/{uid}",
    async (event) => {
        const uid = event.params.uid;
        const db = admin.firestore();
        const deviceIds = new Set();

        const ownedDevices = await db.collection("devices").where("ownerUid", "==", uid).get();
        ownedDevices.forEach((doc) => deviceIds.add(doc.id));

        const assignments = await db.collection("deviceAssignments").where("userUid", "==", uid).get();
        assignments.forEach((doc) => {
            const deviceId = doc.data().deviceId;
            if (deviceId) deviceIds.add(deviceId);
        });

        for (const deviceId of deviceIds) {
            await regenerateSmsRecipients(db, deviceId);
        }
    }
);

exports.onDeviceWrittenUpdateSmsRecipients = onDocumentWritten(
    "devices/{deviceId}",
    async (event) => {
        await regenerateSmsRecipients(admin.firestore(), event.params.deviceId);
    }
);

// A cycle counts as active when explicitly marked ACTIVE, or when it predates
// the status field entirely and was never completed - same legacy rule as
// the Android client's CycleStatus.isActive(), so the cloud and app sides
// agree on what "active" means for a cycle document.
function isCycleDataActive(data) {
    if (!data) return false;
    const status = data.status;
    if (status === null || status === undefined || status === "") {
        return !data.endDate;
    }
    return String(status).toUpperCase() === "ACTIVE";
}

// Ranks a candidate active cycle so the projection can deterministically pick
// one even if legacy data has more than one ACTIVE cycle, or is missing
// fields newer cycles always have. cycleNumber is preferred when present
// (matches the ordering the app itself displays cycles in), but a cycle
// created before that field existed - or written by a path that never set
// it - must still be discoverable, never silently skipped. Tiers, in order:
//   0: has a valid numeric cycleNumber - highest wins
//   1: no cycleNumber, has startDate - newest wins
//   2: neither, has createdAt (always set since Cycle's constructor) - newest wins
//   3: none of the above - falls back to document ID as a stable tie-break
// A lower tier always outranks a higher one; only ties within the same tier
// compare by value, then finally by document ID.
function cycleRankKey(id, data) {
    const cycleNumber = data.cycleNumber;
    if (typeof cycleNumber === "number" && Number.isFinite(cycleNumber)) {
        return [0, cycleNumber, id];
    }
    const startMillis = data.startDate && typeof data.startDate.toMillis === "function"
        ? data.startDate.toMillis() : null;
    if (startMillis !== null) {
        return [1, startMillis, id];
    }
    const createdAt = typeof data.createdAt === "number" ? data.createdAt : null;
    if (createdAt !== null) {
        return [2, createdAt, id];
    }
    return [3, 0, id];
}

// True when `key` should replace `currentKey` as the authoritative cycle.
function outranks(key, currentKey) {
    if (key[0] !== currentKey[0]) return key[0] < currentKey[0];
    if (key[1] !== currentKey[1]) return key[1] > currentKey[1];
    return key[2] > currentKey[2];
}

// Single source of truth for the /devices/{deviceId}/harvestSchedule RTDB
// projection - the minimum fields the ESP needs to generate an offline
// HARVEST_DUE event (cycle identity + next due date), never the whole cycle
// document. Always re-derives the authoritative cycle from a fresh scan of
// Firestore rather than reasoning incrementally from whichever cycle wrote
// last, so it is correct (and idempotent) whether it's called from the
// per-write trigger or from a manual/backfill reconciliation:
//   - a stray extra ACTIVE cycle from old data can't get lost behind the one
//     that happens to have written most recently
//   - a deleted or newly-COMPLETED cycle can't wrongly clobber a different,
//     still-active cycle's projection
// Deliberately an unfiltered, unordered collection get() rather than
// .orderBy("cycleNumber") or a status equality filter: Firestore excludes
// any document missing the ordered/filtered field entirely, and legacy
// cycles missing cycleNumber (or predating the status field) are a real,
// already-handled case elsewhere in this codebase (see the app's
// Cycle_Details_Fragment fallback mapping) - this reconciliation exists
// specifically to recover cycles the normal path lost track of, so it must
// not apply the same kind of filter that could cause that in the first place.
async function syncHarvestScheduleForDevice(db, rtdb, deviceId) {
    const cyclesSnap = await db.collection("devices").doc(deviceId)
        .collection("cycles").get();

    let authoritativeId = null;
    let authoritative = null;
    let authoritativeKey = null;
    for (const doc of cyclesSnap.docs) {
        const data = doc.data();
        if (!isCycleDataActive(data)) continue;
        const key = cycleRankKey(doc.id, data);
        if (authoritativeKey === null || outranks(key, authoritativeKey)) {
            authoritativeId = doc.id;
            authoritative = data;
            authoritativeKey = key;
        }
    }

    if (!authoritative) {
        logger.info(`[CYCLE-SYNC] device=${deviceId} no active cycle`);
        await rtdb.ref(`/devices/${deviceId}/harvestSchedule`).set({active: false, updatedAt: Date.now()});
        return {active: false};
    }

    // No cycleNumber on a legacy/pre-field cycle is not an error - firmware's
    // own parser already defaults to 0 when the JSON key is absent
    // (FirebaseManager.cpp readHarvestSchedule()), and cycleNumber is only
    // ever used there for a diagnostic Serial.print(), never a gating
    // condition, so 0 is a genuinely safe (not fabricated) compatibility
    // value - it's the same value an absent field would already produce.
    const cycleNumber = typeof authoritative.cycleNumber === "number"
        && Number.isFinite(authoritative.cycleNumber) ? authoritative.cycleNumber : 0;

    const nextHarvestDate = authoritative.nextHarvestDate;
    const nextHarvestAt = nextHarvestDate && typeof nextHarvestDate.toMillis === "function"
        ? Math.floor(nextHarvestDate.toMillis() / 1000)
        : 0;

    logger.info(`[CYCLE-SYNC] device=${deviceId} activeCycle=${authoritativeId} nextHarvestAt=${nextHarvestAt}`);

    await rtdb.ref(`/devices/${deviceId}/harvestSchedule`).set({
        cycleId: authoritativeId,
        cycleNumber,
        nextHarvestAt,
        active: true,
        updatedAt: Date.now()
    });
    logger.info(`[CYCLE-SYNC] device=${deviceId} RTDB projection updated`);

    return {active: true, cycleId: authoritativeId, cycleNumber, nextHarvestAt};
}

exports.onCycleWrittenUpdateHarvestSchedule = onDocumentWritten(
    "devices/{deviceId}/cycles/{cycleId}",
    async (event) => {
        const deviceId = event.params.deviceId;
        try {
            await syncHarvestScheduleForDevice(admin.firestore(), admin.database(), deviceId);
        } catch (error) {
            logger.error(`[CYCLE-SYNC] device=${deviceId} projection failed`, {error: error.message});
            throw error;
        }
    }
);

// One-time/on-demand repair for a device whose harvestSchedule projection
// was lost or never written (e.g. an ACTIVE cycle created before this
// projection existed, or an earlier trigger invocation failed) - re-derives
// the RTDB node from Firestore using the exact same logic as the write
// trigger. Admin-only: this is a manual recovery tool, not a client-facing
// feature, so it follows the same auth + role-check convention as
// changePersonnelPassword above rather than trusting caller-supplied data.
exports.reconcileHarvestSchedule = onCall(async (request) => {
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "Authentication is required.");
    }

    const deviceId = typeof request.data?.deviceId === "string" ? request.data.deviceId.trim() : "";
    if (!deviceId) {
        throw new HttpsError("invalid-argument", "A deviceId is required.");
    }

    const db = admin.firestore();
    const adminProfile = await db.collection("users").doc(request.auth.uid).get();
    if (!adminProfile.exists || String(adminProfile.data().role || "").toUpperCase() !== "ADMIN") {
        throw new HttpsError("permission-denied", "Only an authenticated Admin may perform this action.");
    }

    try {
        const result = await syncHarvestScheduleForDevice(db, admin.database(), deviceId);
        return {success: true, ...result};
    } catch (error) {
        logger.error(`[CYCLE-SYNC] device=${deviceId} manual reconciliation failed`, {error: error.message});
        throw new HttpsError("internal", "Unable to reconcile the harvest schedule for this device.");
    }
});

// Bridges a firmware-replayed offline event into the SAME
// devices/{deviceId}/notifications history every other producer already
// writes to - no separate /offlineNotifications system. Idempotent by
// eventId (document.create() + already-exists as success, same pattern used
// throughout this file), and deliberately sends no FCM: the farmer likely
// already received this via SMS while the device was offline, so a fresh
// real-time-looking push two hours later would be misleading. Normal live
// FCM from every other producer in this file is unaffected.
exports.onNotificationQueued = onValueWritten({
    ref: "/devices/{deviceId}/notificationQueue/{eventId}",
    instance: "basilience-database-default-rtdb",
    retry: true
}, async (event) => {
    const deviceId = event.params.deviceId;
    const eventId = event.params.eventId;
    const data = event.data.after.val();

    if (!data || data.status === "acked") {
        // Null: entry deleted. "acked": this write is our own ack below (RTDB
        // triggers on this ref fire for descendant writes too), or a stale
        // re-trigger - either way, already handled.
        return;
    }

    // Maps the firmware's event type to the SAME type strings every other
    // producer in this file already uses, so a replayed event renders with
    // the correct icon/title in the existing Android history UI instead of
    // falling through to the generic "INFORMATION" styling.
    const FIRMWARE_TYPE_TO_HISTORY_TYPE = {
        LOW_WATER: "parameter",
        HIGH_WATER_TEMP: "parameter",
        HIGH_AIR_TEMP: "parameter",
        SENSOR_FAULT: "hardware",
        DEVICE_UNREACHABLE: "connectivity_offline",
        HARVEST_DUE: "harvest"
    };

    const db = admin.firestore();
    const notificationRef = db.collection("devices").doc(deviceId).collection("notifications").doc(eventId);

    try {
        await notificationRef.create({
            title: data.title || "Basilience Alert",
            message: data.message || "",
            type: FIRMWARE_TYPE_TO_HISTORY_TYPE[data.type] || "info",
            // Original device-observed time is preserved, never replaced by
            // replay/server time - only falls back to now() if the firmware
            // itself never had a valid RTC timestamp to offer.
            timestamp: (data.timestampValid && data.occurredAt) ? data.occurredAt * 1000 : Date.now(),
            isRead: false,
            eventId,
            offlineRecorded: true,
            smsFallbackUsed: Boolean(data.smsFallbackUsed)
        });
        logger.info(`Offline event ${eventId} for device ${deviceId} recorded in history (no FCM - offline/SMS-fallback)`);
    } catch (error) {
        if (!(error && (error.code === 6 || error.code === "already-exists"))) {
            logger.error(`Failed to mirror queued notification ${eventId} for device ${deviceId}`, error);
            return; // leave unacked; firmware will resubmit later
        }
    }

    await admin.database().ref(`/devices/${deviceId}/notificationQueue/${eventId}/status`).set("acked");
});

//==================================================================
// Secure Device Auth: bootstrap endpoint minting a Firebase custom token
// (uid = deviceId) for a device that proves knowledge of its provisioned
// bootstrap secret. MAC+secret only ever prove physical device identity here
// server-side - the client-supplied deviceId is never trusted (there is no
// client-supplied deviceId at all; it is resolved from the existing
// /provisioning/{mac}/deviceToken mapping via the Admin SDK, which bypasses
// RTDB rules the same as every other Admin SDK call in this file).
//==================================================================

const BOOTSTRAP_FAIL_LIMIT = 5;
const BOOTSTRAP_LOCKOUT_MS = 15 * 60 * 1000;

function normalizeMac(mac) {
    if (typeof mac !== "string") return null;
    const cleaned = mac.trim().toUpperCase().replace(/[^0-9A-F]/g, "");
    if (cleaned.length !== 12) return null;
    // Colon-less, matching both the firmware's legacy getMacAddress() (used
    // to build the existing /provisioning/{mac}/deviceToken RTDB key) and
    // the actual stored key format (confirmed via a live read:
    // /provisioning/704BCA48FCB4/deviceToken). The firmware's bootstrap
    // payload sends WiFi.macAddress() WITH colons, so this function is
    // exactly where that format has to be normalized away before it's used
    // as an RTDB path segment - previously this re-inserted colons instead,
    // so the lookup key never matched the stored one and every real
    // bootstrap attempt failed at the provisioning-mapping stage.
    return cleaned;
}

exports.deviceAuthBootstrap = onRequest(async (req, res) => {
    let diagnosticStage = "REQUEST_START";
    logger.info("[BOOTSTRAP] Request received");

    try {
        if (req.method !== "POST") {
            logger.info("[BOOTSTRAP] REJECT stage=INVALID_METHOD");
            res.status(405).json({error: "Method not allowed"});
            return;
        }

        // Gen 2 onRequest auto-parses req.body into an object when
        // Content-Type: application/json is set (the firmware sets this) -
        // confirmed by direct testing against the deployed function. This
        // still handles the Buffer/string shapes defensively in case that
        // header is ever missing/different, without weakening validation:
        // a parse failure or non-object body falls through to the same
        // generic 400 the missing-field case already used.
        diagnosticStage = "READ_BODY";
        let body = req.body;
        let bodyParsed = true;
        if (Buffer.isBuffer(body) || typeof body === "string") {
            const raw = Buffer.isBuffer(body) ? body.toString("utf8") : body;
            try {
                body = raw.length > 0 ? JSON.parse(raw) : {};
            } catch (parseError) {
                bodyParsed = false;
            }
        }
        if (body === null || typeof body !== "object") {
            body = {};
            bodyParsed = false;
        }
        logger.info(`[BOOTSTRAP] Body parsed=${bodyParsed}`);

        if (!bodyParsed) {
            logger.info("[BOOTSTRAP] REJECT stage=INVALID_REQUEST");
            res.status(400).json({error: "Invalid request"});
            return;
        }

        diagnosticStage = "NORMALIZE_MAC";
        const mac = normalizeMac(typeof body.mac === "string" ? body.mac : null);
        const deviceSecret = typeof body.deviceSecret === "string" ? body.deviceSecret : "";

        logger.info(`[BOOTSTRAP] MAC=${mac || "INVALID"}`);

        diagnosticStage = "VALIDATE_REQUEST";
        // Shape validation only - never logs the secret value itself, only
        // whether one was present.
        if (!mac || !deviceSecret) {
            logger.warn("deviceAuthBootstrap: malformed request", {hasMac: Boolean(mac), hasSecret: Boolean(deviceSecret)});
            logger.info("[BOOTSTRAP] REJECT stage=INVALID_REQUEST");
            res.status(400).json({error: "Invalid request"});
            return;
        }

        logger.info(`[BOOTSTRAP] Secret supplied=${Boolean(deviceSecret)}`);
        // Diagnostic only - mirrors the firmware's own length bound, does not
        // gate any behavior here.
        logger.info(`[BOOTSTRAP] Secret format valid=${deviceSecret.length >= 16 && deviceSecret.length <= 128}`);

        // Step 1-2 done above (shape/MAC normalization). Step 3: resolve the
        // authoritative deviceId server-side from the existing provisioning
        // mapping - the client never gets to assert its own deviceId.
        diagnosticStage = "LOOKUP_PROVISIONING";
        const rtdb = admin.database();
        const tokenSnap = await rtdb.ref(`/provisioning/${mac}/deviceToken`).get();
        const deviceId = tokenSnap.exists() ? String(tokenSnap.val()) : null;

        logger.info(`[BOOTSTRAP] Provisioning mapping found=${Boolean(deviceId)}`);
        if (deviceId) {
            logger.info(`[BOOTSTRAP] Resolved deviceId=${deviceId}`);
        }

        if (!deviceId) {
            // Generic failure - never reveals whether this MAC is known.
            logger.info("[BOOTSTRAP] REJECT stage=MAC_MAPPING_NOT_FOUND");
            res.status(401).json({error: "Authentication failed"});
            return;
        }

        diagnosticStage = "LOOKUP_CREDENTIAL";
        const db = admin.firestore();
        const credRef = db.collection("deviceCredentials").doc(deviceId);
        const credSnap = await credRef.get();

        logger.info(`[BOOTSTRAP] Credential record found=${credSnap.exists}`);

        if (!credSnap.exists) {
            // Generic failure - never reveals whether a credential record
            // exists for this device.
            logger.info("[BOOTSTRAP] REJECT stage=CREDENTIAL_NOT_FOUND");
            res.status(401).json({error: "Authentication failed"});
            return;
        }

        const cred = credSnap.data();
        const now = Date.now();

        diagnosticStage = "CHECK_LOCKOUT";
        const lockoutActive = Boolean(cred.bootstrapLockedUntil && cred.bootstrapLockedUntil > now);
        logger.info(`[BOOTSTRAP] failCount=${cred.bootstrapFailCount || 0}`);
        logger.info(`[BOOTSTRAP] lockoutActive=${lockoutActive}`);

        if (lockoutActive) {
            logger.info("[BOOTSTRAP] REJECT stage=LOCKED_OUT");
            res.status(429).json({error: "Authentication temporarily locked"});
            return;
        }

        // Constant-time comparison of SHA-256 hashes - the stored secret is
        // never plaintext, and the comparison itself cannot leak timing
        // information about how many leading bytes matched.
        diagnosticStage = "HASH_SECRET";
        const receivedHash = crypto.createHash("sha256").update(deviceSecret).digest();
        const storedHash = Buffer.from(cred.secretHash || "", "hex");

        diagnosticStage = "COMPARE_SECRET";
        const match = storedHash.length === receivedHash.length
            && crypto.timingSafeEqual(storedHash, receivedHash);

        logger.info(`[BOOTSTRAP] Secret hash match=${match}`);

        if (!match) {
            const failCount = (cred.bootstrapFailCount || 0) + 1;
            const update = {bootstrapFailCount: failCount, updatedAt: now};
            if (failCount >= BOOTSTRAP_FAIL_LIMIT) {
                // Bounded lockout, not a permanent brick - a transient
                // mistake (e.g. a typo during manual injection) recovers on
                // its own after the cooldown.
                update.bootstrapLockedUntil = now + BOOTSTRAP_LOCKOUT_MS;
                update.bootstrapFailCount = 0;
            }
            await credRef.update(update);
            logger.info("[BOOTSTRAP] REJECT stage=SECRET_MISMATCH");
            res.status(401).json({error: "Authentication failed"});
            return;
        }

        await credRef.update({
            bootstrapFailCount: 0,
            bootstrapLockedUntil: admin.firestore.FieldValue.delete(),
            lastBootstrapAt: now,
            updatedAt: now
        });

        diagnosticStage = "MINT_TOKEN";
        let customToken;
        try {
            customToken = await admin.auth().createCustomToken(deviceId, {deviceId});
        } catch (mintError) {
            logger.error("[BOOTSTRAP] Custom token minting failed", mintError);
            logger.info("[BOOTSTRAP] REJECT stage=TOKEN_MINT_FAILED");
            res.status(500).json({error: "Internal error"});
            return;
        }

        logger.info("[BOOTSTRAP] Authentication accepted");
        logger.info(`[BOOTSTRAP] Custom token minted for deviceId=${deviceId}`);

        // deviceId is returned alongside the token purely because it is not
        // secret (it is already the Firestore claim code shown to Admins
        // during device claiming) and a first-time device that has not yet
        // persisted one needs it - never secretHash, never internal
        // credential/lockout state.
        res.status(200).json({customToken, deviceId});
    } catch (error) {
        // Only the stage and the error's class name are logged - every
        // throwable reachable here originates from JSON.parse, the Admin
        // SDK, or Node's own runtime, never from code that embeds the
        // secret/hash/token into a message, but the message text itself is
        // still withheld as a precaution rather than relying on that.
        logger.error(`[BOOTSTRAP] UNEXPECTED_ERROR stage=${diagnosticStage}`);
        logger.error(`[BOOTSTRAP] Error type=${(error && error.name) || "UnknownError"}`);
        if (!res.headersSent) {
            res.status(500).json({error: "Internal error"});
        }
    }
});
