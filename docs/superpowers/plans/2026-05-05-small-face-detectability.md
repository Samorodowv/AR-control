# Small Face Detectability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect smaller and farther faces in the existing face-recognition preview pipeline.

**Architecture:** Tune the existing ML Kit detector minimum face size and `FaceBoxFilter` thresholds. Keep the existing aspect-ratio checks and two-hit `FaceBoxStabilizer` as false-positive guardrails.

**Tech Stack:** Kotlin, JUnit4, Android Gradle plugin, ML Kit face detection.

---

## File Structure

- Modify: `app/src/test/java/com/example/ar_control/face/FaceBoxFilterTest.kt`
  - Add a regression test proving a 60x70 px far-face box is accepted at 1280x720.
  - Keep the tiny 40x40 false-positive test rejected.
- Modify: `app/src/main/java/com/example/ar_control/face/FaceBoxFilter.kt`
  - Lower minimum width, height, and area ratios to accept the target far-face box.
- Modify: `app/src/main/java/com/example/ar_control/face/MlKitFaceRecognizer.kt`
  - Lower ML Kit `.setMinFaceSize()` from `0.15f` to `0.08f`.

## Task 1: Relax Post-Detection Box Filter

**Files:**
- Modify: `app/src/test/java/com/example/ar_control/face/FaceBoxFilterTest.kt`
- Modify: `app/src/main/java/com/example/ar_control/face/FaceBoxFilter.kt`

- [x] **Step 1: Write failing test**

Add this test to `FaceBoxFilterTest`:

```kotlin
@Test
fun acceptsFarFaceGeometry() {
    assertTrue(
        filter.isAccepted(
            DetectionBoundingBox(left = 610f, top = 250f, right = 670f, bottom = 320f),
            previewSize
        )
    )
}
```

- [x] **Step 2: Run the filter test and confirm failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.example.ar_control.face.FaceBoxFilterTest
```

Expected: FAIL because the current area threshold rejects the 60x70 px box.

- [x] **Step 3: Implement minimal filter tuning**

Change `FaceBoxFilter` defaults:

```kotlin
private val minWidthRatio: Float = 0.045f,
private val minHeightRatio: Float = 0.065f,
private val minAreaRatio: Float = 0.004f,
```

Keep `minAspectRatio = 0.60f` and `maxAspectRatio = 1.65f`.

- [x] **Step 4: Run the filter test and confirm pass**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.example.ar_control.face.FaceBoxFilterTest
```

Expected: PASS.

## Task 2: Lower ML Kit Minimum Face Size

**Files:**
- Modify: `app/src/main/java/com/example/ar_control/face/MlKitFaceRecognizer.kt`

- [x] **Step 1: Update ML Kit detector setting**

Change:

```kotlin
.setMinFaceSize(0.15f)
```

to:

```kotlin
.setMinFaceSize(0.08f)
```

- [x] **Step 2: Run focused face tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.example.ar_control.face.FaceBoxFilterTest --tests com.example.ar_control.face.FaceBoxStabilizerTest --tests com.example.ar_control.face.MlKitFaceRecognizerPostDetectionStateTest
```

Expected: PASS.

## Task 3: Full Verification

**Files:**
- No production file changes expected.

- [x] **Step 1: Run full debug unit tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: PASS.

- [x] **Step 2: Run release build**

Run:

```powershell
.\gradlew.bat :app:assembleRelease
```

Expected: PASS.
