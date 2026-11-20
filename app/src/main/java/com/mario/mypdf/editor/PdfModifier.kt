package com.mario.mypdf.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

object PdfModifier {

    fun modifyPdf(
        context: Context,
        inputPdfUri: Uri,
        textAnnotations: List<PdfTextAnnotation>,
        signatureAnnotations: List<PdfSignatureAnnotation>,
        eraserPaths: List<PdfEraserPath> = emptyList(),
        cropRects: Map<Int, android.graphics.RectF> = emptyMap()
    ): File? {
        var document: PDDocument? = null
        try {
            PDFBoxResourceLoader.init(context)

            val inputStream = context.contentResolver.openInputStream(inputPdfUri) ?: return null
            document = inputStream.use { PDDocument.load(it) }

            if (document.numberOfPages == 0) {
                document.close()
                return null
            }

            // -------------------------
            // RITAGLIO (CROP)
            // -------------------------
            for ((pageIdx, rect) in cropRects) {
                if (pageIdx in 0 until document.numberOfPages) {
                    val page = document.getPage(pageIdx)
                    val mediaBox = page.mediaBox
                    val pageHeight = mediaBox.height
                    
                    // Converti da Top-Left (UI) a Bottom-Left (PDF)
                    // Nota: rect è in punti PDF
                    val newLeft = rect.left.coerceIn(0f, mediaBox.width)
                    val newRight = rect.right.coerceIn(0f, mediaBox.width)
                    val top = rect.top.coerceIn(0f, pageHeight)
                    val bottom = rect.bottom.coerceIn(0f, pageHeight)
                    val newTop = pageHeight - minOf(top, bottom)
                    val newBottom = pageHeight - maxOf(top, bottom)
                    if (newRight <= newLeft || newTop <= newBottom) continue

                    val newBox = PDRectangle(
                        newLeft,
                        newBottom,
                        newRight - newLeft,
                        newTop - newBottom
                    )
                    page.mediaBox = newBox
                    page.cropBox = newBox
                }
            }

            // -------------------------
            // GOMMA (ERASER) - Disegna linee bianche
            // -------------------------
            for (path in eraserPaths) {
                if (path.pageIndex !in 0 until document.numberOfPages) continue
                val annotationPage = document.getPage(path.pageIndex)
                val annotationPageHeight = annotationPage.mediaBox.height
                
                val contentStream = PDPageContentStream(
                    document,
                    annotationPage,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true
                )
                
                contentStream.setStrokingColor(1f, 1f, 1f) // Bianco
                contentStream.setLineWidth(path.strokeWidth)
                
                if (path.points.isNotEmpty()) {
                    val p0 = path.points[0]
                    contentStream.moveTo(p0.x, annotationPageHeight - p0.y)
                    for (i in 1 until path.points.size) {
                        val pi = path.points[i]
                        contentStream.lineTo(pi.x, annotationPageHeight - pi.y)
                    }
                    contentStream.stroke()
                }
                contentStream.close()
            }

            // -------------------------
            // TESTO
            // -------------------------
            for (ann in textAnnotations) {
                if (ann.pageIndex !in 0 until document.numberOfPages) continue
                val annotationPage = document.getPage(ann.pageIndex)
                val annotationPageHeight = annotationPage.mediaBox.height
                
                val contentStream = PDPageContentStream(
                    document,
                    annotationPage,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true
                )
                
                contentStream.beginText()
                contentStream.setFont(PDType1Font.HELVETICA, ann.fontSize)

                val r = ((ann.color shr 16) and 0xFF) / 255f
                val g = ((ann.color shr 8) and 0xFF) / 255f
                val b = (ann.color and 0xFF) / 255f
                contentStream.setNonStrokingColor(r, g, b)

                val pdfX = ann.x
                val pdfY = annotationPageHeight - ann.y

                // Imposta la matrice di trasformazione (Identità) prima di applicare la rotazione
                contentStream.setTextMatrix(Matrix())

                // Applica rotazione e posizionamento.
                val matrix = Matrix.getRotateInstance(Math.toRadians(-ann.rotation.toDouble()), pdfX, pdfY)
                contentStream.setTextMatrix(matrix)

                val lines = ann.text.split("\n")
                for (i in lines.indices) {
                    if (i > 0) {
                        // Calcoliamo la posizione di ogni riga successiva
                        // Calcoliamo la posizione relativa (0, -offset) e la ruotiamo rispetto all'ancora iniziale
                        val angle = Math.toRadians(-ann.rotation.toDouble())
                        val offsetY = -(i * ann.fontSize * 1.2f)
                        val rotatedX = pdfX + (0 * Math.cos(angle) - offsetY * Math.sin(angle)).toFloat()
                        val rotatedY = pdfY + (0 * Math.sin(angle) + offsetY * Math.cos(angle)).toFloat()
                        
                        val m = Matrix.getRotateInstance(angle, rotatedX, rotatedY)
                        contentStream.setTextMatrix(m)
                    }
                    contentStream.showText(lines[i])
                }
                contentStream.endText()
                contentStream.close()
            }

            // -------------------------
            // FIRME (IMMAGINI)
            // -------------------------
            for (sign in signatureAnnotations) {
                if (sign.pageIndex !in 0 until document.numberOfPages) continue
                val annotationPage = document.getPage(sign.pageIndex)
                val annotationPageHeight = annotationPage.mediaBox.height

                val contentStream = PDPageContentStream(
                    document,
                    annotationPage,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true
                )

                val bmp: Bitmap = sign.bitmap
                val x = sign.x
                val y = annotationPageHeight - sign.y - sign.height
                val pdImage = LosslessFactory.createFromImage(document, bmp)

                // Applica rotazione tramite matrice se necessario
                if (sign.rotation != 0f) {
                    val matrix = Matrix.getRotateInstance(
                        Math.toRadians(-sign.rotation.toDouble()),
                        x + sign.width / 2f,
                        y + sign.height / 2f
                    )
                    // Per le immagini PDFBox drawImage non supporta direttamente la matrice come setTextMatrix
                    // Dobbiamo concatenare la trasformazione al content stream
                    contentStream.transform(matrix)
                    contentStream.drawImage(pdImage, -sign.width / 2f, -sign.height / 2f, sign.width, sign.height)
                } else {
                    contentStream.drawImage(pdImage, x, y, sign.width, sign.height)
                }

                contentStream.close()
            }

            // Salva in un nuovo file nella cache
            val outFile = File(context.cacheDir, "edited_pdf_${System.currentTimeMillis()}.pdf")
            document.save(outFile)
            
            return outFile

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try { document?.close() } catch (_: Exception) {}
        }
    }

    // Legacy support
    fun modifyPdf(
        context: Context,
        inputPdfPath: String,
        textAnnotations: List<PdfTextAnnotation>,
        signatureAnnotations: List<PdfSignatureAnnotation>,
        eraserPaths: List<PdfEraserPath> = emptyList(),
        cropRects: Map<Int, android.graphics.RectF> = emptyMap()
    ): File? {
        return modifyPdf(context, Uri.fromFile(File(inputPdfPath)), textAnnotations, signatureAnnotations, eraserPaths, cropRects)
    }
}
