/*
 * Copyright (C) 2026 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */
package app.anchor.rotation

/**
 * Converts workspace coordinates between the **display** frame (what the launcher renders and what
 * `ItemInfo.cellX/cellY` hold in memory) and the **portrait-canonical** frame (what the DB stores).
 *
 * ## The two frames
 *
 * Since the pre-computed transpose, the DB stores every workspace item exactly once, in portrait
 * coordinates, and never rewrites them on rotation. The loader maps portrait→display on read
 * ([LoaderCursor]); everything that writes must map display→portrait on the way back out, or it
 * would persist landscape coordinates into a portrait-canonical DB and move the item.
 *
 * In portrait the two frames are identical, so every conversion here is a no-op — which is why a
 * missing conversion is invisible until the device is rotated, and why the conversions live in one
 * place rather than being open-coded at each call site.
 *
 * ## Gating
 *
 * Both the active frame and the "is the DB canonical yet" question come from the same single
 * sources the read side uses ([AnchorTransposeHook.getActiveRotation] and
 * [PortraitCanonicalMigration.isLiveDbCanonical]), so the read and write sides cannot disagree.
 * On a DB that has not been converted yet the conversions are skipped entirely, matching the read
 * side's identical fallback.
 */
object CoordFrame {

    /** True when coordinates need converting at all — i.e. the DB is canonical and we are rotated. */
    private fun conversionActive(): Boolean =
        PortraitCanonicalMigration.isLiveDbCanonical() &&
            AnchorTransposeHook.getActiveRotation() != android.view.Surface.ROTATION_0

    /**
     * Converts a display-frame item position to the portrait-canonical position to store.
     *
     * Span-aware: for a multi-cell widget the display top-left is a *different* corner of the
     * bounding box than the portrait top-left, so the spans must be supplied. [displaySpanX] and
     * [displaySpanY] are the spans as the item currently has them (display frame).
     *
     * @return (portraitCellX, portraitCellY), or the input unchanged when no conversion applies.
     */
    @JvmStatic
    fun toPortraitCell(
        displayCellX: Int,
        displayCellY: Int,
        displaySpanX: Int,
        displaySpanY: Int,
    ): Pair<Int, Int> {
        if (!conversionActive()) return Pair(displayCellX, displayCellY)
        val grid = AnchorTransposeHook.getPortraitGrid() ?: return Pair(displayCellX, displayCellY)
        return GridTransposeHelper.reverseWidgetPositionToPortrait(
            displayCellX, displayCellY, displaySpanX, displaySpanY,
            grid.first, grid.second, AnchorTransposeHook.getActiveRotation(),
        )
    }

    /**
     * Converts display-frame spans to the portrait-canonical spans to store. Spans swap on a quarter
     * turn and are unchanged at 0°/180°.
     */
    @JvmStatic
    fun toPortraitSpan(displaySpanX: Int, displaySpanY: Int): Pair<Int, Int> {
        if (!conversionActive()) return Pair(displaySpanX, displaySpanY)
        return GridTransposeHelper.reverseSpanToPortrait(
            displaySpanX, displaySpanY, AnchorTransposeHook.getActiveRotation(),
        )
    }
}
