// RTDB security rules test matrix for database.rules.json (repo root).
// Runs entirely against the local Firebase RTDB emulator - never touches
// production data and never deploys anything. See package.json's
// "test:rules" script for how this is invoked (firebase emulators:exec).

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");
const {
    initializeTestEnvironment,
    assertSucceeds,
    assertFails,
} = require("@firebase/rules-unit-testing");

const RULES_PATH = path.join(__dirname, "..", "..", "database.rules.json");
const EMULATOR_PORT = 9010;

let testEnv;

test.before(async () => {
    testEnv = await initializeTestEnvironment({
        projectId: "basilience-rules-test",
        database: {
            rules: fs.readFileSync(RULES_PATH, "utf8"),
            host: "127.0.0.1",
            port: EMULATOR_PORT,
        },
    });
});

test.after(async () => {
    if (testEnv) await testEnv.cleanup();
});

test.beforeEach(async () => {
    await testEnv.clearDatabase();
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        const db = ctx.database();
        await db.ref("deviceAccess/device-A").set({
            "admin-A": {role: "ADMIN"},
            "farmer-A": {role: "FARMER"},
        });
        await db.ref("devices/device-A/status").set({online: true, lastServerSeen: 1});
        await db.ref("devices/device-A/sensors").set({ph: 6.2});
        await db.ref("devices/device-A/actuatorStatus").set({pump: false});
        await db.ref("devices/device-A/commands").set({manualMode: false});
        await db.ref("devices/device-A/settings").set({minPH: 5.5});
        await db.ref("devices/device-A/smsRecipients").set({
            "admin-A": {phone: "+639171234567", enabled: true, role: "ADMIN"},
        });
        await db.ref("devices/device-A/harvestSchedule").set({cycleId: "c1"});
        await db.ref("devices/device-A/notificationQueue/evt1").set({status: "pending"});
        await db.ref("provisioning/AABBCCDDEEFF/deviceToken").set("device-A");
    });
});

function ctxFor(uid) {
    return uid === null ? testEnv.unauthenticatedContext() : testEnv.authenticatedContext(uid);
}

test("unauthenticated: cannot read device status", async () => {
    const db = ctxFor(null).database();
    await assertFails(db.ref("devices/device-A/status").once("value"));
});

test("unauthenticated: cannot write commands", async () => {
    const db = ctxFor(null).database();
    await assertFails(db.ref("devices/device-A/commands/manualMode").set(true));
});

test("unauthenticated: cannot read deviceAccess or provisioning", async () => {
    const db = ctxFor(null).database();
    await assertFails(db.ref("deviceAccess/device-A").once("value"));
    await assertFails(db.ref("provisioning/AABBCCDDEEFF/deviceToken").once("value"));
});

test("cross-device: device-B's identity cannot read device-A", async () => {
    const db = ctxFor("device-B").database();
    await assertFails(db.ref("devices/device-A/status").once("value"));
});

test("cross-device: device-B's identity cannot write into device-A", async () => {
    const db = ctxFor("device-B").database();
    await assertFails(db.ref("devices/device-A/sensors/ph").set(7.0));
});

test("admin: can read whitelisted device paths", async () => {
    const db = ctxFor("admin-A").database();
    await assertSucceeds(db.ref("devices/device-A/status").once("value"));
    await assertSucceeds(db.ref("devices/device-A/sensors").once("value"));
    await assertSucceeds(db.ref("devices/device-A/commands").once("value"));
    await assertSucceeds(db.ref("devices/device-A/settings").once("value"));
});

test("admin: can write commands and settings", async () => {
    const db = ctxFor("admin-A").database();
    await assertSucceeds(db.ref("devices/device-A/commands/manualMode").set(true));
    await assertSucceeds(db.ref("devices/device-A/settings/minPH").set(5.8));
});

test("admin: can push a debug test notification", async () => {
    const db = ctxFor("admin-A").database();
    await assertSucceeds(
        db.ref("devices/device-A/debug/testNotifications/offline/test1").set({title: "t"})
    );
});

test("admin: still cannot read ESP-only sensitive subtrees", async () => {
    const db = ctxFor("admin-A").database();
    await assertFails(db.ref("devices/device-A/smsRecipients").once("value"));
    await assertFails(db.ref("devices/device-A/harvestSchedule").once("value"));
    await assertFails(db.ref("devices/device-A/notificationQueue").once("value"));
});

test("admin: still cannot read deviceAccess or provisioning", async () => {
    const db = ctxFor("admin-A").database();
    await assertFails(db.ref("deviceAccess/device-A").once("value"));
    await assertFails(db.ref("provisioning/AABBCCDDEEFF/deviceToken").once("value"));
});

test("admin: cannot write sensor telemetry (ESP-owned)", async () => {
    const db = ctxFor("admin-A").database();
    await assertFails(db.ref("devices/device-A/sensors/ph").set(7.0));
    await assertFails(db.ref("devices/device-A/status/online").set(false));
});

test("personnel: can read whitelisted device paths", async () => {
    const db = ctxFor("farmer-A").database();
    await assertSucceeds(db.ref("devices/device-A/status").once("value"));
    await assertSucceeds(db.ref("devices/device-A/actuatorStatus").once("value"));
});

test("personnel: cannot write commands or settings", async () => {
    const db = ctxFor("farmer-A").database();
    await assertFails(db.ref("devices/device-A/commands/manualMode").set(true));
    await assertFails(db.ref("devices/device-A/settings/minPH").set(6.0));
});

test("personnel: cannot push a debug test notification", async () => {
    const db = ctxFor("farmer-A").database();
    await assertFails(
        db.ref("devices/device-A/debug/testNotifications/offline/test2").set({title: "t"})
    );
});

test("removed personnel: no deviceAccess entry means full read deny", async () => {
    const db = ctxFor("ex-farmer").database();
    await assertFails(db.ref("devices/device-A/status").once("value"));
    await assertFails(db.ref("devices/device-A/sensors").once("value"));
});

test("ESP: full read access to its own subtree, including sensitive paths", async () => {
    const db = ctxFor("device-A").database();
    await assertSucceeds(db.ref("devices/device-A/smsRecipients").once("value"));
    await assertSucceeds(db.ref("devices/device-A/harvestSchedule").once("value"));
    await assertSucceeds(db.ref("devices/device-A/notificationQueue").once("value"));
});

test("ESP: full write access to its own subtree", async () => {
    const db = ctxFor("device-A").database();
    await assertSucceeds(db.ref("devices/device-A/sensors/ph").set(6.5));
    await assertSucceeds(db.ref("devices/device-A/commands/current").set({op: "start"}));
    await assertSucceeds(db.ref("devices/device-A/notificationQueue/evt2").set({status: "sent"}));
});

test("ESP: still cannot read deviceAccess or provisioning directly", async () => {
    const db = ctxFor("device-A").database();
    await assertFails(db.ref("deviceAccess/device-A").once("value"));
    await assertFails(db.ref("provisioning/AABBCCDDEEFF/deviceToken").once("value"));
});

test("ESP: cannot write into another device's subtree", async () => {
    const db = ctxFor("device-A").database();
    await assertFails(db.ref("devices/device-B/sensors/ph").set(6.5));
});
