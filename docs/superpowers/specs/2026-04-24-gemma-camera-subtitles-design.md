# Gemma Camera Subtitles Design

## Summary

Add an optional `Gemma subtitles` mode that generates short scene captions from live camera frames and displays them as a floating pill over the fullscreen preview. The mode uses a sideloaded Gemma 4 `.litertlm` model with LiteRT-LM on device. The model is not committed to the repository or bundled in the APK.

## Goals

- Add a `Gemma subtitles` checkbox to the control screen.
- Let the user import or configure a `.litertlm` model file from device storage.
- Persist the copied private model path and checkbox state across app launches.
- Start camera-frame captioning automatically when preview starts and the checkbox is enabled.
- Render the latest caption as a floating pill over the camera feed.
- Keep preview usable if model loading or caption inference fails.

## Non-Goals

- Bundling Gemma 4 model files in `app/src/main/assets`.
- Cloud inference or network model calls.
- Audio transcription subtitles.
- Writing subtitles into recorded video files.
- Multi-turn chat UI or agent tools.
- Continuous per-frame inference.

## Current Context

The app already has a control screen, immersive fullscreen preview, `PreviewViewModel`, persisted recording and object-detection preferences, and a frame callback pipeline used by recording and YOLO detection. `FrameCallbackTargetFanOut` can already distribute UVC frame callbacks to multiple consumers, which is the right extension point for captioning.

Official Google guidance points to LiteRT-LM for Gemma 4 on-device Android inference. The Kotlin API supports `.litertlm` models, background initialization, streaming responses, and multimodal `ImageBytes` or `ImageFile` content.

## User Experience

On the control screen, add:

- `Gemma subtitles` checkbox near `Record video` and `Object detection`.
- `Import Gemma model` action, shown when no model path is configured or when the user wants to replace it.
- Model status text such as `Gemma model: not configured` or the imported filename.

When enabled without a configured model, the app should not block preview. It should show a clear error/status message and leave the preview path functional.

In fullscreen preview, captions appear as a floating pill near the bottom center. The pill is hidden until the first caption arrives, then updates only when a new caption is generated. Keep text short, centered, and readable over moving video.

## Architecture

### GemmaSubtitlePreferences

Persist:

- Whether Gemma subtitles are enabled.
- The private copied `.litertlm` model file path.
- The original display name when available.

Use SharedPreferences, matching `SharedPreferencesRecordingPreferences` and `SharedPreferencesDetectionPreferences`.

### GemmaModelImporter

Own model import from Android document picker results:

- Accept a selected `content://` URI.
- Copy the file into app-private storage, for example `filesDir/models/gemma-subtitles.litertlm`.
- Persist the private path and display name through `GemmaSubtitlePreferences`.
- Replace the previous model atomically enough to avoid leaving preferences pointed at a missing file.

### GemmaFrameCaptioner

Expose a small app-owned interface independent from LiteRT-LM:

- `start(previewSize, onCaptionUpdated, onError): GemmaCaptionSession`
- `GemmaCaptionSession.inputTarget: RecordingInputTarget.FrameCallbackTarget`
- `close()`

The LiteRT-LM implementation initializes `Engine` on a background dispatcher, creates short-lived or managed conversations, samples frames at a fixed interval, drops frames while inference is already running, and emits only the latest caption.

The prompt should force subtitle-sized output:

`Describe the visible scene in one short subtitle, max 12 words. Return only the subtitle.`

### PreviewViewModel Integration

Extend `PreviewUiState` with:

- `gemmaSubtitlesEnabled`
- `canChangeGemmaSubtitles`
- `gemmaModelDisplayName`
- `gemmaSubtitleText`

Add `setGemmaSubtitlesEnabled()` and model-import completion handling. During preview startup, `maybeStartFramePipeline()` should include the Gemma caption target when subtitles are enabled and a model is configured. Existing recording and detection targets should still fan out through `FrameCallbackTargetFanOut`.

On preview stop, close the active caption session and clear `gemmaSubtitleText`.

### MainActivity Integration

Add checkbox and import button listeners. Use `ActivityResultContracts.OpenDocument` with a MIME fallback of `*/*`. After selection, `MainActivity` should pass the URI and display name to `PreviewViewModel`, and the ViewModel should delegate copying/persistence to an injected `GemmaModelImporter` on an IO dispatcher. Render the floating pill TextView over `previewContainer`.

## Error Handling

- Missing model: show `Gemma model is not configured`; preview still starts.
- Model load failure: disable the active caption session, show the failure in camera status, keep preview running.
- Inference failure: clear the active caption session for this preview and leave the last known preview state recoverable.
- Safe mode: disable Gemma checkbox and clear subtitle text, matching record/detection behavior.

## Testing

Use TDD for implementation:

- Preference tests for enabled state and model path/display name.
- Importer tests with fake content resolver or isolated copy helper.
- ViewModel tests for enabling/disabling, missing model behavior, caption updates, preview stop cleanup, and fan-out coexistence with recording/detection.
- MainActivity instrumentation or Robolectric-style tests for checkbox rendering and floating pill visibility.

Manual hardware validation should cover Huawei P60 Pro plus XREAL One Pro with a sideloaded Gemma 4 `.litertlm` model.
