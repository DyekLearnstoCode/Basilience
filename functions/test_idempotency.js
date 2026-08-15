
const admin = require('firebase-admin');
const sinon = require('sinon');
let documents = {};
const firestoreMock = {
    collection: (col) => firestoreMock,
    doc: (id) => {
        return {
            collection: (c) => firestoreMock,
            set: async (data) => {
                documents[id] = data;
            },
            get: async () => ({ exists: !!documents[id], data: () => documents[id] })
        };
    },
    add: async () => {}
};
const messagingMock = {
    sendEachForMulticast: async () => ({ successCount: 1, failureCount: 0 })
};
admin.firestore = () => firestoreMock;
admin.messaging = () => messagingMock;

const { onAlertUpdated } = require('./index.js');

async function runTests() {
    documents = {}; // clear
    console.log('Invocation 1');
    const event1 = {
        id: 'evt_idem',
        params: { deviceId: 'test_dev' },
        data: {
            before: { val: () => ({ lowWater: false }) },
            after: { val: () => ({ lowWater: true }) }
        }
    };
    await onAlertUpdated.run(event1);
    console.log('Doc Count after 1:', Object.keys(documents).length);
    
    console.log('Invocation 2 (same event id)');
    await onAlertUpdated.run(event1);
    console.log('Doc Count after 2:', Object.keys(documents).length);
    
    console.log('Invocation 3 (different event id, same alert type)');
    const event2 = {
        id: 'evt_idem_2',
        params: { deviceId: 'test_dev' },
        data: {
            before: { val: () => ({ lowWater: false }) },
            after: { val: () => ({ lowWater: true }) }
        }
    };
    await onAlertUpdated.run(event2);
    console.log('Doc Count after 3:', Object.keys(documents).length);
}
runTests().catch(console.error);

