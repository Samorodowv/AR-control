# Gemma Model Background Download Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the Gemma model download running when the phone locks or the app moves to the background.

**Architecture:** Move download ownership from `PreviewViewModel.viewModelScope` to a unique WorkManager foreground `CoroutineWorker`. The ViewModel only enqueues/observes work and maps worker progress back to the existing download button state.

**Tech Stack:** Kotlin, AndroidX WorkManager 2.11.2, foreground service type `dataSync`, existing `GemmaModelDownloader`.

---

### Task 1: ViewModel Scheduler Contract

**Files:**
- Create: `app/src/main/java/com/example/ar_control/gemma/GemmaModelDownloadScheduler.kt`
- Modify: `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt`
- Modify: `app/src/test/java/com/example/ar_control/ui/preview/PreviewViewModelTest.kt`

- [ ] Write failing tests for enqueue, progress, success, and failure state mapping.
- [ ] Add a `GemmaModelDownloadScheduler` interface exposing `downloadState: StateFlow<GemmaModelDownloadWorkState>` and `enqueueDownload()`.
- [ ] Replace direct downloader calls in `PreviewViewModel` with scheduler observation.
- [ ] Verify `:app:testDebugUnitTest --tests "com.example.ar_control.ui.preview.PreviewViewModelTest"`.

### Task 2: Foreground Worker

**Files:**
- Create: `app/src/main/java/com/example/ar_control/gemma/GemmaModelDownloadWorker.kt`
- Create: `app/src/main/res/drawable/ic_gemma_download_notification.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] Implement a `CoroutineWorker` that creates a foreground notification, calls `GemmaModelDownloader`, writes progress with `setProgress`, and returns success/failure output data.
- [ ] Use `ForegroundInfo(..., ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)` on Android 10+.
- [ ] Reuse the existing downloader checksum and app-private storage path.

### Task 3: WorkManager Wiring

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt`
- Modify: `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModelFactory.kt`

- [ ] Add `androidx.work:work-runtime-ktx:2.11.2`.
- [ ] Declare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and merge WorkManager `SystemForegroundService` with `android:foregroundServiceType="dataSync"`.
- [ ] Inject `WorkManagerGemmaModelDownloadScheduler` into the ViewModel.

### Task 4: Verification

**Files:**
- Run commands only.

- [ ] Run `.\gradlew.bat :app:testDebugUnitTest`.
- [ ] Run `.\gradlew.bat :app:assembleAndroidTest`.
- [ ] Run `.\gradlew.bat :app:assembleRelease`.
