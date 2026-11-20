package com.mario.mypdf.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OcrHelper {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognizeText(bitmap: Bitmap, rotationDegrees: Int = 0): String {
        return suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, rotationDegrees)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val recognizedText = visionText.text
                    if (continuation.isActive) continuation.resume(recognizedText)
                }
                .addOnFailureListener { e ->
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
        }
    }
}
