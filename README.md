# AR Control

Native Android app for `XREAL One Pro` camera preview and recording on `Huawei P60 Pro`.

The project does not use the XREAL Unity SDK. It uses:

- vendored `one-xr` for the glasses session/control path
- vendored `UVCCamera` for USB/UVC preview
- direct USB/HID camera enable logic in this repo
- app-owned recording, clip history, diagnostics, and crash recovery

## What the app does

- Connects to `XREAL One Pro` natively from Android.
- Enables the glasses camera through USB/HID.
- Shows live camera preview in the phone UI.
- Switches preview into immersive fullscreen.
- Supports fit-center preview with black bars.
- Supports preview zoom with hardware volume keys.
- Optionally records preview sessions to MP4.
- Persists a history of recorded clips.
- Opens, shares, and deletes recorded clips.
- Preserves diagnostics across crashes and can launch into a guarded recovery screen.

## Current target

- Phone: `Huawei P60 Pro`
- Glasses: `XREAL One Pro`
- Android baseline: `minSdk 31`, `targetSdk 36`
- Toolchain: `JDK 17`

The app is optimized for the hardware above first. Other devices may work, but they are not the current support target.

## Important decisions

### No `ControlGlasses` dependency

The shipped app does not require the `ControlGlasses` companion app.

The project uses the native network path from vendored `one-xr` and direct USB/UVC camera access instead of the official Unity/XREAL SDK flow.

### Vendored dependencies instead of remote runtime/build dependence

The repo vendors:

- [`vendor/onexr`](vendor/onexr)
- [`vendor/uvccamera`](vendor/uvccamera)

This keeps builds reproducible and lets the app own fixes at the hardware boundary.

### Camera enable and camera streaming are separate concerns

The app treats:

- glasses session/control
- USB permission
- HID camera enable
- UVC preview/recording

as separate layers with separate failure handling and logging.

### Recording is tied to preview lifecycle

When `Record video` is enabled:

- recording starts automatically after preview starts
- recording stops automatically when preview stops or fullscreen exits

There is no independent in-preview record button.

### Recordings are app-owned

Clips are stored in app-scoped storage and indexed by a local JSON catalog.

This keeps delete/share behavior predictable and avoids `MediaStore` complexity for milestone 1.

### Crash recovery is a first-class feature

After abnormal termination during preview or recording:

- the next launch can be intercepted by `LaunchGuardActivity`
- diagnostics remain shareable
- the app can enter safe mode
- camera and recording can be temporarily disabled until the user explicitly re-enables testing

### The recording path uses frame callbacks, not `startCapture(surface)`

The project previously explored the UVC capture-surface path and moved away from it for stability.

Current recording is based on:

- `UVCCamera.setFrameCallback(...)`
- app-side `MediaCodec` + `MediaMuxer`

### Native UVC callback pixel format had to be corrected locally

The vendored `UVCCamera` callback mapping in `UVCPreview.cpp` was patched in this repo so `YUV420SP` and `NV21` map to the correct conversion functions for recorded video color fidelity.

## Architecture

## App entry and startup safety

- [`app/src/main/java/com/example/ar_control/startup/LaunchGuardActivity.kt`](app/src/main/java/com/example/ar_control/startup/LaunchGuardActivity.kt)
  Launcher activity. Intercepts launch after abnormal termination and gives access to shareable diagnostics before opening the main app.
- [`app/src/main/java/com/example/ar_control/ArControlApp.kt`](app/src/main/java/com/example/ar_control/ArControlApp.kt)
  Application class. Owns lazy container bootstrap and persistent diagnostics setup.
- [`app/src/main/java/com/example/ar_control/recovery/RecoveryManager.kt`](app/src/main/java/com/example/ar_control/recovery/RecoveryManager.kt)
  Tracks abnormal termination, safe mode, and broken clip cleanup.

## Dependency wiring

- [`app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt`](app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt)
  Central composition root for:
  - `GlassesSession`
  - USB/HID transport
  - UVC camera source
  - recording preferences
  - clip repository
  - video recorder
  - recovery manager
  - diagnostics

## Glasses session and control

- [`app/src/main/java/com/example/ar_control/xreal/GlassesSession.kt`](app/src/main/java/com/example/ar_control/xreal/GlassesSession.kt)
  App-facing interface.
- [`app/src/main/java/com/example/ar_control/xreal/OneXrGlassesSession.kt`](app/src/main/java/com/example/ar_control/xreal/OneXrGlassesSession.kt)
  Wraps vendored `one-xr`.
- [`app/src/main/java/com/example/ar_control/xreal/ProductionOneXrFacade.kt`](app/src/main/java/com/example/ar_control/xreal/ProductionOneXrFacade.kt)
  Binds app code to vendored library behavior.

This path uses the glasses’ link-local network route and is independent from the camera streaming path.

## USB/HID camera enable

- [`app/src/main/java/com/example/ar_control/usb/AndroidUsbPermissionGateway.kt`](app/src/main/java/com/example/ar_control/usb/AndroidUsbPermissionGateway.kt)
  Owns USB permission requests and Huawei-specific fallback behavior.
- [`app/src/main/java/com/example/ar_control/usb/AndroidHidTransport.kt`](app/src/main/java/com/example/ar_control/usb/AndroidHidTransport.kt)
  Sends the HID command sequence that enables the camera/UVC path.
- [`app/src/main/java/com/example/ar_control/usb/AndroidEyeUsbConfigurator.kt`](app/src/main/java/com/example/ar_control/usb/AndroidEyeUsbConfigurator.kt)
  Coordinates the enable flow and waits for re-enumeration.

## Camera preview

- [`app/src/main/java/com/example/ar_control/camera/UvcCameraSource.kt`](app/src/main/java/com/example/ar_control/camera/UvcCameraSource.kt)
  App-facing preview start/stop and recording capture boundary.
- [`app/src/main/java/com/example/ar_control/camera/AndroidUvcLibraryAdapter.kt`](app/src/main/java/com/example/ar_control/camera/AndroidUvcLibraryAdapter.kt)
  Main USB/UVC adapter over vendored `UVCCamera`.
- [`app/src/main/java/com/example/ar_control/camera/UvcPreviewConfigurator.kt`](app/src/main/java/com/example/ar_control/camera/UvcPreviewConfigurator.kt)
  Selects preview size and preview format.

Preview rendering is hosted in:

- [`app/src/main/java/com/example/ar_control/MainActivity.kt`](app/src/main/java/com/example/ar_control/MainActivity.kt)
- [`app/src/main/java/com/example/ar_control/preview/AspectRatioTextureView.kt`](app/src/main/java/com/example/ar_control/preview/AspectRatioTextureView.kt)
- [`app/src/main/java/com/example/ar_control/preview/PreviewTransformCalculator.kt`](app/src/main/java/com/example/ar_control/preview/PreviewTransformCalculator.kt)

## UI state and screen behavior

- [`app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt`](app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt)
  Main coordinator for:
  - glasses session state
  - camera enable
  - preview start/stop
  - zoom state
  - recording state
  - clip list state
  - safe mode state

`MainActivity` hosts two major modes:

- control screen
- immersive fullscreen preview

Fullscreen preview behavior:

- fit-center with black bars
- small in-preview back button
- hardware back exits preview
- volume up/down zoom the preview

## Recording and clip storage

- [`app/src/main/java/com/example/ar_control/recording/MediaCodecVideoRecorder.kt`](app/src/main/java/com/example/ar_control/recording/MediaCodecVideoRecorder.kt)
  Encodes app-owned MP4 files using `MediaCodec` and `MediaMuxer`.
- [`app/src/main/java/com/example/ar_control/recording/SharedPreferencesRecordingPreferences.kt`](app/src/main/java/com/example/ar_control/recording/SharedPreferencesRecordingPreferences.kt)
  Persists the `Record video` checkbox state.
- [`app/src/main/java/com/example/ar_control/recording/JsonClipRepository.kt`](app/src/main/java/com/example/ar_control/recording/JsonClipRepository.kt)
  Stores clip metadata in a JSON catalog.
- [`app/src/main/java/com/example/ar_control/recording/AndroidClipFileSharer.kt`](app/src/main/java/com/example/ar_control/recording/AndroidClipFileSharer.kt)
  Creates `FileProvider` URIs for open/share actions.

Clip files are stored under app-scoped movies storage:

- `externalFilesDir(Environment.DIRECTORY_MOVIES)/recordings/`

The clip catalog is stored alongside recordings as JSON.

## Diagnostics and recovery

- [`app/src/main/java/com/example/ar_control/diagnostics/SessionLog.kt`](app/src/main/java/com/example/ar_control/diagnostics/SessionLog.kt)
  Persistent session/event log.
- [`app/src/main/java/com/example/ar_control/diagnostics/DiagnosticsReportBuilder.kt`](app/src/main/java/com/example/ar_control/diagnostics/DiagnosticsReportBuilder.kt)
  Builds human-shareable reports from current UI state plus session logs.
- [`app/src/main/java/com/example/ar_control/startup/LaunchDiagnosticsLoader.kt`](app/src/main/java/com/example/ar_control/startup/LaunchDiagnosticsLoader.kt)
  Builds launch-time diagnostics when the app previously crashed.

The app’s `Share Logs` flow is the primary diagnostics surface. `adb logcat` is useful for platform-level context, but the most important app-specific state is in the shared in-app report.

## Project structure

```text
app/
  src/main/java/com/example/ar_control/
    camera/        UVC preview and camera source boundaries
    diagnostics/   session log and diagnostics reports
    di/            app container and object graph
    preview/       TextureView transform and fit-center helpers
    recording/     MP4 recording, clip repository, share/open helpers
    recovery/      crash recovery and safe mode
    startup/       guarded launcher and launch diagnostics
    ui/            ViewModel, UI state, clip adapter
    usb/           USB permission, HID transport, camera enable flow
    xreal/         glasses session wrapper over vendored one-xr
vendor/
  onexr/           vendored native glasses-session dependency
  uvccamera/       vendored USB/UVC camera stack with local fixes
docs/
  hardware/        device bring-up and validation runbooks
  superpowers/     design specs and implementation plans from development
```

## Build and run

## Debug build

From the repo root:

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK:

- `app/build/outputs/apk/debug/app-debug.apk`

## Release build

```powershell
.\gradlew.bat :app:assembleRelease
```

Release APK:

- `app/build/outputs/apk/release/app-release.apk`

Release signing is configured through:

- [`keystore.properties`](keystore.properties)
- [`signing/`](signing)

## Install on device

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.ar_control/.startup.LaunchGuardActivity
```

`LaunchGuardActivity` is the launcher now, not `MainActivity`.

## Verification commands

Primary verification command:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleAndroidTest :app:assembleRelease
```

APK signature verification:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0\apksigner.bat" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Emulator vs real hardware

The emulator is useful for:

- basic UI behavior
- clip grid rendering
- launch guard / recovery UI
- non-hardware tests

The emulator is not useful for:

- real glasses session behavior
- USB/HID camera enable
- real UVC camera preview from XREAL glasses
- end-to-end hardware recording validation

Real-device validation still needs `Huawei P60 Pro` + `XREAL One Pro`.

## Expected device workflow

1. Launch the app.
2. Wait for the glasses session to become available.
3. Tap `Enable Camera`.
4. Accept USB permission if prompted.
5. Tap `Start Preview`.
6. Accept UVC/camera USB permission if prompted.
7. Use fullscreen preview.
8. Use back to stop preview.
9. If `Record video` is enabled, the clip should finalize and appear in the grid.

## Recording workflow

Control screen behavior:

- `Record video` checkbox controls whether the next preview session records.
- Recorded clips are shown in a grid, newest first.
- Selecting a clip enables:
  - `Open`
  - `Share`
  - `Delete`

Preview behavior:

- recording starts automatically after preview starts
- recording finalizes automatically when preview exits

The app does not currently:

- record audio
- export clips to the public gallery by default
- provide in-app video playback
- generate thumbnails

## Safe mode and crash recovery

If the app dies during preview or recording:

- the next launch may open the guarded launch screen first
- launch diagnostics can be shared before opening the app
- the app may enter safe mode
- camera and recording can stay disabled until the user explicitly confirms re-enabling camera testing

Broken active recording artifacts are handled during recovery. The recovery layer logs broken clip metadata and tries to prevent stale files/catalog state from poisoning startup.

## Known limitations

- The project is hardware-targeted to `Huawei P60 Pro` + `XREAL One Pro`.
- Preview/recording behavior outside that pair is not guaranteed.
- The app still depends on vendored native/community code at the hardware boundary.
- Recording uses camera frame callbacks, so any future performance work should measure callback throughput carefully.
- The project has local vendor fixes; upstream updates must be merged deliberately, not blindly.

## Developer notes for future changes

### If you touch the UVC callback or recording path

Check:

- [`app/src/main/java/com/example/ar_control/recording/MediaCodecVideoRecorder.kt`](app/src/main/java/com/example/ar_control/recording/MediaCodecVideoRecorder.kt)
- [`app/src/main/java/com/example/ar_control/camera/AndroidUvcLibraryAdapter.kt`](app/src/main/java/com/example/ar_control/camera/AndroidUvcLibraryAdapter.kt)
- [`vendor/uvccamera/src/main/jni/UVCCamera/UVCPreview.cpp`](vendor/uvccamera/src/main/jni/UVCCamera/UVCPreview.cpp)
- [`app/src/test/java/com/example/ar_control/camera/UvcVendorPixelFormatContractTest.kt`](app/src/test/java/com/example/ar_control/camera/UvcVendorPixelFormatContractTest.kt)

### If you touch startup or crash handling

Check:

- [`app/src/main/java/com/example/ar_control/startup/LaunchGuardActivity.kt`](app/src/main/java/com/example/ar_control/startup/LaunchGuardActivity.kt)
- [`app/src/main/java/com/example/ar_control/recovery/RecoveryManager.kt`](app/src/main/java/com/example/ar_control/recovery/RecoveryManager.kt)
- [`app/src/main/java/com/example/ar_control/diagnostics/SessionLog.kt`](app/src/main/java/com/example/ar_control/diagnostics/SessionLog.kt)

### If you touch the preview screen UX

Check:

- [`app/src/main/java/com/example/ar_control/MainActivity.kt`](app/src/main/java/com/example/ar_control/MainActivity.kt)
- [`app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt`](app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt)
- [`app/src/main/java/com/example/ar_control/preview/`](app/src/main/java/com/example/ar_control/preview)

## Reference docs in this repo

- [Native camera preview design](docs/superpowers/specs/2026-04-17-xreal-one-pro-native-camera-preview-design.md)
- [Preview recording design](docs/superpowers/specs/2026-04-17-xreal-one-pro-preview-recording-design.md)
- [Hardware bring-up runbook](docs/hardware/xreal-one-pro-huawei-p60-pro-preview.md)
- [Vendored `one-xr` notes](vendor/onexr/UPSTREAM.md)
- [Vendored `UVCCamera` notes](vendor/uvccamera/UPSTREAM.md)

## Short status summary

Today’s shipped app supports:

- native glasses connection
- camera enable
- fullscreen preview
- zoom via volume keys
- automatic recording tied to preview
- persistent clip history
- clip open/share/delete
- persistent diagnostics
- guarded crash recovery

This README should be kept as the primary developer entry point. When behavior changes, update this file first and the deeper design docs second.
