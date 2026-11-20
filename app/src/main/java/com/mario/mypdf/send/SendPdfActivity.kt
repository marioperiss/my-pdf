package com.mario.mypdf.send

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mario.mypdf.databinding.ActivitySendPdfBinding

class SendPdfActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySendPdfBinding
    private var pdfUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySendPdfBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Recuperiamo l'URI passato
        pdfUri = intent.data ?: intent.getStringExtra("PDF_URI")?.let { Uri.parse(it) }

        if (pdfUri == null) {
            Toast.makeText(this, "Nessun PDF selezionato", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.btnSharePdf.setOnClickListener {
            sharePdf()
        }

        binding.btnEmailPdf.setOnClickListener {
            sendEmailPdf()
        }
    }

    private fun sharePdf() {
        pdfUri?.let { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri("", uri)
            }
            startActivity(Intent.createChooser(intent, "Invia PDF via..."))
        }
    }

    private fun sendEmailPdf() {
        pdfUri?.let { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Documento PDF")
                putExtra(Intent.EXTRA_TEXT, "In allegato il documento PDF richiesto.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Invia via Email..."))
        }
    }
}

