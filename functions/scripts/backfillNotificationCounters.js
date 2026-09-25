// One-time migration: seeds users/{uid}/counters/notifications with the
// TRUE current unread count for every eligible user, computed from existing
// devices/*/notifications documents' readBy maps.
//
// Why this is needed: exports.onNotificationWrittenSyncUnreadCounters (in
// ../index.js) only ever applies a +1/-1 DELTA in response to a new write.
// It has no way to know the correct starting value for notifications that
// already existed before that trigger was deployed - this script computes
// that starting value once, after which the trigger keeps it accurate
// incrementally forever. Safe to re-run: it fully recomputes and overwrites
// each user's counter document rather than incrementing, so re-running after
// a partial failure just produces the same correct end state.
//
// Usage (run once, after deploying the updated functions but before/at the
// same time as the updated Android client ships):
//   cd functions
//   node scripts/backfillNotificationCounters.js --dry-run   (report only, no writes)
//   node scripts/backfillNotificationCounters.js             (actually writes)
//
// Requires credentials for the basilience Firebase project - either run
// `gcloud auth application-default login` first, or set
// GOOGLE_APPLICATION_CREDENTIALS to a service account key with Firestore
// read/write access.

const DRY_RUN = process.argv.includes("--dry-run");

const admin = require("firebase-admin");

admin.initializeApp();

const db = admin.firestore();

async function getDeviceEligibleUserIds(deviceId) {
    const uids = new Set();
    const deviceDoc = await db.collection("devices").doc(deviceId).get();
    if (!deviceDoc.exists) return uids;

    const data = deviceDoc.data();
    const ownerUid = data.ownerUid || data.ownerAdminUid;
    if (ownerUid) uids.add(ownerUid);

    const assignmentsSnapshot = await db.collection("deviceAssignments")
        .where("deviceId", "==", deviceId)
        .get();

    for (const doc of assignmentsSnapshot.docs) {
        const userUid = doc.data().userUid;
        if (!userUid || userUid === ownerUid) continue;
        const userDoc = await db.collection("users").doc(userUid).get();
        if (!userDoc.exists) continue;
        const user = userDoc.data();
        if ((user.role === "FARMER" || user.role === "PERSONNEL") && user.ownerAdminUid === ownerUid) {
            uids.add(userUid);
        }
    }

    return uids;
}

async function main() {
    console.log(DRY_RUN ? "=== DRY RUN - no writes will be made ===" : "=== LIVE RUN - counters will be written ===");

    // totals[uid] = { perDevice: {deviceId: count}, total: count }
    const totals = {};

    const devicesSnapshot = await db.collection("devices").get();
    console.log(`Found ${devicesSnapshot.size} device(s).`);

    let totalNotifications = 0;

    for (const deviceDoc of devicesSnapshot.docs) {
        const deviceId = deviceDoc.id;
        const eligibleUids = await getDeviceEligibleUserIds(deviceId);
        if (eligibleUids.size === 0) {
            console.log(`  ${deviceId}: no eligible users, skipping.`);
            continue;
        }

        const notificationsSnapshot = await db.collection("devices").doc(deviceId)
            .collection("notifications").get();
        totalNotifications += notificationsSnapshot.size;

        let unreadForAnyone = 0;
        for (const notifDoc of notificationsSnapshot.docs) {
            const readBy = notifDoc.data().readBy || {};
            for (const uid of eligibleUids) {
                if (uid in readBy) continue;
                unreadForAnyone++;
                if (!totals[uid]) totals[uid] = {perDevice: {}, total: 0};
                totals[uid].perDevice[deviceId] = (totals[uid].perDevice[deviceId] || 0) + 1;
                totals[uid].total += 1;
            }
        }

        console.log(`  ${deviceId}: ${notificationsSnapshot.size} notification(s), `
            + `${unreadForAnyone} unread-user-pair(s) across ${eligibleUids.size} eligible user(s).`);
    }

    const uids = Object.keys(totals);
    console.log(`\nSummary: ${devicesSnapshot.size} device(s), ${totalNotifications} notification(s) scanned, `
        + `${uids.length} user(s) would receive a non-empty counter document.`);
    for (const uid of uids) {
        console.log(`  ${uid}: total=${totals[uid].total}, perDevice=${JSON.stringify(totals[uid].perDevice)}`);
    }

    if (DRY_RUN) {
        console.log("\nDry run only - no counter documents were written. Re-run without --dry-run to write them.");
        return;
    }

    console.log(`\nWriting counters for ${uids.length} user(s)...`);

    // Batched in chunks of 400 (Firestore's batch limit is 500 writes).
    const CHUNK_SIZE = 400;
    for (let i = 0; i < uids.length; i += CHUNK_SIZE) {
        const batch = db.batch();
        for (const uid of uids.slice(i, i + CHUNK_SIZE)) {
            const ref = db.collection("users").doc(uid).collection("counters").doc("notifications");
            batch.set(ref, totals[uid]);
        }
        await batch.commit();
        console.log(`  Committed ${Math.min(i + CHUNK_SIZE, uids.length)}/${uids.length}.`);
    }

    console.log("Done.");
}

main().then(() => process.exit(0)).catch((err) => {
    console.error("Backfill failed:", err);
    process.exit(1);
});
