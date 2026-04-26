package app.anchor.navigation

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen transparent overlay added to DragLayer for the duration of an icon drag.
 *
 * Shows a compact button strip at the top edge (if a row above exists) and/or bottom edge (if a
 * row below exists). Dragging the icon into an edge band triggers a row switch.
 *
 * Phases:
 *   Phase 1 (current) — compact button with label + page-count dots; fixed-duration transition.
 *   Phase 2           — spring physics on the button reveal / row-switch animation.
 *   Phase 3           — replace dot indicators with bitmap thumbnails captured via drawToBitmap().
 */
class TwoRowDragOverlay(
    context: Context,
    private val activeRowIndex: Int,
    private val rowCount: Int,
    /** Page count per row, indexed by row index. */
    private val pageCounts: List<Int>,
    /** Height to match the DropTargetBar so the button sits alongside Remove, not behind it. */
    private val buttonHeightPx: Int,
    /** Invoked with the target row index when the drag enters that row's band. */
    private val onRowSwitch: (targetRowIndex: Int) -> Unit,
) : FrameLayout(context) {

    private val density      = resources.displayMetrics.density
    private val buttonWidthPx = (BUTTON_WIDTH_DP * density).toInt()

    private val hasRowAbove = activeRowIndex < rowCount - 1
    private val hasRowBelow = activeRowIndex > 0

    private var switched = false
    private var bandEnterTime = 0L
    private var pendingBand = 0  // 0 = none, 1 = upper, -1 = lower

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(true)

        if (hasRowAbove) {
            val button = buildButton(isAbove = true, rowIndex = activeRowIndex + 1)
            addView(button, LayoutParams(buttonWidthPx, buttonHeightPx).apply {
                gravity = Gravity.TOP or Gravity.START
            })
        }
        if (hasRowBelow) {
            val button = buildButton(isAbove = false, rowIndex = activeRowIndex - 1)
            addView(button, LayoutParams(buttonWidthPx, buttonHeightPx).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            })
        }
    }

    private fun buildButton(isAbove: Boolean, rowIndex: Int): View {
        val dp        = density
        val pageCount = pageCounts.getOrElse(rowIndex) { 1 }
        val label     = if (isAbove) "Row Up ↑" else "Row Down ↓"
        val padH      = (10 * dp).toInt()
        val padV      = (8  * dp).toInt()

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            setBackgroundColor(BUTTON_COLOR)
            setPadding(padH, padV, padH, padV)

            addView(TextView(context).apply {
                text      = label
                textSize  = 12f
                setTextColor(Color.WHITE)
                gravity   = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            })

            // Page-count dots — Phase 3 will replace these with bitmap thumbnails
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    topMargin = (5 * dp).toInt()
                }
                val dotSize   = (5 * dp).toInt()
                val dotMargin = (3 * dp).toInt()
                repeat(pageCount.coerceIn(1, 8)) {
                    addView(View(context).apply {
                        setBackgroundColor(DOT_COLOR)
                        layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                            marginStart = dotMargin; marginEnd = dotMargin
                        }
                    })
                }
            })
        }
    }

    /**
     * Called on every drag-move with the drag icon's Y in DragLayer coordinates.
     * The detection zone is the full-width band at each edge. A row switch fires only after the
     * icon has dwelled in the band for [DWELL_MS] — passing through quickly does not trigger,
     * which prevents accidental switches when dragging sideways near the top of the screen.
     */
    fun onDragMoved(dragY: Float) {
        if (switched) return
        val inUpperBand = hasRowAbove && dragY < buttonHeightPx
        val inLowerBand = hasRowBelow && height > 0 && dragY > height - buttonHeightPx
        val now = System.currentTimeMillis()
        when {
            inUpperBand -> {
                if (pendingBand != 1) { pendingBand = 1; bandEnterTime = now }
                else if (now - bandEnterTime >= DWELL_MS) { switched = true; onRowSwitch(activeRowIndex + 1) }
            }
            inLowerBand -> {
                if (pendingBand != -1) { pendingBand = -1; bandEnterTime = now }
                else if (now - bandEnterTime >= DWELL_MS) { switched = true; onRowSwitch(activeRowIndex - 1) }
            }
            else -> pendingBand = 0
        }
    }

    companion object {
        private const val BUTTON_WIDTH_DP = 120f
        private const val DWELL_MS = 400L
        private val BUTTON_COLOR = Color.argb(0xCC, 0x10, 0x10, 0x20)
        private val DOT_COLOR    = Color.argb(0xBB, 0xFF, 0xFF, 0xFF)
    }
}
