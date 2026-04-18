package com.example.ar_control.recording

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

internal class AndroidClipFileSharer(
    private val contentUriResolver: (File) -> Uri
) : ClipFileSharer {

    internal constructor(
        context: Context,
        uriForFile: (Context, String, File) -> Uri
    ) : this(buildContentUriResolver(context, uriForFile))

    constructor(context: Context) : this(context, FileProvider::getUriForFile)

    override fun buildOpenIntent(clip: RecordedClip): Intent {
        val contentUri = contentUriResolver(File(clip.filePath))
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, clip.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun buildShareIntent(clip: RecordedClip): Intent {
        val contentUri = contentUriResolver(File(clip.filePath))
        return Intent(Intent.ACTION_SEND).apply {
            setType(clip.mimeType)
            putExtra(Intent.EXTRA_STREAM, contentUri)
            clipData = ClipData.newRawUri("clip", contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private companion object {
        fun buildContentUriResolver(
            context: Context,
            uriForFile: (Context, String, File) -> Uri
        ): (File) -> Uri {
            val appContext = context.applicationContext
            val authority = fileProviderAuthority(appContext)
            return { file -> uriForFile(appContext, authority, file) }
        }

        fun fileProviderAuthority(context: Context): String = "${context.packageName}.fileprovider"
    }
}
