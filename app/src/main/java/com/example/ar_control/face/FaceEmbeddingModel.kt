package com.example.ar_control.face

import android.graphics.Bitmap

interface FaceEmbeddingModel : AutoCloseable {
    fun embed(faceBitmap: Bitmap): FaceEmbedding

    override fun close()
}

