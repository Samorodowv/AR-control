# Gemma Web Download Design

## Context

The current Gemma subtitle flow imports a user-selected `.litertlm` file through Android's document picker, copies it into app-private storage, and stores that private path in `GemmaSubtitlePreferences`. The captioning path already expects a local app-private model path, so the inference side does not need to change.

The approved change is to remove user-provided filesystem import and fetch the model from a public open source web source instead. The selected source is Hugging Face `litert-community/gemma-4-E2B-it-litert-lm`, using `gemma-4-E2B-it.litertlm` from:

`https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true`

Google AI Edge documents LiteRT GenAI models through the LiteRT Hugging Face community, and the model page lists this file as an Apache-2.0 LiteRT-LM model.

## Goals

- Replace `Import Gemma model` with `Download Gemma model`.
- Remove `ActivityResultContracts.OpenDocument` and URI-based model import.
- Download over HTTPS into app-private storage at `filesDir/models/gemma-subtitles.litertlm`.
- Keep the existing Gemma checkbox and caption overlay behavior.
- Persist the downloaded private model path through existing Gemma preferences.
- Show a clear downloading state and prevent duplicate downloads.

## Non-Goals

- No custom model URL field.
- No multi-model catalog UI.
- No Hugging Face authentication flow.
- No resumable background download manager in this iteration.

## Architecture

Replace the URI importer with a focused downloader component. The downloader owns the public model source, opens the HTTPS stream on `Dispatchers.IO`, writes to a temporary file in the model directory, and atomically moves it into place only after the download succeeds. On failure or cancellation, it deletes the temporary file and leaves the previous configured model untouched.

`PreviewViewModel` exposes a `downloadGemmaModel()` action. During download, `PreviewUiState` tracks a download-in-progress flag and optional progress text when content length is available. `MainActivity` binds the existing model button to this new action, disables it while downloading, and updates the status text.

The existing `LiteRtGemmaFrameCaptioner` continues to receive a local private file path from preferences. It never loads directly from external storage or a user-selected filesystem path.

## Error Handling

Network failures, missing response bodies, invalid response codes, and filesystem write failures return a user-facing error such as `Could not download Gemma model`. Cancellation cleans up temporary files without overwriting the existing model. If the model was already downloaded, a failed re-download keeps the old model path active.

## Testing

Update unit tests around the downloader with fake streams/connections:

- Successful download writes bytes, atomically replaces the target file, and stores the expected display name.
- Failed open or failed copy removes temporary files and does not persist a model path.
- `PreviewViewModel.downloadGemmaModel()` updates loading state, model display name, and error state.
- `MainActivity` no longer launches the document picker and uses the new download button text.

Run the existing test suite and release build after implementation.

