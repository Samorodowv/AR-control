package com.example.ar_control.gemma

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.ar_control.R
import com.example.ar_control.diagnostics.PersistentSessionLog
import com.example.ar_control.diagnostics.SessionLog
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class WorkManagerGemmaModelDownloadScheduler(
    private val workManager: WorkManager
) : GemmaModelDownloadScheduler {
    override val downloadState: Flow<GemmaModelDownloadWorkState> =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
            .map { workInfos -> workInfos.toDownloadWorkState() }
            .distinctUntilChanged()

    override fun enqueueDownload() {
        val request = OneTimeWorkRequestBuilder<GemmaModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancelDownload() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}

class GemmaModelDownloadWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {
    private val sessionLog: SessionLog by lazy {
        PersistentSessionLog(
            file = File(applicationContext.filesDir, "diagnostics/session-log.txt")
        )
    }
    private var lastProgressText: String? = null
    private var lastProgressUpdateMillis: Long = 0L

    override suspend fun doWork(): Result {
        sessionLog.record("GemmaModelDownloadWorker", "Download worker started")
        setForeground(createForegroundInfo(progress = null))
        val preferences = SharedPreferencesGemmaSubtitlePreferences(applicationContext)
        val downloader = GemmaModelDownloader(
            context = applicationContext,
            preferences = preferences
        )
        return try {
            when (val result = downloader.downloadModel(::publishProgress)) {
                is GemmaModelDownloadResult.Downloaded -> {
                    sessionLog.record(
                        "GemmaModelDownloadWorker",
                        "Download worker finished: ${result.displayName}"
                    )
                    Result.success(workDataOf(KEY_DISPLAY_NAME to result.displayName))
                }

                is GemmaModelDownloadResult.Failed -> {
                    sessionLog.record(
                        "GemmaModelDownloadWorker",
                        "Download worker failed: ${result.reason}"
                    )
                    Result.failure(workDataOf(KEY_FAILURE_REASON to result.reason))
                }
            }
        } catch (error: CancellationException) {
            sessionLog.record("GemmaModelDownloadWorker", "Download worker cancelled")
            throw error
        }
    }

    private suspend fun publishProgress(progress: GemmaModelDownloadProgress) {
        val statusText = progress.toNotificationText()
        val now = SystemClock.elapsedRealtime()
        if (statusText == lastProgressText && now - lastProgressUpdateMillis < PROGRESS_UPDATE_INTERVAL_MILLIS) {
            return
        }
        lastProgressText = statusText
        lastProgressUpdateMillis = now
        sessionLog.record("GemmaModelDownloadWorker", statusText)
        setProgress(progress.toWorkData())
        setForeground(createForegroundInfo(progress))
    }

    private fun createForegroundInfo(progress: GemmaModelDownloadProgress?): ForegroundInfo {
        createNotificationChannel()
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.gemma_model_download_notification_title))
            .setContentText(
                progress?.toNotificationText()
                    ?: applicationContext.getString(R.string.gemma_model_download_notification_starting)
            )
            .setSmallIcon(R.drawable.ic_gemma_download_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(
                progress?.totalBytes?.takeIf { it > 0L }?.let { 100 } ?: 0,
                progress?.percentComplete() ?: 0,
                progress?.totalBytes == null
            )
            .addAction(
                android.R.drawable.ic_delete,
                applicationContext.getString(android.R.string.cancel),
                cancelIntent
            )
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun createNotificationChannel() {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            applicationContext.getString(R.string.gemma_model_download_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }
}

private fun List<WorkInfo>.toDownloadWorkState(): GemmaModelDownloadWorkState {
    val selected = firstOrNull { workInfo ->
        workInfo.state == WorkInfo.State.ENQUEUED ||
            workInfo.state == WorkInfo.State.RUNNING ||
            workInfo.state == WorkInfo.State.BLOCKED
    } ?: firstOrNull()
    return selected?.toDownloadWorkState() ?: GemmaModelDownloadWorkState.Idle
}

private fun WorkInfo.toDownloadWorkState(): GemmaModelDownloadWorkState {
    return when (state) {
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.RUNNING,
        WorkInfo.State.BLOCKED -> GemmaModelDownloadWorkState.Running(progress.toDownloadProgress())
        WorkInfo.State.SUCCEEDED -> GemmaModelDownloadWorkState.Completed(
            displayName = outputData.getString(KEY_DISPLAY_NAME)
        )
        WorkInfo.State.FAILED -> GemmaModelDownloadWorkState.Failed(
            reason = outputData.getString(KEY_FAILURE_REASON) ?: COULD_NOT_DOWNLOAD_GEMMA_MODEL
        )
        WorkInfo.State.CANCELLED -> GemmaModelDownloadWorkState.Idle
    }
}

private fun GemmaModelDownloadProgress.toWorkData() = workDataOf(
    KEY_BYTES_DOWNLOADED to bytesDownloaded,
    KEY_TOTAL_BYTES to (totalBytes ?: UNKNOWN_TOTAL_BYTES)
)

private fun androidx.work.Data.toDownloadProgress(): GemmaModelDownloadProgress? {
    if (!keyValueMap.containsKey(KEY_BYTES_DOWNLOADED)) {
        return null
    }
    val totalBytes = getLong(KEY_TOTAL_BYTES, UNKNOWN_TOTAL_BYTES)
        .takeIf { it != UNKNOWN_TOTAL_BYTES }
    return GemmaModelDownloadProgress(
        bytesDownloaded = getLong(KEY_BYTES_DOWNLOADED, 0L),
        totalBytes = totalBytes
    )
}

private fun GemmaModelDownloadProgress.toNotificationText(): String {
    val percent = percentComplete() ?: return "Downloading Gemma model..."
    return "Downloading Gemma model: $percent%"
}

private fun GemmaModelDownloadProgress.percentComplete(): Int? {
    val total = totalBytes ?: return null
    if (total <= 0L) {
        return null
    }
    return ((bytesDownloaded * 100L) / total).coerceIn(0L, 100L).toInt()
}

private const val UNIQUE_WORK_NAME = "gemma_model_download"
private const val WORK_TAG = "gemma_model_download"
private const val NOTIFICATION_CHANNEL_ID = "gemma_model_download"
private const val NOTIFICATION_ID = 1_024
private const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
private const val KEY_TOTAL_BYTES = "total_bytes"
private const val KEY_DISPLAY_NAME = "display_name"
private const val KEY_FAILURE_REASON = "failure_reason"
private const val UNKNOWN_TOTAL_BYTES = -1L
private const val PROGRESS_UPDATE_INTERVAL_MILLIS = 1_000L
private const val COULD_NOT_DOWNLOAD_GEMMA_MODEL = "Could not download Gemma model"
