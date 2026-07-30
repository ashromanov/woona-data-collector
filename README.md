# Woona Android

Android 12+ app for dog profiles, BLE/replay recordings, optional synchronized
video, local SQLite metadata, export, and Google Drive backup.

## Setup

Install JDK 17 or newer, then install the project SDK packages:

```bash
./tools/setup-android-sdk.sh
```

The script installs the official command-line SDK in
`$ANDROID_SDK_ROOT`, `$ANDROID_HOME`, or `~/Android/Sdk` and creates the ignored
`local.properties`.

## Build and test

```bash
./tools/check.sh fast
./tools/check.sh full
```

`fast` runs JVM tests, lint, builds the debug APK, and verifies its debug
signature. `full` also runs instrumentation tests on the managed Pixel 2 /
Google APIs 31 emulator. Gradle creates, cleans, and stops that device.

The APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The managed-device task can also be run directly:

```bash
./gradlew woonaApi31DebugAndroidTest
```

## Manual emulator launch

Create a reusable API 31 emulator once:

```bash
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
"$sdk_root/cmdline-tools/latest/bin/avdmanager" create avd \
  --force --name woona-manual-api31 \
  --package "system-images;android-31;google_apis;x86_64" \
  --device "pixel_2"
```

Start it, build/install the app, and open the main activity:

```bash
"$sdk_root/emulator/emulator" -avd woona-manual-api31 &
"$sdk_root/platform-tools/adb" wait-for-device
./gradlew installDebug
"$sdk_root/platform-tools/adb" shell am start \
  -n com.woona.drivetest/com.example.myapplication.MainActivity
```

An emulator validates UI, SQLite, and fake-BLE flows only. Real BLE, camera
output, video quality, and hardware synchronization require a physical Android
device.
