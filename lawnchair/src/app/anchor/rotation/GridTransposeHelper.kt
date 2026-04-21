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
}
