# XREAL One Pro Native Camera Preview Design

## Summary

Build milestone 1 of native Android support for `XREAL One Pro` on `Huawei P60 Pro`. The app will show a live preview from the glasses camera embedded in the phone UI, without using the XREAL Unity SDK.

The design separates two concerns:

- Glasses session and control use a vendored snapshot of the community-proven `one-xr` native Android path.
- Eye camera enable and preview stay in this repo and use Android USB host access with a UVC-oriented streaming pipeline.

This keeps the field-tested control path reusable while preserving direct ownership over the most device-specific camera behavior.

## Goals

- Connect to `XREAL One Pro` natively on Android without Unity.
- Enable the glasses camera and show live preview in the phone UI.
- Keep the implementation modular so later milestones can consume frames for CV or rendering.
- Make hardware failures diagnosable on the target phone.

## Non-Goals

- Beam Pro support.
- Unity/XREAL SDK integration.
- In-glasses UI composition or passthrough rendering.
- Video recording, streaming, or frame persistence.
- Generalized support for all Android phones in milestone 1.

## Constraints And Assumptions

- Target phone: `Huawei P60 Pro`.
- Target glasses: `XREAL One Pro`.
- Milestone 1 preview is embedded in the phone UI first.
- The app currently has no production code and should be treated as a greenfield Android implementation.
- Camera access is not based on a stable public XREAL native Android camera SDK; the camera path must be treated as a USB/UVC integration problem.

## Recommended Approach

Use a hybrid strategy.

- Vendor a pinned snapshot of `one-xr` into the repo for glasses session and control.
- Implement camera enable and camera preview natively in this repo.

Rejected alternatives:

- Reimplement all XREAL control/session behavior from scratch. This adds unnecessary reverse-engineering risk for milestone 1.
- Depend directly on remote community repos at build time. This increases maintenance risk and reduces reproducibility.

## Architecture

### 1. GlassesSession

`GlassesSession` is the app-facing boundary for glasses state and control. It wraps the vendored `one-xr` code and exposes only the subset needed for milestone 1.

Responsibilities:

- Establish and monitor the glasses connection.
- Surface connection state to the UI.
- Expose small control actions such as recenter if needed later.
- Avoid leaking third-party library types into the rest of the app.

Non-responsibilities:

- Camera enable.
- USB permission handling.
- Camera frame transport.

### 2. EyeUsbConfigurator

`EyeUsbConfigurator` owns the low-level USB/HID sequence required to switch the device into a camera-usable mode. Its behavior is modeled after known community driver work for `XREAL One`.

Responsibilities:

- Detect the relevant USB device/interface before preview.
- Send the minimal configuration sequence needed to enable the Eye/UVC path.
- Wait for disconnect/re-enumeration if the device changes mode.
- Report precise failure reasons.

Non-responsibilities:

- Rendering preview frames.
- Managing long-lived UI state.

### 3. CameraSource

`CameraSource` is an internal abstraction with one primary implementation in milestone 1.

Primary implementation:

- `UvcCameraSource`

Optional diagnostic implementation:

- `ExternalCamera2Source`

Milestone 1 is designed around `UvcCameraSource` as the main path because `Huawei P60 Pro` is known to work in practice and because direct USB permission behavior already appears in third-party apps. `ExternalCamera2Source` may be added only as a probe/fallback path, not as the core design.

Responsibilities:

- Request and observe Android USB permission.
- Open and close the streaming source.
- Normalize stream frames into one app-facing representation for preview.
- Report device and backend metadata for diagnostics.

### 4. PreviewViewModel

`PreviewViewModel` coordinates app state without owning hardware details.

State includes:

- Glasses connection state.
- Eye mode enable state.
- USB permission state.
- Active camera backend.
- Preview active or inactive.
- User-visible error state.

### 5. MainActivity

`MainActivity` hosts a minimal diagnostic screen for milestone 1.

Required UI elements:

- Glasses connection indicator.
- Button to enable camera mode.
- Button to start preview.
- Button to stop preview.
- Text showing the active backend and device state.
- Embedded preview surface.

This UI is intentionally diagnostic-first. It is not the final product UI.

## Data Flow

### Startup

1. App starts.
2. `GlassesSession` initializes and reports whether the glasses session is available.
3. UI reflects connected or unavailable state.

### Enable Camera

1. User taps `Enable Camera`.
2. `PreviewViewModel` calls `EyeUsbConfigurator`.
3. `EyeUsbConfigurator` sends the USB/HID configuration sequence.
4. If mode switching causes re-enumeration, the app waits for the new USB device state.
5. UI reports success or a specific error.

### Start Preview

1. User taps `Start Preview`.
2. App requests USB permission for the camera device if not already granted.
3. `UvcCameraSource` opens the stream.
4. Frames are delivered to the preview surface in the phone UI.
5. UI reports the active backend and running state.

### Stop Preview

1. User taps `Stop Preview`.
2. `UvcCameraSource` releases the stream.
3. `GlassesSession` remains alive.
4. Preview can be restarted without restarting the app.

## Dependency Strategy

### Vendored Source

The repo should vendor a pinned source snapshot of `one-xr` rather than consume it remotely.

Reasons:

- Reproducible builds.
- No runtime or build-time dependence on upstream repository availability.
- Freedom to adapt packaging and interfaces to the app.
- Easier debugging inside Android Studio.

The vendored code must be wrapped behind local interfaces so replacing or removing it later does not require large application changes.

### In-Repo Camera Logic

The camera path stays entirely in this repo.

Reasons:

- It is the highest-risk and most hardware-sensitive part.
- We need to instrument it heavily for device bring-up.
- We should control the abstraction boundaries for later frame consumers.

## Android Integration Requirements

### Manifest And Features

The app will need:

- USB host capability.
- USB intent/filter metadata for the target device path.
- Any preview-stack permissions required by the chosen rendering path.

Normal Android camera permission alone should not be assumed sufficient because the expected path includes direct USB access and explicit user approval.

### Toolchain

The project should be upgraded to a modern Android baseline compatible with current Kotlin/AndroidX expectations and the vendored native Android control code. `JDK 17` is the expected baseline.

## Error Handling

Failures must be separated by stage so hardware debugging is practical.

Error categories:

- `glasses_session_unavailable`
- `camera_enable_failed`
- `camera_device_not_found`
- `usb_permission_denied`
- `device_reenumeration_timeout`
- `stream_open_failed`
- `stream_interrupted`

Behavior requirements:

- Keep the app responsive after all expected failures.
- Allow retry after permission denial or re-enumeration timeout.
- Do not tear down the entire glasses session when preview fails.
- Surface short user-facing messages and detailed debug logs.

## Observability

Milestone 1 depends on hardware debugging, so structured logs are part of the design.

The app should log:

- Device attach/detach events.
- Vendor/product/interface details for matched USB devices.
- Camera enable attempts and outcomes.
- Permission request and grant/deny results.
- Backend selection.
- Preview start/stop events.
- Stream errors and disconnects.

## Testing Strategy

### Local Validation

Primary validation is manual on real hardware:

- `Huawei P60 Pro`
- `XREAL One Pro`

Required end-to-end checks:

1. Launch app with glasses connected.
2. Confirm glasses session availability.
3. Enable Eye camera mode.
4. Accept USB permission prompt.
5. Show live preview in phone UI.
6. Stop and restart preview.
7. Disconnect and reconnect glasses.
8. Relaunch app and repeat without reinstall.

### Automated Coverage

Automated tests in milestone 1 should focus on:

- ViewModel state transitions.
- Error mapping and retry behavior.
- Non-hardware parsing or configuration helpers.

Hardware streaming itself should not be faked aggressively in the first milestone; fake only the interfaces we own.

## Risks

### Phone-Specific Behavior

`Huawei P60 Pro` is the chosen target because it is already known to work well in practice. Even so, USB re-enumeration timing and permission behavior may still vary across OS updates.

### Community Driver Drift

The vendored `one-xr` snapshot may diverge from upstream. This is acceptable for milestone 1 because reproducibility matters more than passive upstream alignment.

### Camera Transport Complexity

The Eye camera path is the least documented part. The design mitigates this by isolating it in `EyeUsbConfigurator` and `UvcCameraSource`.

## Milestone 1 Definition Of Done

Milestone 1 is complete when:

- The app starts on `Huawei P60 Pro`.
- It detects or establishes a usable glasses session.
- It enables the Eye camera path.
- It requests USB permission successfully.
- It displays a stable live preview in the phone UI.
- Preview can be stopped and restarted.
- Failure states are visible and diagnosable.

## Implementation Notes For Planning

The next planning phase should produce:

- The exact module/file layout for vendored `one-xr`.
- The manifest and Gradle changes.
- The UI shell for the diagnostic preview screen.
- The interface contracts for `GlassesSession`, `EyeUsbConfigurator`, and `CameraSource`.
- The validation commands and test checklist for milestone 1 hardware bring-up.
