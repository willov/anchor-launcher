package app.anchor.applist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Vertical alphabet index strip.
 *
 * Set [letters] to the ordered list of section letters present in the grid.
 * [visibleLetters] controls which range is highlighted as a single spanning pill —
 * update it from a RecyclerView scroll listener to keep the pill in sync.
 * [onLetterSelected] fires on touch/drag so the grid can jump to that section.
 *
 * Ported from the scratch launcher's AlphabetIndexView.kt.
 */
class AlphabetIndexView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var letters: List<String> = emptyList()
        set(value) { field = value; invalidate() }

    /** Letters whose sections are currently visible — draws a single spanning pill over them. */
    var visibleLetters: Set<String> = emptySet()
        set(value) { if (field != value) { field = value; invalidate() } }

    var onLetterSelected: ((String) -> Unit)? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF")
    }

    private var pressedIndex = -1

    override fun onDraw(canvas: Canvas) {
        if (letters.isEmpty()) return
        val itemH = height.toFloat() / letters.size
        val sp  = resources.displayMetrics.scaledDensity
        textPaint.textSize = (itemH * 0.62f).coerceIn(8f * sp, 13f * sp)

        val cx  = width / 2f
        val pad = 2f * resources.displayMetrics.density
        val r   = (width / 2f) - pad   // full pill shape

        // Single pill spanning first → last visible letter
        val visibleIndices = letters.indices.filter { letters[it] in visibleLetters }
        if (visibleIndices.isNotEmpty()) {
            val spanTop    = itemH * visibleIndices.first()
            val spanBottom = itemH * (visibleIndices.last() + 1)
            canvas.drawRoundRect(RectF(pad, spanTop + pad, width - pad, spanBottom - pad), r, r, pillPaint)
        }

        // Pressed-state highlight on top
        if (pressedIndex in letters.indices) {
            val top = itemH * pressedIndex
            canvas.drawRoundRect(RectF(pad, top + pad, width - pad, top + itemH - pad), r, r, highlightPaint)
        }

        letters.forEachIndexed { i, letter ->
            val top      = itemH * i
            val baseline = top + itemH / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(letter, cx, baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (letters.isEmpty()) return false
                val index = (event.y / height * letters.size).toInt().coerceIn(0, letters.size - 1)
                if (index != pressedIndex) {
                    pressedIndex = index
                    invalidate()
                    onLetterSelected?.invoke(letters[index])
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
