# XREAL One Pro Preview Recording And Clip Grid Design

## Summary

Add optional video recording to the existing native preview flow. A new `Record video` checkbox on the control screen will let the user opt into automatic recording for the next preview session. When enabled, recording starts automatically after preview starts and stops automatically when preview stops or fullscreen preview is exited.

Recorded clips will be stored as app-owned files and shown on the control screen in a persistent history grid. The user will be able to select a clip, open it in the system video player, share it, or delete it.

## Goals

- Add a `Record video` checkbox to the control screen.
- Start recording automatically when preview starts if the checkbox is enabled.
- Stop and finalize recording automatically when preview stops or fullscreen preview is exited.
- Persist a history of recorded clips across app launches.
- Show the clip history on the control screen in a grid layout.
- Let the user open a recorded clip in the system video player.
- Let the user share or delete a selected recorded clip.
- Keep preview usable even if recording fails.

## Non-Goals

- In-app video playback.
- Recording audio.
- Exporting clips into the system gallery by default.
- Cloud sync or remote upload.
- Clip trimming, renaming, or editing.
- Thumbnail generation in milestone 1.

## Current Context

The app already has:

- A single-activity control screen plus immersive fullscreen preview.
- A `PreviewViewModel` that coordinates camera enable, preview start, preview stop, and zoom state.
- A native UVC preview path built on the vendored `UVCCamera` stack.
- A control-screen diagnostics/logging model.

The vendored UVC stack also exposes a capture-surface path via `UVCCamera.startCapture(surface)`, which is the preferred recording integration point for this feature.

## User Experience

### Control Screen

Add these elements below the existing control buttons:

- `Record video` checkbox.
- `Recorded clips` section title.
- Grid of previously recorded clips, ordered newest first.
- Action row for the currently selected clip:
  - `Open`
  - `Share`
  - `Delete`

Each clip card should show:

- A video placeholder visual, not a generated thumbnail.
- Recording timestamp.
- Duration when available.
- Selection state.

Behavior:

- Tapping a clip selects it.
- `Open` launches the system video player with the selected clip URI.
- `Share` opens the Android share sheet for the selected clip.
- `Delete` shows confirmation before removing the selected clip.
- If no clip is selected, `Open`, `Share`, and `Delete` stay disabled.

### Preview Screen

Preview stays immersive fullscreen and otherwise keeps current behavior.

When `Record video` is enabled:

1. The user taps `Start Preview`.
2. Preview starts first.
3. Recording starts immediately after preview start succeeds.
4. The user enters fullscreen preview as today.
5. Recording stops automatically when:
   - `Stop Preview` is triggered,
   - the small preview back button is used, or
   - the hardware back button exits fullscreen preview.

There is no separate record toggle inside fullscreen preview in this milestone.

## Architecture

### 1. RecordingPreferences

Persist the `Record video` checkbox state so the app remembers the user preference across launches.

Responsibilities:

- Read and write the recording-enabled preference.
- Expose the current value to the UI layer.

### 2. VideoRecorder

Own the lifecycle of one recording session.

Responsibilities:

- Create the output file for the current preview session.
- Prepare the encoder/muxer pipeline.
- Expose a capture `Surface`.
- Start recording after preview start succeeds.
- Stop and finalize recording on preview stop.
- Return final clip metadata or a concrete failure reason.

Implementation decision:

- Use the UVC capture-surface path with a `MediaCodec` + `MediaMuxer` based recorder.
- Do not use `MediaRecorder` for milestone 1.

Reason:

- The UVC camera already supports attaching a capture surface.
- `MediaCodec`/`MediaMuxer` gives tighter control over finalization, metadata, and error handling.

### 3. ClipRepository

Persist and query recorded clip metadata.

Responsibilities:

- Store clip metadata in a local catalog.
- Return clips ordered newest first.
- Remove stale entries when files are missing.
- Delete a clip file and its metadata atomically from the app’s perspective.

Implementation decision:

- Use a lightweight JSON-backed catalog stored in app files.
- Do not introduce Room in this milestone.

Reason:

- The catalog is small and app-owned.
- JSON keeps implementation scope lower and avoids adding a new database layer for the first recording milestone.

### 4. ClipFileSharer

Handle external intents for recorded clips.

Responsibilities:

- Build content URIs through `FileProvider`.
- Launch `ACTION_VIEW` for system playback.
- Launch `ACTION_SEND` for sharing.

### 5. PreviewViewModel

Remain the coordinator for preview lifecycle, recording lifecycle, and clip-grid UI state.

Additional state to add:

- Recording preference enabled/disabled.
- Recording status:
  - idle
  - starting
  - recording
  - finalizing
  - failed
- Selected clip id.
- Loaded clip list.
- Whether clip actions are enabled.

## Data Model

Each recorded clip entry should contain:

- Stable id.
- App-owned file path.
- Created-at timestamp.
- Duration in milliseconds.
- Video width and height when known.
- File size in bytes.
- MIME type.

Catalog rules:

- If the file is missing during load, drop the stale entry.
- Only add the clip to the catalog after recording finalization succeeds.
- If finalization fails, delete the incomplete file if possible and do not show it in the grid.

## Storage Strategy

Store video files in app-scoped movie storage:

- Preferred location: `externalFilesDir(Environment.DIRECTORY_MOVIES)/recordings/`

Reason:

- Easy to manage without broad storage permissions.
- Files are private to the app unless explicitly opened/shared.
- Share/delete behavior stays predictable.

## Recording Flow

### Start Preview With Recording Disabled

1. User taps `Start Preview`.
2. Camera preview starts.
3. App enters fullscreen preview.
4. No recording resources are created.

### Start Preview With Recording Enabled

1. User taps `Start Preview`.
2. Camera preview starts.
3. Recorder prepares an output file and capture surface.
4. UVC capture is attached to the recorder surface.
5. Recording starts.
6. App enters fullscreen preview.

If recorder setup fails:

- Preview remains allowed to run.
- UI shows a recording error on the control screen after preview exit or immediately if preview does not continue.
- No broken clip entry is added.

### Stop Preview

1. User stops preview or exits fullscreen preview.
2. Preview stops.
3. Recorder stops and finalizes output.
4. Repository inserts the new clip entry.
5. Control screen reappears with the refreshed clip grid.

## Error Handling

Treat recording failures separately from preview failures.

Error cases to model explicitly:

- Recording preparation failed.
- Capture surface attach failed.
- Recorder start failed.
- Recorder finalization failed.
- Clip open failed because file is missing.
- Clip share failed because URI generation failed.
- Clip delete failed because file or catalog removal failed.

Behavior rules:

- Preview must not be blocked permanently by recording failure.
- A recording failure should not leave the app stuck in fullscreen preview.
- Delete failures should not silently remove UI state unless the file and catalog entry were both handled.
- Missing files should be cleaned out of the catalog on next load.

## Logging And Diagnostics

Add structured logs for:

- Recording checkbox changes.
- Recorder prepare/start/stop/finalize lifecycle.
- Output file path creation.
- Clip catalog insert/load/delete operations.
- Open/share/delete actions from the control screen.
- Recording failures distinct from preview failures.

## Testing Strategy

### Unit Tests

Add tests for:

- Preview start with recording disabled.
- Preview start with recording enabled.
- Preview stop auto-finalizes recording.
- Failed recording does not leave a persisted clip entry.
- Clip repository ordering newest first.
- Clip selection state and action enablement.
- Delete removes both file and catalog entry.
- Stale file cleanup removes broken catalog entries.

### UI Tests

Add UI coverage for:

- Checkbox persistence.
- Clip grid visible on the control screen.
- Clip selection updates enabled action buttons.
- Starting preview with recording enabled still enters fullscreen preview.
- Exiting fullscreen preview returns to the control screen and refreshes the clip grid.

### Manual Device Validation

Validate on `Huawei P60 Pro` with `XREAL One Pro`:

1. Enable `Record video`.
2. Start preview.
3. Confirm fullscreen preview behaves as before.
4. Exit preview.
5. Confirm a new clip appears in the control-screen grid.
6. Open the clip in the system video player.
7. Share the clip.
8. Delete the clip.
9. Record multiple clips and confirm newest-first ordering.
10. Disable `Record video` and confirm preview no longer records.

## Risks

### Recording Finalization Timing

Stopping preview and finalizing video on the same transition can expose timing bugs. The design keeps recording state explicit so finalization can complete before the clip is added to the catalog.

### Storage Growth

App-owned storage avoids system-gallery complexity, but recordings may accumulate. The clip grid and delete action are required in the first milestone to keep storage manageable.

### UVC Capture Compatibility

Preview currently works through a `TextureView`. Recording adds a second surface path. The design isolates this in the recorder/camera integration layer so failures remain diagnosable.

## Definition Of Done

This change is complete when:

- The user can enable or disable `Record video` from the control screen.
- Preview sessions record automatically only when that checkbox is enabled.
- Recording stops automatically when preview stops or fullscreen preview is exited.
- Recorded clips persist across app launches.
- The control screen shows a newest-first grid of recorded clips.
- The user can select a clip and open, share, or delete it.
- Preview still works when recording is disabled.
- Recording failures are visible and do not break preview recovery.
