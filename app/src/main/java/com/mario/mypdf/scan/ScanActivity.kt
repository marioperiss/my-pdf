package com.mario.mypdf.scan

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mario.mypdf.camera.toBitmap
import com.mario.mypdf.databinding.ActivityScanBinding
import com.mario.mypdf.ocr.OcrHelper
import com.mario.mypdf.pdf.DocumentGenerator
import com.mario.mypdf.pdf.PDFCreator
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.media.MediaPlayer
import com.mario.mypdf.R

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var isTorchOn = false

    // Sessione multipagina
    private val sessionPages = mutableListOf<Bitmap>()
    private val sessionOcrTexts = mutableListOf<String>()

    private val cameraPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "Per usare la scansione è necessario consentire l'accesso alla fotocamera", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val importImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                val progressDialog = showProgressDialog("Importazione ed OCR in corso...")
                progressDialog.show()
                lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        try {
                            contentResolver.openInputStream(uri)?.use { stream ->
                                android.graphics.BitmapFactory.decodeStream(stream)
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (bitmap != null) {
                        val processResult = withContext(Dispatchers.IO) {
                            DocumentProcessor.processDetailed(bitmap, 0)
                        }
                        val recognizedText = withContext(Dispatchers.IO) {
                            try {
                                OcrHelper.recognizeText(processResult.ocrBitmap, 0).trim()
                            } catch (_: Exception) {
                                ""
                            }
                        }

                        progressDialog.dismiss()
                        sessionPages.add(processResult.processedBitmap)
                        sessionOcrTexts.add(recognizedText)
                        updatePageUI()
                        playSuccessSound()
                        Toast.makeText(this@ScanActivity, "Immagine importata con successo!", Toast.LENGTH_SHORT).show()
                    } else {
                        progressDialog.dismiss()
                        Toast.makeText(this@ScanActivity, "Impossibile caricare l'immagine selezionata", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updatePageUI()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            cameraPermissionRequest.launch(Manifest.permission.CAMERA)
        }

        binding.btnCapture.setOnClickListener {
            takePhoto()
        }

        binding.btnImport.setOnClickListener {
            importImageLauncher.launch("image/*")
        }

        binding.btnFlash.setOnClickListener {
            toggleFlash()
        }

        binding.btnReviewPages.setOnClickListener {
            if (sessionPages.isEmpty()) {
                Toast.makeText(this, "Nessuna pagina scansionata o importata. Scatta o importa almeno una pagina.", Toast.LENGTH_SHORT).show()
            } else {
                showMultiPageReviewDialog()
            }
        }
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setBufferFormat(ImageFormat.JPEG)
                    .setTargetRotation(binding.cameraPreview.display.rotation)
                    .build()

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture
                )
                imageCapture = capture
            } catch (e: Exception) {
                imageCapture = null
                camera = null
                android.util.Log.e("ScanActivity", "Impossibile avviare la fotocamera", e)
                Toast.makeText(this, "Impossibile avviare la fotocamera: ${e.message}", Toast.LENGTH_LONG).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleFlash() {
        val cam = camera
        if (cam == null) {
            Toast.makeText(this, "Fotocamera non pronta", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            isTorchOn = !isTorchOn
            cam.cameraControl.enableTorch(isTorchOn)
            binding.btnFlash.setColorFilter(if (isTorchOn) android.graphics.Color.YELLOW else android.graphics.Color.WHITE)
            Toast.makeText(this, if (isTorchOn) "Flash acceso" else "Flash spento", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Flash non disponibile su questo dispositivo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePageUI() {
        val count = sessionPages.size
        binding.tvPageCount.text = "Pagine: $count"
        binding.btnReviewPages.text = "Pagine ($count)"
    }

    private fun showProgressDialog(message: String): AlertDialog {
        val progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            setPadding(30, 30, 30, 30)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(40, 30, 40, 30)
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(progressBar)
            addView(TextView(this@ScanActivity).apply {
                text = message
                setPadding(30, 0, 0, 0)
                textSize = 16f
            })
        }
        return AlertDialog.Builder(this)
            .setView(layout)
            .setCancelable(false)
            .create()
    }

    private fun takePhoto() {
        val capture = imageCapture
        if (capture == null) {
            Toast.makeText(this, "Fotocamera in fase di avvio, attendi un istante...", Toast.LENGTH_SHORT).show()
            startCamera()
            return
        }

        val progressDialog = showProgressDialog("Elaborazione e OCR in corso...")
        progressDialog.show()

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {

                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap: Bitmap
                    val processResult: DocumentProcessor.ProcessResult
                    val rotationDegrees = image.imageInfo.rotationDegrees
                    try {
                        bitmap = image.toBitmap()
                        processResult = DocumentProcessor.processDetailed(bitmap, rotationDegrees)
                    } catch (e: Exception) {
                        progressDialog.dismiss()
                        android.util.Log.e("ScanActivity", "Impossibile elaborare la foto acquisita", e)
                        Toast.makeText(this@ScanActivity, "Errore durante l'elaborazione della scansione", Toast.LENGTH_LONG).show()
                        return
                    } finally {
                        image.close()
                    }

                    lifecycleScope.launch {
                        val recognizedText = withContext(Dispatchers.IO) {
                            try {
                                OcrHelper.recognizeText(processResult.ocrBitmap, 0).trim()
                            } catch (_: Exception) {
                                try {
                                    OcrHelper.recognizeText(bitmap, rotationDegrees).trim()
                                } catch (_: Exception) {
                                    ""
                                }
                            }
                        }

                        progressDialog.dismiss()
                        sessionPages.add(processResult.processedBitmap)
                        sessionOcrTexts.add(recognizedText)
                        updatePageUI()
                        playSuccessSound()
                        Toast.makeText(this@ScanActivity, "Pagina ${sessionPages.size} aggiunta con successo!", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    progressDialog.dismiss()
                    exception.printStackTrace()
                    Toast.makeText(this@ScanActivity, "Errore cattura immagine", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun showMultiPageReviewDialog() {
        var dialog: AlertDialog? = null
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val defaultName = "scan_$timestamp"

        val editTextName = EditText(this).apply {
            setText(defaultName)
            setSelectAllOnFocus(true)
            setPadding(30, 20, 30, 20)
        }

        val combinedOcrText = sessionOcrTexts.joinToString("\n\n--- Pagina ---\n\n")
        val editOcrText = EditText(this).apply {
            setText(combinedOcrText)
            setPadding(20, 20, 20, 20)
            setBackgroundColor(android.graphics.Color.parseColor("#F9F9F9"))
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                300
            ).apply { setMargins(0, 5, 0, 15) }
        }

        val containerPages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun refreshPagesList() {
            containerPages.removeAllViews()
            if (sessionPages.isEmpty()) {
                dialog?.dismiss()
                updatePageUI()
                return
            }

            for ((index, bmp) in sessionPages.withIndex()) {
                val pageRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(10, 10, 10, 10)
                    setBackgroundColor(android.graphics.Color.parseColor("#E0E0E0"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        200
                    ).apply { setMargins(0, 0, 0, 8) }
                }

                val thumbView = ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    layoutParams = LinearLayout.LayoutParams(130, 180)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }

                val titleText = TextView(this).apply {
                    text = "  Pagina ${index + 1}"
                    textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val btnRotate = Button(this).apply {
                    text = "🔄 Ruota"
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { setMargins(4, 0, 4, 0) }
                    setOnClickListener {
                        val matrix = android.graphics.Matrix().apply { postRotate(90f) }
                        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                        sessionPages[index] = rotated
                        refreshPagesList()
                    }
                }

                val btnDelete = Button(this).apply {
                    text = "❌ Elimina"
                    setBackgroundColor(android.graphics.Color.parseColor("#D32F2F"))
                    setTextColor(android.graphics.Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { setMargins(4, 0, 4, 0) }
                    setOnClickListener {
                        sessionPages.removeAt(index)
                        if (index < sessionOcrTexts.size) sessionOcrTexts.removeAt(index)
                        refreshPagesList()
                    }
                }

                pageRow.addView(thumbView)
                pageRow.addView(titleText)
                pageRow.addView(btnRotate)
                pageRow.addView(btnDelete)
                containerPages.addView(pageRow)
            }
            updatePageUI()
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                450
            )
            addView(containerPages)
        }

        refreshPagesList()

        val btnPdfImage = Button(this).apply {
            text = "📄 1. Salva PDF Immagine (Piena Pagina, Senza Bordi)"
            setBackgroundColor(android.graphics.Color.parseColor("#1976D2"))
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 10, 0, 6) }
        }

        val btnPdfText = Button(this).apply {
            text = "📝 2. Salva PDF Testuale / Ricercabile (OCR Corretto)"
            setBackgroundColor(android.graphics.Color.parseColor("#388E3C"))
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, 6) }
        }

        val btnWord = Button(this).apply {
            text = "📋 3. Salva come Word (.docx)"
            setBackgroundColor(android.graphics.Color.parseColor("#0288D1"))
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, 6) }
        }

        val btnTxt = Button(this).apply {
            text = "📄 4. Salva come Testo Semplice (.txt)"
            setBackgroundColor(android.graphics.Color.parseColor("#7B1FA2"))
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, 6) }
        }

        val btnAddMore = Button(this).apply {
            text = "➕ Scatta un'altra pagina"
            setBackgroundColor(android.graphics.Color.parseColor("#455A64"))
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, 8) }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 20, 30, 10)
            
            addView(TextView(this@ScanActivity).apply { 
                text = "Pagine Scansionate:" 
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(scrollView)

            addView(TextView(this@ScanActivity).apply { 
                text = "Testo Riconosciuto (Modificabile prima dell'esportazione):" 
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 10, 0, 0)
            })
            addView(editOcrText)

            addView(TextView(this@ScanActivity).apply { text = "Nome del file:" })
            addView(editTextName)

            addView(btnPdfImage)
            addView(btnPdfText)
            addView(btnWord)
            addView(btnTxt)
            addView(btnAddMore)
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Revisione ed Esportazione")
            .setView(layout)
            .setCancelable(true)
            .create()

        btnPdfImage.setOnClickListener {
            if (sessionPages.isEmpty()) {
                Toast.makeText(this, "Nessuna pagina da salvare", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val fileName = editTextName.text.toString().trim().ifEmpty { defaultName }
            dialog?.dismiss()
            savePdfImageAndFinish(sessionPages.toList(), fileName)
        }

        btnPdfText.setOnClickListener {
            val textContent = editOcrText.text.toString().trim()
            if (textContent.isBlank()) {
                Toast.makeText(this, "Il testo da esportare è vuoto. Inserisci o correggi il testo.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val fileName = editTextName.text.toString().trim().ifEmpty { defaultName }
            dialog?.dismiss()
            saveTextAndFinish(textContent, DocumentGenerator.Format.PDF_TEXT, fileName)
        }

        btnWord.setOnClickListener {
            val textContent = editOcrText.text.toString().trim()
            if (textContent.isBlank()) {
                Toast.makeText(this, "Il testo da esportare è vuoto. Inserisci o correggi il testo.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val fileName = editTextName.text.toString().trim().ifEmpty { defaultName }
            dialog?.dismiss()
            saveTextAndFinish(textContent, DocumentGenerator.Format.WORD, fileName)
        }

        btnTxt.setOnClickListener {
            val textContent = editOcrText.text.toString().trim()
            if (textContent.isBlank()) {
                Toast.makeText(this, "Il testo da esportare è vuoto. Inserisci o correggi il testo.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val fileName = editTextName.text.toString().trim().ifEmpty { defaultName }
            dialog?.dismiss()
            saveTextAndFinish(textContent, DocumentGenerator.Format.TEXT_PLAIN, fileName)
        }

        btnAddMore.setOnClickListener {
            dialog?.dismiss()
        }

        dialog?.show()
    }

    private fun savePdfImageAndFinish(bitmaps: List<Bitmap>, fileName: String) {
        val progressDialog = showProgressDialog("Generazione PDF in corso...")
        progressDialog.show()

        lifecycleScope.launch {
            val uri = try {
                withContext(Dispatchers.IO) {
                    PDFCreator.createMultiPagePdf(this@ScanActivity, bitmaps, fileName)
                }
            } catch (e: Exception) {
                android.util.Log.e("ScanActivity", "Errore salvataggio PDF immagine", e)
                null
            }

            progressDialog.dismiss()
            if (uri != null) {
                playSuccessSound()
                showSuccessDialog(uri, fileName)
            } else {
                Toast.makeText(this@ScanActivity, "Errore salvataggio PDF. Controlla la cartella di destinazione.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveTextAndFinish(text: String, format: DocumentGenerator.Format, fileName: String) {
        val progressDialog = showProgressDialog("Esportazione documento in corso...")
        progressDialog.show()

        lifecycleScope.launch {
            val uri = try {
                withContext(Dispatchers.IO) {
                    DocumentGenerator.generateAndSave(this@ScanActivity, text, format, fileName)
                }
            } catch (e: Exception) {
                android.util.Log.e("ScanActivity", "Errore salvataggio documento testuale", e)
                null
            }

            progressDialog.dismiss()
            if (uri != null) {
                playSuccessSound()
                showSuccessDialog(uri, fileName)
            } else {
                Toast.makeText(this@ScanActivity, "Errore salvataggio file. Controlla la cartella di destinazione.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun playSuccessSound() {
        try {
            val player = MediaPlayer.create(this@ScanActivity, R.raw.success)
            player.start()
        } catch (_: Exception) {}
    }

    private fun showSuccessDialog(uri: android.net.Uri, fileName: String) {
        val displayPath = uri.path?.let { 
            if (it.contains(":")) it.split(":").last() else it 
        } ?: fileName

        AlertDialog.Builder(this@ScanActivity)
            .setTitle("Esportazione Completata")
            .setMessage("Documento salvato con successo!\n\nPercorso: $displayPath")
            .setPositiveButton("OK") { _, _ ->
                finish()
            }
            .setNegativeButton("Condividi") { _, _ ->
                val mimeType = when {
                    fileName.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    fileName.endsWith(".txt", true) -> "text/plain"
                    else -> "application/pdf"
                }
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri(fileName, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(Intent.createChooser(shareIntent, "Condividi documento"))
                    finish()
                } catch (e: android.content.ActivityNotFoundException) {
                    android.util.Log.e("ScanActivity", "Nessuna app disponibile per condividere il documento", e)
                    Toast.makeText(this@ScanActivity, "Nessuna app disponibile per condividere questo documento", Toast.LENGTH_LONG).show()
                }
            }
            .setCancelable(false)
            .show()
    }
}
