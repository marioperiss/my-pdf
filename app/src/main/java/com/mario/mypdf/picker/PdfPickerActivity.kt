package com.mario.mypdf.picker

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.mario.mypdf.databinding.ActivityPdfPickerBinding
import com.mario.mypdf.viewer.PdfViewerActivity
import java.text.SimpleDateFormat
import java.util.*

class PdfPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfPickerBinding
    private data class DocItem(val file: DocumentFile, val name: String, val lastModified: Long)

    private val allDocs = mutableListOf<DocItem>()
    private val displayedDocs = mutableListOf<DocItem>()
    private lateinit var adapter: ArrayAdapter<String>

    private var sortByNameAsc = true
    private var sortByDateDesc = true

    private val pickExternalPdfLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val intent = Intent(this, PdfViewerActivity::class.java).apply {
                    data = uri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                startActivity(intent)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadDocumentsFromAppFolder()
    }

    override fun onResume() {
        super.onResume()
        loadDocumentsFromAppFolder()
    }

    private fun setupUI() {
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.listViewDocs.adapter = adapter

        binding.listViewDocs.setOnItemClickListener { _, _, position, _ ->
            if (position in displayedDocs.indices) {
                val doc = displayedDocs[position]
                showDocumentOptionsDialog(doc)
            }
        }

        binding.btnPickExternal.setOnClickListener {
            pickExternalPdfLauncher.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.oasis.opendocument.text", "text/plain"))
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterAndDisplay(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnSortName.setOnClickListener {
            sortByNameAsc = !sortByNameAsc
            sortDocs(byName = true)
        }

        binding.btnSortDate.setOnClickListener {
            sortByDateDesc = !sortByDateDesc
            sortDocs(byName = false)
        }
    }

    private fun loadDocumentsFromAppFolder() {
        allDocs.clear()
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val uriString = prefs.getString("save_folder", null)

        if (uriString == null) {
            Toast.makeText(this, "Nessuna cartella di salvataggio impostata. Selezionane una nella Home.", Toast.LENGTH_LONG).show()
            updateDisplay()
            return
        }

        try {
            val treeUri = Uri.parse(uriString)
            val docTree = DocumentFile.fromTreeUri(this, treeUri)
            if (docTree != null && docTree.isDirectory) {
                for (file in docTree.listFiles()) {
                    val name = file.name ?: continue
                    val lastMod = file.lastModified()
                    allDocs.add(DocItem(file, name, lastMod))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Errore caricamento documenti: ${e.message}", Toast.LENGTH_LONG).show()
        }

        sortDocs(byName = true)
    }

    private fun sortDocs(byName: Boolean) {
        if (byName) {
            if (sortByNameAsc) {
                allDocs.sortBy { it.name.lowercase() }
            } else {
                allDocs.sortByDescending { it.name.lowercase() }
            }
        } else {
            if (sortByDateDesc) {
                allDocs.sortByDescending { it.lastModified }
            } else {
                allDocs.sortBy { it.lastModified }
            }
        }
        filterAndDisplay(binding.etSearch.text.toString())
    }

    private fun filterAndDisplay(query: String) {
        displayedDocs.clear()
        val lowerQuery = query.lowercase().trim()
        for (doc in allDocs) {
            if (lowerQuery.isEmpty() || doc.name.lowercase().contains(lowerQuery)) {
                displayedDocs.add(doc)
            }
        }

        val items = displayedDocs.map { doc ->
            val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(doc.lastModified))
            "${doc.name}\nUltima modifica: $dateStr"
        }
        adapter.clear()
        adapter.addAll(items)
        adapter.notifyDataSetChanged()
    }

    private fun updateDisplay() {
        filterAndDisplay(binding.etSearch.text.toString())
    }

    private fun showDocumentOptionsDialog(doc: DocItem) {
        val options = arrayOf("Apri / Visualizza", "Rinomina", "Duplica", "Condividi", "Elimina")
        AlertDialog.Builder(this)
            .setTitle(doc.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openDocument(doc)
                    1 -> renameDocument(doc)
                    2 -> duplicateDocument(doc)
                    3 -> shareDocument(doc)
                    4 -> confirmDeleteDocument(doc)
                }
            }
            .show()
    }

    private fun openDocument(doc: DocItem) {
        val uri = doc.file.uri
        val mimeType = doc.file.type ?: "application/pdf"
        if (mimeType.contains("pdf", true)) {
            val intent = Intent(this, PdfViewerActivity::class.java).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } else {
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

    private fun renameDocument(doc: DocItem) {
        val editText = EditText(this).apply {
            setText(doc.name)
            setSelectAllOnFocus(true)
            setPadding(30, 20, 30, 20)
        }

        AlertDialog.Builder(this)
            .setTitle("Rinomina Documento")
            .setView(editText)
            .setPositiveButton("Salva") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != doc.name) {
                    val success = doc.file.renameTo(newName)
                    if (success) {
                        Toast.makeText(this, "Documento rinominato", Toast.LENGTH_SHORT).show()
                        loadDocumentsFromAppFolder()
                    } else {
                        Toast.makeText(this, "Impossibile rinominare il file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun duplicateDocument(doc: DocItem) {
        val originalName = doc.name
        val extension = originalName.substringAfterLast('.', "")
        val baseName = if (extension.isNotEmpty()) originalName.dropLast(extension.length + 1) else originalName
        val newName = "${baseName}_copia" + if (extension.isNotEmpty()) ".$extension" else ""

        try {
            val parent = doc.file.parentFile
            if (parent != null && parent.canWrite()) {
                val mime = doc.file.type ?: "application/pdf"
                val newFile = parent.createFile(mime, newName)
                if (newFile != null) {
                    contentResolver.openInputStream(doc.file.uri)?.use { input ->
                        contentResolver.openOutputStream(newFile.uri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    Toast.makeText(this, "Documento duplicato", Toast.LENGTH_SHORT).show()
                    loadDocumentsFromAppFolder()
                } else {
                    Toast.makeText(this, "Impossibile creare il file duplicato", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Errore duplicazione: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareDocument(doc: DocItem) {
        val mimeType = doc.file.type ?: "application/pdf"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, doc.file.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, "Condividi documento"))
        } catch (e: Exception) {
            Toast.makeText(this, "Nessuna app di condivisione disponibile", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteDocument(doc: DocItem) {
        AlertDialog.Builder(this)
            .setTitle("Elimina Documento")
            .setMessage("Sei sicuro di voler eliminare '${doc.name}'? Questa operazione non può essere annullata.")
            .setPositiveButton("Elimina") { _, _ ->
                val deleted = doc.file.delete()
                if (deleted) {
                    Toast.makeText(this, "Documento eliminato", Toast.LENGTH_SHORT).show()
                    loadDocumentsFromAppFolder()
                } else {
                    Toast.makeText(this, "Impossibile eliminare il file", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}
