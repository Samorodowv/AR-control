# Gemma Web Model Download Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace user-selected Gemma model import with a direct HTTPS download from the public LiteRT community Gemma model.

**Architecture:** Keep inference unchanged: `LiteRtGemmaFrameCaptioner` still receives a private local file path from `GemmaSubtitlePreferences`. Replace the URI-based importer with a downloader that fetches the pinned Hugging Face `.litertlm` file, writes a temp file, atomically moves it into `filesDir/models/gemma-subtitles.litertlm`, and records the private path. `PreviewViewModel` owns download state and `MainActivity` exposes a single `Download Gemma model` button.

**Tech Stack:** Android Kotlin, coroutines, `HttpURLConnection`/`URLConnection`, `java.nio.file.Files`, ViewModel state, Robolectric/JUnit, Gradle Android plugin.

---

## File Structure

- Replace `app/src/main/java/com/example/ar_control/gemma/GemmaModelImporter.kt` with `GemmaModelDownloader.kt`. Responsibility: public model source, HTTPS stream opening, atomic private-file download, result/progress types.
- Replace `app/src/test/java/com/example/ar_control/gemma/GemmaModelImporterTest.kt` with `GemmaModelDownloaderTest.kt`. Responsibility: downloader copy, replace, failure, cancellation, and progress tests.
- Modify `app/src/main/java/com/example/ar_control/ui/preview/PreviewUiState.kt`. Responsibility: expose download in-progress/progress text.
- Modify `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt`. Responsibility: replace `importGemmaModel()` with `downloadGemmaModel()`.
- Modify `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModelFactory.kt` and `app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt`. Responsibility: inject downloader instead of importer.
- Modify `app/src/main/java/com/example/ar_control/MainActivity.kt`, `app/src/main/res/layout/activity_main.xml`, and `app/src/main/res/values/strings.xml`. Responsibility: remove document picker and bind the download button/status.
- Modify `app/src/test/java/com/example/ar_control/ui/preview/PreviewViewModelTest.kt` and `app/src/test/java/com/example/ar_control/MainActivityUiLogicTest.kt`. Responsibility: ViewModel download state and UI status/button rendering.
- Modify `README.md`. Responsibility: document web download instead of local import.

### Task 1: Downloader Tests

**Files:**
- Rename: `app/src/test/java/com/example/ar_control/gemma/GemmaModelImporterTest.kt` to `app/src/test/java/com/example/ar_control/gemma/GemmaModelDownloaderTest.kt`

- [ ] **Step 1: Rename the test file**

```powershell
git mv app/src/test/java/com/example/ar_control/gemma/GemmaModelImporterTest.kt app/src/test/java/com/example/ar_control/gemma/GemmaModelDownloaderTest.kt
```

- [ ] **Step 2: Write failing downloader tests**

Replace the test class name and imports, then add tests with this shape:

```kotlin
@RunWith(RobolectricTestRunner::class)
class GemmaModelDownloaderTest {
    @Test
    fun downloadModelCopiesContentAndPersistsMetadata() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-download-test").toFile()
        val preferences = FakeGemmaSubtitlePreferences()
        val sourceBytes = byteArrayOf(1, 2, 3, 4)
        val downloader = GemmaModelDownloader(
            targetDirectory = targetDirectory,
            preferences = preferences,
            source = GemmaModelDownloadSource(
                url = URL("https://example.test/gemma.litertlm"),
                displayName = "gemma-4-E2B-it.litertlm"
            ),
            openStream = {
                GemmaModelDownloadStream(
                    inputStream = ByteArrayInputStream(sourceBytes),
                    contentLengthBytes = sourceBytes.size.toLong()
                )
            }
        )
        val progress = mutableListOf<GemmaModelDownloadProgress>()

        val result = downloader.downloadModel(progress::add)

        assertTrue(result is GemmaModelDownloadResult.Downloaded)
        val downloaded = result as GemmaModelDownloadResult.Downloaded
        assertEquals(File(targetDirectory, "gemma-subtitles.litertlm").absolutePath, downloaded.path)
        assertEquals("gemma-4-E2B-it.litertlm", downloaded.displayName)
        assertArrayEquals(sourceBytes, File(downloaded.path).readBytes())
        assertEquals(downloaded.path, preferences.getModelPath())
        assertEquals("gemma-4-E2B-it.litertlm", preferences.getModelDisplayName())
        assertTrue(progress.last().bytesDownloaded == sourceBytes.size.toLong())
        assertEquals(sourceBytes.size.toLong(), progress.last().totalBytes)
    }
}
```

Also adapt the existing failure/cancellation tests to call `downloadModel()` instead of `importModel(uri, name)` and expect:

```kotlin
GemmaModelDownloadResult.Failed("Could not download Gemma model")
```

- [ ] **Step 3: Run the focused test and confirm it fails for missing downloader symbols**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.ar_control.gemma.GemmaModelDownloaderTest"
```

Expected: compilation fails with unresolved references such as `GemmaModelDownloader` and `GemmaModelDownloadResult`.

- [ ] **Step 4: Commit the failing test**

```powershell
git add app/src/test/java/com/example/ar_control/gemma/GemmaModelDownloaderTest.kt
git commit -m "test: specify Gemma model downloader"
```

### Task 2: Downloader Implementation

**Files:**
- Rename: `app/src/main/java/com/example/ar_control/gemma/GemmaModelImporter.kt` to `app/src/main/java/com/example/ar_control/gemma/GemmaModelDownloader.kt`

- [ ] **Step 1: Rename the production file**

```powershell
git mv app/src/main/java/com/example/ar_control/gemma/GemmaModelImporter.kt app/src/main/java/com/example/ar_control/gemma/GemmaModelDownloader.kt
```

- [ ] **Step 2: Replace URI import code with web download code**

Use this public API and keep helpers internal to the file:

```kotlin
data class GemmaModelDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?
)

sealed interface GemmaModelDownloadResult {
    data class Downloaded(val path: String, val displayName: String) : GemmaModelDownloadResult
    data class Failed(val reason: String) : GemmaModelDownloadResult
}

internal data class GemmaModelDownloadSource(
    val url: URL,
    val displayName: String
)

internal class GemmaModelDownloadStream(
    val inputStream: InputStream,
    val contentLengthBytes: Long?,
    private val closeConnection: () -> Unit = {}
) : Closeable {
    override fun close() {
        runCatching { inputStream.close() }
        closeConnection()
    }
}

class GemmaModelDownloader internal constructor(
    private val targetDirectory: File,
    private val preferences: GemmaSubtitlePreferences,
    private val source: GemmaModelDownloadSource = DEFAULT_SOURCE,
    private val openStream: (URL) -> GemmaModelDownloadStream?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(context: Context, preferences: GemmaSubtitlePreferences) : this(
        targetDirectory = File(context.applicationContext.filesDir, MODEL_DIRECTORY_NAME),
        preferences = preferences,
        openStream = ::openHttpsStream
    )

    suspend fun downloadModel(
        onProgress: (GemmaModelDownloadProgress) -> Unit = {}
    ): GemmaModelDownloadResult = withContext(ioDispatcher) {
        val stream = try {
            openStream(source.url)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        } ?: return@withContext GemmaModelDownloadResult.Failed(COULD_NOT_DOWNLOAD_REASON)

        var tempFile: File? = null
        try {
            targetDirectory.mkdirs()
            val targetFile = File(targetDirectory, MODEL_FILE_NAME)
            tempFile = File.createTempFile(MODEL_FILE_NAME, ".tmp", targetDirectory)
            copyWithProgress(stream.inputStream, tempFile, stream.contentLengthBytes, onProgress)
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            val targetPath = targetFile.absolutePath
            preferences.setModel(targetPath, source.displayName)
            GemmaModelDownloadResult.Downloaded(targetPath, source.displayName)
        } catch (error: Exception) {
            tempFile?.delete()
            if (error is CancellationException) throw error
            GemmaModelDownloadResult.Failed(COULD_NOT_DOWNLOAD_REASON)
        } finally {
            stream.close()
        }
    }
}
```

Production stream helper:

```kotlin
private fun openHttpsStream(url: URL): GemmaModelDownloadStream {
    val connection = url.openConnection()
    connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
    connection.readTimeout = READ_TIMEOUT_MILLIS
    if (connection is HttpURLConnection) {
        connection.instanceFollowRedirects = true
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            connection.disconnect()
            throw IOException("Unexpected HTTP $responseCode")
        }
    }
    return GemmaModelDownloadStream(
        inputStream = connection.inputStream,
        contentLengthBytes = connection.contentLengthLong.takeIf { it > 0L },
        closeConnection = {
            if (connection is HttpURLConnection) {
                connection.disconnect()
            }
        }
    )
}
```

Copy helper:

```kotlin
private fun copyWithProgress(
    source: InputStream,
    targetFile: File,
    totalBytes: Long?,
    onProgress: (GemmaModelDownloadProgress) -> Unit
) {
    var bytesDownloaded = 0L
    onProgress(GemmaModelDownloadProgress(bytesDownloaded, totalBytes))
    targetFile.outputStream().use { output ->
        val buffer = ByteArray(COPY_BUFFER_SIZE_BYTES)
        while (true) {
            val read = source.read(buffer)
            if (read == -1) {
                break
            }
            output.write(buffer, 0, read)
            bytesDownloaded += read.toLong()
            onProgress(GemmaModelDownloadProgress(bytesDownloaded, totalBytes))
        }
    }
}
```

Constants:

```kotlin
private const val MODEL_DIRECTORY_NAME = "models"
private const val MODEL_FILE_NAME = "gemma-subtitles.litertlm"
private const val MODEL_DISPLAY_NAME = "gemma-4-E2B-it.litertlm"
private const val MODEL_URL =
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"
private const val COULD_NOT_DOWNLOAD_REASON = "Could not download Gemma model"
private const val CONNECT_TIMEOUT_MILLIS = 15_000
private const val READ_TIMEOUT_MILLIS = 30_000
private const val COPY_BUFFER_SIZE_BYTES = 128 * 1024
private val DEFAULT_SOURCE = GemmaModelDownloadSource(URL(MODEL_URL), MODEL_DISPLAY_NAME)
```

- [ ] **Step 3: Run the downloader tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.ar_control.gemma.GemmaModelDownloaderTest"
```

Expected: PASS.

- [ ] **Step 4: Commit downloader implementation**

```powershell
git add app/src/main/java/com/example/ar_control/gemma/GemmaModelDownloader.kt app/src/test/java/com/example/ar_control/gemma/GemmaModelDownloaderTest.kt
git commit -m "feat: download Gemma model from web"
```

### Task 3: ViewModel Download State

**Files:**
- Modify: `app/src/main/java/com/example/ar_control/ui/preview/PreviewUiState.kt`
- Modify: `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt`
- Modify: `app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModelFactory.kt`
- Modify: `app/src/test/java/com/example/ar_control/ui/preview/PreviewViewModelTest.kt`

- [ ] **Step 1: Write failing ViewModel tests**

Add imports:

```kotlin
import com.example.ar_control.gemma.GemmaModelDownloader
import com.example.ar_control.gemma.GemmaModelDownloadSource
import com.example.ar_control.gemma.GemmaModelDownloadStream
import java.io.ByteArrayInputStream
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.test.advanceUntilIdle
```

Add a test:

```kotlin
@Test
fun downloadGemmaModel_updatesUiStateAndPreferenceStore() = runTest {
    val preferences = FakeGemmaSubtitlePreferences()
    val downloader = GemmaModelDownloader(
        targetDirectory = Files.createTempDirectory("view-model-gemma-download").toFile(),
        preferences = preferences,
        source = GemmaModelDownloadSource(
            url = URL("https://example.test/gemma.litertlm"),
            displayName = "gemma-4-E2B-it.litertlm"
        ),
        openStream = {
            GemmaModelDownloadStream(
                inputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                contentLengthBytes = 3L
            )
        },
        ioDispatcher = dispatcher
    )
    val viewModel = buildViewModel(
        gemmaSubtitlePreferences = preferences,
        gemmaModelDownloader = downloader,
        cleanupScope = cleanupScope
    )

    viewModel.downloadGemmaModel()
    advanceUntilIdle()

    assertFalse(viewModel.uiState.value.isGemmaModelDownloadInProgress)
    assertEquals("gemma-4-E2B-it.litertlm", viewModel.uiState.value.gemmaModelDisplayName)
    assertEquals("gemma-4-E2B-it.litertlm", preferences.getModelDisplayName())
    assertEquals(null, viewModel.uiState.value.gemmaModelDownloadProgressText)
}
```

Add a failure test that uses `openStream = { null }` and asserts:

```kotlin
assertFalse(viewModel.uiState.value.isGemmaModelDownloadInProgress)
assertEquals("Could not download Gemma model", viewModel.uiState.value.errorMessage)
assertEquals(null, viewModel.uiState.value.gemmaModelDisplayName)
```

- [ ] **Step 2: Run ViewModel tests and confirm they fail**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.ar_control.ui.preview.PreviewViewModelTest"
```

Expected: compilation fails because `downloadGemmaModel`, `gemmaModelDownloader`, and download UI state fields do not exist yet.

- [ ] **Step 3: Implement ViewModel state and action**

In `PreviewUiState`, add:

```kotlin
val isGemmaModelDownloadInProgress: Boolean = false,
val gemmaModelDownloadProgressText: String? = null,
```

In `PreviewViewModel`, replace importer imports/constructor field with:

```kotlin
private val gemmaModelDownloader: GemmaModelDownloader? = null,
```

Add:

```kotlin
fun downloadGemmaModel() {
    val downloader = gemmaModelDownloader ?: return
    if (_uiState.value.isGemmaModelDownloadInProgress) {
        return
    }
    viewModelScope.launch {
        sessionLog.record("PreviewViewModel", "Gemma model download started")
        _uiState.value = applyRecoveryState(_uiState.value.copy(
            isGemmaModelDownloadInProgress = true,
            gemmaModelDownloadProgressText = GEMMA_MODEL_DOWNLOADING,
            errorMessage = null
        ))
        when (val result = downloader.downloadModel(::onGemmaModelDownloadProgress)) {
            is GemmaModelDownloadResult.Downloaded -> {
                sessionLog.record("PreviewViewModel", "Gemma model download finished: ${result.displayName}")
                _uiState.value = applyRecoveryState(_uiState.value.copy(
                    isGemmaModelDownloadInProgress = false,
                    gemmaModelDownloadProgressText = null,
                    gemmaModelDisplayName = result.displayName,
                    errorMessage = null
                ))
            }
            is GemmaModelDownloadResult.Failed -> {
                sessionLog.record("PreviewViewModel", "Gemma model download failed: ${result.reason}")
                _uiState.value = applyRecoveryState(_uiState.value.copy(
                    isGemmaModelDownloadInProgress = false,
                    gemmaModelDownloadProgressText = null,
                    errorMessage = result.reason
                ))
            }
        }
    }
}
```

Add helper:

```kotlin
private fun onGemmaModelDownloadProgress(progress: GemmaModelDownloadProgress) {
    _uiState.value = applyRecoveryState(_uiState.value.copy(
        gemmaModelDownloadProgressText = progress.toStatusText()
    ))
}

private fun GemmaModelDownloadProgress.toStatusText(): String {
    val total = totalBytes ?: return GEMMA_MODEL_DOWNLOADING
    if (total <= 0L) {
        return GEMMA_MODEL_DOWNLOADING
    }
    val percent = ((bytesDownloaded * 100L) / total).coerceIn(0L, 100L)
    return "Gemma model: downloading $percent%"
}
```

Remove `importGemmaModel(uri, displayName)`. Add constants:

```kotlin
const val GEMMA_MODEL_DOWNLOADING = "Gemma model: downloading..."
```

- [ ] **Step 4: Update factory wiring**

Replace `GemmaModelImporter` with `GemmaModelDownloader` in `PreviewViewModelFactory` and pass `gemmaModelDownloader = gemmaModelDownloader`.

Update the `buildViewModel()` test helper signature and constructor call:

```kotlin
gemmaModelDownloader: GemmaModelDownloader? = null,
```

```kotlin
gemmaModelDownloader = gemmaModelDownloader,
```

- [ ] **Step 5: Run ViewModel tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.ar_control.ui.preview.PreviewViewModelTest"
```

Expected: PASS.

- [ ] **Step 6: Commit ViewModel wiring**

```powershell
git add app/src/main/java/com/example/ar_control/ui/preview/PreviewUiState.kt app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModel.kt app/src/main/java/com/example/ar_control/ui/preview/PreviewViewModelFactory.kt app/src/test/java/com/example/ar_control/ui/preview/PreviewViewModelTest.kt
git commit -m "feat: track Gemma model download state"
```

### Task 4: Activity and UI Wiring

**Files:**
- Modify: `app/src/main/java/com/example/ar_control/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/java/com/example/ar_control/MainActivityUiLogicTest.kt`

- [ ] **Step 1: Write failing UI logic tests**

Add layout inflation assertion:

```kotlin
import android.view.View

@Test
fun mainLayout_containsDownloadGemmaModelButton() {
    val themedContext = ContextThemeWrapper(context, R.style.Theme_AR_Control)
    val view = LayoutInflater.from(themedContext).inflate(
        R.layout.activity_main,
        FrameLayout(themedContext),
        false
    )

    assertNotNull(view.findViewById<View>(R.id.downloadGemmaModelButton))
}
```

Add a pure status helper test after extracting the helper in implementation:

```kotlin
@Test
fun gemmaModelStatusMessage_prefersDownloadProgress() {
    assertEquals(
        "Gemma model: downloading 50%",
        PreviewUiState(
            gemmaModelDisplayName = "gemma-4-E2B-it.litertlm",
            gemmaModelDownloadProgressText = "Gemma model: downloading 50%"
        ).gemmaModelStatusMessage(context)
    )
}
```

- [ ] **Step 2: Run UI logic tests and confirm failure**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.ar_control.MainActivityUiLogicTest"
```

Expected: compilation fails until the new ID/helper exist.

- [ ] **Step 3: Update strings and layout**

In `strings.xml`, replace:

```xml
<string name="import_gemma_model">Import Gemma model</string>
```

with:

```xml
<string name="download_gemma_model">Download Gemma model</string>
```

In `activity_main.xml`, replace the button ID/text:

```xml
<Button
    android:id="@+id/downloadGemmaModelButton"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="@dimen/control_item_spacing"
    android:text="@string/download_gemma_model" />
```

- [ ] **Step 4: Remove document picker and bind download action**

In `MainActivity`, remove the `gemmaModelPickerLauncher` property. Keep `ActivityResultContracts.RequestPermission` import for camera permission.

Replace the button click listener with:

```kotlin
binding.downloadGemmaModelButton.setOnClickListener {
    appContainer.sessionLog.record("MainActivity", "Download Gemma model tapped")
    previewViewModel.downloadGemmaModel()
}
```

In `render(uiState)`, replace the status assignment with:

```kotlin
binding.gemmaModelStatusText.text = uiState.gemmaModelStatusMessage(this)
binding.downloadGemmaModelButton.isEnabled = !uiState.isGemmaModelDownloadInProgress
```

Add helper near the existing `previewRecordingStatusMessage` extension:

```kotlin
internal fun PreviewUiState.gemmaModelStatusMessage(context: Context): String {
    return gemmaModelDownloadProgressText
        ?: gemmaModelDisplayName?.let { displayName ->
            context.getString(R.string.gemma_model_configured, displayName)
        }
        ?: context.getString(R.string.gemma_model_not_configured)
}
```

- [ ] **Step 5: Run UI logic tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.ar_control.MainActivityUiLogicTest"
```

Expected: PASS.

- [ ] **Step 6: Commit Activity/UI wiring**

```powershell
git add app/src/main/java/com/example/ar_control/MainActivity.kt app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml app/src/test/java/com/example/ar_control/MainActivityUiLogicTest.kt
git commit -m "feat: replace Gemma import UI with download"
```

### Task 5: Dependency Injection and Documentation

**Files:**
- Modify: `app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt`
- Modify: `README.md`

- [ ] **Step 1: Update DI**

Replace importer fields:

```kotlin
private val gemmaModelDownloader: GemmaModelDownloader by lazy {
    GemmaModelDownloader(
        context = appContext,
        preferences = gemmaSubtitlePreferences
    )
}
```

Pass:

```kotlin
gemmaModelDownloader = gemmaModelDownloader,
```

- [ ] **Step 2: Update README Gemma section**

Replace the local import paragraph with:

```markdown
`Gemma subtitles` downloads the public LiteRT-LM model
`litert-community/gemma-4-E2B-it-litert-lm/gemma-4-E2B-it.litertlm` from Hugging Face
into app-private storage. On a device, tap `Download Gemma model`, wait for the model
status to show the configured file, enable `Gemma subtitles`, then start preview. Captions
appear as a floating pill over fullscreen preview.
```

- [ ] **Step 3: Run a full unit test pass**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 4: Commit DI and docs**

```powershell
git add app/src/main/java/com/example/ar_control/di/DefaultAppContainer.kt README.md
git commit -m "docs: document Gemma model web download"
```

### Task 6: Final Verification and Release Build

**Files:**
- No planned source edits.

- [ ] **Step 1: Check for leftover filesystem-import references**

Run:

```powershell
rg -n "Import Gemma|importGemmaModel|GemmaModelImporter|OpenDocument|selected Gemma model|device storage" app README.md
```

Expected: no matches.

- [ ] **Step 2: Run full tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 3: Build release APK**

Run:

```powershell
.\gradlew.bat assembleRelease
```

Expected: BUILD SUCCESSFUL and release APK at `app/build/outputs/apk/release/app-release.apk`. If `keystore.properties` exists in the worktree, the APK is signed by the configured release key; otherwise Gradle produces an unsigned release APK.

- [ ] **Step 4: Verify final git state**

Run:

```powershell
git status --short
git log --oneline -5
```

Expected: clean working tree and recent commits for downloader tests, implementation, UI, docs, and verification.
