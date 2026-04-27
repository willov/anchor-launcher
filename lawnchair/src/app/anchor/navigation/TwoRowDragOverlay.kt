package app.anchor.navigation

import android.content.Context
import android.widget.FrameLayout

/**
 * Invisible full-screen overlay added to DragLayer for the duration of an icon drag.
 *
 * Detects when the drag icon enters the top or bottom band and triggers a row switch after
 * the icon dwells there for [DWELL_MS]. No visual elements — purely functional.
 */
class TwoRowDragOverlay(
    context: Context,
    private val activeRowIndex: Int,
    private val rowCount: Int,
    private val onRowSwitch: (targetRowIndex: Int) -> Unit,
) : FrameLayout(context) {

    private val bandHeightPx = (BAND_HEIGHT_DP * resources.displayMetrics.density).toInt()

    private val hasRowAbove = activeRowIndex < rowCount - 1
    private val hasRowBelow = activeRowIndex > 0

    private var switched = false
    private var bandEnterTime = 0L
    private var pendingBand = 0  // 0 = none, 1 = upper, -1 = lower

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(true)
    }

    /** Called on every drag-move with the drag icon's Y in DragLayer coordinates. */
    fun onDragMoved(dragY: Float) {
        if (switched) return
        val inUpperBand = hasRowAbove && dragY < bandHeightPx
        val inLowerBand = hasRowBelow && height > 0 && dragY > height - bandHeightPx
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
        private const val BAND_HEIGHT_DP = 80f
        private const val DWELL_MS = 400L
    }
}
