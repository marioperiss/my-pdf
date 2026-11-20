package com.mario.mypdf.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max

object DocumentProcessor {

    data class ProcessResult(
        val processedBitmap: Bitmap, // Enhanced / binarized for display, print, and image PDF
        val ocrBitmap: Bitmap       // Grayscale / warped (non-binarized) for accurate OCR
    )

    fun process(bitmap: Bitmap, rotationDegrees: Int = 0): Bitmap {
        return processDetailed(bitmap, rotationDegrees).processedBitmap
    }

    fun processDetailed(bitmap: Bitmap, rotationDegrees: Int = 0): ProcessResult {
        val rotation = ((rotationDegrees % 360) + 360) % 360
        val workingBitmap = when (rotation) {
            90, 180, 270 -> rotateBitmap(bitmap, rotation.toFloat())
            else -> bitmap
        }

        val mat = Mat()
        try {
            Utils.bitmapToMat(workingBitmap, mat)
            val gray = toGray(mat)
            val edged = Mat()
            val hierarchy = Mat()
            val contours = ArrayList<MatOfPoint>()
            try {
                Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
                Imgproc.Canny(gray, edged, 75.0, 200.0)
                Imgproc.findContours(edged, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                val corners = findDocumentContour(contours)

                var warpedGray: Mat? = null
                var warpedColor: Mat? = null
                try {
                    if (corners != null) {
                        warpedGray = perspectiveWarp(gray, corners)
                        warpedColor = perspectiveWarp(mat, corners)
                    }

                    val sourceGray = warpedGray ?: gray
                    val sourceColor = warpedColor ?: mat

                    // 1. OCR Bitmap: Grayscale version (no thresholding) for accurate text recognition
                    val ocrBmp = Bitmap.createBitmap(sourceGray.cols(), sourceGray.rows(), Bitmap.Config.ARGB_8888).also {
                        Utils.matToBitmap(sourceGray, it)
                    }

                    // 2. Processed Bitmap: Enhanced / binarized for display, print, and image PDF (full page layout, no borders)
                    val enhancedBmp = enhanceForPrinting(sourceColor)

                    return ProcessResult(enhancedBmp, ocrBmp)
                } finally {
                    warpedGray?.release()
                    warpedColor?.release()
                }
            } finally {
                gray.release()
                edged.release()
                hierarchy.release()
                contours.forEach { it.release() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            val fallbackBmp = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            return ProcessResult(fallbackBmp, fallbackBmp)
        } finally {
            mat.release()
            if (workingBitmap !== bitmap && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
        }
    }

    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun findDocumentContour(contours: List<MatOfPoint>): Array<Point>? {
        var maxArea = 0.0
        var biggest: Array<Point>? = null

        for (c in contours) {
            val c2f = MatOfPoint2f(*c.toArray())
            try {
                val peri = Imgproc.arcLength(c2f, true)
                val approx = MatOfPoint2f()
                try {
                    Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
                    val area = Imgproc.contourArea(approx)
                    if (approx.total() == 4L && area > maxArea && area > 50000) {
                        maxArea = area
                        biggest = approx.toArray()
                    }
                } finally { approx.release() }
            } finally { c2f.release() }
        }
        return biggest
    }

    private fun perspectiveWarp(inputMat: Mat, points: Array<Point>): Mat? {
        val sortedPoints = sortPoints(points)
        
        val tl = sortedPoints[0]
        val tr = sortedPoints[1]
        val br = sortedPoints[2]
        val bl = sortedPoints[3]

        val widthA = Math.sqrt(Math.pow(br.x - bl.x, 2.0) + Math.pow(br.y - bl.y, 2.0))
        val widthB = Math.sqrt(Math.pow(tr.x - tl.x, 2.0) + Math.pow(tr.y - tl.y, 2.0))
        val maxWidth = max(widthA.toInt(), widthB.toInt())

        val heightA = Math.sqrt(Math.pow(tr.x - br.x, 2.0) + Math.pow(tr.y - br.y, 2.0))
        val heightB = Math.sqrt(Math.pow(tl.x - bl.x, 2.0) + Math.pow(tl.y - bl.y, 2.0))
        val maxHeight = max(heightA.toInt(), heightB.toInt())

        if (maxWidth <= 0 || maxHeight <= 0) return null
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(maxWidth.toDouble(), 0.0),
            Point(maxWidth.toDouble(), maxHeight.toDouble()),
            Point(0.0, maxHeight.toDouble())
        )

        val srcPoints = MatOfPoint2f(*sortedPoints)
        var transformationMatrix: Mat? = null
        try {
            val transform = Imgproc.getPerspectiveTransform(srcPoints, dst)
            transformationMatrix = transform
            return Mat().also { Imgproc.warpPerspective(inputMat, it, transform, Size(maxWidth.toDouble(), maxHeight.toDouble())) }
        } catch (e: Exception) {
            return null
        } finally {
            srcPoints.release()
            dst.release()
            transformationMatrix?.release()
        }
    }

    private fun sortPoints(pts: Array<Point>): Array<Point> {
        val sorted = Array(4) { Point() }
        val sum = pts.map { it.x + it.y }
        sorted[0] = pts[sum.indexOf(sum.minOrNull())]
        sorted[2] = pts[sum.indexOf(sum.maxOrNull())]
        val diff = pts.map { it.y - it.x }
        sorted[1] = pts[diff.indexOf(diff.minOrNull())]
        sorted[3] = pts[diff.indexOf(diff.maxOrNull())]
        return sorted
    }

    private fun enhanceForPrinting(mat: Mat): Bitmap {
        val enhanced = Mat()
        try {
            val grayImage = toGray(mat)
            try {
                Imgproc.adaptiveThreshold(grayImage, enhanced, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 11, 12.0)
                return Bitmap.createBitmap(enhanced.cols(), enhanced.rows(), Bitmap.Config.ARGB_8888).also {
                    Utils.matToBitmap(enhanced, it)
                }
            } finally { grayImage.release() }
        } finally {
            enhanced.release()
        }
    }

    private fun toGray(source: Mat): Mat {
        val gray = Mat()
        when (source.channels()) {
            1 -> source.copyTo(gray)
            3 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGB2GRAY)
            4 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
            else -> {
                gray.release()
                throw IllegalArgumentException("Numero di canali immagine non supportato: ${source.channels()}")
            }
        }
        return gray
    }
}
