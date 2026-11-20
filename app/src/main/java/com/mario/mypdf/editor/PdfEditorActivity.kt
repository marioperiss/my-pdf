package com.mario.mypdf.editor

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mario.mypdf.R
import com.mario.mypdf.databinding.ActivityPdfEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfEditorBinding
    private var pdfUri: android.net.Uri? = null
    private lateinit var currentPageBitmap: android.graphics.Bitmap

    private var pdfPageWidthPoints: Int = 0
    private var pdfPageHeightPoints: Int = 0

    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f

    private var selectedTextColor: Int = android.graphics.Color.BLACK

    private val textAnnotations = mutableListOf<PdfTextAnnotation>()
    private val signatureAnnotations = mutableListOf<PdfSignatureAnnotation>()
    
    private var currentPage: Int = 0
    private var totalPages: Int = 0

    private val eraserPaths = mutableListOf<PdfEraserPath>()
    private val pageCropRects = mutableMapOf<Int, android.graphics.RectF>()

    // Stato modifiche non salvate
    private var hasUnsavedChanges = false

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        binding.pdfPageView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val coords = binding.pdfPageView.getMappedCoords(event.x, event.y)
                lastTouchX = coords.first
                lastTouchY = coords.second
                
                if (binding.tvHint.visibility == View.VISIBLE) {
                    // Modalità Inserimento
                    if (binding.tvHint.text.contains("testo")) {
                        openTextDialog(null)
                    } else if (binding.tvHint.text.contains("firma")) {
                        openSignaturePad()
                    }
                    binding.tvHint.visibility = View.GONE
                } else {
                    // Modalità Selezione/Modifica
                    val clickedText = findTextAt(lastTouchX, lastTouchY)
                    if (clickedText != null) {
                        binding.pdfPageView.setSelectedAnnotationManually(clickedText)
                    } else {
                        val clickedSign = findSignatureAt(lastTouchX, lastTouchY)
                        if (clickedSign != null) {
                            binding.pdfPageView.setSelectedAnnotationManually(clickedSign)
                        }
                    }
                }
            }
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pdfUri = intent.data ?: intent.getStringExtra("pdf_path")?.let { android.net.Uri.fromFile(java.io.File(it)) }

        if (pdfUri == null) {
            Toast.makeText(this, "Percorso PDF non valido", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        renderPdfPage(0)

        binding.containerAddText.setOnClickListener {
            binding.tvHint.visibility = View.VISIBLE
            binding.tvHint.text = "Tocca il PDF dove vuoi inserire il testo"
        }

        binding.containerAddSignature.setOnClickListener {
            binding.tvHint.visibility = View.VISIBLE
            binding.tvHint.text = "Tocca il PDF dove vuoi inserire la firma"
            binding.pdfPageView.currentMode = ZoomableImageView.Mode.SELECT
        }

        binding.containerEraser.setOnClickListener {
            binding.pdfPageView.currentMode = ZoomableImageView.Mode.ERASER
            Toast.makeText(this, "Modalità Gomma Attiva", Toast.LENGTH_SHORT).show()
        }

        binding.containerCrop.setOnClickListener {
            binding.pdfPageView.currentMode = ZoomableImageView.Mode.CROP
            Toast.makeText(this, "Trascina i bordi per ritagliare", Toast.LENGTH_SHORT).show()
        }

        binding.containerReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Pagina")
                .setMessage("Cosa vuoi rimuovere da questa pagina?")
                .setPositiveButton("Ritaglio") { _, _ ->
                    pageCropRects.remove(currentPage)
                    binding.pdfPageView.setCropRect(null)
                    hasUnsavedChanges = true
                    Toast.makeText(this, "Ritaglio rimosso", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Gomma") { _, _ ->
                    eraserPaths.removeAll { it.pageIndex == currentPage }
                    refreshAnnotations()
                    hasUnsavedChanges = true
                    Toast.makeText(this, "Cancellazioni rimosse", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Annulla", null)
                .show()
        }

        binding.containerSave.setOnClickListener {
            saveEditedPdf()
        }

        binding.containerSaveAs.setOnClickListener {
            showSaveAsDialog()
        }

        binding.btnNextPage.setOnClickListener {
            if (currentPage < totalPages - 1) {
                saveCurrentPageState()
                renderPdfPage(currentPage + 1)
            }
        }

        binding.btnPrevPage.setOnClickListener {
            if (currentPage > 0) {
                saveCurrentPageState()
                renderPdfPage(currentPage - 1)
            }
        }

        setupTouchListener()

        // Configura Joystick e Selezione
        binding.pdfPageView.onAnnotationSelected = { selected ->
            if (selected != null) {
                binding.joystickCard.visibility = View.VISIBLE
            } else {
                binding.joystickCard.visibility = View.GONE
            }
        }

        val moveStep = 10f
        binding.btnMoveUp.setOnClickListener { binding.pdfPageView.moveSelectedAnnotation(0f, -moveStep); hasUnsavedChanges = true }
        binding.btnMoveDown.setOnClickListener { binding.pdfPageView.moveSelectedAnnotation(0f, moveStep); hasUnsavedChanges = true }
        binding.btnMoveLeft.setOnClickListener { binding.pdfPageView.moveSelectedAnnotation(-moveStep, 0f); hasUnsavedChanges = true }
        binding.btnMoveRight.setOnClickListener { binding.pdfPageView.moveSelectedAnnotation(moveStep, 0f); hasUnsavedChanges = true }
        binding.btnRotateText.setOnClickListener { binding.pdfPageView.rotateSelectedAnnotation(15f); hasUnsavedChanges = true }
        binding.btnConfirmAnnotation.setOnClickListener {
            binding.pdfPageView.setSelectedAnnotationManually(null)
            Toast.makeText(this, "Posizione confermata", Toast.LENGTH_SHORT).show()
        }
        binding.btnDeleteAnnotation.setOnClickListener { 
            binding.pdfPageView.removeSelectedAnnotation()
            hasUnsavedChanges = true
            Toast.makeText(this, "Elemento rimosso", Toast.LENGTH_SHORT).show()
        }
        binding.btnEditAnnotation.setOnClickListener {
            val selected = binding.pdfPageView.selectedAnnotation
            if (selected is PdfTextAnnotation) {
                openTextDialog(selected)
            }
        }

        // Pulsanti Zoom e Rotazione
        binding.btnZoomIn.setOnClickListener {
            binding.pdfPageView.zoomIn()
        }

        binding.btnZoomOut.setOnClickListener {
            binding.pdfPageView.zoomOut()
        }

        binding.btnRotate.setOnClickListener {
            binding.pdfPageView.rotateRight()
            Toast.makeText(this, "Pagina ruotata", Toast.LENGTH_SHORT).show()
        }
        
        binding.containerSave.setOnLongClickListener {
            if (textAnnotations.isNotEmpty() || signatureAnnotations.isNotEmpty()) {
                textAnnotations.clear()
                signatureAnnotations.clear()
                refreshAnnotations()
                hasUnsavedChanges = false
                Toast.makeText(this, "Modifiche annullate", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (hasUnsavedChanges) {
            AlertDialog.Builder(this)
                .setTitle("Modifiche non salvate")
                .setMessage("Ci sono modifiche non salvate. Vuoi uscire senza salvare?")
                .setPositiveButton("Esci senza salvare") { _, _ ->
                    super.onBackPressed()
                }
                .setNegativeButton("Annulla", null)
                .setNeutralButton("Salva prima di uscire") { _, _ ->
                    saveEditedPdf()
                }
                .show()
        } else {
            super.onBackPressed()
        }
    }

    private fun saveCurrentPageState() {
        eraserPaths.removeAll { it.pageIndex == currentPage }
        eraserPaths.addAll(binding.pdfPageView.getEraserPaths())
        binding.pdfPageView.getCropRect()?.let {
            pageCropRects[currentPage] = it
        }
    }

    private fun renderPdfPage(pageIndex: Int) {
        val uri = pdfUri ?: return
        if (totalPages == 0) {
            try {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    android.graphics.pdf.PdfRenderer(pfd).use { renderer -> totalPages = renderer.pageCount }
                }
            } catch (e: Exception) {
                android.util.Log.e("PdfEditorActivity", "Errore apertura PDF renderer", e)
            }
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                PdfRendererHelper.renderPage(this@PdfEditorActivity, uri, pageIndex)
            }
            if (result != null) {
                currentPageBitmap = result.bitmap
                pdfPageWidthPoints = result.widthPoints
                pdfPageHeightPoints = result.heightPoints
                
                binding.pdfPageView.setImageBitmap(result.bitmap)
                binding.pdfPageView.setPdfPageSize(pdfPageWidthPoints, pdfPageHeightPoints)

                currentPage = pageIndex
                binding.pdfPageView.setCurrentPageIndex(pageIndex)
                binding.tvPageIndicator.text = "${currentPage + 1} / $totalPages"
                binding.pdfPageView.setCropRect(pageCropRects[pageIndex])
                refreshAnnotations()
            }
        }
    }

    private fun openTextDialog(existing: PdfTextAnnotation?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_text, null)
        val editText = dialogView.findViewById<EditText>(R.id.etAnnotationText)
        val fontSizePicker = dialogView.findViewById<SeekBar>(R.id.sbFontSize)
        val tvSizePreview = dialogView.findViewById<TextView>(R.id.tvFontSizePreview)
        val btnColor = dialogView.findViewById<android.widget.Button>(R.id.btnChangeColor)

        var tempColor = existing?.color ?: selectedTextColor
        existing?.let {
            editText.setText(it.text)
            fontSizePicker.progress = (it.fontSize - 10).toInt()
            tvSizePreview.text = "Dimensione: ${it.fontSize.toInt()}"
        }

        fontSizePicker.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { tvSizePreview.text = "Dimensione: ${p + 10}" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Aggiungi Testo" else "Modifica Testo")
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                val text = editText.text.toString()
                val size = (fontSizePicker.progress + 10).toFloat()
                if (text.isNotEmpty()) {
                    if (existing != null) {
                        existing.text = text
                        existing.fontSize = size
                        existing.color = tempColor
                        binding.pdfPageView.setSelectedAnnotationManually(existing)
                    } else {
                        val newAnn = PdfTextAnnotation(text, lastTouchX, lastTouchY, size, tempColor, currentPage)
                        textAnnotations.add(newAnn)
                        binding.pdfPageView.setSelectedAnnotationManually(newAnn)
                    }
                    hasUnsavedChanges = true
                    refreshAnnotations()
                }
            }
            .setNegativeButton("Annulla", null)
            .create()

        btnColor?.setOnClickListener {
            val colors = arrayOf("Nero", "Rosso", "Blu", "Verde")
            val colorValues = intArrayOf(android.graphics.Color.BLACK, android.graphics.Color.RED, android.graphics.Color.BLUE, android.graphics.Color.GREEN)
            AlertDialog.Builder(this).setTitle("Scegli Colore").setItems(colors) { _, w ->
                tempColor = colorValues[w]
                selectedTextColor = tempColor
                Toast.makeText(this, "Colore selezionato", Toast.LENGTH_SHORT).show()
            }.show()
        }

        dialog.show()
    }

    private fun findTextAt(x: Float, y: Float): PdfTextAnnotation? {
        for (ann in textAnnotations.asReversed()) {
            val dist = Math.sqrt(Math.pow((ann.x - x).toDouble(), 2.0) + Math.pow((ann.y - y).toDouble(), 2.0))
            if (dist < ann.fontSize * 3) return ann
        }
        return null
    }

    private fun findSignatureAt(x: Float, y: Float): PdfSignatureAnnotation? {
        for (ann in signatureAnnotations.asReversed()) {
            if (x >= ann.x && x <= ann.x + ann.width && y >= ann.y && y <= ann.y + ann.height) return ann
        }
        return null
    }

    private fun refreshAnnotations() {
        val filteredTexts = textAnnotations.filter { it.pageIndex == currentPage }
        val filteredSigns = signatureAnnotations.filter { it.pageIndex == currentPage }
        val filteredErasers = eraserPaths.filter { it.pageIndex == currentPage }
        binding.pdfPageView.setAnnotations(filteredTexts, filteredSigns, filteredErasers)
    }

    @Suppress("DEPRECATION")
    private fun openSignaturePad() {
        startActivityForResult(Intent(this, SignaturePadActivity::class.java), 2001)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK) {
            SignatureStorage.signatureBitmap?.let {
                signatureAnnotations.add(PdfSignatureAnnotation(it, lastTouchX, lastTouchY, 250f, 120f, currentPage))
                hasUnsavedChanges = true
                refreshAnnotations()
            }
        }
    }

    private fun saveEditedPdf() {
        saveCurrentPageState()
        val uri = pdfUri ?: return
        val texts = textAnnotations.toList()
        val signatures = signatureAnnotations.toList()
        val erasers = eraserPaths.toList()
        val crops = pageCropRects.mapValues { android.graphics.RectF(it.value) }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = PdfModifier.modifyPdf(this@PdfEditorActivity, uri, texts, signatures, erasers, crops)
                        ?: throw IllegalStateException("Errore durante la generazione del PDF")
                    try {
                        replaceOriginalSafely(uri, output)
                    } finally {
                        output.delete()
                    }
                }
                hasUnsavedChanges = false
                Toast.makeText(this@PdfEditorActivity, "PDF salvato con successo!", Toast.LENGTH_LONG).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@PdfEditorActivity, "Errore durante il salvataggio: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun replaceOriginalSafely(uri: android.net.Uri, replacement: java.io.File) {
        val backup = java.io.File.createTempFile("pdf-original-", ".bak", cacheDir)
        try {
            val original = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Impossibile creare una copia di sicurezza del PDF")
            original.use { input -> backup.outputStream().use { input.copyTo(it) } }

            try {
                val destination = contentResolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException("Il provider non consente di scrivere sul PDF")
                destination.use { out -> replacement.inputStream().use { it.copyTo(out) } }
                backup.delete()
            } catch (writeError: Exception) {
                try {
                    val restore = contentResolver.openOutputStream(uri, "wt")
                        ?: throw IllegalStateException("Il provider non consente il ripristino")
                    restore.use { out -> backup.inputStream().use { it.copyTo(out) } }
                    backup.delete()
                } catch (restoreError: Exception) {
                    throw IllegalStateException(
                        "Scrittura e ripristino falliti. Copia originale conservata in ${backup.absolutePath}",
                        restoreError
                    )
                }
                throw writeError
            }
        } finally {
            if (backup.length() == 0L) backup.delete()
        }
    }

    private fun showSaveAsDialog() {
        val editText = EditText(this)
        val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        editText.setText("modificato_$timeStamp")
        editText.setSelectAllOnFocus(true)

        AlertDialog.Builder(this)
            .setTitle("Salva come nuovo PDF")
            .setMessage("Inserisci il nome del file:")
            .setView(editText)
            .setPositiveButton("Salva") { _, _ ->
                val fileName = editText.text.toString()
                if (fileName.isNotBlank()) {
                    saveAsNewPdf(fileName)
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun saveAsNewPdf(fileName: String) {
        saveCurrentPageState()
        val uri = pdfUri ?: return
        
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val treeUriString = prefs.getString("save_folder", null)
        
        if (treeUriString == null) {
            Toast.makeText(this, "Errore: Cartella di salvataggio non configurata", Toast.LENGTH_LONG).show()
            return
        }

        val texts = textAnnotations.toList()
        val signatures = signatureAnnotations.toList()
        val erasers = eraserPaths.toList()
        val crops = pageCropRects.mapValues { android.graphics.RectF(it.value) }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = PdfModifier.modifyPdf(this@PdfEditorActivity, uri, texts, signatures, erasers, crops)
                        ?: throw IllegalStateException("Errore durante la generazione del PDF")
                    try {
                        val treeUri = android.net.Uri.parse(treeUriString)
                        val docTree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this@PdfEditorActivity, treeUri)
                            ?: throw IllegalStateException("Cartella di salvataggio non disponibile")
                        
                        val finalFileName = if (fileName.lowercase().endsWith(".pdf")) fileName else "$fileName.pdf"
                        val newFile = docTree.createFile("application/pdf", finalFileName)
                            ?: throw IllegalStateException("Impossibile creare il file nella cartella")

                        try {
                            val destination = contentResolver.openOutputStream(newFile.uri)
                                ?: throw IllegalStateException("Impossibile aprire il nuovo PDF per la scrittura")
                            destination.use { out -> output.inputStream().use { it.copyTo(out) } }
                        } catch (e: Exception) {
                            newFile.delete()
                            throw e
                        }
                    } finally {
                        output.delete()
                    }
                }
                hasUnsavedChanges = false
                Toast.makeText(this@PdfEditorActivity, "Nuovo PDF salvato", Toast.LENGTH_LONG).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@PdfEditorActivity, "Errore durante il salvataggio: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
