package com.mario.mypdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PDFCreator {

    fun createSinglePagePdf(context: Context, bitmap: Bitmap, customFileName: String? = null): Uri? {
        return createMultiPagePdf(context, listOf(bitmap), customFileName)
    }

    fun createMultiPagePdf(context: Context, bitmaps: List<Bitmap>, customFileName: String? = null): Uri? {
        PDFBoxResourceLoader.init(context)

        if (bitmaps.isEmpty()) return null
        for (bmp in bitmaps) {
            require(bmp.width > 0 && bmp.height > 0) { "Immagine non valida" }
        }

        val document = PDDocument()
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val requestedName = customFileName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()
        val fileName = if (!requestedName.isNullOrBlank()) {
            if (requestedName.endsWith(".pdf", ignoreCase = true)) requestedName else "$requestedName.pdf"
        } else {
            "scan_$timeStamp.pdf"
        }

        var createdFile: DocumentFile? = null
        return try {
            for (bitmap in bitmaps) {
                val portrait = bitmap.height >= bitmap.width
                val pageWidth = if (portrait) 595f else 842f
                val pageHeight = pageWidth * bitmap.height / bitmap.width
                val page = PDPage(PDRectangle(pageWidth, pageHeight))
                document.addPage(page)

                val pdImage = LosslessFactory.createFromImage(document, bitmap)

                val contentStream = PDPageContentStream(
                    document,
                    page,
                    PDPageContentStream.AppendMode.OVERWRITE,
                    true,
                    true
                )
                contentStream.use { it.drawImage(pdImage, 0f, 0f, pageWidth, pageHeight) }
            }

            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val uriString = prefs.getString("save_folder", null)

            if (uriString == null) {
                android.util.Log.e("PDFCreator", "Cartella di salvataggio non configurata")
                return null
            }

            val treeUri = Uri.parse(uriString)
            val docTree = DocumentFile.fromTreeUri(context, treeUri)

            if (docTree == null || !docTree.canWrite()) {
                android.util.Log.e("PDFCreator", "Non è possibile scrivere nella cartella: $uriString")
                return null
            }

            val pdfFile = docTree.createFile("application/pdf", fileName)
                ?: return null
            createdFile = pdfFile

            val output = context.contentResolver.openOutputStream(pdfFile.uri)
                ?: throw IllegalStateException("Impossibile aprire il file PDF per la scrittura")
            output.use { out ->
                document.save(out)
            }
            
            val finalUri = pdfFile.uri
            document.close()
            
            finalUri
        } catch (e: Exception) {
            e.printStackTrace()
            try { createdFile?.delete() } catch (_: Exception) { }
            null
        } finally {
            try { document.close() } catch (_: Exception) {}
        }
    }
}
