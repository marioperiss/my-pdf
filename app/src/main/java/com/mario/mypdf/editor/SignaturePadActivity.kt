package com.mario.mypdf.editor

import android.graphics.Bitmap
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mario.mypdf.databinding.ActivitySignaturePadBinding

class SignaturePadActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignaturePadBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignaturePadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pulsante "Pulisci"
        binding.btnClear.setOnClickListener {
            binding.signatureView.clear()
        }

        // Pulsante "Salva"
        binding.btnSave.setOnClickListener {
            val bitmap = binding.signatureView.getSignatureBitmap()
            if (bitmap != null) {
                // Catturiamo SOLO il contenuto della SignatureView (inchiostro) 
                // mantenendo il canale Alpha per la trasparenza totale.
                SignatureStorage.signatureBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                setResult(RESULT_OK)
                finish()
            } else {
                setResult(RESULT_CANCELED)
                finish()
            }
        }

        // Pulsante "Annulla"
        binding.btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }
}
