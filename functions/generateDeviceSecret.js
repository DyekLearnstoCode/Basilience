// ADMIN / MANUFACTURING TOOL - not a Cloud Function, not deployed. Run
// locally with Node against a real Firebase project to generate a Secure
// Device Auth bootstrap secret for one device.
//
// Usage:
//   node generateDeviceSecret.js <deviceId>
//
// Requires Application Default Credentials for a principal allowed to write
// Firestore on this project, e.g.:
//   gcloud auth application-default login
// or a service account key referenced via GOOGLE_APPLICATION_CREDENTIALS.
//
// What this does:
//   1. Verifies devices/{deviceId} actually exists (refuses to generate a
//      secret for an unknown device).
//   2. Generates 32 cryptographically random bytes, encoded base64url
//      (unpadded) - the exact form the firmware will send as `deviceSecret`.
//   3. Stores ONLY sha256(secretString) in deviceCredentials/{deviceId} -
//      never the plaintext secret itself, anywhere.
//   4. Prints the plaintext secret to stdout ONCE. That is the only copy
//      that will ever exist outside the physical device it gets injected
//      into - copy it immediately into your secure delivery mechanism
//      (the local-AP /secure-provision step) and do not paste it anywhere
//      that persists (chat logs, tickets, shared docs, etc).
//
// This script deliberately does not touch RTDB, does not modify firmware,
// and does not commit anything - it is the smallest tool that fits the
// "manufacturing/admin process" step in the Secure Device Auth design.

const admin = require("firebase-admin");
const crypto = require("crypto");

const deviceId = process.argv[2];

if (!deviceId) {
    console.error("Usage: node generateDeviceSecret.js <deviceId>");
    process.exit(1);
}

admin.initializeApp({
    projectId: "basilience-database",
    databaseURL: "https://basilience-database-default-rtdb.asia-southeast1.firebasedatabase.app"
});

async function main() {
    const db = admin.firestore();

    const deviceDoc = await db.collection("devices").doc(deviceId).get();
    if (!deviceDoc.exists) {
        console.error(`Refusing to generate a secret: devices/${deviceId} does not exist.`);
        process.exit(1);
    }

    const secretBytes = crypto.randomBytes(32);
    const secret = secretBytes.toString("base64url"); // matches firmware's expected encoding exactly
    const secretHash = crypto.createHash("sha256").update(secret).digest("hex");

    const now = Date.now();
    const credRef = db.collection("deviceCredentials").doc(deviceId);
    const existing = await credRef.get();

    await credRef.set({
        secretHash,
        bootstrapFailCount: 0,
        createdAt: existing.exists ? existing.data().createdAt : now,
        updatedAt: now
        // bootstrapLockedUntil / lastBootstrapAt intentionally omitted until
        // the device actually bootstraps or fails an attempt.
    }, {merge: false});

    console.log("");
    console.log("=================================================================");
    console.log(`Device secret generated for deviceId: ${deviceId}`);
    console.log("");
    console.log(secret);
    console.log("");
    console.log("This is the ONLY time this plaintext value will ever be shown.");
    console.log("Only sha256(secret) was persisted (deviceCredentials.secretHash).");
    console.log("Deliver it to the physical device now via the local-AP");
    console.log("/secure-provision endpoint (during an explicit provisioning");
    console.log("session), then discard this terminal output. Do not paste it");
    console.log("into chat, tickets, or any other persisted location.");
    console.log("=================================================================");
    console.log("");
}

main()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error("Failed to generate device secret:", error.message || error);
        process.exit(1);
    });
