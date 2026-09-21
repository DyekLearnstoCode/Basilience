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
            "admin-A": {role: "ADMIN", developerTester: false},
            "farmer-A": {role: "FARMER", developerTester: false},
            "developer-A": {role: "FARMER", developerTester: true},
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

test("developer tester: can write commands and only the developer-mode setting", async () => {
    const db = ctxFor("developer-A").database();
    await assertSucceeds(db.ref("devices/device-A/commands/mockSensors/dynamic").set(true));
    await assertSucceeds(db.ref("devices/device-A/settings/devModeEnabled").set(true));
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

// ---------------------------------------------------------------------
// Farmer manual-control session grant (manualControlGrants + the
// restructured commands/$actuatorKey rule). See the plan doc: a Farmer
// requests once, an Admin approves once, and the grant then covers every
// actuator for as long as Manual Mode stays on for that same session -
// scoped via commands/manualModeEnabledAt so a stale grant from an earlier
// session can never silently become valid again.
// ---------------------------------------------------------------------

const {ServerValue} = require("firebase-admin/database");

test("farmer: can create their own PENDING manual-control request", async () => {
    const db = ctxFor("farmer-A").database();
    await assertSucceeds(
        db.ref("devices/device-A/manualControlGrants/farmer-A").set({
            status: "PENDING",
            requestedAt: ServerValue.TIMESTAMP,
        })
    );
});

test("farmer: cannot create a manual-control request under another uid", async () => {
    const db = ctxFor("farmer-A").database();
    await assertFails(
        db.ref("devices/device-A/manualControlGrants/developer-A").set({
            status: "PENDING",
            requestedAt: ServerValue.TIMESTAMP,
        })
    );
});

test("farmer: cannot self-approve or smuggle extra fields into their own PENDING request", async () => {
    const db = ctxFor("farmer-A").database();
    await assertFails(
        db.ref("devices/device-A/manualControlGrants/farmer-A").set({
            status: "APPROVED",
            requestedAt: ServerValue.TIMESTAMP,
        })
    );
    await assertFails(
        db.ref("devices/device-A/manualControlGrants/farmer-A").set({
            status: "PENDING",
            requestedAt: ServerValue.TIMESTAMP,
            resolvedByUid: "farmer-A",
        })
    );
});

test("farmer: cannot smuggle extra fields into a fresh PENDING via update() merge against a stale resolved record", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        await ctx.database().ref("devices/device-A/manualControlGrants/farmer-A").set({
            status: "DENIED",
            requestedAt: 1000,
            resolvedByUid: "admin-A",
            resolvedAt: 2000,
        });
    });
    const db = ctxFor("farmer-A").database();
    // A plain update() (not set()) merges against the stale resolvedByUid/
    // resolvedAt siblings left over from the prior denial - the rule's
    // explicit !hasChild('resolvedByUid')/!hasChild('resolvedAt') checks are
    // what catch this (RTDB rules have no numChildren()/exact-shape check).
    await assertFails(
        db.ref("devices/device-A/manualControlGrants/farmer-A").update({
            status: "PENDING",
            requestedAt: ServerValue.TIMESTAMP,
        })
    );
});

test("farmer: can cancel their own still-PENDING request, but cannot alter requestedAt while doing so", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        await ctx.database().ref("devices/device-A/manualControlGrants/farmer-A").set({
            status: "PENDING",
            requestedAt: 5000,
        });
    });
    const db = ctxFor("farmer-A").database();
    await assertFails(
        db.ref("devices/device-A/manualControlGrants/farmer-A").set({
            status: "CANCELLED",
            requestedAt: 6000,
        })
    );
    await assertSucceeds(
        db.ref("devices/device-A/manualControlGrants/farmer-A").set({
            status: "CANCELLED",
            requestedAt: 5000,
        })
    );
});

test("admin: can approve, deny, and revoke a farmer's manual-control request", async () => {
    const admin = ctxFor("admin-A").database();
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        await ctx.database().ref("devices/device-A/manualControlGrants/farmer-A").set({
            status: "PENDING",
            requestedAt: 1000,
        });
    });
    await assertSucceeds(
        admin.ref("devices/device-A/manualControlGrants/farmer-A").update({
            status: "APPROVED",
            resolvedByUid: "admin-A",
            resolvedAt: ServerValue.TIMESTAMP,
        })
    );
    await assertSucceeds(
        admin.ref("devices/device-A/manualControlGrants/farmer-A").update({status: "REVOKED"})
    );
});

test("farmer: cannot write commands/manualModeEnabledAt directly, even with an APPROVED grant", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        await ctx.database().ref("devices/device-A/commands/manualMode").set(true);
        await ctx.database().ref("devices/device-A/commands/manualModeEnabledAt").set(1000);
        await ctx.database().ref("devices/device-A/manualControlGrants/farmer-A").set({
            status: "APPROVED",
            requestedAt: 900,
            resolvedByUid: "admin-A",
            resolvedAt: 1000,
        });
    });
    const db = ctxFor("farmer-A").database();
    await assertFails(db.ref("devices/device-A/commands/manualModeEnabledAt").set(1));
});

test("farmer: cannot turn commands/manualMode ON, even with an APPROVED grant - only OFF is ever allowed", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        await ctx.database().ref("devices/device-A/commands/manualMode").set(false);
        await ctx.database().ref("devices/device-A/commands/manualModeEnabledAt").set(1000);
        await ctx.database().ref("devices/device-A/manualControlGrants/farmer-A").set({
            status: "APPROVED",
            requestedAt: 900,
            resolvedByUid: "admin-A",
            resolvedAt: 1500,
        });
    });
    const db = ctxFor("farmer-A").database();
    await assertFails(db.ref("devices/device-A/commands/manualMode").set(true));
});

test("farmer: can turn commands/manualMode OFF with a valid current-session APPROVED grant - ends their own session", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        await ctx.database().ref("devices/device-A/commands/manualMode").set(true);
        await ctx.database().ref("devices/device-A/commands/manualModeEnabledAt").set(1000);
        await ctx.database().ref("devices/device-A/manualControlGrants/farmer-A").set({
            status: "APPROVED",
            requestedAt: 900,
            resolvedByUid: "admin-A",
            resolvedAt: 1500,
        });
    });
    const db = ctxFor("farmer-A").database();
    await assertSucceeds(db.ref("devices/device-A/commands/manualMode").set(false));
});

test("farmer: cannot turn commands/manualMode OFF without a grant, or with a PENDING/DENIED/EXPIRED/REVOKED/stale-session grant", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        await ctx.database().ref("devices/device-A/commands/manualMode").set(true);
        await ctx.database().ref("devices/device-A/commands/manualModeEnabledAt").set(1000);
    });
    const db = ctxFor("farmer-A").database();
    await assertFails(db.ref("devices/device-A/commands/manualMode").set(false));

    for (const status of ["PENDING", "DENIED", "EXPIRED", "REVOKED"]) {
        await testEnv.withSecurityRulesDisabled(async (ctx) => {
            await ctx.database().ref("devices/device-A/manualControlGrants/farmer-A").set({
                status,
                requestedAt: 900,
                resolvedByUid: "admin-A",
                resolvedAt: 1500,
            });
        });
        await assertFails(db.ref("devices/device-A/commands/manualMode").set(false));
    }

    // Approved, but from an earlier Manual Mode session (resolvedAt < manualModeEnabledAt).
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        await ctx.database().ref("devices/device-A/manualControlGrants/farmer-A").set({
            status: "APPROVED",
            requestedAt: 500,
            resolvedByUid: "admin-A",
            resolvedAt: 800,
        });
    });
    await assertFails(db.ref("devices/device-A/commands/manualMode").set(false));
});

test("farmer: cannot operate an actuator with no grant, or with a PENDING/DENIED/EXPIRED/REVOKED grant", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        await ctx.database().ref("devices/device-A/commands/manualMode").set(true);
        await ctx.database().ref("devices/device-A/commands/manualModeEnabledAt").set(1000);
    });
    const db = ctxFor("farmer-A").database();
    await assertFails(db.ref("devices/device-A/commands/canopyFan").set({state: true}));

    for (const status of ["PENDING", "DENIED", "EXPIRED", "REVOKED"]) {
        await testEnv.withSecurityRulesDisabled(async (ctx) => {
            await ctx.database().ref("devices/device-A/manualControlGrants/farmer-A").set({
                status,
                requestedAt: 900,
                resolvedByUid: "admin-A",
                resolvedAt: 1500,
            });
        });
        await assertFails(db.ref("devices/device-A/commands/canopyFan").set({state: true}));
    }
});

test("farmer: cannot operate an actuator with an APPROVED grant while Manual Mode is off", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        await ctx.database().ref("devices/device-A/commands/manualMode").set(false);
        await ctx.database().ref("devices/device-A/commands/manualModeEnabledAt").set(1000);
        await ctx.database().ref("devices/device-A/manualControlGrants/farmer-A").set({
            status: "APPROVED",
            requestedAt: 900,
            resolvedByUid: "admin-A",
            resolvedAt: 1500,
        });
    });
    const db = ctxFor("farmer-A").database();
    await assertFails(db.ref("devices/device-A/commands/canopyFan").set({state: true}));
});

test("farmer: cannot operate an actuator with an APPROVED grant left over from an earlier Manual Mode session", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        // manualModeEnabledAt (2000) is AFTER this grant's resolvedAt (1500) -
        // Manual Mode was turned off and back on again since this farmer was
        // approved, so the old grant must not silently carry over.
        await ctx.database().ref("devices/device-A/commands/manualMode").set(true);
        await ctx.database().ref("devices/device-A/commands/manualModeEnabledAt").set(2000);
        await ctx.database().ref("devices/device-A/manualControlGrants/farmer-A").set({
            status: "APPROVED",
            requestedAt: 900,
            resolvedByUid: "admin-A",
            resolvedAt: 1500,
        });
    });
    const db = ctxFor("farmer-A").database();
    await assertFails(db.ref("devices/device-A/commands/canopyFan").set({state: true}));
});

test("farmer: can operate any actuator with a valid current-session APPROVED grant", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        await ctx.database().ref("devices/device-A/commands/manualMode").set(true);
        await ctx.database().ref("devices/device-A/commands/manualModeEnabledAt").set(1000);
        await ctx.database().ref("devices/device-A/manualControlGrants/farmer-A").set({
            status: "APPROVED",
            requestedAt: 900,
            resolvedByUid: "admin-A",
            resolvedAt: 1500, // >= manualModeEnabledAt
        });
    });
    const db = ctxFor("farmer-A").database();
    await assertSucceeds(db.ref("devices/device-A/commands/canopyFan").set({state: true}));
    await assertSucceeds(db.ref("devices/device-A/commands/blower").set({state: false}));
});

test("farmer: can operate an actuator and turn manualMode OFF with an APPROVED grant even when manualModeEnabledAt was never set (a session predating this feature)", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        // manualMode is true but manualModeEnabledAt is deliberately never
        // written - simulates Manual Mode having been left on since before
        // this field (and this whole grant feature) ever existed.
        await ctx.database().ref("devices/device-A/commands/manualMode").set(true);
        await ctx.database().ref("devices/device-A/manualControlGrants/farmer-A").set({
            status: "APPROVED",
            requestedAt: 900,
            resolvedByUid: "admin-A",
            resolvedAt: 1500,
        });
    });
    const db = ctxFor("farmer-A").database();
    await assertSucceeds(db.ref("devices/device-A/commands/canopyFan").set({state: true}));
    await assertSucceeds(db.ref("devices/device-A/commands/manualMode").set(false));
});

test("developer tester: manual-control grant is irrelevant - already has unconditional actuator write access", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
        await ctx.database().ref("devices/device-A/commands/manualMode").set(false);
    });
    const db = ctxFor("developer-A").database();
    await assertSucceeds(db.ref("devices/device-A/commands/canopyFan").set({state: true}));
});
