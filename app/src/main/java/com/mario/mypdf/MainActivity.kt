package com.mario.mypdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.mario.mypdf.camera.CameraActivity
import com.mario.mypdf.databinding.ActivityMainBinding
import com.mario.mypdf.editor.PdfEditorActivity
import com.mario.mypdf.picker.PdfPickerActivity
import com.mario.mypdf.send.SendPdfActivity
import com.mario.mypdf.viewer.PdfViewerActivity
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                prefs.edit().putString("save_folder", uri.toString()).apply()

                Toast.makeText(this, "Cartella selezionata!", Toast.LENGTH_SHORT).show()
            }
        }

    private val editPdfLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                val intent = Intent(this, PdfEditorActivity::class.java).apply {
                    data = uri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                startActivity(intent)
            }
        }

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "Impossibile mantenere il permesso di lettura del documento", e)
                }

                val mimeType = contentResolver.getType(uri)?.lowercase().orEmpty()
                if (mimeType == "application/pdf") {
                    startActivity(Intent(this, PdfViewerActivity::class.java).apply {
                        data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } else {
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        startActivity(Intent.createChooser(viewIntent, "Apri documento con..."))
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Nessuna applicazione può aprire il documento selezionato", e)
                        Toast.makeText(this, "Nessuna app trovata per aprire questo documento", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    private val sendPdfLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    val mimeType = contentResolver.getType(uri) ?: ""
                    if (mimeType == "application/pdf") {
                        val intent = Intent(this, SendPdfActivity::class.java).apply {
                            data = uri
                            putExtra("PDF_URI", uri.toString())
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(intent)
                    } else {
                        // Per Word e ODT, apriamo direttamente con le app di sistema
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            startActivity(Intent.createChooser(intent, "Apri documento con..."))
                        } catch (e: Exception) {
                            Toast.makeText(this, "Nessuna app trovata per aprire questo file", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inizializza OpenCV
        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "Errore OpenCV", Toast.LENGTH_SHORT).show()
        }

        // Configurazione click sulle Card
        binding.cardScan.setOnClickListener {
            startActivity(Intent(this, com.mario.mypdf.scan.ScanActivity::class.java))
        }

        binding.cardOpen.setOnClickListener {
            openDocumentLauncher.launch(arrayOf(
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.oasis.opendocument.text",
                "text/plain"
            ))
        }

        binding.cardEdit.setOnClickListener {
            editPdfLauncher.launch(arrayOf("application/pdf"))
        }

        binding.cardSend.setOnClickListener {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val uriString = prefs.getString("save_folder", null)
            
            if (uriString == null) {
                Toast.makeText(this, "Seleziona prima la cartella di salvataggio", Toast.LENGTH_SHORT).show()
                folderPicker.launch(null)
                return@setOnClickListener
            }

            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                val mimetypes = arrayOf(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.oasis.opendocument.text",
                    "text/plain"
                )
                putExtra(Intent.EXTRA_MIME_TYPES, mimetypes)
                uriString.let {
                    val uri = Uri.parse(it)
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
                }
            }
            sendPdfLauncher.launch(intent)
        }

        binding.btnChooseFolder.setOnClickListener {
            folderPicker.launch(null)
        }
    }
}
