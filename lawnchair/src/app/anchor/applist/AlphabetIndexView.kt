package app.anchor.applist

import android.content.Context
import android.content.res.Configuration
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

    companion object {
        /** Width of the strip in dp. */
        const val STRIP_WIDTH_DP = 28
        /** Gap in dp between the strip and the app icons. */
        const val STRIP_GAP_DP = 8

        /**
         * Extra dp added to the gap in landscape only. Landscape has horizontal room to spare and
         * the letters otherwise read as cramped against the screen edge. Deliberately *not* a bump
         * to [STRIP_GAP_DP], because portrait is the tighter axis — widening it there would eat
         * icon width.
         */
        const val STRIP_GAP_EXTRA_LANDSCAPE_DP = 12

        /**
         * Tablets: empty space between the pill and the drawer's edge. The pill keeps its phone
         * width and is drawn clear of this margin, so on a large drawer the letters no longer sit
         * hard against the edge. The margin still accepts touches. Half as much in portrait, where
         * the full landscape value made the strip column read as too wide.
         */
        const val STRIP_EDGE_PADDING_TABLET_DP = 16
        const val STRIP_EDGE_PADDING_TABLET_PORTRAIT_DP = 8

        /** Tablets: extra gap between the strip and the app icons, on top of [STRIP_GAP_DP]. */
        const val STRIP_GAP_EXTRA_TABLET_DP = 8
        const val STRIP_GAP_EXTRA_TABLET_PORTRAIT_DP = 4

        /**
         * Total horizontal space (dp) the strip reserves on the right edge of the drawer
         * RecyclerView so icons never scroll behind it. Single source of truth shared by
         * [SearchContainerView] and the core padding hook.
         */
        const val RESERVED_WIDTH_DP = STRIP_WIDTH_DP + STRIP_GAP_DP

        private fun isLandscape(context: Context): Boolean =
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        /** Same threshold Launcher3 uses for tablets. */
        private fun isTablet(context: Context): Boolean =
            context.resources.configuration.smallestScreenWidthDp >= 600

        /** The phone-landscape widening applies to phones only; tablets use the edge margin instead. */
        private fun phoneLandscapeExtraDp(context: Context): Int =
            if (isLandscape(context) && !isTablet(context)) STRIP_GAP_EXTRA_LANDSCAPE_DP else 0

        /**
         * Gap (dp) between the strip and the app icons for the current device and orientation.
         * Wider in phone landscape ([STRIP_GAP_EXTRA_LANDSCAPE_DP]) and on tablets
         * ([STRIP_GAP_EXTRA_TABLET_DP]).
         */
        @JvmStatic
        fun stripGapDp(context: Context): Int =
            STRIP_GAP_DP + phoneLandscapeExtraDp(context) + when {
                !isTablet(context) -> 0
                isLandscape(context) -> STRIP_GAP_EXTRA_TABLET_DP
                else -> STRIP_GAP_EXTRA_TABLET_PORTRAIT_DP
            }

        /** Margin (px) between the pill and the drawer edge — tablets only, see [STRIP_EDGE_PADDING_TABLET_DP]. */
        @JvmStatic
        fun edgePaddingPx(context: Context): Int {
            val dp = when {
                !isTablet(context) -> 0
                isLandscape(context) -> STRIP_EDGE_PADDING_TABLET_DP
                else -> STRIP_EDGE_PADDING_TABLET_PORTRAIT_DP
            }
            return (dp * context.resources.displayMetrics.density).toInt()
        }

        /**
         * Width (px) of the strip view itself for the current device and orientation: the pill
         * (padded out in phone landscape so the letters sit further from the screen edge) plus the
         * tablet edge margin.
         */
        @JvmStatic
        fun stripWidthPx(context: Context): Int {
            val density = context.resources.displayMetrics.density
            return ((STRIP_WIDTH_DP + phoneLandscapeExtraDp(context)) * density).toInt() +
                edgePaddingPx(context)
        }

        /**
         * Total horizontal space (px) reserved on the drawer's right edge, for the current
         * orientation. Must stay consistent with [stripWidthPx] — this is [stripWidthPx] plus the
         * icon gap, so the strip always has clear space on both sides of it.
         */
        @JvmStatic
        fun reservedWidthPx(context: Context): Int =
            stripWidthPx(context) +
                (stripGapDp(context) * context.resources.displayMetrics.density).toInt()
    }

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

    /** Vertical extent the letters are laid out in, inset by padding (clears the search bar at top). */
    private val contentTop get() = paddingTop.toFloat()
    private val contentHeight get() = (height - paddingTop - paddingBottom).toFloat()

    override fun onDraw(canvas: Canvas) {
        if (letters.isEmpty()) return
        val itemH = contentHeight / letters.size
        val sp  = resources.displayMetrics.scaledDensity
        textPaint.textSize = (itemH * 0.62f).coerceIn(8f * sp, 13f * sp)

        // Pill column: the view minus the tablet edge margin, which sits on the drawer-edge side
        // (the strip is aligned to the parent's end, so that is the left edge in RTL).
        val edge  = edgePaddingPx(context).toFloat()
        val left  = if (layoutDirection == LAYOUT_DIRECTION_RTL) edge else 0f
        val right = if (layoutDirection == LAYOUT_DIRECTION_RTL) width.toFloat() else width - edge

        val cx  = (left + right) / 2f
        val pad = 2f * resources.displayMetrics.density
        val r   = (right - left) / 2f - pad   // full pill shape

        // Single pill spanning first → last visible letter
        val visibleIndices = letters.indices.filter { letters[it] in visibleLetters }
        if (visibleIndices.isNotEmpty()) {
            val spanTop    = contentTop + itemH * visibleIndices.first()
            val spanBottom = contentTop + itemH * (visibleIndices.last() + 1)
            canvas.drawRoundRect(RectF(left + pad, spanTop + pad, right - pad, spanBottom - pad), r, r, pillPaint)
        }

        // Pressed-state highlight on top
        if (pressedIndex in letters.indices) {
            val top = contentTop + itemH * pressedIndex
            canvas.drawRoundRect(RectF(left + pad, top + pad, right - pad, top + itemH - pad), r, r, highlightPaint)
        }

        letters.forEachIndexed { i, letter ->
            val top      = contentTop + itemH * i
            val baseline = top + itemH / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(letter, cx, baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (letters.isEmpty()) return false
                val rel = (event.y - contentTop) / contentHeight.coerceAtLeast(1f)
                val index = (rel * letters.size).toInt().coerceIn(0, letters.size - 1)
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
