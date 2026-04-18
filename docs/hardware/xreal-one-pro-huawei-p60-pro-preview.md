# XREAL One Pro + Huawei P60 Pro Preview Bring-Up

Task 7 hardware note for the native `one-xr` plus USB/UVC preview flow.

## Hardware Target

- Phone: `Huawei P60 Pro`
- Glasses: `XREAL One Pro`
- No prerequisite companion app required

## Expected Flow

The intended hardware path is:

1. Connect `XREAL One Pro` to the `Huawei P60 Pro`.
2. Launch `AR_Control`.
3. Confirm the glasses session becomes available.
4. Tap `Enable Camera`.
5. Wait for USB re-enumeration.
6. Accept the control-device USB permission prompt, if shown.
7. Tap `Start Preview`.
8. Accept the camera/UVC USB permission prompt, if shown.
9. Confirm the live camera image appears in the phone UI.
10. Exit fullscreen preview with the on-screen back button or the hardware back button.
11. Repeat the preview flow without restarting the app.

This document describes the tested/expected flow only. In this workspace, the phone and glasses are not attached, so the end-to-end hardware bring-up could not be completed here.

## Manual Validation Steps

Use this exact checklist on the target device:

1. Install the debug build on the `Huawei P60 Pro`.
2. Connect `XREAL One Pro`.
3. Launch `AR_Control`.
4. Verify the glasses state transitions to available.
5. Tap `Enable Camera`.
6. Confirm the USB device re-enumerates.
7. Accept the control-device USB permission prompt, if shown.
8. Tap `Start Preview`.
9. Accept the camera/UVC USB permission prompt, if shown.
10. Verify the preview renders in the app.
11. Exit fullscreen preview with the on-screen back button or the hardware back button.
12. Tap `Start Preview` again to confirm restart works.
13. Disconnect and reconnect the glasses.
14. Repeat the flow without reinstalling the app.

## Recording Validation Checklist

Use this checklist after preview bring-up succeeds:

1. Enable `Record video`.
2. Tap `Start Preview`.
3. Confirm preview starts with recording enabled.
4. Exit fullscreen preview with the back button to trigger recording finalization.
5. Confirm a new clip appears in the recorded clips area.
6. Open the clip in the system video player.
7. Share the clip.
8. Delete the clip.
9. Repeat with multiple clips and confirm the newest clip appears first.
10. Disable `Record video`.
11. Start and stop preview again.
12. Confirm no new clip is created while recording is disabled.

## Commands

Run these from the repository root when a real device is attached:

```powershell
.\gradlew.bat :app:assembleDebug
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.ar_control/.MainActivity
adb shell dumpsys usb
```

Notes:

- `:app:assembleDebug` builds the debug APK.
- `adb install -r` installs or updates the app on the phone.
- `adb shell am start` is the direct launch command for the main activity.
- `adb shell dumpsys usb` helps confirm what USB devices and interfaces Android currently sees.
- Use the app's `Share Logs` button after reproducing a problem. The app's main diagnostics are stored in the in-memory session log and are not emitted as structured `android.util.Log` lines.
- `adb logcat` can still be useful for platform-level USB or permission behavior, but it is supplemental to the shared in-app diagnostics.

## Expected Failure Mapping

- No glasses session: verify the glasses are connected in the expected mode and that the phone still exposes the `169.254.2.1` link-local route used by `one-xr`.
- No USB prompt: verify the enable flow actually triggered device re-enumeration.
- Preview fails to open: inspect the UVC transport logs first.
- App launches but preview stays black: verify the camera permission prompt was accepted and the USB device stayed attached after enable.
- `adb devices` shows nothing: the phone is not connected, USB debugging is not enabled, or host-side adb cannot access the device.

## Current Workspace Limitation

Gradle builds and unit tests have succeeded in this workspace, so the app can be compiled locally here.

The remaining limitation is hardware access: no `Huawei P60 Pro` or `XREAL One Pro` is attached to this workspace, so install, USB permission, live preview, and recording validation still need to be run on the target device.

## What To Re-run On Real Hardware

When the phone is attached to a machine with adb access, rerun:

1. `.\gradlew.bat :app:assembleDebug`
2. `adb devices`
3. `adb install -r app\build\outputs\apk\debug\app-debug.apk`
4. `adb shell am start -n com.example.ar_control/.MainActivity`
5. The manual checklist above
6. Reproduce the issue and use `Share Logs` from the app
7. `adb shell dumpsys usb`
