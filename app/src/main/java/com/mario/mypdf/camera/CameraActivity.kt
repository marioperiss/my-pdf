package com.mario.mypdf.camera

import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.mario.mypdf.databinding.ActivityCameraBinding
import com.mario.mypdf.pdf.PDFCreator
import android.media.MediaPlayer
import com.mario.mypdf.R
import com.mario.mypdf.viewer.PdfViewerActivity

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding

    private var imageCapture: ImageCapture? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "Permesso fotocamera negato", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Verifica cartella di salvataggio all'avvio
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getString("save_folder", null) == null) {
            Toast.makeText(this, "Attenzione: Seleziona una cartella nella Home prima di scansionare", Toast.LENGTH_LONG).show()
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        binding.btnCapture.setOnClickListener {
            takePhotoAndCreatePdf()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also { p ->
                p.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setBufferFormat(ImageFormat.JPEG)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Errore avvio fotocamera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhotoAndCreatePdf() {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {

                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)

                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                        val pdfUri = PDFCreator.createSinglePagePdf(this@CameraActivity, bitmap)

                        if (pdfUri != null) {
                            Toast.makeText(
                                this@CameraActivity,
                                "PDF creato con successo!",
                                Toast.LENGTH_SHORT
                            ).show()

                            try {
                                val player = MediaPlayer.create(this@CameraActivity, R.raw.success)
                                player.start()
                            } catch (_: Exception) {}

                            val intent = Intent(this@CameraActivity, PdfViewerActivity::class.java)
                            intent.data = pdfUri
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            startActivity(intent)

                            finish()
                        } else {
                            // Controllo se la cartella è impostata
                            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                            val folder = prefs.getString("save_folder", null)
                            val message = if (folder == null) {
                                "Errore: Seleziona prima una cartella di destinazione nella Home"
                            } else {
                                "Errore nel salvataggio del PDF. Controlla i permessi della cartella."
                            }
                            Toast.makeText(this@CameraActivity, message, Toast.LENGTH_LONG).show()
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(
                            this@CameraActivity,
                            "Errore post-creazione: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    Toast.makeText(
                        this@CameraActivity,
                        "Errore cattura immagine",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}

