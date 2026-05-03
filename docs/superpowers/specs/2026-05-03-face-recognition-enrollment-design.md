# Face Recognition Enrollment Design

## Goal

Add reliable-first on-device face recognition to the preview flow. The first implementation uses ML Kit for face detection and a swappable LiteRT embedding model slot for recognition quality upgrades.

## User Behavior

- While preview is running, the app monitors camera frames for faces.
- A double press on Volume Up within 600 ms requests enrollment of the current visible face.
- Enrollment succeeds only when exactly one usable face is visible and a face embedding model is available.
- The app stores the embedding locally and compares later visible faces using cosine similarity.
- The HUD exposes short status text such as model missing, no face, multiple faces, remembered, or unknown.
- Single Volume Up keeps the existing zoom behavior.

## Architecture

Face recognition is implemented as a separate frame session that plugs into the existing recording/detection/Gemma frame fan-out. ML Kit performs face-box detection on YUV frames. A `FaceEmbeddingModel` converts the best aligned face crop into a normalized embedding through LiteRT when a model asset exists.

The first model asset path is `models/face_embedding.tflite`. This keeps the app buildable before the model is added. Recommended initial converted model is EdgeFace or GhostFaceNets for reliability, with FaceLiVTv2-XS as a later quality upgrade after conversion is proven.

## Components

- `face/FaceRecognitionSession`: consumes preview frames, runs detection/embedding, and publishes state.
- `face/FaceEmbeddingModel`: interface for model inference.
- `face/LiteRtFaceEmbeddingModel`: LiteRT implementation loading `models/face_embedding.tflite`.
- `face/FaceEmbeddingStore`: local remembered-face persistence.
- `face/FaceEnrollmentController`: handles pending enrollment requests against the latest recognition state.
- `ui/preview/VolumeUpDoublePressDetector`: separates single zoom from double-press enrollment.

## Data Flow

1. `PreviewViewModel` starts face recognition whenever preview starts.
2. Camera frames fan out to recording, YOLO, Gemma, and face recognition as needed.
3. The face session publishes current recognition status.
4. `MainActivity` renders the status text and routes Volume Up through the double-press detector.
5. On double press, `PreviewViewModel.rememberCurrentFace()` asks the face session to enroll the latest single-face embedding.

## Error Handling

- Missing model asset: session stays alive, reports model missing, and enrollment is blocked.
- No face: enrollment returns a no-face status.
- Multiple faces: enrollment is blocked to avoid remembering the wrong person.
- Recognition failures are logged and reported as short status text without stopping preview.

## Testing

Unit tests cover double-press timing, cosine matching, embedding-store round trips, enrollment gating, view-model state updates, and frame target fan-out integration. Instrumentation tests only need to compile after new dependencies and fakes are updated.
