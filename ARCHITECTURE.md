# Architecture

## Purpose

This project is a BLE data capture app. It scans for a device, opens a GATT session, subscribes to notifications, reconstructs packets from BLE fragments, writes raw packets to disk, and exposes session state to the UI.

The current implementation concentrates nearly all logic in `MainActivity`. That is the main structural problem. The goal of this document is to define the target architecture and the rules for refactoring so the codebase remains stable while multiple agents work on it.

## Platform Baseline

- `minSdk = 33` (Android 13+ only)
- No Android 12 or lower compatibility paths
- One BLE permission model only:
  - `BLUETOOTH_SCAN`
  - `BLUETOOTH_CONNECT`
- Use only modern Android 13+ GATT callback forms

This is intentional. Supporting Android 12 and below adds permission branching, deprecated callbacks, and more lifecycle edge cases without helping the current project goals.

## Design Principles

- No business logic in `Activity`.
- No protocol parsing inside Android framework callbacks.
- BLE stack access must be isolated behind a small interface.
- Packet parsing and statistics must be plain Kotlin and unit-testable.
- File writing and file sharing are separate responsibilities.
- UI reads state and emits intents; it does not own BLE operations directly.
- Every long-lived resource must have explicit lifecycle ownership and shutdown.
- Every bug fix in BLE/session/parsing code must include a test when technically possible.

## Target Module Structure

This project is currently a single `app` module. Refactoring should move toward smaller modules with explicit responsibilities.

### `app`

Android entry point and composition root only.

Responsibilities:
- `Application` / `Activity`
- dependency wiring
- navigation
- permission entry flow

Must not contain:
- packet parsing
- BLE session orchestration details
- file I/O logic
- long-running thread management

### `core:ble`

BLE transport and session management.

Responsibilities:
- scanning
- connect/disconnect
- service discovery
- notification subscription
- translating GATT callbacks into app-level events/state

Rules:
- no parsing of device payloads
- no file writes
- no direct UI state mutation
- one callback instance per scanner/session where identity matters
- all GATT statuses must be checked and surfaced

### `core:protocol`

Pure Kotlin packet framing and parsing.

Responsibilities:
- header detection
- fragment accumulation
- packet boundary detection
- length decoding
- counter extraction
- packet loss/statistics calculation
- sensor payload parsing for charting or downstream use

Rules:
- no Android imports
- deterministic input/output
- heavily unit-tested

### `core:storage`

Binary persistence and export support.

Responsibilities:
- open/close output file
- buffered writes
- flush policy
- exported file lookup
- shareable URI creation support

Rules:
- no BLE APIs
- no parsing logic
- explicit close/flush semantics

### `feature:device`

Device flow UI and presentation logic.

Responsibilities:
- screen state
- user intents
- view model / presenter
- rendering scan results, connection state, counters, actions

Rules:
- depends on abstractions, not concrete Android BLE calls
- no direct `BluetoothGatt` / `BluetoothAdapter` access

## State Boundaries

The app should converge on these boundaries:

- UI state:
  - scanning / idle
  - found devices
  - connecting
  - connected / disconnected
  - packet counters
  - export availability
  - visible errors

- BLE session state:
  - idle
  - scanning
  - connecting
  - connected
  - services discovered
  - notifications enabled
  - disconnected
  - failed

- protocol state:
  - incomplete fragment buffer
  - completed packet stream
  - counter continuity

No single class should own all three.

## Concurrency Rules

- No unmanaged threads started from `Activity`.
- Use a lifecycle-owned scope for app orchestration.
- If a queue or worker is required, it must have:
  - explicit owner
  - explicit startup point
  - explicit shutdown path
  - tests or at least deterministic behavior boundaries
- Shared mutable buffers must be owned by a small component with a narrow API.
- Background work must not update Compose state directly from random code paths.

## Android-Specific Rules

- Permission requests must go through one code path only.
- BLE operations must not run unless required permissions are granted.
- GATT callbacks must validate `status` before continuing.
- Null characteristics and descriptors must be handled explicitly.
- `startScan()` and `stopScan()` must use the same callback instance.
- File sharing must stay behind `FileProvider`; no relaxed StrictMode workarounds.
- Internal app storage is preferred unless there is a strong product reason otherwise.

## Testing Policy

### Required Unit Tests

`core:protocol` must have unit tests for:
- header detection
- fragmented packet reassembly
- handling garbage before header
- packet length validation
- counter extraction
- gap/loss calculation
- malformed/incomplete packet handling

BLE session logic must have unit tests for:
- scan start/stop transitions
- connect/disconnect transitions
- service discovery failure
- notification subscription failure
- duplicate/disordered callback handling where relevant

### Required Smoke Coverage

The project should have at least one smoke path covering:

1. permission granted
2. scan starts
3. device selected
4. GATT connects
5. services discovered
6. notifications enabled
7. packet received
8. file written
9. file share action available

This can begin as a lightweight fake-driven integration test before full instrumentation coverage exists.

## Refactor Rules For Agents

- Do not expand `MainActivity`.
- Do not introduce new logic that increases Android version branching.
- Prefer extracting pure Kotlin classes before changing behavior.
- Keep write ownership narrow:
  - protocol changes belong in protocol files
  - BLE changes belong in BLE files
  - UI changes belong in feature files
- Avoid cross-cutting edits unless necessary for the current step.
- If behavior changes, add or update tests in the same change.
- If a temporary adapter layer is needed during migration, keep it thin and mark it for deletion.

## Migration Plan

Refactoring should happen in this order:

1. Raise platform floor to Android 13+ and delete pre-13 compatibility code.
2. Extract packet framing, parsing, and counters from `MainActivity` into plain Kotlin classes.
3. Extract BLE scan/connect/subscribe logic into a session component behind an interface.
4. Extract file persistence into a storage component.
5. Introduce a view model or presenter for UI state.
6. Add smoke coverage for the happy path.
7. Remove leftover monolith code from `MainActivity`.

## Definition Of Done

A refactor step is only complete when:

- responsibilities are narrower than before
- behavior is unchanged or intentionally documented
- new boundaries are easier to test than the old ones
- tests cover the moved logic
- `MainActivity` becomes thinner, not thicker

