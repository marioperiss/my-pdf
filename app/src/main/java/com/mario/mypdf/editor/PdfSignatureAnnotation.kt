package com.mario.mypdf.editor

import android.graphics.Bitmap

data class PdfSignatureAnnotation(
    val bitmap: Bitmap,
    var x: Float,
    var y: Float,
    var width: Float,
    var height: Float,
    var pageIndex: Int = 0,
    var rotation: Float = 0f
)
