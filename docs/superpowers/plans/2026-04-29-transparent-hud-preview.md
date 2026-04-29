# Transparent HUD Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a control-screen toggle that makes fullscreen preview emit black background plus overlays only, so XREAL glasses can appear visually transparent.

**Architecture:** Keep the UVC preview session running on the existing `TextureView`, but set the preview texture alpha to `0f` when HUD mode is enabled. The fullscreen preview container remains pure black, and overlays such as detection boxes, recording status, and the back button remain visible.

**Tech Stack:** Native Android XML views, Kotlin `PreviewViewModel`, Robolectric JVM tests, Android instrumentation test compilation.

---

### Task 1: Add HUD Mode State

**Files:**
- Modify: `app/src/main/java/com/example/ar_control/ui/preview/PreviewUiState.kt`
- Modify: `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt`
- Test: `app/src/test/java/com/example/ar_control/ui/preview/PreviewViewModelTest.kt`

- [ ] **Step 1: Write the failing ViewModel test**

Add this test near the existing checkbox preference tests:

```kotlin
@Test
fun setTransparentHudEnabled_updatesUiState() = runTest {
    val viewModel = buildViewModel(cleanupScope = cleanupScope)

    viewModel.setTransparentHudEnabled(true)

    assertTrue(viewModel.uiState.value.transparentHudEnabled)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.ui.preview.PreviewViewModelTest.setTransparentHudEnabled_updatesUiState"`

Expected: FAIL because `setTransparentHudEnabled` and `transparentHudEnabled` do not exist.

- [ ] **Step 3: Implement minimal ViewModel state**

Add to `PreviewUiState`:

```kotlin
val transparentHudEnabled: Boolean = false,
val canChangeTransparentHud: Boolean = true
```

Add to `PreviewViewModel`:

```kotlin
fun setTransparentHudEnabled(enabled: Boolean) {
    if (recoverySnapshot.isSafeMode) {
        sessionLog.record("PreviewViewModel", "Transparent HUD preference change ignored because safe mode is active")
        return
    }
    sessionLog.record("PreviewViewModel", "Transparent HUD preference changed: $enabled")
    _uiState.value = applyRecoveryState(_uiState.value.copy(transparentHudEnabled = enabled))
}
```

Also make `applyRecoveryState()` force `transparentHudEnabled = false` and `canChangeTransparentHud = false` while safe mode is active, and `canChangeTransparentHud = true` otherwise.

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.ui.preview.PreviewViewModelTest.setTransparentHudEnabled_updatesUiState"`

Expected: PASS.

### Task 2: Wire The Control Toggle And Rendering

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/example/ar_control/MainActivity.kt`
- Test: `app/src/test/java/com/example/ar_control/MainActivityUiLogicTest.kt`
- Test: `app/src/androidTest/java/com/example/ar_control/MainActivityTest.kt`

- [ ] **Step 1: Write the failing JVM UI-logic test**

Add this test to `MainActivityUiLogicTest`:

```kotlin
@Test
fun previewTextureAlpha_hidesPreviewOnlyWhenHudModeIsRunning() {
    assertEquals(0f, previewTextureAlpha(isPreviewRunning = true, transparentHudEnabled = true))
    assertEquals(1f, previewTextureAlpha(isPreviewRunning = true, transparentHudEnabled = false))
    assertEquals(1f, previewTextureAlpha(isPreviewRunning = false, transparentHudEnabled = true))
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.MainActivityUiLogicTest.previewTextureAlpha_hidesPreviewOnlyWhenHudModeIsRunning"`

Expected: FAIL because `previewTextureAlpha` does not exist.

- [ ] **Step 3: Implement the XML and Activity wiring**

Add `transparent_hud` string:

```xml
<string name="transparent_hud">Transparent HUD</string>
```

Add a `MaterialCheckBox` with id `transparentHudCheckbox` beside the existing preview options.

In `MainActivity`, add a checked-change listener that calls:

```kotlin
previewViewModel.setTransparentHudEnabled(isChecked)
```

During `render(uiState)`, set:

```kotlin
binding.previewTextureView.alpha = previewTextureAlpha(
    isPreviewRunning = uiState.isPreviewRunning,
    transparentHudEnabled = uiState.transparentHudEnabled
)
binding.detectionOverlayView.alpha = 1f
```

Add this helper to `MainActivity.kt`:

```kotlin
internal fun previewTextureAlpha(
    isPreviewRunning: Boolean,
    transparentHudEnabled: Boolean
): Float {
    return if (isPreviewRunning && transparentHudEnabled) 0f else 1f
}
```

- [ ] **Step 4: Run compile/test verification**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleAndroidTest`

Expected: BUILD SUCCESSFUL.

### Task 3: Document Hardware Validation

**Files:**
- Modify: `docs/hardware/xreal-one-pro-huawei-p60-pro-preview.md`

- [ ] **Step 1: Add the manual hardware checklist**

Add a section that instructs testing with XREAL glasses:

```markdown
## Transparent HUD Preview Check

1. Connect `XREAL One Pro` to the `Huawei P60 Pro`.
2. Open the app.
3. Enable `Transparent HUD`.
4. Tap `Enable Camera`.
5. Tap `Start Preview`.
6. Confirm the glasses show a pure-black background with only overlays visible.
7. Confirm black areas are optically see-through in the glasses.
8. Disable `Transparent HUD`, start preview again, and confirm normal camera preview returns.
```

- [ ] **Step 2: Run docs-neutral verification**

Run: `.\gradlew.bat :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

### Self-Review

- Spec coverage: the plan covers a user-visible toggle, black-background HUD rendering, safe-mode masking, and hardware validation.
- Placeholder scan: no `TBD`, `TODO`, or unspecified implementation steps remain.
- Type consistency: state and method names are consistently `transparentHudEnabled`, `canChangeTransparentHud`, and `setTransparentHudEnabled`.
