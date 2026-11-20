package com.mario.mypdf.editor

data class PdfTextAnnotation(
    var text: String,
    var x: Float,
    var y: Float,
    var fontSize: Float,
    var color: Int,
    var pageIndex: Int = 0,
    var rotation: Float = 0f
)

