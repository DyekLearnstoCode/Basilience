---
trigger: always_on
---

This workspace contains the Basilience project.

Project Overview

Basilience is an integrated IoT system consisting of:

• Android application (Java)
• Firebase Realtime Database
• ESP32 firmware

The three components must always be treated as a single integrated system rather than independent applications.

Architecture

The Android application follows MVVM and the Repository Pattern.

Communication should generally follow:

UI

↓

ViewModel

↓

Repository

↓

Firebase

↓

ESP32

↓

Sensor / Actuator

↓

Firebase

↓

Repository

↓

ViewModel

↓

UI

Whenever a request involves communication, inspect the complete chain before making recommendations.

Implementation Requirements

Before generating code:

1. Inspect all relevant files.
2. Understand the existing architecture.
3. Identify affected modules.
4. Explain the communication flow.
5. List every file that will be modified.
6. Explain why each file changes.
7. Generate code only after the implementation plan is complete.

Never modify unrelated files.

Never duplicate business logic.

Never bypass MVVM.

Prefer extending existing classes rather than creating new ones unless architecturally necessary.

Communication Rules

Always verify:

• Firebase read paths
• Firebase write paths
• Firebase listeners
• JSON structures
• Data models
• Repository communication
• ESP32 command handling
• Sensor updates
• Actuator updates

Do not assume communication is working because Firebase accepted a write.

Trace every command from the Android UI to the ESP32 and back to the Android UI.

UI Consistency

The UI must represent the actual system state.

Do not treat a successful Firebase write as a successful actuator operation.

Distinguish between:

• Idle
• Sending
• Waiting for ESP
• Executing
• Confirmed
• Failed
• Timeout
• Offline

Loading indicators should remain active until the ESP32 confirms execution or a timeout/error occurs.

Verify that:

• Toggle states
• Status indicators
• Loading dialogs
• Notifications
• Snackbars
• Progress indicators

all reflect the actual device state.

Inspect for stale UI, duplicate observers, race conditions, incorrect state transitions, and synchronization issues.

Debugging

When debugging communication issues, inspect all related Android, Firebase, and ESP32 files before proposing a solution.

Follow the entire execution path rather than isolated methods.

Explain the root cause before suggesting fixes.

Code Generation

When producing code:

• Follow the existing project structure.
• Follow existing naming conventions.
• Preserve backward compatibility where practical.
• Keep implementations modular.
• Minimize the scope of changes.
• Reuse existing utilities, repositories, managers, and models whenever possible.

If information is insufficient, inspect additional files before making recommendations. Never invent missing implementations or behavior.

Repository Inspection Requirements

Before producing any analysis:

• Crawl the entire repository.
• Build a dependency graph of the project.
• Index all Android, Firebase, and ESP32 files.
• Follow imports, references, interfaces, inheritance, callbacks, listeners, and dependencies.
• Do not stop after finding the first relevant file.
• Continue traversing until the complete communication path has been verified.
• If a file references another class involved in communication, inspect that class as well.
• Continue recursively until the communication chain is complete.