
const admin = require('firebase-admin');
const sinon = require('sinon');
// Mock admin before requiring index.js
const firestoreMock = {
    collection: () => firestoreMock,
    doc: () => firestoreMock,
    set: async () => {},
    add: async () => {}
};
const messagingMock = {
    sendEachForMulticast: async () => ({ successCount: 1, failureCount: 0 })
};
admin.firestore = () => firestoreMock;
admin.messaging = () => messagingMock;

const { onAlertUpdated, onStatusUpdated } = require('./index.js');

async function runTests() {
    console.log('Testing null -> true (Rising Edge)');
    const event1 = {
        id: 'evt_1',
        params: { deviceId: 'test_dev' },
        data: {
            before: { val: () => null },
            after: { val: () => ({ lowWater: true }) }
        }
    };
    const setSpy = sinon.spy(firestoreMock, 'set');
    const sendSpy = sinon.spy(messagingMock, 'sendEachForMulticast');
    await onAlertUpdated.run(event1);
    console.log('Test D (null -> true) Docs Set:', setSpy.callCount);
    console.log('Test D (null -> true) FCM Sent:', sendSpy.callCount);

    setSpy.resetHistory();
    sendSpy.resetHistory();

    console.log('Testing false -> true');
    const event2 = {
        id: 'evt_2',
        params: { deviceId: 'test_dev' },
        data: {
            before: { val: () => ({ lowWater: false }) },
            after: { val: () => ({ lowWater: true }) }
        }
    };
    await onAlertUpdated.run(event2);
    console.log('Test A (false -> true) Docs Set:', setSpy.callCount);
    
    setSpy.resetHistory();
    sendSpy.resetHistory();

    console.log('Testing true -> true');
    const event3 = {
        id: 'evt_3',
        params: { deviceId: 'test_dev' },
        data: {
            before: { val: () => ({ lowWater: true }) },
            after: { val: () => ({ lowWater: true }) }
        }
    };
    await onAlertUpdated.run(event3);
    console.log('Test B (true -> true) Docs Set:', setSpy.callCount);

    setSpy.resetHistory();
    sendSpy.resetHistory();

    console.log('Testing true -> false');
    const event4 = {
        id: 'evt_4',
        params: { deviceId: 'test_dev' },
        data: {
            before: { val: () => ({ lowWater: true }) },
            after: { val: () => ({ lowWater: false }) }
        }
    };
    await onAlertUpdated.run(event4);
    console.log('Test C (true -> false) Docs Set:', setSpy.callCount);

    setSpy.resetHistory();
    sendSpy.resetHistory();

    console.log('Testing reservoirLocked (false -> true)');
    const event5 = {
        id: 'evt_5',
        params: { deviceId: 'test_dev' },
        data: {
            before: { val: () => ({ reservoirLocked: false }) },
            after: { val: () => ({ reservoirLocked: true }) }
        }
    };
    await onStatusUpdated.run(event5);
    console.log('Test Phase 3 (reservoirLocked) Docs Set:', setSpy.callCount);
    
    setSpy.resetHistory();
    sendSpy.resetHistory();

    console.log('Testing safetyLock (false -> true)');
    const event6 = {
        id: 'evt_6',
        params: { deviceId: 'test_dev' },
        data: {
            before: { val: () => ({ safetyLock: false }) },
            after: { val: () => ({ safetyLock: true }) }
        }
    };
    await onStatusUpdated.run(event6);
    console.log('Test E (safetyLock false->true) Docs Set:', setSpy.callCount);

    setSpy.resetHistory();
    sendSpy.resetHistory();

    console.log('Testing safetyLock (true -> true) while unrelated field changes');
    const event7 = {
        id: 'evt_7',
        params: { deviceId: 'test_dev' },
        data: {
            before: { val: () => ({ safetyLock: true, other: 1 }) },
            after: { val: () => ({ safetyLock: true, other: 2 }) }
        }
    };
    await onStatusUpdated.run(event7);
    console.log('Test G (safetyLock true->true unrelated) Docs Set:', setSpy.callCount);

}
runTests().catch(console.error);

