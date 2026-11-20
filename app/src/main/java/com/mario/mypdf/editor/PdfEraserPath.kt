package com.mario.mypdf.editor

import android.graphics.PointF

data class PdfEraserPath(
    val points: MutableList<PointF> = mutableListOf(),
    var pageIndex: Int = 0,
    var strokeWidth: Float = 20f
)
