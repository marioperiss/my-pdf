package com.mario.mypdf.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

data class RenderedPage(val bitmap: Bitmap, val widthPoints: Int, val heightPoints: Int)

object PdfRendererHelper {

    /**
     * Renders a PDF page to a Bitmap.
     * Includes a safeguard against "too large bitmap" crashes by scaling down if necessary.
     */
    fun renderPage(context: Context, uri: Uri, pageIndex: Int = 0): RenderedPage? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            renderer = PdfRenderer(pfd)

            if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                return null
            }

            page = renderer.openPage(pageIndex)

            val originalWidth = page.width
            val originalHeight = page.height

            // Safeguard: Limit max dimension to avoid "Canvas: trying to draw too large bitmap"
            val maxDimension = 2500 
            var scale = 2.0f // Default scale for better quality
            
            if (originalWidth * scale > maxDimension || originalHeight * scale > maxDimension) {
                scale = Math.min(maxDimension.toFloat() / originalWidth, maxDimension.toFloat() / originalHeight)
            }

            val width = (originalWidth * scale).toInt()
            val height = (originalHeight * scale).toInt()

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            return RenderedPage(bitmap, originalWidth, originalHeight)

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try { page?.close() } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Legacy support for String paths.
     */
    fun renderPage(context: Context, pdfPath: String, pageIndex: Int = 0): RenderedPage? {
        val file = File(pdfPath)
        if (!file.exists()) return null
        return renderPage(context, Uri.fromFile(file), pageIndex)
    }
}
