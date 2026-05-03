# Face Recognition Enrollment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build reliable-first on-device face recognition with double Volume Up enrollment.

**Architecture:** Add a face-recognition frame session beside the existing YOLO and Gemma frame sessions. Keep the embedding model swappable through a LiteRT interface and make the app degrade cleanly when `models/face_embedding.tflite` is not present.

**Tech Stack:** Kotlin, Android ViewModel, ML Kit face detection, LiteRT `CompiledModel`, SharedPreferences/JSON persistence, Robolectric/JUnit tests.

---

### Task 1: Double Volume Up Enrollment Trigger

**Files:**
- Create: `app/src/main/java/com/example/ar_control/ui/preview/VolumeUpDoublePressDetector.kt`
- Test: `app/src/test/java/com/example/ar_control/ui/preview/VolumeUpDoublePressDetectorTest.kt`
- Modify: `app/src/main/java/com/example/ar_control/MainActivity.kt`

- [ ] Write tests proving two presses within 600 ms emit `DoublePress`, a single press emits `SinglePress` after timeout, and late second press becomes a new first press.
- [ ] Implement `VolumeUpDoublePressDetector`.
- [ ] Route `KEYCODE_VOLUME_UP` through it in `MainActivity`; double press calls `previewViewModel.rememberCurrentFace()`, single press calls `zoomInPreview()`.

### Task 2: Face Embedding Matching and Persistence

**Files:**
- Create: `app/src/main/java/com/example/ar_control/face/FaceEmbedding.kt`
- Create: `app/src/main/java/com/example/ar_control/face/FaceEmbeddingMatcher.kt`
- Create: `app/src/main/java/com/example/ar_control/face/FaceEmbeddingStore.kt`
- Test: `app/src/test/java/com/example/ar_control/face/FaceEmbeddingMatcherTest.kt`
- Test: `app/src/test/java/com/example/ar_control/face/InMemoryFaceEmbeddingStoreTest.kt`

- [ ] Write tests for cosine similarity, threshold match, no-match behavior, and store round trip.
- [ ] Implement immutable embedding records and matcher.
- [ ] Implement a store interface plus in-memory test store.

### Task 3: Face Recognition Session Contract

**Files:**
- Create: `app/src/main/java/com/example/ar_control/face/FaceRecognitionSession.kt`
- Create: `app/src/main/java/com/example/ar_control/face/FaceRecognizer.kt`
- Test: `app/src/test/java/com/example/ar_control/face/FaceEnrollmentControllerTest.kt`

- [ ] Write tests for enrollment blocked by missing model, no face, multiple faces, and successful single-face embedding.
- [ ] Implement `FaceRecognitionState`, `FaceEnrollmentResult`, `FaceRecognizer`, and `FaceRecognitionSession` interfaces.
- [ ] Add a no-op recognizer that reports the model missing.

### Task 4: LiteRT and ML Kit Implementation Shell

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/example/ar_control/face/LiteRtFaceEmbeddingModel.kt`
- Create: `app/src/main/java/com/example/ar_control/face/MlKitFaceRecognizer.kt`

- [ ] Add ML Kit face detection dependency.
- [ ] Implement LiteRT model loader for `models/face_embedding.tflite`.
- [ ] Implement ML Kit detector session with frame throttling and model-missing status.
- [ ] Keep behavior safe when the model asset is absent.

### Task 5: ViewModel and HUD Integration

**Files:**
- Modify: `app/src/main/java/com/example/ar_control/ui/preview/PreviewUiState.kt`
- Modify: `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt`
- Modify: `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModelFactory.kt`
- Modify: `app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt`
- Modify: `app/src/main/java/com/example/ar_control/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/example/ar_control/ui/preview/PreviewViewModelTest.kt`

- [ ] Write tests that preview start wires face recognition into the frame fan-out.
- [ ] Write tests that `rememberCurrentFace()` updates UI state for success and blocked states.
- [ ] Render face recognition status text in the preview HUD.
- [ ] Wire production dependencies in `DefaultAppContainer`.

### Task 6: Verification and Release

**Files:**
- All changed files.

- [ ] Run `.\gradlew.bat :app:testDebugUnitTest`.
- [ ] Run `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleAndroidTest :app:assembleRelease`.
- [ ] Verify release APK signature with `apksigner verify --print-certs`.
- [ ] Commit implementation and report model artifact status.

