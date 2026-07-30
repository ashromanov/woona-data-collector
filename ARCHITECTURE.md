# Architecture

## Scope

Woona is a single-module Android 12+ application for dog profiles, BLE sensor
recordings, replay, optional video capture, local export, and Google Drive
backup. The project intentionally stays in one `app` module; package boundaries
provide enough separation for the current size.

## Application flow

`MainActivity` is the Android composition and lifecycle owner. It:

- restores language, theme, BLE, profile, and Drive preferences;
- requires a dog profile before scanning or recording;
- collects a session questionnaire before live capture and optionally before
  replay;
- creates the recording row, then delegates capture to
  `DeviceFeatureController`;
- renders the Compose app shell and profile dialogs;
- prepares local sharing and Drive backup after capture.

`DeviceFeatureModule` wires the BLE session, packet processor, UI state holder,
replay controller, file stores, database, and video recorder. Production code
and fake-driven tests use the same controller boundaries.

## Boundaries

- `ble/`: Android BLE scan, GATT connection, notification subscription,
  transport profiles, and session state. It emits fragments and state; it does
  not parse packets or render UI.
- `protocol/`: deterministic packet assembly, validation, sensor parsing, and
  loss statistics. Keep it plain Kotlin and Android-free.
- `feature/device/`: capture/replay orchestration and observable UI state. It
  coordinates the other boundaries but does not implement GATT or file formats.
- `storage/`: packet, raw-fragment, diagnostic, CSV, ZIP, and `FileProvider`
  handling. It must not own BLE behavior.
- `data/`: dog profiles, recording lifecycle, artifacts, SQLite migrations,
  questionnaire JSON, and synchronization metadata.
- `profile/`, `AppShell.kt`, and `DeviceScreen.kt`: Compose UI. UI emits actions
  and renders state; persistence and hardware work stay outside composables.
- `video/`: Camera2/MediaRecorder ownership for optional `video.mp4`.
- `drive/`: authorization, queued WorkManager uploads, and manual Drive saves.

## Data model and files

`WoonaDatabase` stores `filesDir/Woona/woona.sqlite`, enables foreign keys, and
uses schema version 2:

- `dog_profiles`: reusable dog questionnaire;
- `recordings`: profile link, `live`/`replay` source, lifecycle status,
  timestamps, timezone, session questionnaire, and relative directory;
- `artifacts`: typed files belonging to a recording.

Recording files live under:

```text
filesDir/Woona/recordings/<profile-id>/<local-date>/<recording-id>/
```

Possible artifacts are `packets.bin`, `raw_fragments.binlog`,
`diagnostics.log`, `channel.csv`, `video.mp4`, and `sync.json`. Exports use
snapshots so capture files are not read while they are still being written.
ZIP manifests include profile, session, recording, and synchronization
metadata.

## Recording lifecycle

1. The selected profile and session questionnaire create a `preparing` row.
2. Live BLE readiness or replay start marks the row `recording`.
3. The packet processor writes artifacts and publishes batched UI updates.
4. Optional video records into the same directory.
5. Disconnect, replay completion, error, pause, or shutdown finalizes the row
   as `completed`, `failed`, or `interrupted` and registers existing artifacts.
6. On application start, unfinished rows are marked `interrupted`.

## Video synchronization

At BLE capture readiness or replay start, the controller samples wall-clock time
around `SystemClock.elapsedRealtimeNanos()` and stores a sensor clock anchor.
For live recordings, the video recorder reports first-frame monotonic and camera
timestamps. `sync.json` stores their offset, timing uncertainty, timestamp
source, camera properties, and stop metadata. Writes are atomic where the
filesystem supports atomic moves.

This aligns artifacts on one monotonic timeline; it is not proof of physical
sensor/camera synchronization quality.

## Testing

- JVM tests cover protocol, state, storage/export, Drive, path, and timing logic.
- Instrumentation tests cover Compose navigation/insets, SQLite, preferences,
  and a fake-driven BLE capture path.
- `woonaApi31DebugAndroidTest` runs instrumentation tests on the Gradle-managed
  Pixel 2 / API 31 Google image.
- Real BLE transport, camera behavior, `video.mp4` quality, and hardware timing
  still require a physical Android device.
