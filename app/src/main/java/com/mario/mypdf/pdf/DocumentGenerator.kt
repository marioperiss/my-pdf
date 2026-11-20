package com.mario.mypdf.pdf

import android.content.Context
import android.net.Uri
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.odftoolkit.odfdom.doc.OdfTextDocument
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

object DocumentGenerator {

    enum class Format {
        PDF_TEXT, WORD, ODT, TEXT_PLAIN
    }

    suspend fun generateAndSave(
        context: Context,
        textContent: String,
        format: Format,
        baseFileName: String? = null
    ): Uri? = withContext(Dispatchers.IO) {
        require(textContent.isNotBlank()) { "Non è stato riconosciuto alcun testo da salvare" }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val requestedName = baseFileName?.let {
            if (it.isBlank()) "document_$timestamp" else it
        } ?: "document_$timestamp"

        val mimeType = when (format) {
            Format.PDF_TEXT -> "application/pdf"
            Format.WORD -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            Format.ODT -> "application/vnd.oasis.opendocument.text"
            Format.TEXT_PLAIN -> "text/plain"
        }
        val extension = when (format) {
            Format.PDF_TEXT -> ".pdf"
            Format.WORD -> ".docx"
            Format.ODT -> ".odt"
            Format.TEXT_PLAIN -> ".txt"
        }
        val safeName = requestedName.substringAfterLast('/').substringAfterLast('\\').trim()
        val normalizedName = if (safeName.endsWith(extension, ignoreCase = true)) {
            safeName.dropLast(extension.length)
        } else safeName
        val fileName = normalizedName.ifBlank { "document_$timestamp" }
        val fullFileName = fileName + extension

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val uriString = prefs.getString("save_folder", null)
            ?: throw IllegalStateException("Seleziona prima una cartella di salvataggio")

        val treeUri = Uri.parse(uriString)
        val documentFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("La cartella di salvataggio non è accessibile")
        check(documentFile.canWrite()) { "Non è possibile scrivere nella cartella selezionata" }

        val newFile = documentFile.createFile(mimeType, fullFileName)
            ?: throw IllegalStateException("Impossibile creare il file nella cartella selezionata")

        try {
            val outputStream = context.contentResolver.openOutputStream(newFile.uri)
                ?: throw IllegalStateException("Impossibile aprire il file per la scrittura")
            outputStream.use {
            when (format) {
                Format.PDF_TEXT -> generatePdfText(it, textContent)
                Format.WORD -> generateWord(it, fileName, textContent)
                Format.ODT -> generateOdt(it, fileName, textContent)
                Format.TEXT_PLAIN -> generateTextPlain(it, fileName, textContent)
            }
            }
            newFile.uri
        } catch (e: Exception) {
            newFile.delete()
            throw e
        }
    }

    private fun generatePdfText(outputStream: OutputStream, text: String) {
        val writer = PdfWriter(outputStream)
        val pdf = PdfDocument(writer)
        val document = Document(pdf)

        document.setMargins(0f, 0f, 0f, 0f)
        text.split("\n").forEach { line ->
            document.add(Paragraph(line).setFontSize(12f).setMargin(0f))
        }

        document.close()
    }

    private fun generateWord(outputStream: OutputStream, title: String, text: String) {
        XWPFDocument().use { doc ->
            // Titolo
            val titlePara = doc.createParagraph()
            val titleRun = titlePara.createRun()
            titleRun.isBold = true
            titleRun.fontSize = 20
            titleRun.setText(title)
            
            // Spazio dopo il titolo
            doc.createParagraph()

            // Contenuto: dividiamo per linee per gestire correttamente gli invii a capo
            text.split("\n").forEach { line ->
                if (line.isNotBlank()) {
                    val para = doc.createParagraph()
                    val run = para.createRun()
                    run.fontSize = 12
                    run.setText(line)
                } else {
                    // Riga vuota
                    doc.createParagraph()
                }
            }
            
            doc.write(outputStream)
        }
    }

    private fun generateOdt(outputStream: OutputStream, title: String, text: String) {
        val doc = OdfTextDocument.newTextDocument()
        
        // Titolo
        doc.newParagraph(title.uppercase())
        doc.newParagraph("") 
        
        // Contenuto
        text.split("\n").forEach { line ->
            doc.newParagraph(line)
        }

        doc.save(outputStream)
    }

    private fun generateTextPlain(outputStream: OutputStream, title: String, text: String) {
        val fullContent = "$title\n${"=".repeat(title.length)}\n\n$text"
        outputStream.write(fullContent.toByteArray(StandardCharsets.UTF_8))
    }
}
