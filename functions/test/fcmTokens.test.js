// Push-token storage tests: security rules for users/{uid}/fcmTokens/{installationId}
// and the Cloud Functions token lookup/cleanup in ../fcmTokens.js.
// Runs entirely against the local Firestore emulator (see package.json's
// "test:fcm" script) - never touches production data.

process.env.FIRESTORE_EMULATOR_HOST = process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8080";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");
const admin = require("firebase-admin");
const {
    initializeTestEnvironment,
    assertSucceeds,
    assertFails,
} = require("@firebase/rules-unit-testing");
const {doc, setDoc, deleteDoc, getDoc, getDocs, collection, serverTimestamp} = require("firebase/firestore");

const PROJECT_ID = "demo-basilience";
const RULES_PATH = path.join(__dirname, "..", "..", "firestore.rules");
const [HOST, PORT] = process.env.FIRESTORE_EMULATOR_HOST.split(":");

const INSTALL_A = "11111111-1111-4111-8111-111111111111";
const INSTALL_B = "22222222-2222-4222-8222-222222222222";
const TOKEN_A = "token-phone-A";
const TOKEN_B = "token-phone-B";

let testEnv;
let db;
let fcmTokens;

function freshModule() {
    delete require.cache[require.resolve("../fcmTokens")];
    return require("../fcmTokens");
}

test.before(async () => {
    testEnv = await initializeTestEnvironment({
        projectId: PROJECT_ID,
        firestore: {rules: fs.readFileSync(RULES_PATH, "utf8"), host: HOST, port: Number(PORT)},
    });
    if (!admin.apps.length) admin.initializeApp({projectId: PROJECT_ID});
    db = admin.firestore();
});

test.after(async () => {
    await testEnv.cleanup();
});

test.beforeEach(async () => {
    await testEnv.clearFirestore();
    fcmTokens = freshModule();
});

// --------------------------------------------------------------- helpers

function clientRecord(token, overrides = {}) {
    return {token, updatedAt: serverTimestamp(), platform: "android", ...overrides};
}

function tokenDoc(fs_, uid, installationId) {
    return doc(fs_, "users", uid, "fcmTokens", installationId);
}

async function seed(fn) {
    await testEnv.withSecurityRulesDisabled(async ctx => fn(ctx.firestore()));
}

async function seedFixture() {
    await seed(async fs_ => {
        await setDoc(doc(fs_, "users", "adminA"), {role: "ADMIN", ownerAdminUid: null});
        await setDoc(doc(fs_, "users", "farmerLinked"), {role: "FARMER", ownerAdminUid: "adminA"});
        await setDoc(doc(fs_, "users", "farmerUnlinked"), {role: "FARMER", ownerAdminUid: "someoneElse"});
        await setDoc(doc(fs_, "users", "stranger"), {role: "ADMIN", ownerAdminUid: null});
        await setDoc(doc(fs_, "devices", "dev1"), {ownerUid: "adminA"});
        await setDoc(doc(fs_, "deviceAssignments", "farmerLinked_dev1"), {deviceId: "dev1", userUid: "farmerLinked"});
        await setDoc(doc(fs_, "deviceAssignments", "farmerUnlinked_dev1"), {deviceId: "dev1", userUid: "farmerUnlinked"});
    });
}

async function putToken(uid, installationId, token) {
    await db.collection("users").doc(uid).collection("fcmTokens").doc(installationId)
        .set({token, updatedAt: admin.firestore.FieldValue.serverTimestamp(), platform: "android"});
}

async function tokenIds(uid) {
    const snap = await db.collection("users").doc(uid).collection("fcmTokens").get();
    return snap.docs.map(d => d.id).sort();
}

// --------------------------------------------------------------- security rules

test("rules: one installation registers its own record", async () => {
    const ctx = testEnv.authenticatedContext("adminA");
    await assertSucceeds(setDoc(tokenDoc(ctx.firestore(), "adminA", INSTALL_A), clientRecord(TOKEN_A)));
});

test("rules: two installations of the same Admin coexist", async () => {
    const ctx = testEnv.authenticatedContext("adminA");
    await assertSucceeds(setDoc(tokenDoc(ctx.firestore(), "adminA", INSTALL_A), clientRecord(TOKEN_A)));
    await assertSucceeds(setDoc(tokenDoc(ctx.firestore(), "adminA", INSTALL_B), clientRecord(TOKEN_B)));
    assert.deepEqual(await tokenIds("adminA"), [INSTALL_A, INSTALL_B]);
});

test("rules: updating phone B does not alter phone A", async () => {
    const ctx = testEnv.authenticatedContext("adminA");
    await assertSucceeds(setDoc(tokenDoc(ctx.firestore(), "adminA", INSTALL_A), clientRecord(TOKEN_A)));
    await assertSucceeds(setDoc(tokenDoc(ctx.firestore(), "adminA", INSTALL_B), clientRecord(TOKEN_B)));
    await assertSucceeds(setDoc(tokenDoc(ctx.firestore(), "adminA", INSTALL_B), clientRecord("token-phone-B-rotated")));
    const a = await db.doc(`users/adminA/fcmTokens/${INSTALL_A}`).get();
    const b = await db.doc(`users/adminA/fcmTokens/${INSTALL_B}`).get();
    assert.equal(a.get("token"), TOKEN_A);
    assert.equal(b.get("token"), "token-phone-B-rotated");
});

test("rules: logging out B (deleting B's record) leaves A registered", async () => {
    const ctx = testEnv.authenticatedContext("adminA");
    await assertSucceeds(setDoc(tokenDoc(ctx.firestore(), "adminA", INSTALL_A), clientRecord(TOKEN_A)));
    await assertSucceeds(setDoc(tokenDoc(ctx.firestore(), "adminA", INSTALL_B), clientRecord(TOKEN_B)));
    await assertSucceeds(deleteDoc(tokenDoc(ctx.firestore(), "adminA", INSTALL_B)));
    assert.deepEqual(await tokenIds("adminA"), [INSTALL_A]);
});

test("rules: deleting a record that does not exist is allowed (logout is idempotent)", async () => {
    const ctx = testEnv.authenticatedContext("adminA");
    await assertSucceeds(deleteDoc(tokenDoc(ctx.firestore(), "adminA", INSTALL_B)));
});

test("rules: a user cannot write or delete another user's token records", async () => {
    await putToken("adminA", INSTALL_A, TOKEN_A);
    const other = testEnv.authenticatedContext("stranger");
    await assertFails(setDoc(tokenDoc(other.firestore(), "adminA", INSTALL_B), clientRecord(TOKEN_B)));
    await assertFails(setDoc(tokenDoc(other.firestore(), "adminA", INSTALL_A), clientRecord("hijacked")));
    await assertFails(deleteDoc(tokenDoc(other.firestore(), "adminA", INSTALL_A)));
    assert.equal((await db.doc(`users/adminA/fcmTokens/${INSTALL_A}`).get()).get("token"), TOKEN_A);
});

test("rules: even an Admin cannot touch another user's token records", async () => {
    await seedFixture();
    await putToken("farmerLinked", INSTALL_A, TOKEN_A);
    const admin_ = testEnv.authenticatedContext("adminA");
    await assertFails(setDoc(tokenDoc(admin_.firestore(), "farmerLinked", INSTALL_B), clientRecord(TOKEN_B)));
    await assertFails(deleteDoc(tokenDoc(admin_.firestore(), "farmerLinked", INSTALL_A)));
});

test("rules: signed-out clients are refused", async () => {
    const anon = testEnv.unauthenticatedContext();
    await assertFails(setDoc(tokenDoc(anon.firestore(), "adminA", INSTALL_A), clientRecord(TOKEN_A)));
    await assertFails(deleteDoc(tokenDoc(anon.firestore(), "adminA", INSTALL_A)));
});

test("rules: malformed records are refused", async () => {
    const ctx = testEnv.authenticatedContext("adminA");
    const fs_ = ctx.firestore();
    await assertFails(setDoc(tokenDoc(fs_, "adminA", "not-a-uuid"), clientRecord(TOKEN_A)));
    await assertFails(setDoc(tokenDoc(fs_, "adminA", INSTALL_A), clientRecord("")));
    await assertFails(setDoc(tokenDoc(fs_, "adminA", INSTALL_A), clientRecord(TOKEN_A, {platform: "ios"})));
    await assertFails(setDoc(tokenDoc(fs_, "adminA", INSTALL_A), clientRecord(TOKEN_A, {extra: "x"})));
    await assertFails(setDoc(tokenDoc(fs_, "adminA", INSTALL_A), {token: TOKEN_A, platform: "android"}));
    await assertFails(setDoc(tokenDoc(fs_, "adminA", INSTALL_A), clientRecord(TOKEN_A, {updatedAt: new Date(0)})));
    await assertFails(setDoc(tokenDoc(fs_, "adminA", INSTALL_A), clientRecord(123)));
});

test("rules: clients cannot read token records back", async () => {
    await putToken("adminA", INSTALL_A, TOKEN_A);
    const ctx = testEnv.authenticatedContext("adminA");
    await assertFails(getDoc(tokenDoc(ctx.firestore(), "adminA", INSTALL_A)));
    await assertFails(getDocs(collection(ctx.firestore(), "users", "adminA", "fcmTokens")));
});

// --------------------------------------------------------------- Cloud Functions lookup

test("functions: both of an Admin's phones are returned, once each", async () => {
    await seedFixture();
    await putToken("adminA", INSTALL_A, TOKEN_A);
    await putToken("adminA", INSTALL_B, TOKEN_B);
    const tokens = await fcmTokens.getDeviceUserTokens(db, "dev1");
    assert.deepEqual([...tokens].sort(), [TOKEN_A, TOKEN_B]);
});

test("functions: a legacy field equal to a new record is sent only once", async () => {
    await seedFixture();
    await db.collection("users").doc("adminA").update({fcmToken: TOKEN_B}); // migrated phone B still has the legacy copy
    await putToken("adminA", INSTALL_A, TOKEN_A);
    await putToken("adminA", INSTALL_B, TOKEN_B);
    const tokens = await fcmTokens.getDeviceUserTokens(db, "dev1");
    assert.deepEqual([...tokens].sort(), [TOKEN_A, TOKEN_B]);
    assert.equal(tokens.filter(t => t === TOKEN_B).length, 1);
});

test("functions: a legacy-only user (not yet migrated) still receives pushes", async () => {
    await seedFixture();
    await db.collection("users").doc("adminA").update({fcmToken: "legacy-only"});
    const tokens = await fcmTokens.getDeviceUserTokens(db, "dev1");
    assert.deepEqual(tokens, ["legacy-only"]);
});

test("functions: legacy plus a different new token are both sent", async () => {
    await seedFixture();
    await db.collection("users").doc("adminA").update({fcmToken: "legacy-phone"});
    await putToken("adminA", INSTALL_A, TOKEN_A);
    const tokens = await fcmTokens.getDeviceUserTokens(db, "dev1");
    assert.deepEqual([...tokens].sort(), [TOKEN_A, "legacy-phone"].sort());
});

test("functions: authorization is preserved (linked Farmer included, unlinked and unassigned excluded)", async () => {
    await seedFixture();
    await putToken("adminA", INSTALL_A, TOKEN_A);
    await putToken("farmerLinked", INSTALL_A, "farmer-phone-1");
    await putToken("farmerLinked", INSTALL_B, "farmer-phone-2");
    await putToken("farmerUnlinked", INSTALL_A, "unlinked-farmer-phone");
    await putToken("stranger", INSTALL_A, "stranger-phone");
    const tokens = await fcmTokens.getDeviceUserTokens(db, "dev1");
    assert.deepEqual([...tokens].sort(), [TOKEN_A, "farmer-phone-1", "farmer-phone-2"].sort());
});

test("functions: the same token held by two records is returned once", async () => {
    await seedFixture();
    await putToken("adminA", INSTALL_A, TOKEN_A);
    await putToken("farmerLinked", INSTALL_A, TOKEN_A);
    const tokens = await fcmTokens.getDeviceUserTokens(db, "dev1");
    assert.deepEqual(tokens, [TOKEN_A]);
});

test("functions: no tokens means an empty list", async () => {
    await seedFixture();
    assert.deepEqual(await fcmTokens.getDeviceUserTokens(db, "dev1"), []);
    assert.deepEqual(await fcmTokens.getDeviceUserTokens(db, "missing-device"), []);
});

// --------------------------------------------------------------- invalid-token cleanup

test("cleanup: an invalid token B removes only B's record and leaves A untouched", async () => {
    await seedFixture();
    await putToken("adminA", INSTALL_A, TOKEN_A);
    await putToken("adminA", INSTALL_B, TOKEN_B);
    await fcmTokens.getDeviceUserTokens(db, "dev1"); // the send gathers tokens first
    const removed = await fcmTokens.removeInvalidToken(db, TOKEN_B);
    assert.equal(removed.installationRecords, 1);
    assert.deepEqual(await tokenIds("adminA"), [INSTALL_A]);
    assert.equal((await db.doc(`users/adminA/fcmTokens/${INSTALL_A}`).get()).get("token"), TOKEN_A);
});

test("cleanup: the legacy field is cleared only when it holds the invalid token", async () => {
    await seedFixture();
    await db.collection("users").doc("adminA").update({fcmToken: TOKEN_B});
    await putToken("adminA", INSTALL_A, TOKEN_A);
    await putToken("adminA", INSTALL_B, TOKEN_B);
    await fcmTokens.getDeviceUserTokens(db, "dev1");
    await fcmTokens.removeInvalidToken(db, TOKEN_B);
    assert.equal((await db.collection("users").doc("adminA").get()).get("fcmToken"), undefined);
    assert.deepEqual(await tokenIds("adminA"), [INSTALL_A]);

    // A different legacy value is left alone when A's token is the invalid one.
    await db.collection("users").doc("adminA").update({fcmToken: "someone-else"});
    await fcmTokens.getDeviceUserTokens(db, "dev1");
    await fcmTokens.removeInvalidToken(db, TOKEN_A);
    assert.equal((await db.collection("users").doc("adminA").get()).get("fcmToken"), "someone-else");
});

test("cleanup: a record whose token was refreshed meanwhile is not deleted", async () => {
    await seedFixture();
    await putToken("adminA", INSTALL_B, TOKEN_B);
    await fcmTokens.getDeviceUserTokens(db, "dev1");
    await putToken("adminA", INSTALL_B, "token-phone-B-rotated"); // phone B refreshed its token after the send began
    const removed = await fcmTokens.removeInvalidToken(db, TOKEN_B);
    assert.equal(removed.installationRecords, 0);
    assert.deepEqual(await tokenIds("adminA"), [INSTALL_B]);
});

test("cleanup: an instance that did not gather the token finds the record by token", async () => {
    await seedFixture();
    await putToken("adminA", INSTALL_A, TOKEN_A);
    await putToken("adminA", INSTALL_B, TOKEN_B);
    const coldInstance = freshModule(); // no in-memory record of who held which token
    const removed = await coldInstance.removeInvalidToken(db, TOKEN_B);
    assert.equal(removed.installationRecords, 1);
    assert.deepEqual(await tokenIds("adminA"), [INSTALL_A]);
});
