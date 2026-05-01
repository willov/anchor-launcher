package app.anchor.rotation

import android.view.Surface

/**
 * Remaps grid coordinates so icons stay at the same physical screen position after rotation.
 *
 * Portrait is the canonical coordinate space. All items are stored in portrait coords.
 * Call [remapCoordinates] to get display coords for a given rotation, and
 * [reverseMapToPortrait] to convert a display cell back to portrait coords for storage.
 *
 * Ported from the scratch launcher's GridRotationHelper.kt.
 */
object GridTransposeHelper {

    /**
     * Maps portrait (col, row) to display (col, row) for the given Surface rotation.
     * Returns a (displayCol, displayRow) pair.
     */
    fun remapCoordinates(
        portraitCol: Int,
        portraitRow: Int,
        portraitCols: Int,
        portraitRows: Int,
        rotation: Int,
    ): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_0   -> Pair(portraitCol, portraitRow)
        Surface.ROTATION_90  -> Pair(portraitRow, (portraitCols - 1) - portraitCol)
        Surface.ROTATION_180 -> Pair((portraitCols - 1) - portraitCol, (portraitRows - 1) - portraitRow)
        Surface.ROTATION_270 -> Pair((portraitRows - 1) - portraitRow, portraitCol)
        else                 -> Pair(portraitCol, portraitRow)
    }

    /**
     * Reverse-maps a display (col, row) back to portrait coordinates.
     * Used when placing an item at a position in the current rotated grid.
     */
    fun reverseMapToPortrait(
        displayCol: Int,
        displayRow: Int,
        portraitCols: Int,
        portraitRows: Int,
        rotation: Int,
    ): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_0   -> Pair(displayCol, displayRow)
        Surface.ROTATION_90  -> Pair((portraitCols - 1) - displayRow, displayCol)
        Surface.ROTATION_180 -> Pair((portraitCols - 1) - displayCol, (portraitRows - 1) - displayRow)
        Surface.ROTATION_270 -> Pair(displayRow, (portraitRows - 1) - displayCol)
        else                 -> Pair(displayCol, displayRow)
    }

    /**
     * Returns (cols, rows) grid dimensions for the given rotation.
     * Portrait and 180° keep the same dimensions; 90° and 270° swap them.
     */
    fun dimensionsForRotation(
        portraitCols: Int,
        portraitRows: Int,
        rotation: Int,
    ): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_0, Surface.ROTATION_180 -> Pair(portraitCols, portraitRows)
        else                                     -> Pair(portraitRows, portraitCols)
    }

    /**
     * Counter-rotation degrees to apply to icon views so they stay upright
     * while the window has rotated by [surfaceRotation].
     */
    fun counterRotationDeg(surfaceRotation: Int): Float = when (surfaceRotation) {
        Surface.ROTATION_0   ->   0f
        Surface.ROTATION_90  -> -90f
        Surface.ROTATION_180 -> 180f
        Surface.ROTATION_270 ->  90f
        else                 ->   0f
    }

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
     * [remapCoordinates] only maps a single cell (top-left corner) and is correct for 1×1 items.
     * For multi-cell widgets the top-left in portrait is NOT the top-left in the rotated grid —
     * a different corner of the bounding box becomes the new top-left. Without this correction
     * the transposed position overflows the grid bounds and [checkItemPlacement] removes the
     * widget.
     *
     * Derivation for ROTATION_90 (clockwise): cell (c,r) → (r, pCols-1-c).
     *   Portrait bounding box: cols [col, col+spanX-1], rows [row, row+spanY-1].
     *   Landscape X range: [row, row+spanY-1]  → newCellX = row
     *   Landscape Y range: [pCols-1-(col+spanX-1), pCols-1-col]  → newCellY = pCols-col-spanX
     *
     * Verifies to [remapCoordinates] when spanX=spanY=1.
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
     * Span-aware reverse mapping: recovers the portrait (col, row) for a widget given its
     * *display* cell coordinates and *display* span dimensions for [rotation].
     *
     * Uses display spans (as read from the DB) rather than portrait spans, because those are
     * what's stored when the device is in [rotation].
     *
     * Verifies to [reverseMapToPortrait] when displaySpanX=displaySpanY=1.
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
