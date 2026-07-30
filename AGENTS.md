# Agent rules

- This workspace is Android-only. Do not add iOS, cross-platform scaffolding,
  remotes, or CI unless explicitly requested.
- Inspect `git status` first. Preserve unrelated dirty-worktree changes and keep
  diffs minimal.
- Keep the current single `app` module. Reuse existing code and dependencies
  before adding abstractions or libraries.
- Respect boundaries: `ble` owns transport/GATT, `protocol` owns pure packet
  logic, `storage` owns files/exports, `data` owns SQLite and recording metadata,
  and Compose UI only renders state and emits actions.
- Every behavior change needs the smallest relevant runnable test. Run
  `./tools/check.sh fast`; run `full` for SQLite, Compose, preferences, or
  fake-BLE instrumentation changes.
- Preserve explicit shutdown/finalization for BLE, replay, packet writers,
  video, and recording state. Do not trade data safety for a smaller diff.
- Emulator tests never prove real BLE behavior, camera behavior, `video.mp4`
  quality, or hardware synchronization. Report those as physical-device checks.
