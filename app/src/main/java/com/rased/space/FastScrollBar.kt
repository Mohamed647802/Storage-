package com.rased.space

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

class FastScrollBar @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {

    var label: (Int) -> String = { "" }

    private var rv: RecyclerView? = null
    private val d = resources.displayMetrics.density
    private val barW = 48 * d
    private val thumbH = 120 * d
    private var dragging = false
    private var frac = 0f

    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99262D52.toInt() }
    private val pThumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7C5CFF.toInt() }
    private val pThumbOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB7A6FF.toInt() }
    private val pGrip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND
    }
    private val pBubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A3157.toInt() }
    private val pBubbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7C5CFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * d
    }
    private val pText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 15 * d; isFakeBoldText = true
    }

    fun attach(r: RecyclerView) {
        rv = r
        r.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!dragging) invalidate()
            }
        })
    }

    private fun maxScroll(): Int {
        val r = rv ?: return 0
        return r.computeVerticalScrollRange() - r.computeVerticalScrollExtent()
    }

    override fun onDraw(canvas: Canvas) {
        val r = rv ?: return
        val max = maxScroll()
        if (max <= 0) return
        if (!dragging) frac = (r.computeVerticalScrollOffset().toFloat() / max).coerceIn(0f, 1f)

        val right = width.toFloat()
        val left = right - barW
        canvas.drawRoundRect(RectF(left + 8 * d, 0f, right - 8 * d, height.toFloat()), 16 * d, 16 * d, pTrack)

        val top = frac * (height - thumbH)
        val thumb = RectF(left + 2 * d, top, right - 2 * d, top + thumbH)
        canvas.drawRoundRect(thumb, 14 * d, 14 * d, if (dragging) pThumbOn else pThumb)
        val cx = thumb.centerX()
        val cy = thumb.centerY()
        for (o in floatArrayOf(-12 * d, 0f, 12 * d)) {
            canvas.drawLine(cx - 11 * d, cy + o, cx + 11 * d, cy + o, pGrip)
        }

        if (dragging) {
            val n = r.adapter?.itemCount ?: 0
            if (n > 0) {
                val pos = (frac * (n - 1)).roundToInt()
                val avail = left - 24 * d - 20 * d
                val txt = TextUtils.ellipsize(label(pos), pText, avail, TextUtils.TruncateAt.END).toString()
                val w = pText.measureText(txt) + 28 * d
                val bRight = left - 10 * d
                val box = RectF(bRight - w, cy - 26 * d, bRight, cy + 26 * d)
                canvas.drawRoundRect(box, 16 * d, 16 * d, pBubble)
                canvas.drawRoundRect(box, 16 * d, 16 * d, pBubbleStroke)
                val fm = pText.fontMetrics
                canvas.drawText(txt, box.left + 14 * d, cy - (fm.ascent + fm.descent) / 2, pText)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // نقبل اللمس فقط على الشريط، وباقي المساحة تمر للقائمة
                if (maxScroll() <= 0 || e.x < width - barW) return false
                dragging = true
                parent.requestDisallowInterceptTouchEvent(true)
                move(e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) { move(e.y); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                parent.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return false
    }

    private fun move(y: Float) {
        val r = rv ?: return
        frac = ((y - thumbH / 2) / (height - thumbH)).coerceIn(0f, 1f)
        val n = r.adapter?.itemCount ?: 0
        if (n > 0) {
            (r.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset((frac * (n - 1)).roundToInt(), 0)
        }
        invalidate()
    }
}
