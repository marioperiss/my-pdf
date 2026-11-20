package com.mario.mypdf.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy

fun ImageProxy.toBitmap(): Bitmap {
    check(format == ImageFormat.JPEG) {
        "Formato immagine non supportato: $format. È richiesto JPEG."
    }
    val imagePlane = planes.firstOrNull()
        ?: throw IllegalStateException("La foto acquisita non contiene dati immagine")
    val buffer = imagePlane.buffer
    check(buffer.hasRemaining()) { "La foto acquisita è vuota" }

    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
        "Impossibile decodificare la foto acquisita"
    }
}
