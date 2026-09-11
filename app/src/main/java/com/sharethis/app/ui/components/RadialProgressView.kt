package com.sharethis.app.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.sharethis.app.R

/**
 * Circular transfer progress gauge: track + sweep arc + centered percent
 * label with a speed sublabel. Zero dependencies, 60fps-safe (no
 * allocations in onDraw).
 */
class RadialProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress = 0f
    private var label = "0%"
    private var sublabel = ""

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val sublabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val arcBounds = RectF()

    init {
        val density = resources.displayMetrics.density
        val a = context.obtainStyledAttributes(attrs, R.styleable.RadialProgressView)
        try {
            trackPaint.color = a.getColor(
                R.styleable.RadialProgressView_trackColor, 0x33FFFFFF
            )
            sweepPaint.color = a.getColor(
                R.styleable.RadialProgressView_progressColor, 0xFF4CAF50.toInt()
            )
            val stroke = a.getDimension(
                R.styleable.RadialProgressView_progressStroke, 14f * density
            )
            trackPaint.strokeWidth = stroke
            sweepPaint.strokeWidth = stroke
            labelPaint.color = a.getColor(
                R.styleable.RadialProgressView_labelColor, 0xFFFFFFFF.toInt()
            )
            labelPaint.textSize = a.getDimension(
                R.styleable.RadialProgressView_labelSize, 34f * density
            )
            sublabelPaint.color = a.getColor(
                R.styleable.RadialProgressView_labelColor, 0xFFFFFFFF.toInt()
            )
            sublabelPaint.textSize = 14f * density
            sublabelPaint.alpha = 200
        } finally {
            a.recycle()
        }
    }

    fun setProgress(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped != progress) {
            progress = clamped
            label = "${(clamped * 100).toInt()}%"
            invalidate()
        }
    }

    fun setSublabel(text: String) {
        if (text != sublabel) {
            sublabel = text
            invalidate()
        }
    }

    fun reset() {
        progress = 0f
        label = "0%"
        sublabel = ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val halfStroke = trackPaint.strokeWidth / 2f + 2f
        val cx = width / 2f
        val cy = height / 2f
        val radius = size / 2f - halfStroke
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)
        if (progress > 0f) {
            canvas.drawArc(arcBounds, -90f, progress * 360f, false, sweepPaint)
        }
        val labelY = cy - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(label, cx, labelY, labelPaint)
        if (sublabel.isNotEmpty()) {
            canvas.drawText(sublabel, cx, labelY + labelPaint.textSize * 0.9f, sublabelPaint)
        }
    }
}
