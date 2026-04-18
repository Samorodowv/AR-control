# XREAL One Pro Preview Recording Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional preview-tied video recording, persist a clip history in app-owned storage, and expose a control-screen grid with open/share/delete actions for recorded clips.

**Architecture:** Keep the existing single-activity + `PreviewViewModel` flow, but add a separate recording subsystem that starts only after preview succeeds. Recording state, clip selection, and clip-list refresh stay in the ViewModel; the UVC layer gains explicit start/stop capture hooks; Android intents for open/share stay in the activity layer through a small sharer helper.

**Tech Stack:** Kotlin, Android Views, ViewModel + StateFlow, RecyclerView/GridLayoutManager, SharedPreferences/DataStore-style app preference wrapper, JSON-backed catalog in app files, MediaCodec + MediaMuxer, Android FileProvider, vendored UVC transport, JUnit4, Espresso

---

## Preflight

- Fix the current Git safe-directory issue before using commit checkpoints:

```powershell
git config --global --add safe.directory C:/Users/nikolay/AndroidStudioProjects/AR_Control
```

- Use `Huawei P60 Pro` as the validation device.
- Keep milestone scope to video-only recording; do not add audio, thumbnails, gallery export, or in-app playback.
- Validate debug builds first before rebuilding the signed release APK.

## Planned File Structure

### Existing files to modify

- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/dimens.xml`
- `app/src/main/java/com/example/ar_control/MainActivity.kt`
- `app/src/main/java/com/example/ar_control/di/AppContainer.kt`
- `app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt`
- `app/src/main/java/com/example/ar_control/ui/preview/PreviewUiState.kt`
- `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt`
- `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModelFactory.kt`
- `app/src/main/java/com/example/ar_control/camera/CameraSource.kt`
- `app/src/main/java/com/example/ar_control/camera/UvcCameraSource.kt`
- `app/src/main/java/com/example/ar_control/camera/UvcLibraryAdapter.kt`
- `app/src/main/java/com/example/ar_control/camera/AndroidUvcLibraryAdapter.kt`
- `app/src/main/java/com/example/ar_control/diagnostics/DiagnosticsReportBuilder.kt`
- `app/src/test/java/com/example/ar_control/ui/preview/PreviewViewModelTest.kt`
- `app/src/androidTest/java/com/example/ar_control/MainActivityTest.kt`
- `docs/hardware/xreal-one-pro-huawei-p60-pro-preview.md`

### New app files

- `app/src/main/java/com/example/ar_control/recording/RecordedClip.kt`
- `app/src/main/java/com/example/ar_control/recording/RecordingStatus.kt`
- `app/src/main/java/com/example/ar_control/recording/RecordingPreferences.kt`
- `app/src/main/java/com/example/ar_control/recording/SharedPreferencesRecordingPreferences.kt`
- `app/src/main/java/com/example/ar_control/recording/ClipRepository.kt`
- `app/src/main/java/com/example/ar_control/recording/JsonClipRepository.kt`
- `app/src/main/java/com/example/ar_control/recording/VideoRecorder.kt`
- `app/src/main/java/com/example/ar_control/recording/MediaCodecVideoRecorder.kt`
- `app/src/main/java/com/example/ar_control/recording/ClipFileSharer.kt`
- `app/src/main/java/com/example/ar_control/recording/AndroidClipFileSharer.kt`
- `app/src/main/java/com/example/ar_control/ui/clips/RecordedClipListItem.kt`
- `app/src/main/java/com/example/ar_control/ui/clips/RecordedClipAdapter.kt`
- `app/src/main/res/layout/item_recorded_clip.xml`
- `app/src/main/res/xml/file_paths.xml`

### New test files

- `app/src/test/java/com/example/ar_control/recording/SharedPreferencesRecordingPreferencesTest.kt`
- `app/src/test/java/com/example/ar_control/recording/JsonClipRepositoryTest.kt`
- `app/src/test/java/com/example/ar_control/recording/MediaCodecVideoRecorderTest.kt`
- `app/src/test/java/com/example/ar_control/camera/UvcCameraSourceRecordingTest.kt`
- `app/src/test/java/com/example/ar_control/recording/AndroidClipFileSharerTest.kt`

### Ownership and boundaries

- `recording/*` owns persisted clip metadata, recording preference, recording lifecycle, and file sharing helpers.
- `camera/*` owns UVC preview open/close plus attaching and detaching the recording capture surface.
- `ui/preview/*` owns user-visible screen state and orchestration between preview, recording, and clip list.
- `ui/clips/*` owns grid rendering only.
- `MainActivity` owns permission prompts, fullscreen transitions, delete confirmation dialog, and Android intents.

### Dependency direction

- `MainActivity` depends on `PreviewViewModel` and `ClipFileSharer`.
- `PreviewViewModel` depends on `GlassesSession`, `EyeUsbConfigurator`, `UsbPermissionGateway`, `CameraSource`, `RecordingPreferences`, `ClipRepository`, and `VideoRecorder`.
- `UvcCameraSource` depends on `UvcLibraryAdapter`.
- `MediaCodecVideoRecorder` depends on Android media APIs and app-owned storage paths.

## Task 1: Add recording preferences and clip catalog foundations

**Files:**
- Create: `app/src/main/java/com/example/ar_control/recording/RecordedClip.kt`
- Create: `app/src/main/java/com/example/ar_control/recording/RecordingStatus.kt`
- Create: `app/src/main/java/com/example/ar_control/recording/RecordingPreferences.kt`
- Create: `app/src/main/java/com/example/ar_control/recording/SharedPreferencesRecordingPreferences.kt`
- Create: `app/src/main/java/com/example/ar_control/recording/ClipRepository.kt`
- Create: `app/src/main/java/com/example/ar_control/recording/JsonClipRepository.kt`
- Test: `app/src/test/java/com/example/ar_control/recording/SharedPreferencesRecordingPreferencesTest.kt`
- Test: `app/src/test/java/com/example/ar_control/recording/JsonClipRepositoryTest.kt`

- [ ] **Step 1: Write failing tests for recording preference persistence**

```kotlin
package com.example.ar_control.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SharedPreferencesRecordingPreferencesTest {

    private lateinit var preferences: SharedPreferencesRecordingPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("recording_prefs_test", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = SharedPreferencesRecordingPreferences(
            context = context,
            fileName = "recording_prefs_test"
        )
    }

    @Test
    fun defaultsToDisabled() {
        assertFalse(preferences.isRecordingEnabled())
    }

    @Test
    fun persistsUpdatedValue() {
        preferences.setRecordingEnabled(true)
        assertTrue(preferences.isRecordingEnabled())
    }
}
```

- [ ] **Step 2: Write failing tests for JSON clip catalog ordering and stale-file cleanup**

```kotlin
package com.example.ar_control.recording

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonClipRepositoryTest {

    @Test
    fun loadClips_returnsNewestFirst() = runTest {
        val dir = createTempDir(prefix = "clip_repo")
        val fileOne = File(dir, "one.mp4").apply { writeBytes(byteArrayOf(1)) }
        val fileTwo = File(dir, "two.mp4").apply { writeBytes(byteArrayOf(2)) }
        val repository = JsonClipRepository(
            catalogFile = File(dir, "clips.json")
        )

        repository.insert(
            RecordedClip(
                id = "older",
                filePath = fileOne.absolutePath,
                createdAtEpochMillis = 1_000L,
                durationMillis = 100L,
                width = 1920,
                height = 1080,
                fileSizeBytes = fileOne.length(),
                mimeType = "video/mp4"
            )
        )
        repository.insert(
            RecordedClip(
                id = "newer",
                filePath = fileTwo.absolutePath,
                createdAtEpochMillis = 2_000L,
                durationMillis = 100L,
                width = 1920,
                height = 1080,
                fileSizeBytes = fileTwo.length(),
                mimeType = "video/mp4"
            )
        )

        assertEquals(listOf("newer", "older"), repository.load().map { it.id })
    }

    @Test
    fun loadClips_removesMissingFiles() = runTest {
        val dir = createTempDir(prefix = "clip_repo_missing")
        val missing = File(dir, "missing.mp4")
        val repository = JsonClipRepository(
            catalogFile = File(dir, "clips.json")
        )

        repository.insert(
            RecordedClip(
                id = "missing",
                filePath = missing.absolutePath,
                createdAtEpochMillis = 3_000L,
                durationMillis = 100L,
                width = 1920,
                height = 1080,
                fileSizeBytes = 0L,
                mimeType = "video/mp4"
            )
        )

        assertTrue(repository.load().isEmpty())
    }
}
```

- [ ] **Step 3: Run the new tests to verify they fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.recording.SharedPreferencesRecordingPreferencesTest" --tests "com.example.ar_control.recording.JsonClipRepositoryTest"
```

Expected: FAIL because the recording model, preference wrapper, and clip repository classes do not exist yet.

- [ ] **Step 4: Add the recording model and preference/repository interfaces**

`app/src/main/java/com/example/ar_control/recording/RecordedClip.kt`

```kotlin
package com.example.ar_control.recording

data class RecordedClip(
    val id: String,
    val filePath: String,
    val createdAtEpochMillis: Long,
    val durationMillis: Long,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long,
    val mimeType: String
)
```

`app/src/main/java/com/example/ar_control/recording/RecordingStatus.kt`

```kotlin
package com.example.ar_control.recording

sealed interface RecordingStatus {
    data object Idle : RecordingStatus
    data object Starting : RecordingStatus
    data object Recording : RecordingStatus
    data object Finalizing : RecordingStatus
    data class Failed(val reason: String) : RecordingStatus
}
```

`app/src/main/java/com/example/ar_control/recording/RecordingPreferences.kt`

```kotlin
package com.example.ar_control.recording

interface RecordingPreferences {
    fun isRecordingEnabled(): Boolean
    fun setRecordingEnabled(enabled: Boolean)
}
```

`app/src/main/java/com/example/ar_control/recording/ClipRepository.kt`

```kotlin
package com.example.ar_control.recording

interface ClipRepository {
    suspend fun load(): List<RecordedClip>
    suspend fun insert(clip: RecordedClip)
    suspend fun delete(clipId: String): Boolean
}
```

- [ ] **Step 5: Implement the shared-preferences wrapper and JSON-backed catalog**

`app/src/main/java/com/example/ar_control/recording/SharedPreferencesRecordingPreferences.kt`

```kotlin
package com.example.ar_control.recording

import android.content.Context

class SharedPreferencesRecordingPreferences(
    context: Context,
    fileName: String = "recording_prefs"
) : RecordingPreferences {

    private val preferences = context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override fun isRecordingEnabled(): Boolean {
        return preferences.getBoolean(KEY_RECORDING_ENABLED, false)
    }

    override fun setRecordingEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_RECORDING_ENABLED, enabled).apply()
    }

    private companion object {
        const val KEY_RECORDING_ENABLED = "recording_enabled"
    }
}
```

`app/src/main/java/com/example/ar_control/recording/JsonClipRepository.kt`

```kotlin
package com.example.ar_control.recording

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class JsonClipRepository(
    private val catalogFile: File
) : ClipRepository {

    override suspend fun load(): List<RecordedClip> = withContext(Dispatchers.IO) {
        val clips = readAll().filter { File(it.filePath).exists() }
            .sortedByDescending { it.createdAtEpochMillis }
        writeAll(clips)
        clips
    }

    override suspend fun insert(clip: RecordedClip) = withContext(Dispatchers.IO) {
        val updated = (readAll() + clip).sortedByDescending { it.createdAtEpochMillis }
        writeAll(updated)
    }

    override suspend fun delete(clipId: String): Boolean = withContext(Dispatchers.IO) {
        val existing = readAll()
        val target = existing.firstOrNull { it.id == clipId } ?: return@withContext false
        val fileDeleted = runCatching { File(target.filePath).delete() }.getOrDefault(false)
        val updated = existing.filterNot { it.id == clipId }
        writeAll(updated)
        fileDeleted || !File(target.filePath).exists()
    }

    private fun readAll(): List<RecordedClip> {
        if (!catalogFile.exists()) return emptyList()
        val raw = catalogFile.readText()
        if (raw.isBlank()) return emptyList()
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                add(array.getJSONObject(index).toRecordedClip())
            }
        }
    }

    private fun writeAll(clips: List<RecordedClip>) {
        catalogFile.parentFile?.mkdirs()
        val array = JSONArray()
        clips.forEach { clip -> array.put(clip.toJson()) }
        catalogFile.writeText(array.toString())
    }

    private fun RecordedClip.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("filePath", filePath)
        .put("createdAtEpochMillis", createdAtEpochMillis)
        .put("durationMillis", durationMillis)
        .put("width", width)
        .put("height", height)
        .put("fileSizeBytes", fileSizeBytes)
        .put("mimeType", mimeType)

    private fun JSONObject.toRecordedClip(): RecordedClip = RecordedClip(
        id = getString("id"),
        filePath = getString("filePath"),
        createdAtEpochMillis = getLong("createdAtEpochMillis"),
        durationMillis = getLong("durationMillis"),
        width = getInt("width"),
        height = getInt("height"),
        fileSizeBytes = getLong("fileSizeBytes"),
        mimeType = getString("mimeType")
    )
}
```

- [ ] **Step 6: Run the preference/repository tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.recording.SharedPreferencesRecordingPreferencesTest" --tests "com.example.ar_control.recording.JsonClipRepositoryTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/ar_control/recording/RecordedClip.kt app/src/main/java/com/example/ar_control/recording/RecordingStatus.kt app/src/main/java/com/example/ar_control/recording/RecordingPreferences.kt app/src/main/java/com/example/ar_control/recording/SharedPreferencesRecordingPreferences.kt app/src/main/java/com/example/ar_control/recording/ClipRepository.kt app/src/main/java/com/example/ar_control/recording/JsonClipRepository.kt app/src/test/java/com/example/ar_control/recording/SharedPreferencesRecordingPreferencesTest.kt app/src/test/java/com/example/ar_control/recording/JsonClipRepositoryTest.kt
git commit -m "feat: add recording preferences and clip catalog"
```

## Task 2: Add clip sharing/opening support through FileProvider

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/file_paths.xml`
- Create: `app/src/main/java/com/example/ar_control/recording/ClipFileSharer.kt`
- Create: `app/src/main/java/com/example/ar_control/recording/AndroidClipFileSharer.kt`
- Create: `app/src/test/java/com/example/ar_control/recording/AndroidClipFileSharerTest.kt`

- [ ] **Step 1: Write a failing test for sharer intent construction**

```kotlin
package com.example.ar_control.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidClipFileSharerTest {

    @Test
    fun buildShareIntent_usesVideoMimeType() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sharer = AndroidClipFileSharer(context)
        val clip = RecordedClip(
            id = "clip",
            filePath = context.cacheDir.resolve("clip.mp4").apply { writeBytes(byteArrayOf(1)) }.absolutePath,
            createdAtEpochMillis = 1L,
            durationMillis = 100L,
            width = 1920,
            height = 1080,
            fileSizeBytes = 1L,
            mimeType = "video/mp4"
        )

        val intent = sharer.buildShareIntent(clip)
        assertEquals("video/mp4", intent.type)
    }
}
```

- [ ] **Step 2: Run the sharer test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.recording.AndroidClipFileSharerTest"
```

Expected: FAIL because the sharer and FileProvider setup do not exist yet.

- [ ] **Step 3: Add the FileProvider manifest and path configuration**

`app/src/main/AndroidManifest.xml`

```xml
<application
    ...>

    <provider
        android:name="androidx.core.content.FileProvider"
        android:authorities="${applicationId}.fileprovider"
        android:exported="false"
        android:grantUriPermissions="true">
        <meta-data
            android:name="android.support.FILE_PROVIDER_PATHS"
            android:resource="@xml/file_paths" />
    </provider>

</application>
```

`app/src/main/res/xml/file_paths.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <external-files-path
        name="recordings"
        path="Movies/recordings/" />
</paths>
```

- [ ] **Step 4: Add the sharer interface and Android implementation**

`app/src/main/java/com/example/ar_control/recording/ClipFileSharer.kt`

```kotlin
package com.example.ar_control.recording

import android.content.Intent

interface ClipFileSharer {
    fun buildOpenIntent(clip: RecordedClip): Intent
    fun buildShareIntent(clip: RecordedClip): Intent
}
```

`app/src/main/java/com/example/ar_control/recording/AndroidClipFileSharer.kt`

```kotlin
package com.example.ar_control.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

class AndroidClipFileSharer(
    private val context: Context
) : ClipFileSharer {

    override fun buildOpenIntent(clip: RecordedClip): Intent {
        val uri = clip.toContentUri()
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, clip.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun buildShareIntent(clip: RecordedClip): Intent {
        val uri = clip.toContentUri()
        return Intent(Intent.ACTION_SEND).apply {
            type = clip.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun RecordedClip.toContentUri() = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        File(filePath)
    )
}
```

- [ ] **Step 5: Run the sharer test**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.recording.AndroidClipFileSharerTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/xml/file_paths.xml app/src/main/java/com/example/ar_control/recording/ClipFileSharer.kt app/src/main/java/com/example/ar_control/recording/AndroidClipFileSharer.kt app/src/test/java/com/example/ar_control/recording/AndroidClipFileSharerTest.kt
git commit -m "feat: add clip sharing and file provider"
```

## Task 3: Add the recorder abstraction and MediaCodec-backed MP4 writer

**Files:**
- Create: `app/src/main/java/com/example/ar_control/recording/VideoRecorder.kt`
- Create: `app/src/main/java/com/example/ar_control/recording/MediaCodecVideoRecorder.kt`
- Test: `app/src/test/java/com/example/ar_control/recording/MediaCodecVideoRecorderTest.kt`

- [ ] **Step 1: Write failing tests for recorder session transitions**

```kotlin
package com.example.ar_control.recording

import com.example.ar_control.camera.PreviewSize
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCodecVideoRecorderTest {

    @Test
    fun start_returnsCaptureSurfaceAndOutputFile() = runTest {
        val recorder = FakeMediaCodecVideoRecorder()
        val result = recorder.start(PreviewSize(width = 1920, height = 1080))
        assertTrue(result is VideoRecorder.StartResult.Started)
    }

    @Test
    fun stop_returnsFinishedClipMetadata() = runTest {
        val recorder = FakeMediaCodecVideoRecorder()
        recorder.start(PreviewSize(width = 1920, height = 1080))
        val result = recorder.stop()
        assertTrue(result is VideoRecorder.StopResult.Finished)
    }
}
```

- [ ] **Step 2: Run the recorder tests to verify they fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.recording.MediaCodecVideoRecorderTest"
```

Expected: FAIL because the recorder interface and implementation do not exist yet.

- [ ] **Step 3: Add the recorder interface with explicit start/stop/cancel results**

`app/src/main/java/com/example/ar_control/recording/VideoRecorder.kt`

```kotlin
package com.example.ar_control.recording

import android.view.Surface
import com.example.ar_control.camera.PreviewSize

interface VideoRecorder {
    sealed interface StartResult {
        data class Started(
            val captureSurface: Surface,
            val outputFilePath: String,
            val startedAtEpochMillis: Long
        ) : StartResult

        data class Failed(val reason: String) : StartResult
    }

    sealed interface StopResult {
        data class Finished(val clip: RecordedClip) : StopResult
        data class Failed(val reason: String) : StopResult
    }

    suspend fun start(previewSize: PreviewSize): StartResult
    suspend fun stop(): StopResult
    suspend fun cancel()
}
```

- [ ] **Step 4: Implement a `MediaCodecVideoRecorder` that writes MP4 files into app-scoped storage**

`app/src/main/java/com/example/ar_control/recording/MediaCodecVideoRecorder.kt`

```kotlin
package com.example.ar_control.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.view.Surface
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.diagnostics.NoOpSessionLog
import com.example.ar_control.diagnostics.SessionLog
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaCodecVideoRecorder(
    private val outputDirectory: File,
    private val sessionLog: SessionLog = NoOpSessionLog
) : VideoRecorder {

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var outputFile: File? = null
    private var startedAtEpochMillis: Long = 0L
    private var previewSize: PreviewSize? = null

    override suspend fun start(previewSize: PreviewSize): VideoRecorder.StartResult = withContext(Dispatchers.IO) {
        runCatching {
            outputDirectory.mkdirs()
            val file = File(outputDirectory, "clip-${System.currentTimeMillis()}-${UUID.randomUUID()}.mp4")
            val format = MediaFormat.createVideoFormat(MIME_TYPE, previewSize.width, previewSize.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, previewSize.width * previewSize.height * 6)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val createdCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            createdCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val createdSurface = createdCodec.createInputSurface()
            createdCodec.start()

            val createdMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            codec = createdCodec
            muxer = createdMuxer
            inputSurface = createdSurface
            outputFile = file
            startedAtEpochMillis = System.currentTimeMillis()
            this@MediaCodecVideoRecorder.previewSize = previewSize

            sessionLog.record("MediaCodecVideoRecorder", "Recording prepared at ${file.absolutePath}")
            VideoRecorder.StartResult.Started(
                captureSurface = createdSurface,
                outputFilePath = file.absolutePath,
                startedAtEpochMillis = startedAtEpochMillis
            )
        }.getOrElse { error ->
            cancelInternal()
            VideoRecorder.StartResult.Failed(error.message ?: "recording_prepare_failed")
        }
    }

    override suspend fun stop(): VideoRecorder.StopResult = withContext(Dispatchers.IO) {
        val file = outputFile ?: return@withContext VideoRecorder.StopResult.Failed("recording_not_started")
        val size = previewSize ?: return@withContext VideoRecorder.StopResult.Failed("missing_preview_size")
        val duration = (System.currentTimeMillis() - startedAtEpochMillis).coerceAtLeast(0L)

        runCatching {
            drainEncoderEndOfStream()
            releaseEncoder()
            val clip = RecordedClip(
                id = file.nameWithoutExtension,
                filePath = file.absolutePath,
                createdAtEpochMillis = startedAtEpochMillis,
                durationMillis = duration,
                width = size.width,
                height = size.height,
                fileSizeBytes = file.length(),
                mimeType = MIME_TYPE
            )
            sessionLog.record("MediaCodecVideoRecorder", "Recording finalized at ${file.absolutePath}")
            clip
        }.fold(
            onSuccess = { clip -> VideoRecorder.StopResult.Finished(clip) },
            onFailure = { error ->
                cancelInternal()
                VideoRecorder.StopResult.Failed(error.message ?: "recording_finalize_failed")
            }
        )
    }

    override suspend fun cancel() = withContext(Dispatchers.IO) {
        cancelInternal()
    }

    private fun drainEncoderEndOfStream() {
        // Implement buffer draining and muxer track start here.
        // Keep the draining loop private to this class.
    }

    private fun releaseEncoder() {
        runCatching { inputSurface?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        inputSurface = null
        codec = null
        muxer = null
        outputFile = null
        previewSize = null
    }

    private fun cancelInternal() {
        val file = outputFile
        releaseEncoder()
        runCatching { file?.delete() }
    }

    private companion object {
        const val MIME_TYPE = "video/avc"
    }
}
```

Implementation note: if the drain loop becomes too large, split it into a `MediaCodecMuxerDrainer.kt` helper in the same package, but keep the public interface unchanged.

- [ ] **Step 5: Run the recorder tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.recording.MediaCodecVideoRecorderTest"
```

Expected: PASS after adjusting the test double or the real recorder test seam to avoid full hardware codec dependence in unit tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/ar_control/recording/VideoRecorder.kt app/src/main/java/com/example/ar_control/recording/MediaCodecVideoRecorder.kt app/src/test/java/com/example/ar_control/recording/MediaCodecVideoRecorderTest.kt
git commit -m "feat: add media codec video recorder"
```

## Task 4: Extend the camera layer to start and stop recording capture independently of preview

**Files:**
- Modify: `app/src/main/java/com/example/ar_control/camera/CameraSource.kt`
- Modify: `app/src/main/java/com/example/ar_control/camera/UvcCameraSource.kt`
- Modify: `app/src/main/java/com/example/ar_control/camera/UvcLibraryAdapter.kt`
- Modify: `app/src/main/java/com/example/ar_control/camera/AndroidUvcLibraryAdapter.kt`
- Test: `app/src/test/java/com/example/ar_control/camera/UvcCameraSourceRecordingTest.kt`

- [ ] **Step 1: Write failing tests for starting and stopping capture after preview start**

```kotlin
package com.example.ar_control.camera

import android.view.Surface
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UvcCameraSourceRecordingTest {

    @Test
    fun startRecording_mapsAdapterStartedResult() = runTest {
        val adapter = FakeUvcLibraryAdapter(recordingResult = UvcLibraryAdapter.RecordingResult.Started)
        val source = UvcCameraSource(adapter)

        val result = source.startRecording(FakeSurface())

        assertEquals(CameraSource.RecordingStartResult.Started, result)
    }
}

private class FakeSurface : Surface(null)
```

- [ ] **Step 2: Run the camera recording tests to verify they fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.camera.UvcCameraSourceRecordingTest"
```

Expected: FAIL because `CameraSource` and `UvcLibraryAdapter` do not yet expose recording methods.

- [ ] **Step 3: Extend the camera interfaces with explicit recording hooks**

`app/src/main/java/com/example/ar_control/camera/CameraSource.kt`

```kotlin
package com.example.ar_control.camera

import android.view.Surface

interface CameraSource {
    interface SurfaceToken

    sealed interface StartResult {
        data class Started(val previewSize: PreviewSize) : StartResult
        data class Failed(val reason: String) : StartResult
    }

    sealed interface RecordingStartResult {
        data object Started : RecordingStartResult
        data class Failed(val reason: String) : RecordingStartResult
    }

    suspend fun start(surfaceToken: SurfaceToken): StartResult
    suspend fun stop()
    suspend fun startRecording(captureSurface: Surface): RecordingStartResult
    suspend fun stopRecording()
}
```

`app/src/main/java/com/example/ar_control/camera/UvcLibraryAdapter.kt`

```kotlin
package com.example.ar_control.camera

import android.view.Surface

interface UvcLibraryAdapter {
    sealed interface OpenResult { /* keep existing variants */ }

    sealed interface RecordingResult {
        data object Started : RecordingResult
        data object NotOpen : RecordingResult
        data object AlreadyRecording : RecordingResult
        data object Failed : RecordingResult
    }

    suspend fun open(surfaceToken: CameraSource.SurfaceToken): OpenResult
    suspend fun stop()
    suspend fun startRecording(captureSurface: Surface): RecordingResult
    suspend fun stopRecording()
}
```

- [ ] **Step 4: Implement recording capture in `UvcCameraSource` and `AndroidUvcLibraryAdapter`**

`app/src/main/java/com/example/ar_control/camera/UvcCameraSource.kt`

```kotlin
override suspend fun startRecording(captureSurface: Surface): CameraSource.RecordingStartResult {
    return when (adapter.startRecording(captureSurface)) {
        UvcLibraryAdapter.RecordingResult.Started -> CameraSource.RecordingStartResult.Started
        UvcLibraryAdapter.RecordingResult.NotOpen -> CameraSource.RecordingStartResult.Failed("recording_camera_not_open")
        UvcLibraryAdapter.RecordingResult.AlreadyRecording -> CameraSource.RecordingStartResult.Failed("recording_already_running")
        UvcLibraryAdapter.RecordingResult.Failed -> CameraSource.RecordingStartResult.Failed("recording_start_failed")
    }
}

override suspend fun stopRecording() {
    runCatching { adapter.stopRecording() }
}
```

`app/src/main/java/com/example/ar_control/camera/AndroidUvcLibraryAdapter.kt`

```kotlin
private var isCaptureRunning = false

override suspend fun startRecording(captureSurface: Surface): UvcLibraryAdapter.RecordingResult {
    val camera = synchronized(stateLock) { uvcCamera } ?: return UvcLibraryAdapter.RecordingResult.NotOpen
    if (isCaptureRunning) return UvcLibraryAdapter.RecordingResult.AlreadyRecording
    return runCatching {
        camera.startCapture(captureSurface)
        isCaptureRunning = true
        sessionLog.record("AndroidUvcLibraryAdapter", "UVC recording capture started")
        UvcLibraryAdapter.RecordingResult.Started
    }.getOrElse {
        sessionLog.record("AndroidUvcLibraryAdapter", "UVC recording capture failed: ${it.message ?: "unknown_error"}")
        UvcLibraryAdapter.RecordingResult.Failed
    }
}

override suspend fun stopRecording() {
    val camera = synchronized(stateLock) { uvcCamera } ?: return
    if (!isCaptureRunning) return
    runCatching { camera.stopCapture() }
    isCaptureRunning = false
    sessionLog.record("AndroidUvcLibraryAdapter", "UVC recording capture stopped")
}
```

Make sure `releaseOwnedResources()` also resets `isCaptureRunning = false`.

- [ ] **Step 5: Run the camera recording tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.camera.UvcCameraSourceRecordingTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/ar_control/camera/CameraSource.kt app/src/main/java/com/example/ar_control/camera/UvcCameraSource.kt app/src/main/java/com/example/ar_control/camera/UvcLibraryAdapter.kt app/src/main/java/com/example/ar_control/camera/AndroidUvcLibraryAdapter.kt app/src/test/java/com/example/ar_control/camera/UvcCameraSourceRecordingTest.kt
git commit -m "feat: add camera recording capture hooks"
```

## Task 5: Teach `PreviewViewModel` to coordinate recording and clip history

**Files:**
- Modify: `app/src/main/java/com/example/ar_control/ui/preview/PreviewUiState.kt`
- Modify: `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt`
- Modify: `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModelFactory.kt`
- Test: `app/src/test/java/com/example/ar_control/ui/preview/PreviewViewModelTest.kt`

- [ ] **Step 1: Add failing ViewModel tests for recording-on-preview-start and clip selection**

```kotlin
@Test
fun startPreview_withRecordingEnabled_startsRecorderAfterPreview() = runTest {
    val cameraSource = FakeCameraSource(startResult = CameraSource.StartResult.Started(PreviewSize(1920, 1080)))
    val recorder = FakeVideoRecorder()
    val preferences = FakeRecordingPreferences(enabled = true)
    val repository = FakeClipRepository()
    val viewModel = PreviewViewModel(
        glassesSession = FakeGlassesSession.available(),
        eyeUsbConfigurator = FakeEyeUsbConfigurator.success(),
        usbPermissionGateway = FakeUsbPermissionGateway(granted = true),
        cameraSource = cameraSource,
        recordingPreferences = preferences,
        clipRepository = repository,
        videoRecorder = recorder,
        sessionLog = NoOpSessionLog
    )

    viewModel.startPreview(FakeSurfaceToken())
    advanceUntilIdle()

    assertTrue(recorder.started)
    assertTrue(viewModel.uiState.value.isPreviewRunning)
}

@Test
fun deleteSelectedClip_removesItFromGrid() = runTest {
    val clip = RecordedClip(/* ... */)
    val repository = FakeClipRepository(clips = mutableListOf(clip))
    val viewModel = buildViewModel(clipRepository = repository)

    viewModel.selectClip(clip.id)
    viewModel.deleteSelectedClip()
    advanceUntilIdle()

    assertTrue(viewModel.uiState.value.recordedClips.isEmpty())
}
```

- [ ] **Step 2: Run the ViewModel tests to verify they fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.ui.preview.PreviewViewModelTest"
```

Expected: FAIL because the ViewModel does not yet know about recording preferences, recorder state, or recorded clips.

- [ ] **Step 3: Expand `PreviewUiState` with recording and clip-grid state**

`app/src/main/java/com/example/ar_control/ui/preview/PreviewUiState.kt`

```kotlin
package com.example.ar_control.ui.preview

import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.recording.RecordedClip
import com.example.ar_control.recording.RecordingStatus

data class PreviewUiState(
    val glassesStatus: String = "Glasses: disconnected",
    val cameraStatus: String = "Camera: idle",
    val canEnableCamera: Boolean = true,
    val canStartPreview: Boolean = false,
    val canStopPreview: Boolean = false,
    val isPreviewRunning: Boolean = false,
    val previewSize: PreviewSize? = null,
    val zoomFactor: Float = 1.0f,
    val recordVideoEnabled: Boolean = false,
    val recordingStatus: RecordingStatus = RecordingStatus.Idle,
    val recordedClips: List<RecordedClip> = emptyList(),
    val selectedClipId: String? = null,
    val errorMessage: String? = null
) {
    val selectedClip: RecordedClip?
        get() = recordedClips.firstOrNull { it.id == selectedClipId }

    val canOpenSelectedClip: Boolean
        get() = selectedClip != null

    val canShareSelectedClip: Boolean
        get() = selectedClip != null

    val canDeleteSelectedClip: Boolean
        get() = selectedClip != null
}
```

- [ ] **Step 4: Inject preferences, repository, and recorder into the ViewModel factory**

`app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModelFactory.kt`

```kotlin
class PreviewViewModelFactory(
    private val glassesSession: GlassesSession,
    private val eyeUsbConfigurator: EyeUsbConfigurator,
    private val usbPermissionGateway: UsbPermissionGateway,
    private val cameraSource: CameraSource,
    private val recordingPreferences: RecordingPreferences,
    private val clipRepository: ClipRepository,
    private val videoRecorder: VideoRecorder,
    private val sessionLog: SessionLog
) : ViewModelProvider.Factory {
    ...
    return PreviewViewModel(
        glassesSession = glassesSession,
        eyeUsbConfigurator = eyeUsbConfigurator,
        usbPermissionGateway = usbPermissionGateway,
        cameraSource = cameraSource,
        recordingPreferences = recordingPreferences,
        clipRepository = clipRepository,
        videoRecorder = videoRecorder,
        sessionLog = sessionLog
    ) as T
}
```

- [ ] **Step 5: Implement recording orchestration and clip selection in `PreviewViewModel`**

Key methods to add:

```kotlin
fun setRecordVideoEnabled(enabled: Boolean)
fun selectClip(clipId: String)
fun clearSelectedClip()
fun deleteSelectedClip()
private suspend fun refreshRecordedClips()
private suspend fun maybeStartRecording(previewSize: PreviewSize)
private suspend fun stopPreviewAndFinalizeRecording()
```

Key preview flow changes:

```kotlin
when (val result = cameraSource.start(surfaceToken)) {
    is CameraSource.StartResult.Started -> {
        sessionLog.record("PreviewViewModel", "Preview started successfully")
        _uiState.value = previewRunningState(result.previewSize)
        maybeStartRecording(result.previewSize)
    }
    ...
}
```

```kotlin
private suspend fun maybeStartRecording(previewSize: PreviewSize) {
    if (!_uiState.value.recordVideoEnabled) return
    _uiState.value = _uiState.value.copy(recordingStatus = RecordingStatus.Starting)

    when (val recorderResult = videoRecorder.start(previewSize)) {
        is VideoRecorder.StartResult.Started -> {
            when (val captureResult = cameraSource.startRecording(recorderResult.captureSurface)) {
                CameraSource.RecordingStartResult.Started -> {
                    sessionLog.record("PreviewViewModel", "Recording started successfully")
                    _uiState.value = _uiState.value.copy(recordingStatus = RecordingStatus.Recording)
                }
                is CameraSource.RecordingStartResult.Failed -> {
                    sessionLog.record("PreviewViewModel", "Recording capture failed: ${captureResult.reason}")
                    videoRecorder.cancel()
                    _uiState.value = _uiState.value.copy(recordingStatus = RecordingStatus.Failed(captureResult.reason))
                }
            }
        }
        is VideoRecorder.StartResult.Failed -> {
            sessionLog.record("PreviewViewModel", "Recorder start failed: ${recorderResult.reason}")
            _uiState.value = _uiState.value.copy(recordingStatus = RecordingStatus.Failed(recorderResult.reason))
        }
    }
}
```

On stop:

```kotlin
fun stopPreview() {
    viewModelScope.launch {
        sessionLog.record("PreviewViewModel", "Stop preview tapped")
        if (_uiState.value.recordingStatus is RecordingStatus.Recording) {
            _uiState.value = _uiState.value.copy(recordingStatus = RecordingStatus.Finalizing)
            cameraSource.stopRecording()
            when (val stopResult = videoRecorder.stop()) {
                is VideoRecorder.StopResult.Finished -> {
                    clipRepository.insert(stopResult.clip)
                    refreshRecordedClips()
                }
                is VideoRecorder.StopResult.Failed -> {
                    _uiState.value = _uiState.value.copy(recordingStatus = RecordingStatus.Failed(stopResult.reason))
                }
            }
        } else {
            videoRecorder.cancel()
        }
        cameraSource.stop()
        _uiState.value = enabledCameraState(errorMessage = _uiState.value.errorMessage)
    }
}
```

Load initial preference and clip list in `init`.

- [ ] **Step 6: Run the ViewModel tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.ui.preview.PreviewViewModelTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/ar_control/ui/preview/PreviewUiState.kt app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModelFactory.kt app/src/test/java/com/example/ar_control/ui/preview/PreviewViewModelTest.kt
git commit -m "feat: wire recording and clip history into viewmodel"
```

## Task 6: Add the control-screen checkbox, clip grid, and open/share/delete UI

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values/dimens.xml`
- Modify: `app/src/main/java/com/example/ar_control/MainActivity.kt`
- Modify: `app/src/main/java/com/example/ar_control/di/AppContainer.kt`
- Modify: `app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt`
- Create: `app/src/main/java/com/example/ar_control/ui/clips/RecordedClipListItem.kt`
- Create: `app/src/main/java/com/example/ar_control/ui/clips/RecordedClipAdapter.kt`
- Create: `app/src/main/res/layout/item_recorded_clip.xml`
- Test: `app/src/androidTest/java/com/example/ar_control/MainActivityTest.kt`

- [ ] **Step 1: Write a failing instrumentation test for the record checkbox and clip action row**

```kotlin
@Test
fun controlScreen_showsRecordCheckboxAndDisabledClipActions() {
    ActivityScenario.launch(MainActivity::class.java)

    onView(withId(R.id.recordVideoCheckbox)).check(matches(isDisplayed()))
    onView(withId(R.id.recordedClipsRecyclerView)).check(matches(isDisplayed()))
    onView(withId(R.id.openClipButton)).check(matches(not(isEnabled())))
    onView(withId(R.id.shareClipButton)).check(matches(not(isEnabled())))
    onView(withId(R.id.deleteClipButton)).check(matches(not(isEnabled())))
}
```

- [ ] **Step 2: Run the instrumentation test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --tests "com.example.ar_control.MainActivityTest.controlScreen_showsRecordCheckboxAndDisabledClipActions"
```

Expected: FAIL because the checkbox, grid, and clip action row are not in the layout yet.

- [ ] **Step 3: Add RecyclerView dependency and extend the app container**

`app/build.gradle.kts`

```kotlin
dependencies {
    implementation(libs.androidx.recyclerview)
    ...
}
```

`app/src/main/java/com/example/ar_control/di/AppContainer.kt`

```kotlin
interface AppContainer {
    val clipFileSharer: ClipFileSharer
    val diagnosticsReportBuilder: DiagnosticsReportBuilder
    val previewViewModelFactory: PreviewViewModelFactory
    val sessionLog: SessionLog
}
```

`app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt`

```kotlin
private val recordingPreferences: RecordingPreferences by lazy {
    SharedPreferencesRecordingPreferences(appContext)
}

private val clipRepository: ClipRepository by lazy {
    JsonClipRepository(
        catalogFile = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
            .resolve("recordings")
            .resolve("clips.json")
    )
}

private val videoRecorder: VideoRecorder by lazy {
    MediaCodecVideoRecorder(
        outputDirectory = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
            .resolve("recordings"),
        sessionLog = sessionLog
    )
}

override val clipFileSharer: ClipFileSharer by lazy {
    AndroidClipFileSharer(appContext)
}

override val previewViewModelFactory: PreviewViewModelFactory by lazy {
    PreviewViewModelFactory(
        glassesSession = glassesSession,
        eyeUsbConfigurator = eyeUsbConfigurator,
        usbPermissionGateway = usbPermissionGateway,
        cameraSource = cameraSource,
        recordingPreferences = recordingPreferences,
        clipRepository = clipRepository,
        videoRecorder = videoRecorder,
        sessionLog = sessionLog
    )
}
```

- [ ] **Step 4: Add the control-screen checkbox, clip grid, and action row**

`app/src/main/res/layout/activity_main.xml`

```xml
<ScrollView
    android:id="@+id/controlsScrollView"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:id="@+id/controlsContainer"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <!-- existing status + buttons -->

        <com.google.android.material.checkbox.MaterialCheckBox
            android:id="@+id/recordVideoCheckbox"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:text="@string/record_video" />

        <TextView
            android:id="@+id/recordedClipsTitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/recorded_clips"
            android:textAppearance="?attr/textAppearanceTitleMedium" />

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/recordedClipsRecyclerView"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:nestedScrollingEnabled="false" />

        <LinearLayout
            android:id="@+id/clipActionsRow"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:orientation="horizontal">

            <Button
                android:id="@+id/openClipButton"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:enabled="false"
                android:text="@string/open_clip" />

            <Button
                android:id="@+id/shareClipButton"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="8dp"
                android:enabled="false"
                android:text="@string/share_clip" />

            <Button
                android:id="@+id/deleteClipButton"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="8dp"
                android:enabled="false"
                android:text="@string/delete_clip" />
        </LinearLayout>
    </LinearLayout>
</ScrollView>
```

Add `item_recorded_clip.xml` as a simple Material card with a placeholder block, timestamp text, and duration text.

- [ ] **Step 5: Add a grid adapter and wire UI events in `MainActivity`**

`app/src/main/java/com/example/ar_control/ui/clips/RecordedClipListItem.kt`

```kotlin
package com.example.ar_control.ui.clips

data class RecordedClipListItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val isSelected: Boolean
)
```

`app/src/main/java/com/example/ar_control/ui/clips/RecordedClipAdapter.kt`

```kotlin
package com.example.ar_control.ui.clips

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.ar_control.databinding.ItemRecordedClipBinding

class RecordedClipAdapter(
    private val onClipClicked: (String) -> Unit
) : ListAdapter<RecordedClipListItem, RecordedClipAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecordedClipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemRecordedClipBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RecordedClipListItem) {
            binding.clipTitleText.text = item.title
            binding.clipSubtitleText.text = item.subtitle
            binding.root.isChecked = item.isSelected
            binding.root.setOnClickListener { onClipClicked(item.id) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<RecordedClipListItem>() {
        override fun areItemsTheSame(oldItem: RecordedClipListItem, newItem: RecordedClipListItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: RecordedClipListItem, newItem: RecordedClipListItem) = oldItem == newItem
    }
}
```

`MainActivity` wiring:

```kotlin
private val recordedClipAdapter = RecordedClipAdapter { clipId ->
    previewViewModel.selectClip(clipId)
}
```

```kotlin
binding.recordVideoCheckbox.setOnCheckedChangeListener { _, isChecked ->
    appContainer.sessionLog.record("MainActivity", "Record video checkbox changed: $isChecked")
    previewViewModel.setRecordVideoEnabled(isChecked)
}

binding.openClipButton.setOnClickListener {
    latestUiState.selectedClip?.let { clip ->
        startActivity(appContainer.clipFileSharer.buildOpenIntent(clip))
    }
}

binding.shareClipButton.setOnClickListener {
    latestUiState.selectedClip?.let { clip ->
        startActivity(Intent.createChooser(appContainer.clipFileSharer.buildShareIntent(clip), getString(R.string.share_clip)))
    }
}

binding.deleteClipButton.setOnClickListener {
    showDeleteConfirmationForSelectedClip()
}
```

Render updates:

```kotlin
binding.recordVideoCheckbox.isChecked = uiState.recordVideoEnabled
binding.openClipButton.isEnabled = uiState.canOpenSelectedClip
binding.shareClipButton.isEnabled = uiState.canShareSelectedClip
binding.deleteClipButton.isEnabled = uiState.canDeleteSelectedClip
recordedClipAdapter.submitList(uiState.recordedClips.map { clip ->
    RecordedClipListItem(
        id = clip.id,
        title = DateFormat.getDateTimeInstance().format(Date(clip.createdAtEpochMillis)),
        subtitle = formatDuration(clip.durationMillis),
        isSelected = clip.id == uiState.selectedClipId
    )
})
```

Delete confirmation:

```kotlin
private fun showDeleteConfirmationForSelectedClip() {
    val clip = latestUiState.selectedClip ?: return
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.delete_clip)
        .setMessage(getString(R.string.delete_clip_confirmation, clip.id))
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.delete_clip) { _, _ ->
            previewViewModel.deleteSelectedClip()
        }
        .show()
}
```

- [ ] **Step 6: Run unit + instrumentation coverage for the UI shell**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ar_control.ui.preview.PreviewViewModelTest" :app:connectedDebugAndroidTest --tests "com.example.ar_control.MainActivityTest"
```

Expected: PASS on a connected device/emulator.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/src/main/res/layout/activity_main.xml app/src/main/res/layout/item_recorded_clip.xml app/src/main/res/values/strings.xml app/src/main/res/values/dimens.xml app/src/main/java/com/example/ar_control/MainActivity.kt app/src/main/java/com/example/ar_control/di/AppContainer.kt app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt app/src/main/java/com/example/ar_control/ui/clips/RecordedClipListItem.kt app/src/main/java/com/example/ar_control/ui/clips/RecordedClipAdapter.kt app/src/androidTest/java/com/example/ar_control/MainActivityTest.kt
git commit -m "feat: add recording controls and clip grid ui"
```

## Task 7: Update diagnostics and hardware validation docs for recording

**Files:**
- Modify: `app/src/main/java/com/example/ar_control/diagnostics/DiagnosticsReportBuilder.kt`
- Modify: `docs/hardware/xreal-one-pro-huawei-p60-pro-preview.md`

- [ ] **Step 1: Update diagnostics to include recording preference, recording status, and selected clip**

`app/src/main/java/com/example/ar_control/diagnostics/DiagnosticsReportBuilder.kt`

```kotlin
appendLine("Record video enabled: ${uiState.recordVideoEnabled}")
appendLine("Recording status: ${uiState.recordingStatus}")
appendLine("Recorded clips: ${uiState.recordedClips.size}")
appendLine("Selected clip id: ${uiState.selectedClipId ?: "none"}")
```

- [ ] **Step 2: Extend the hardware runbook with recording validation**

Add this checklist section to `docs/hardware/xreal-one-pro-huawei-p60-pro-preview.md`:

```md
## Recording validation

1. Enable `Record video`.
2. Start preview.
3. Exit fullscreen preview with the back button.
4. Confirm a new clip appears in the control-screen grid.
5. Open the clip in the system video player.
6. Share the clip.
7. Delete the clip.
8. Record multiple clips and confirm newest-first ordering.
9. Disable `Record video`.
10. Confirm preview no longer creates clips.
```

- [ ] **Step 3: Run the full verification sweep**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleAndroidTest :app:assembleRelease
```

Expected: PASS.

If a device is attached, also run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Expected: PASS on the attached device/emulator.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/ar_control/diagnostics/DiagnosticsReportBuilder.kt docs/hardware/xreal-one-pro-huawei-p60-pro-preview.md
git commit -m "docs: add recording validation and diagnostics"
```

## Self-Review Checklist

- Spec coverage:
  - Record-video checkbox: covered by Task 6.
  - Auto-start/auto-stop recording tied to preview lifecycle: covered by Tasks 3, 4, and 5.
  - Persisted history grid: covered by Tasks 1, 5, and 6.
  - Open/share/delete selected clip: covered by Tasks 2 and 6.
  - System player playback, not in-app playback: covered by Task 2 and Task 6.
  - Logging and diagnostics: covered by Task 7.
- Placeholder scan:
  - No `TBD`, `TODO`, or deferred placeholder markers remain.
  - The one implementation note in Task 3 is bounded and does not change the public API or scope.
- Type consistency:
  - `RecordedClip`, `RecordingStatus`, `ClipRepository`, `VideoRecorder`, and new `CameraSource` recording methods are introduced before later tasks depend on them.
