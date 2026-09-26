"use strict";

// Push-token lookup and cleanup for the notification senders in index.js.
//
// Tokens are stored one per app installation:
//   users/{uid}/fcmTokens/{installationId} = { token, updatedAt, platform }
// so the same person signed in on several phones keeps every phone's token.
//
// MIGRATION: the older single-token field users/{uid}.fcmToken is still read
// as a fallback, together with the new records, and every token is
// de-duplicated before sending, so a phone that has both gets one push.
// The legacy field is not deleted here.

const admin = require("firebase-admin");
const logger = require("firebase-functions/logger");

const TOKEN_COLLECTION = "fcmTokens";

// token -> paths of the installation records that held it when it was last
// gathered for a send. A send is followed straight away by cleanup in the same
// invocation, so this lets an invalid token remove exactly its own records
// without a collection-group query.
const installationPathsByToken = new Map();

async function readUserTokenEntries(db, uid, legacyToken) {
    const entries = [];
    const snapshot = await db.collection("users").doc(uid).collection(TOKEN_COLLECTION).get();
    for (const doc of snapshot.docs) {
        const token = doc.get("token");
        if (typeof token === "string" && token) entries.push({token, path: doc.ref.path});
    }
    if (typeof legacyToken === "string" && legacyToken) entries.push({token: legacyToken, path: null});
    return entries;
}

function collectEntries(entries, tokensSet) {
    for (const entry of entries) {
        tokensSet.add(entry.token);
        if (entry.path) {
            if (!installationPathsByToken.has(entry.token)) installationPathsByToken.set(entry.token, new Set());
            installationPathsByToken.get(entry.token).add(entry.path);
        }
    }
}

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
                        const entries = await readUserTokenEntries(db, ownerUid, owner.fcmToken);
                        recipients.push({uid: ownerUid, role: owner.role || "UNKNOWN", assignmentStatus: "owner", tokenCount: entries.length});
                        collectEntries(entries, tokensSet);
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
                        const entries = await readUserTokenEntries(db, userUid, user.fcmToken);
                        // An assignment only grants reminder/alert access while the
                        // personnel profile remains linked to this device's owner.
                        if ((user.role === "FARMER" || user.role === "PERSONNEL")
                                && user.ownerAdminUid === ownerUid) {
                            collectEntries(entries, tokensSet);
                        }
                        recipients.push({
                            uid: userUid,
                            role: user.role || "UNKNOWN",
                            assignmentStatus: user.ownerAdminUid === ownerUid ? "assigned-linked" : "assignment-not-linked",
                            tokenCount: entries.length
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

// Removes an invalid/unregistered token from the records that hold it, and only
// those: each installation record is deleted only while it still holds exactly
// this token, so another phone's record (a different token) is never touched.
async function removeInvalidToken(db, token) {
    let installationRecords = 0;
    let legacyUsers = 0;

    let paths = installationPathsByToken.get(token);
    if (!paths || paths.size === 0) {
        // Not gathered by this instance: find the records by token instead.
        paths = new Set();
        try {
            const found = await db.collectionGroup(TOKEN_COLLECTION).where("token", "==", token).get();
            found.docs.forEach(doc => paths.add(doc.ref.path));
        } catch (e) {
            logger.warn("Could not look up installation records for an invalid FCM token", {message: e.message});
        }
    }

    for (const path of paths) {
        const ref = db.doc(path);
        await db.runTransaction(async transaction => {
            const current = await transaction.get(ref);
            if (current.exists && current.get("token") === token) {
                transaction.delete(ref);
                installationRecords++;
            }
        });
    }
    installationPathsByToken.delete(token);

    // Legacy single-token field.
    const users = await db.collection("users").where("fcmToken", "==", token).get();
    for (const userDoc of users.docs) {
        await db.runTransaction(async transaction => {
            const current = await transaction.get(userDoc.ref);
            if (current.exists && current.get("fcmToken") === token) {
                transaction.update(userDoc.ref, {fcmToken: admin.firestore.FieldValue.delete()});
                legacyUsers++;
            }
        });
    }

    return {installationRecords, legacyUsers};
}

module.exports = {getDeviceUserTokens, removeInvalidToken, TOKEN_COLLECTION};
