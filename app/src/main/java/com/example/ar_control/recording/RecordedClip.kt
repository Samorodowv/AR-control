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
