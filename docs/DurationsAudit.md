# App and Whole-System Timing Audit (2026-09-15)

This follows on from an earlier check of the main grow-chamber firmware's own timers. This part extends the same kind of check to the Android app, the cloud backend (Cloud Functions) that connects everything, and the separate Harvest Scale device, since all three depend on their timing numbers actually agreeing with each other and with the firmware.

## The heartbeat interval and offline timeout, both retuned

The main firmware used to send a fresh sensor reading to the cloud every 10 seconds. At some point that was intentionally sped up to every 5 seconds, and the firmware's own code says so directly.

Three other places in the system were never updated to match that change, and all three still assumed the old 10 second number:

- The app's own setting for how often it expects to hear from a device, called `HEARTBEAT_INTERVAL_MS`, was set to 10 seconds. This controls how soon the app shows a device as "Reconnecting."
- The cloud backend's own note explaining why it waits 40 seconds before deciding a device has gone offline said "Firmware publishes sensors every 10 seconds," and its math (three missed heartbeats plus a jitter margin) was built on that number.
- The app kept its own copy of that same 40 second offline number, on purpose, so it lines up with the backend's.

**Fixed, all three:**

- `HEARTBEAT_INTERVAL_MS` in the app is now 5 seconds instead of 10, so "Reconnecting" shows up after about 10 seconds of silence instead of 20.
- The backend's offline cutoff is now 30 seconds instead of 40, and the comment explaining it has been rewritten to use the real numbers.
- The app's own copy of that same cutoff was brought down to 30 seconds too, to stay in step with the backend.

**Why 30 seconds, and not something closer to 15 or 25.** Simply scaling the old "three missed heartbeats" math down to the new 5 second cadence would suggest a much smaller number, something in the 25 second range. That would cut it too close to a second, separate limit that has nothing to do with heartbeat cadence: the firmware itself can take up to 20 seconds to reconnect to Wi-Fi on its own after a normal, brief drop, and that 20 second figure was checked and is still accurate. If the offline cutoff were set too close to that 20 seconds, a completely normal Wi-Fi hiccup could get mistaken for the device actually being offline. Thirty seconds keeps a real 10 second cushion above that 20 second reconnect time, while still being noticeably faster than the old 40 seconds, and still comfortably covers several real missed heartbeats at the true 5 second cadence.

## A wrong comment, fixed

The app had a comment next to one of its own fallback timers that said the firmware's own sensor settling wait is "10 seconds." The real firmware value is 1 minute. The actual 3 second value the app times out after was not affected by this mistake, since it is correctly matched to a different firmware number. Only the wrong number written in the comment has been corrected.

## Checked and confirmed still correct

These are numbers that exist in more than one place across the app, the backend, and the firmware, and were checked against each other. All of them still agree:

- The app's 90 second "Stabilizing" loader for pH and EC matches the firmware's own 90 second settling wait.
- The app's 5 minute timeout while waiting for an operation (like a correction or a refill) to finish matches the firmware's own 5 minute operation limit exactly. The app's own code comment says this was done on purpose.
- The app's 15 minute freshness window for its "Water Outlook" refill estimate is deliberately set to 3 times the backend's own 5 minute sensor-logging interval, and both sides still agree on that.
- The rule against logging the same harvest twice too quickly is enforced in two separate places, the app and the database's own security rules, and both still agree on a 60 second window.

## A small duplicate worth knowing about

The device list screen has its own copy of a 2 second refresh timer that is meant to match a value already defined once elsewhere in the connection-tracking code. It is typed out separately instead of sharing that one definition. Both currently say 2 seconds, so nothing is wrong today. If one of them is ever changed without the other, they would quietly stop agreeing. Not fixed, since this is a minor cleanup rather than a live problem.

## Harvest Scale (the separate weighing device)

This device was checked on its own, since it is a completely different piece of hardware with its own firmware.

**Fixed:** a leftover comment in its main file said its warm-up wait was 10 seconds. The real, correct value, used everywhere else including its own guide document, is 30 seconds. The comment has been corrected.

**Worth knowing, not fixed:** the harvest scale never sends the kind of regular "I'm still here" signal that the main grow-chamber device sends, the one the backend actually uses to decide whether a device is online or offline. Right now this causes no visible problem, since the app does not show an online or offline indicator for the harvest scale anywhere, only its most recent weight. But if an online/offline indicator is ever added for the scale, it will not work correctly until the scale is either wired into that same signal or given its own separate one.

## Summary

Two leftover wrong comments were corrected: the harvest scale's warm-up comment, and the app's sensor-timeout comment.

One real design drift was found and retuned: the app and the backend both still assumed a 10 second heartbeat from the firmware, when the real cadence has been 5 seconds for a while. The app's heartbeat setting, the backend's offline cutoff, and the app's own copy of that cutoff have all been updated to reflect the real 5 second cadence, while still keeping a safe margin above the firmware's own 20 second Wi-Fi reconnect time so a normal brief drop is never mistaken for the device going offline.

Everything else checked, including several other timing numbers that are duplicated across the app, the backend, and the firmware on purpose, was found to still be correctly in agreement.

## Deploying this change

Editing `functions/index.js` only changes the source file. The new offline-timeout numbers do not take effect on the live backend until that function is redeployed (for example with `firebase deploy --only functions`), which has not been done as part of this audit.
