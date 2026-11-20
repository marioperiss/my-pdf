package com.mario.mypdf.viewer

import android.app.AlertDialog
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.viewpager2.widget.ViewPager2
import com.mario.mypdf.databinding.ActivityPdfViewerBinding
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfViewerBinding
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pendingSourceUri: Uri? = null
    private var pendingFileName: String? = null

    private val saveAsLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null && pendingSourceUri != null) {
            copyFile(pendingSourceUri!!, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val pdfUri: Uri? = if (intent.action == Intent.ACTION_SEND) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        } else {
            intent.data
        }

        if (pdfUri == null) {
            Toast.makeText(this, "Nessun PDF ricevuto", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            // Apertura sicura tramite SAF
            pfd = contentResolver.openFileDescriptor(pdfUri, "r")

            if (pfd == null) {
                Toast.makeText(this, "Impossibile aprire il PDF", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            renderer = PdfRenderer(pfd!!)

            val adapter = PdfPageAdapter(renderer!!)
            binding.viewPager.adapter = adapter
            binding.viewPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL

            binding.btnEditPdf.setOnClickListener {
                val editIntent = Intent(this, com.mario.mypdf.editor.PdfEditorActivity::class.java).apply {
                    data = pdfUri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                startActivity(editIntent)
            }

            binding.btnSaveToApp.setOnClickListener {
                showSaveDialog(pdfUri)
            }

            binding.btnDeletePdf.setOnClickListener {
                confirmDelete(pdfUri)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Errore apertura PDF: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun confirmDelete(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Elimina Documento")
            .setMessage("Sei sicuro di voler eliminare definitivamente questo file?")
            .setPositiveButton("Elimina") { _, _ ->
                deletePdf(uri)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun deletePdf(uri: Uri) {
        try {
            // Tenta di eliminare il file tramite DocumentFile
            val file = DocumentFile.fromSingleUri(this, uri)
            if (file != null && file.delete()) {
                Toast.makeText(this, "Documento eliminato", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                // Se SAF non lo permette direttamente, tentiamo con il ContentResolver
                if (contentResolver.delete(uri, null, null) > 0) {
                    Toast.makeText(this, "Documento eliminato", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "Impossibile eliminare questo file (permessi insufficienti)", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Errore durante l'eliminazione: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSaveDialog(uri: Uri) {
        val editText = EditText(this)
        // Estrai il nome originale se possibile
        val originalName = DocumentFile.fromSingleUri(this, uri)?.name ?: "documento.pdf"
        editText.setText(originalName.removeSuffix(".pdf"))
        editText.setSelectAllOnFocus(true)

        AlertDialog.Builder(this)
            .setTitle("Salva in App")
            .setMessage("Inserisci il nome per salvare il documento nella cartella DocumentScan:")
            .setView(editText)
            .setPositiveButton("Salva") { _, _ ->
                val fileName = editText.text.toString()
                if (fileName.isNotBlank()) {
                    savePdfToApp(uri, fileName)
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun savePdfToApp(sourceUri: Uri, fileName: String) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val treeUriString = prefs.getString("save_folder", null)

        val finalName = if (fileName.lowercase().endsWith(".pdf")) fileName else "$fileName.pdf"

        if (treeUriString == null) {
            // Se non c'è una cartella predefinita, chiedi all'utente dove salvare
            Toast.makeText(this, "Cartella predefinita non trovata. Scegli dove salvare.", Toast.LENGTH_LONG).show()
            pendingSourceUri = sourceUri
            saveAsLauncher.launch(finalName)
            return
        }

        try {
            val treeUri = Uri.parse(treeUriString)
            
            // Verifica se abbiamo i permessi persistenti per questa cartella
            val hasPermission = contentResolver.persistedUriPermissions.any { 
                it.uri == treeUri && it.isWritePermission 
            }

            val docTree = DocumentFile.fromTreeUri(this, treeUri)
            if (!hasPermission || docTree == null || !docTree.canWrite()) {
                // Se non abbiamo i permessi o la cartella non è accessibile, usiamo il selettore manuale
                pendingSourceUri = sourceUri
                saveAsLauncher.launch(finalName)
                return
            }
            
            val newFile = docTree.createFile("application/pdf", finalName)

            if (newFile != null) {
                copyFile(sourceUri, newFile.uri)
            } else {
                // Se la creazione fallisce (es. file già esistente o errore filesystem), prova manuale
                pendingSourceUri = sourceUri
                saveAsLauncher.launch(finalName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // In caso di errore imprevisto, permetti il salvataggio manuale
            pendingSourceUri = sourceUri
            saveAsLauncher.launch(finalName)
        }
    }

    private fun copyFile(source: Uri, destination: Uri) {
        try {
            val input = contentResolver.openInputStream(source)
                ?: throw IOException("Impossibile leggere il PDF sorgente")
            input.use { inStream ->
                val output = contentResolver.openOutputStream(destination)
                    ?: throw IOException("Impossibile scrivere il PDF nella destinazione")
                output.use { outStream -> inStream.copyTo(outStream) }
            }
            Toast.makeText(this, "Salvataggio completato!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            try { contentResolver.delete(destination, null, null) } catch (_: Exception) { }
            Toast.makeText(this, "Errore durante il salvataggio: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close()
        pfd?.close()
    }
}
