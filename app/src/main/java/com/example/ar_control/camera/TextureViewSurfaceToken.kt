package com.example.ar_control.camera

import android.view.TextureView

data class TextureViewSurfaceToken(
    val textureView: TextureView
) : CameraSource.SurfaceToken
