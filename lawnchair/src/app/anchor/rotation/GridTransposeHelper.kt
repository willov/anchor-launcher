package app.anchor.rotation

import android.view.Surface

/**
 * Remaps grid coordinates so icons stay at the same physical screen position after rotation.
 *
 * Portrait is the canonical coordinate space. All items are stored in portrait coords. Call
 * [displayCellX]/[displayCellY] (or [remapWidgetPosition] for both at once) to get display coords
 * for a given rotation, and [reverseWidgetPositionToPortrait] to convert a display cell back to
 * portrait coords for storage. Both are span-aware, so they are correct for 1×1 icons as well as
 * multi-cell widgets.
 *
 * Ported from the scratch launcher's GridRotationHelper.kt.
 */
object GridTransposeHelper {

    /**
     * Returns (displaySpanX, displaySpanY) for a widget with portrait-canonical span dimensions.
     * 90° and 270° swap spanX/spanY because the grid axes have transposed.
     */
    fun spanForRotation(portraitSpanX: Int, portraitSpanY: Int, rotation: Int): Pair<Int, Int> =
        when (rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> Pair(portraitSpanX, portraitSpanY)
            else                                     -> Pair(portraitSpanY, portraitSpanX)
        }

    /**
     * Reverse-maps display span dimensions back to portrait-canonical spans.
     * The swap is symmetric — applying this twice returns the original values.
     */
    fun reverseSpanToPortrait(
        displaySpanX: Int,
        displaySpanY: Int,
        rotation: Int,
    ): Pair<Int, Int> = spanForRotation(displaySpanX, displaySpanY, rotation)

    /**
     * Span-aware position mapping: maps the portrait top-left of a widget to the correct
     * top-left in the display orientation for [rotation].
     *
     * For multi-cell widgets the top-left in portrait is NOT the top-left in the rotated grid —
     * a different corner of the bounding box becomes the new top-left. Without this correction
     * the transposed position overflows the grid bounds and [checkItemPlacement] removes the
     * widget. Reduces to a plain single-cell rotation when spanX=spanY=1.
     *
     * Derivation for ROTATION_90 (clockwise): cell (c,r) → (r, pCols-1-c).
     *   Portrait bounding box: cols [col, col+spanX-1], rows [row, row+spanY-1].
     *   Landscape X range: [row, row+spanY-1]  → newCellX = row
     *   Landscape Y range: [pCols-1-(col+spanX-1), pCols-1-col]  → newCellY = pCols-col-spanX
     */
    fun remapWidgetPosition(
        portraitCol: Int, portraitRow: Int,
        portraitSpanX: Int, portraitSpanY: Int,
        portraitCols: Int, portraitRows: Int,
        rotation: Int,
    ): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_0   -> Pair(portraitCol, portraitRow)
        Surface.ROTATION_90  -> Pair(portraitRow, portraitCols - portraitCol - portraitSpanX)
        Surface.ROTATION_180 -> Pair(portraitCols - portraitCol - portraitSpanX,
                                     portraitRows - portraitRow - portraitSpanY)
        Surface.ROTATION_270 -> Pair(portraitRows - portraitRow - portraitSpanY, portraitCol)
        else                 -> Pair(portraitCol, portraitRow)
    }

    /**
     * Display cellX for an item stored portrait-canonically. Span-aware, so it is correct for
     * multi-cell widgets as well as 1×1 icons.
     *
     * Split into X and Y accessors because the loader assigns the two coordinates separately.
     */
    fun displayCellX(
        portraitCol: Int, portraitRow: Int,
        portraitSpanX: Int, portraitSpanY: Int,
        portraitCols: Int, portraitRows: Int,
        rotation: Int,
    ): Int = remapWidgetPosition(
        portraitCol, portraitRow, portraitSpanX, portraitSpanY, portraitCols, portraitRows, rotation,
    ).first

    /** Display cellY for an item stored portrait-canonically. See [displayCellX]. */
    fun displayCellY(
        portraitCol: Int, portraitRow: Int,
        portraitSpanX: Int, portraitSpanY: Int,
        portraitCols: Int, portraitRows: Int,
        rotation: Int,
    ): Int = remapWidgetPosition(
        portraitCol, portraitRow, portraitSpanX, portraitSpanY, portraitCols, portraitRows, rotation,
    ).second

    /**
     * Span-aware reverse mapping: recovers the portrait (col, row) for a widget given its
     * *display* cell coordinates and *display* span dimensions for [rotation].
     *
     * Uses display spans (as read from the DB) rather than portrait spans, because those are
     * what's stored when the device is in [rotation]. Reduces to a plain single-cell reverse
     * rotation when displaySpanX=displaySpanY=1.
     */
    fun reverseWidgetPositionToPortrait(
        displayCol: Int, displayRow: Int,
        displaySpanX: Int, displaySpanY: Int,
        portraitCols: Int, portraitRows: Int,
        rotation: Int,
    ): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_0   -> Pair(displayCol, displayRow)
        // Forward 90°: newCellX=pRow, newCellY=pCols-pCol-pSpanX (where pSpanX=displaySpanY)
        Surface.ROTATION_90  -> Pair(portraitCols - displayRow - displaySpanY, displayCol)
        // Forward 180°: newCellX=pCols-pCol-pSpanX, newCellY=pRows-pRow-pSpanY (spans unchanged)
        Surface.ROTATION_180 -> Pair(portraitCols - displayCol - displaySpanX,
                                     portraitRows - displayRow - displaySpanY)
        // Forward 270°: newCellX=pRows-pRow-pSpanY, newCellY=pCol (where pSpanY=displaySpanX)
        Surface.ROTATION_270 -> Pair(displayRow, portraitRows - displayCol - displaySpanX)
        else                 -> Pair(displayCol, displayRow)
    }
}
