package com.mario.mypdf.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private var scaleFactor = 1f
    private var posX = 0f
    private var posY = 0f
    private var rotationDegrees = 0f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = -1

    private var pdfWidthPoints = 0
    private var pdfHeightPoints = 0
    private var internalScale = 1f

    private var draggedText: PdfTextAnnotation? = null
    private var draggedSignature: PdfSignatureAnnotation? = null
    private var isDraggingAnnotation = false

    enum class Mode { NONE, ADD_TEXT, ADD_SIGNATURE, SELECT, ERASER, CROP }
    var currentMode = Mode.SELECT

    // Elemento attualmente selezionato (per il joystick)
    var selectedAnnotation: Any? = null
        private set

    private val textAnnotations = mutableListOf<PdfTextAnnotation>()
    private val signatureAnnotations = mutableListOf<PdfSignatureAnnotation>()
    private val eraserPaths = mutableListOf<PdfEraserPath>()
    
    private var currentEraserPath: PdfEraserPath? = null
    private var currentPageIndex: Int = 0
    
    private var cropRect: RectF? = null
    private var isResizingCrop = false
    private var activeCropHandle = -1 // 0: TL, 1: TR, 2: BR, 3: BL

    private val overlayPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#88000000")
        style = Paint.Style.FILL
    }

    private val eraserPaint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val cropPaint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val handlePaint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.RED
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val selectedPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = android.graphics.Color.parseColor("#42A5F5") // Un blu più moderno e leggero
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f, 5f), 0f)
    }

    private val borderPaint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(15f, 15f), 0f)
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(0.5f, 5f)
                invalidate()
                return true
            }
        }
    )

    // Callback per la selezione (notifica l'Activity)
    var onAnnotationSelected: ((Any?) -> Unit)? = null

    fun setAnnotations(texts: List<PdfTextAnnotation>, signatures: List<PdfSignatureAnnotation>, erasers: List<PdfEraserPath> = emptyList()) {
        textAnnotations.clear()
        textAnnotations.addAll(texts)
        signatureAnnotations.clear()
        signatureAnnotations.addAll(signatures)
        eraserPaths.clear()
        eraserPaths.addAll(erasers)
        invalidate()
    }

    fun setCurrentPageIndex(pageIndex: Int) {
        currentPageIndex = pageIndex
    }

    fun getEraserPaths(): List<PdfEraserPath> = eraserPaths.map { path ->
        path.copy(points = path.points.map { android.graphics.PointF(it.x, it.y) }.toMutableList())
    }

    fun setCropRect(rect: RectF?) {
        this.cropRect = rect
        invalidate()
    }

    fun getCropRect(): RectF? = cropRect

    fun setPdfPageSize(width: Int, height: Int) {
        this.pdfWidthPoints = width
        this.pdfHeightPoints = height
        updateInternalScale()
        post { centerContent() }
    }

    private fun centerContent() {
        val drawable = drawable ?: return
        val iw = drawable.intrinsicWidth.toFloat()
        val ih = drawable.intrinsicHeight.toFloat()
        
        if (width > 0 && height > 0) {
            // Calcola la posizione per centrare il bitmap nel View
            posX = (width - iw) / 2f
            posY = (height - ih) / 2f
            invalidate()
        }
    }

    private fun updateInternalScale() {
        val drawable = drawable ?: return
        if (pdfWidthPoints > 0) {
            internalScale = drawable.intrinsicWidth.toFloat() / pdfWidthPoints
        }
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        updateInternalScale()
        post { centerContent() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            centerContent()
        }
    }

    fun rotateRight() {
        rotationDegrees = (rotationDegrees + 90f) % 360f
        invalidate()
    }

    fun zoomIn() {
        scaleFactor = (scaleFactor * 1.2f).coerceIn(0.5f, 5f)
        invalidate()
    }

    fun zoomOut() {
        scaleFactor = (scaleFactor / 1.2f).coerceIn(0.5f, 5f)
        invalidate()
    }

    fun getRotationDegrees(): Float = rotationDegrees

    private fun findTextAt(x: Float, y: Float): PdfTextAnnotation? {
        for (ann in textAnnotations.asReversed()) {
            textPaint.textSize = ann.fontSize
            val textWidth = textPaint.measureText(ann.text)
            val textHeight = ann.fontSize
            
            // Definiamo un'area di tocco più generosa attorno al testo
            // Consideriamo anche la rotazione in modo approssimativo (o usiamo un raggio più grande)
            val rect = android.graphics.RectF(
                ann.x - 20, 
                ann.y - textHeight - 20, 
                ann.x + textWidth + 20, 
                ann.y + 20
            )
            
            if (rect.contains(x, y)) return ann
        }
        return null
    }

    fun removeSelectedAnnotation() {
        val selected = selectedAnnotation ?: return
        if (selected is PdfTextAnnotation) {
            textAnnotations.remove(selected)
        } else if (selected is PdfSignatureAnnotation) {
            signatureAnnotations.remove(selected)
        }
        selectedAnnotation = null
        onAnnotationSelected?.invoke(null)
        invalidate()
    }

    private fun findSignatureAt(x: Float, y: Float): PdfSignatureAnnotation? {
        for (ann in signatureAnnotations.asReversed()) {
            val rect = RectF(
                ann.x - 20,
                ann.y - 20,
                ann.x + ann.width + 20,
                ann.y + ann.height + 20
            )
            if (rect.contains(x, y)) return ann
        }
        return null
    }

    fun moveSelectedAnnotation(dxScreen: Float, dyScreen: Float) {
        val rad = Math.toRadians(-rotationDegrees.toDouble())
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()
        
        // Ruota il vettore di movimento e dividi per lo zoom per mantenere la precisione
        val dxPdf = (dxScreen * cos - dyScreen * sin) / (scaleFactor)
        val dyPdf = (dxScreen * sin + dyScreen * cos) / (scaleFactor)

        when (val ann = selectedAnnotation) {
            is PdfTextAnnotation -> {
                ann.x += dxPdf
                ann.y += dyPdf
            }
            is PdfSignatureAnnotation -> {
                ann.x += dxPdf
                ann.y += dyPdf
            }
        }
        invalidate()
    }

    fun rotateSelectedAnnotation(degrees: Float) {
        when (val ann = selectedAnnotation) {
            is PdfTextAnnotation -> {
                ann.rotation = (ann.rotation + degrees) % 360
            }
            is PdfSignatureAnnotation -> {
                ann.rotation = (ann.rotation + degrees) % 360
            }
        }
        invalidate()
    }

    fun setSelectedAnnotationManually(ann: Any?) {
        selectedAnnotation = ann
        onAnnotationSelected?.invoke(ann)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        val action = event.actionMasked
        val (mappedX, mappedY) = getMappedCoords(event.x, event.y)

        if (currentMode == Mode.ERASER) {
            handleEraserEvent(action, mappedX, mappedY)
            return true
        }

        if (currentMode == Mode.CROP) {
            handleCropEvent(action, mappedX, mappedY)
            return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                activePointerId = event.getPointerId(0)

                draggedText = findTextAt(mappedX, mappedY)
                if (draggedText == null) {
                    draggedSignature = findSignatureAt(mappedX, mappedY)
                }
                
                // Aggiorna la selezione solo se abbiamo cliccato su un elemento.
                // Questo impedisce al joystick di sparire cliccando per errore lo sfondo.
                val hit = draggedText ?: draggedSignature
                if (hit != null) {
                    selectedAnnotation = hit
                    onAnnotationSelected?.invoke(selectedAnnotation)
                }
                
                isDraggingAnnotation = (draggedText != null || draggedSignature != null)
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex != -1) {
                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)

                    if (!scaleDetector.isInProgress) {
                        val dx = x - lastTouchX
                        val dy = y - lastTouchY

                        if (isDraggingAnnotation) {
                            val (prevMappedX, prevMappedY) = getMappedCoords(lastTouchX, lastTouchY)
                            val (currMappedX, currMappedY) = getMappedCoords(x, y)
                            val mDx = currMappedX - prevMappedX
                            val mDy = currMappedY - prevMappedY

                            draggedText?.let {
                                it.x += mDx
                                it.y += mDy
                            }
                            draggedSignature?.let {
                                it.x += mDx
                                it.y += mDy
                            }
                            invalidate()
                        } else {
                            posX += dx
                            posY += dy
                            invalidate()
                        }
                    }

                    lastTouchX = x
                    lastTouchY = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
                draggedText = null
                draggedSignature = null
                isDraggingAnnotation = false
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newPointerIndex = if (pointerIndex == 0) 1 else 0
                    lastTouchX = event.getX(newPointerIndex)
                    lastTouchY = event.getY(newPointerIndex)
                    activePointerId = event.getPointerId(newPointerIndex)
                }
            }
        }
        return true
    }

    private fun handleEraserEvent(action: Int, x: Float, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                currentEraserPath = PdfEraserPath().apply {
                    pageIndex = currentPageIndex
                    points.add(android.graphics.PointF(x, y))
                }
                eraserPaths.add(currentEraserPath!!)
            }
            MotionEvent.ACTION_MOVE -> {
                currentEraserPath?.points?.add(android.graphics.PointF(x, y))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentEraserPath = null
            }
        }
    }

    private fun handleCropEvent(action: Int, x: Float, y: Float) {
        val handleSize = 60f / internalScale // Dimensione tocco aumentata
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (cropRect == null) {
                    // Se non c'è un rettangolo, lo creiamo che copre quasi l'intera pagina (80%)
                    val marginX = pdfWidthPoints * 0.1f
                    val marginY = pdfHeightPoints * 0.1f
                    cropRect = RectF(marginX, marginY, pdfWidthPoints - marginX, pdfHeightPoints - marginY)
                    isResizingCrop = false
                } else {
                    val r = cropRect!!
                    activeCropHandle = when {
                        dist(x, y, r.left, r.top) < handleSize -> 0
                        dist(x, y, r.right, r.top) < handleSize -> 1
                        dist(x, y, r.right, r.bottom) < handleSize -> 2
                        dist(x, y, r.left, r.bottom) < handleSize -> 3
                        // Permette di trascinare i bordi (non solo gli angoli)
                        Math.abs(x - r.left) < handleSize && y > r.top && y < r.bottom -> 4 // Bordo sinistro
                        Math.abs(x - r.right) < handleSize && y > r.top && y < r.bottom -> 5 // Bordo destro
                        Math.abs(y - r.top) < handleSize && x > r.left && x < r.right -> 6 // Bordo superiore
                        Math.abs(y - r.bottom) < handleSize && x > r.left && x < r.right -> 7 // Bordo inferiore
                        // Permette di spostare l'intero rettangolo
                        r.contains(x, y) -> 8
                        else -> -1
                    }
                    isResizingCrop = activeCropHandle != -1
                }
                lastTouchX = x
                lastTouchY = y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                cropRect?.let { r ->
                    if (isResizingCrop) {
                        val dx = x - lastTouchX
                        val dy = y - lastTouchY
                        
                        when (activeCropHandle) {
                            0 -> { r.left = x; r.top = y }
                            1 -> { r.right = x; r.top = y }
                            2 -> { r.right = x; r.bottom = y }
                            3 -> { r.left = x; r.bottom = y }
                            4 -> { r.left = x }
                            5 -> { r.right = x }
                            6 -> { r.top = y }
                            7 -> { r.bottom = y }
                            8 -> { r.offset(dx, dy) }
                        }
                        
                        // Vincoli per non far invertire il rettangolo o uscire dai bordi
                        r.left = r.left.coerceIn(0f, r.right - 50f)
                        r.right = r.right.coerceIn(r.left + 50f, pdfWidthPoints.toFloat())
                        r.top = r.top.coerceIn(0f, r.bottom - 50f)
                        r.bottom = r.bottom.coerceIn(r.top + 50f, pdfHeightPoints.toFloat())
                        
                        invalidate()
                    }
                }
                lastTouchX = x
                lastTouchY = y
            }
            MotionEvent.ACTION_UP -> {
                isResizingCrop = false
                activeCropHandle = -1
            }
        }
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) = 
        Math.sqrt(Math.pow((x1 - x2).toDouble(), 2.0) + Math.pow((y1 - y2).toDouble(), 2.0)).toFloat()

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        
        // Applica trasformazioni: traslazione, zoom e rotazione
        canvas.translate(posX, posY)
        canvas.scale(scaleFactor, scaleFactor, width / 2f, height / 2f)
        canvas.rotate(rotationDegrees, width / 2f, height / 2f)
        
        super.onDraw(canvas)

        // Disegna un bordo grigio tratteggiato per delimitare la pagina PDF (solo per l'editor)
        val pdfW = pdfWidthPoints * internalScale
        val pdfH = pdfHeightPoints * internalScale
        canvas.drawRect(0f, 0f, pdfW, pdfH, borderPaint)

        // Disegna cancellazioni (percorsi bianchi)
        for (path in eraserPaths) {
            if (path.points.size < 2) continue
            val p = android.graphics.Path()
            p.moveTo(path.points[0].x * internalScale, path.points[0].y * internalScale)
            for (i in 1 until path.points.size) {
                p.lineTo(path.points[i].x * internalScale, path.points[i].y * internalScale)
            }
            eraserPaint.strokeWidth = path.strokeWidth * internalScale
            canvas.drawPath(p, eraserPaint)
        }

        // Disegna annotazioni testuali
        for (ann in textAnnotations) {
            canvas.save()
            textPaint.color = ann.color
            textPaint.textSize = ann.fontSize * internalScale
            
            val tx = ann.x * internalScale
            val ty = ann.y * internalScale
            
            // Applica rotazione specifica dell'annotazione
            canvas.rotate(ann.rotation, tx, ty)
            
            val lines = ann.text.split("\n")
            var currentY = ty
            var maxWidth = 0f
            
            for (line in lines) {
                canvas.drawText(line, tx, currentY, textPaint)
                val lineWidth = textPaint.measureText(line)
                if (lineWidth > maxWidth) maxWidth = lineWidth
                currentY += ann.fontSize * internalScale * 1.2f // Interlinea
            }
            
            // Evidenzia se selezionato o trascinato con un tratteggio leggerissimo
            if (ann == selectedAnnotation || ann == draggedText) {
                val padding = 2f * internalScale
                canvas.drawRect(
                    tx - padding,
                    ty - (ann.fontSize * internalScale),
                    tx + maxWidth + padding,
                    currentY - (ann.fontSize * internalScale * 0.2f) + padding,
                    selectedPaint
                )
            }
            canvas.restore()
        }

        // Disegna firme
        for (sign in signatureAnnotations) {
            canvas.save()
            val tx = sign.x * internalScale
            val ty = sign.y * internalScale
            val tw = sign.width * internalScale
            val th = sign.height * internalScale

            // Applica rotazione specifica della firma
            canvas.rotate(sign.rotation, tx + tw / 2f, ty + th / 2f)

            val rect = RectF(tx, ty, tx + tw, ty + th)
            canvas.drawBitmap(sign.bitmap, null, rect, null)

            if (sign == selectedAnnotation || sign == draggedSignature) {
                canvas.drawRect(rect, selectedPaint)
            }
            canvas.restore()
        }

        // Disegna rettangolo di ritaglio
        cropRect?.let { r ->
            val screenRect = RectF(
                r.left * internalScale,
                r.top * internalScale,
                r.right * internalScale,
                r.bottom * internalScale
            )
            
            // Disegna un overlay scuro solo dentro i bordi del PDF
            val pdfW = pdfWidthPoints * internalScale
            val pdfH = pdfHeightPoints * internalScale
            
            canvas.drawRect(0f, 0f, pdfW, screenRect.top, overlayPaint)
            canvas.drawRect(0f, screenRect.bottom, pdfW, pdfH, overlayPaint)
            canvas.drawRect(0f, screenRect.top, screenRect.left, screenRect.bottom, overlayPaint)
            canvas.drawRect(screenRect.right, screenRect.top, pdfW, screenRect.bottom, overlayPaint)

            canvas.drawRect(screenRect, cropPaint)
            
            val h = 25f // Maniglie più grandi
            handlePaint.color = android.graphics.Color.RED
            // Angoli
            canvas.drawCircle(screenRect.left, screenRect.top, h, handlePaint)
            canvas.drawCircle(screenRect.right, screenRect.top, h, handlePaint)
            canvas.drawCircle(screenRect.right, screenRect.bottom, h, handlePaint)
            canvas.drawCircle(screenRect.left, screenRect.bottom, h, handlePaint)
            
            // Maniglie sui bordi (linee spesse)
            val linePaint = Paint(handlePaint).apply { strokeWidth = 10f; style = Paint.Style.STROKE }
            val midX = screenRect.centerX()
            val midY = screenRect.centerY()
            canvas.drawLine(midX - 30, screenRect.top, midX + 30, screenRect.top, linePaint)
            canvas.drawLine(midX - 30, screenRect.bottom, midX + 30, screenRect.bottom, linePaint)
            canvas.drawLine(screenRect.left, midY - 30, screenRect.left, midY + 30, linePaint)
            canvas.drawLine(screenRect.right, midY - 30, screenRect.right, midY + 30, linePaint)
        }

        canvas.restore()
    }

    fun getMappedCoords(x: Float, y: Float): Pair<Float, Float> {
        val matrix = Matrix()
        // Deve corrispondere ESATTAMENTE all'ordine in onDraw (T * S * R)
        matrix.setTranslate(posX, posY)
        matrix.preScale(scaleFactor, scaleFactor, width / 2f, height / 2f)
        matrix.preRotate(rotationDegrees, width / 2f, height / 2f)
        
        val inverse = Matrix()
        matrix.invert(inverse)
        
        val pts = floatArrayOf(x, y)
        inverse.mapPoints(pts)

        // Converti da pixel bitmap a punti PDF
        return Pair(pts[0] / internalScale, pts[1] / internalScale)
    }
}
