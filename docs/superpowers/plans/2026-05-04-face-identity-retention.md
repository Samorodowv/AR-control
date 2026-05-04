# Face Identity Retention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep approved/banned face colors stable through short single-face recognition misses.

**Architecture:** Extend `FaceIdentityStabilizer` with a 3-frame retainable miss window. `MlKitFaceRecognizer` will use retention only after exactly one stable face reaches embedding/matching; no-face, multiple-face, and failure paths still clear identity immediately.

**Tech Stack:** Kotlin, JUnit4, Android Gradle plugin, ML Kit face detection, LiteRT face embeddings.

---

## File Structure

- Modify: `app/src/main/java/com/example/ar_control/face/FaceIdentityStabilizer.kt`
  - Add retained stable face storage and miss counting.
  - Add a `retainStableIdentityOnMiss` parameter to `decide()`.
- Modify: `app/src/main/java/com/example/ar_control/face/MlKitFaceRecognizer.kt`
  - Use retainable misses only for single stable face embedding results.
- Modify: `app/src/test/java/com/example/ar_control/face/FaceIdentityStabilizerTest.kt`
  - Add regression tests for retained identity behavior.

## Task 1: Retain Stable Identity Through Single-Face Misses

**Files:**
- Modify: `app/src/test/java/com/example/ar_control/face/FaceIdentityStabilizerTest.kt`
- Modify: `app/src/main/java/com/example/ar_control/face/FaceIdentityStabilizer.kt`

- [x] **Step 1: Write failing tests**

Add tests that prove:

```kotlin
assertEquals(bannedFace, stabilizer.decide(null, retainStableIdentityOnMiss = true))
```

returns the stable face for three retainable misses, then returns `null` on the fourth miss. Also verify a non-retainable miss clears immediately and a different candidate still needs two consecutive matches.

- [x] **Step 2: Run tests and confirm failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.example.ar_control.face.FaceIdentityStabilizerTest
```

Expected: FAIL because `decide()` does not accept `retainStableIdentityOnMiss` and stable identity is cleared on any null match.

- [x] **Step 3: Implement minimal stabilizer change**

Change `FaceIdentityStabilizer` so it stores the stable `RememberedFace`, counts retainable misses, and clears only after 3 retainable misses. Keep the default behavior unchanged by using `retainStableIdentityOnMiss: Boolean = false`.

- [x] **Step 4: Run tests and confirm pass**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.example.ar_control.face.FaceIdentityStabilizerTest
```

Expected: PASS.

## Task 2: Wire Retention Into Face Recognition

**Files:**
- Modify: `app/src/main/java/com/example/ar_control/face/MlKitFaceRecognizer.kt`
- Test: `app/src/test/java/com/example/ar_control/face/MlKitFaceRecognizerPostDetectionStateTest.kt`

- [x] **Step 1: Update recognizer call sites**

Use:

```kotlin
identityStabilizer.decide(match, retainStableIdentityOnMiss = true)
```

only in the single-face embedding recognition path. Keep `identityStabilizer.decide(null)` unchanged for pre-embedding no-face/multiple-face states and frame failures.

- [x] **Step 2: Run focused face tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.example.ar_control.face.FaceIdentityStabilizerTest --tests com.example.ar_control.face.MlKitFaceRecognizerPostDetectionStateTest --tests com.example.ar_control.face.FaceEmbeddingMatcherTest --tests com.example.ar_control.face.FaceBoxStabilizerTest
```

Expected: PASS.

## Task 3: Regression Verification

**Files:**
- No production file changes expected.

- [x] **Step 1: Run UI-facing color and view model tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.example.ar_control.ui.preview.DetectionOverlayViewTest --tests com.example.ar_control.ui.preview.PreviewViewModelTest
```

Expected: PASS.

- [x] **Step 2: Run full debug unit test suite**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: PASS.
