/*
 * Copyright (C) 2026 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.anchor.rotation

import android.view.Surface

/**
 * Pure, framework-free crop + rotation math for [WallpaperStabilizationDrawable], extracted so the
 * pixel-perfect-on-rotation invariant can be locked down by fast JVM unit tests (see
 * `tests/unit/.../WallpaperCropMathTest.kt`). The drawable delegates here so production runs exactly
 * this code.
 *
 * ## Model
 *
 * The wallpaper is a fixed 2D world; the screen is a camera viewport. The camera position
 * `(worldX, worldY)` is in normalised **bitmap-space** ([0,1] each) and is NEVER changed by rotation.
 *
 * The source crop (a rectangle of the bitmap) is computed from `(worldX, worldY)` and the
 * **portrait-canonical** viewport size `(rawW, rawH)` — identical in both orientations. The canvas
 * counter-rotation then places those identical pixels onto the rotated glass. So a pure rotation
 * selects the same source pixels and lands them at the same glass coordinates: pixel-perfect by
 * construction.
 */
object WallpaperCropMath {

    /** Integer rectangle, framework-free (so this file has no android.graphics dependency). */
    data class IRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** A point on the glass, in physical screen pixels. */
    data class GlassPoint(val x: Int, val y: Int)

    /** Which bitmap-space camera axis drives a glass-space move, and with what sign. */
    enum class WorldAxis { X, Y }
    data class AxisMapping(val axis: WorldAxis, val sign: Int)

    /**
     * Given the current [rotation], returns which camera axis ([worldX]/[worldY]) drives a
     * HORIZONTAL-on-glass move (e.g. page scroll) and its sign, such that "+sign" means the visible
     * content moves in the same glass direction as a positive [worldX]/[worldY] would in portrait.
     *
     * Derived from the counter-rotation in [drawnToGlass]:
     *  - ROT_0:   glass = (dx, dy)            → worldX drives glass-X (+)
     *  - ROT_90:  glass = (dy, rawW − dx)     → worldY drives glass-X (+)
     *  - ROT_270: glass = (rawH − dy, dx)     → worldY drives glass-X (−)
     *  - ROT_180: glass = (rawW − dx, rawH−dy)→ worldX drives glass-X (−)
     */
    fun horizontalGlassAxis(rotation: Int): AxisMapping = when (rotation) {
        Surface.ROTATION_90 -> AxisMapping(WorldAxis.Y, +1)
        Surface.ROTATION_270 -> AxisMapping(WorldAxis.Y, -1)
        Surface.ROTATION_180 -> AxisMapping(WorldAxis.X, -1)
        else -> AxisMapping(WorldAxis.X, +1)
    }

    /**
     * The VERTICAL-on-glass counterpart of [horizontalGlassAxis] — which camera axis drives a
     * vertical-on-glass move (row transitions) and its sign.
     *  - ROT_0:   worldY drives glass-Y (+)
     *  - ROT_90:  worldX drives glass-Y (−)   (glass-Y = rawW − dx)
     *  - ROT_270: worldX drives glass-Y (+)   (glass-Y = dx)
     *  - ROT_180: worldY drives glass-Y (−)
     */
    fun verticalGlassAxis(rotation: Int): AxisMapping = when (rotation) {
        Surface.ROTATION_90 -> AxisMapping(WorldAxis.X, -1)
        Surface.ROTATION_270 -> AxisMapping(WorldAxis.X, +1)
        Surface.ROTATION_180 -> AxisMapping(WorldAxis.Y, -1)
        else -> AxisMapping(WorldAxis.Y, +1)
    }

    /**
     * The portrait-canonical viewport dimensions for the given physical [boundsW]x[boundsH] and
     * [rotation]. In landscape the physical bounds are swapped back to portrait orientation so the
     * crop is rotation-invariant.
     */
    fun canonicalViewport(boundsW: Int, boundsH: Int, rotation: Int): Pair<Int, Int> =
        if (isLandscape(rotation)) boundsH to boundsW else boundsW to boundsH

    fun isLandscape(rotation: Int): Boolean =
        rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

    /**
     * The rectangle of the bitmap to sample, given the camera position and the portrait-canonical
     * viewport. Identical for a given `(worldX, worldY)` regardless of rotation.
     */
    fun computeSrcRect(
        worldX: Float,
        worldY: Float,
        bmpW: Int,
        bmpH: Int,
        rawW: Int,
        rawH: Int,
    ): IRect {
        val scrollRoomX = maxOf(0, bmpW - rawW)
        val scrollRoomY = maxOf(0, bmpH - rawH)
        val srcLeft = if (scrollRoomX > 0) (worldX * scrollRoomX).toInt().coerceIn(0, scrollRoomX) else 0
        val srcTop = if (scrollRoomY > 0) (worldY * scrollRoomY).toInt().coerceIn(0, scrollRoomY) else 0
        val srcW = rawW.coerceAtMost(bmpW)
        val srcH = rawH.coerceAtMost(bmpH)
        return IRect(srcLeft, srcTop, srcLeft + srcW, srcTop + srcH)
    }

    /**
     * Maps a point drawn at `(dx, dy)` in the portrait-canonical destination rect (`[0,rawW]×[0,rawH]`)
     * to its physical glass pixel after the counter-rotation canvas transform — the same transform
     * [WallpaperStabilizationDrawable.draw] applies. Used by the rotation-invariant unit test.
     */
    fun drawnToGlass(dx: Int, dy: Int, rawW: Int, rawH: Int, rotation: Int): GlassPoint =
        when (rotation) {
            // translate(0, rawW); rotate(-90): (dx,dy) -> (dy, rawW - dx)
            Surface.ROTATION_90 -> GlassPoint(dy, rawW - dx)
            // translate(rawH, 0); rotate(90): (dx,dy) -> (rawH - dy, dx)
            Surface.ROTATION_270 -> GlassPoint(rawH - dy, dx)
            // translate(rawW, rawH); rotate(180): (dx,dy) -> (rawW - dx, rawH - dy)
            Surface.ROTATION_180 -> GlassPoint(rawW - dx, rawH - dy)
            else -> GlassPoint(dx, dy)
        }

    /**
     * Equal-drift parallax. Each navigation STEP (one page swipe, or one row switch) should drift the
     * wallpaper by the same target [stepDriftPx] glass pixels — but each axis is CAPPED independently
     * at its own pan headroom (scroll-room = bitmap − viewport). So an image that is exactly screen
     * height (no vertical room) still gets full horizontal parallax: the axes never zero each other.
     *
     * The camera moves in normalised world units; this converts a glass-pixel target on one axis into
     * the world-units that axis must travel, capped at 1 (the whole scroll-room). Returns 0 when the
     * axis has no headroom.
     */
    fun worldTravelForGlassDrift(glassDriftPx: Float, scrollRoomPx: Int): Float {
        if (scrollRoomPx <= 0 || glassDriftPx <= 0f) return 0f
        return (glassDriftPx / scrollRoomPx).coerceIn(0f, 1f)
    }

    /**
     * World travel for a FULL row-of-pages sweep (offset 0→1) so each of the (pages−1) page steps
     * drifts [stepDriftPx] glass pixels — capped at the horizontal scroll-room. Independent of the
     * vertical axis.
     */
    fun horizontalSweepWorld(stepDriftPx: Float, scrollRoomH: Int, pagesInRow: Int): Float {
        val steps = (pagesInRow - 1).coerceAtLeast(0)
        return worldTravelForGlassDrift(stepDriftPx * steps, scrollRoomH)
    }

    /**
     * The vertical camera position ([worldY]/[worldX] depending on rotation, in world units) for row
     * [rowIndex], measured from the home rest at the BOTTOM of the image. Each row above sits
     * [stepDriftPx] glass pixels higher, converted to world units and clamped so it never pans past
     * the top of the image. Row 0 returns the bottom rest position (preserving the lock-screen match).
     *
     * @param restBottomWorld worldY of row 0 at rest (bottom of image, e.g. 1 − ROW_MARGIN)
     */
    fun rowWorldOffset(rowIndex: Int, stepDriftPx: Float, scrollRoomV: Int, restBottomWorld: Float): Float {
        if (scrollRoomV <= 0) return restBottomWorld
        val up = worldTravelForGlassDrift(stepDriftPx * rowIndex, scrollRoomV)
        return (restBottomWorld - up).coerceIn(0f, restBottomWorld)
    }

    /**
     * Where a fixed bitmap pixel `(bx, by)` ends up on the glass for the given camera + rotation, or
     * null if that pixel is not inside the current crop. Combines [computeSrcRect] and [drawnToGlass]
     * — this is the function whose output must be invariant across rotation for a fixed camera.
     */
    fun bitmapPixelToGlass(
        bx: Int,
        by: Int,
        worldX: Float,
        worldY: Float,
        bmpW: Int,
        bmpH: Int,
        rawW: Int,
        rawH: Int,
        rotation: Int,
    ): GlassPoint? {
        val src = computeSrcRect(worldX, worldY, bmpW, bmpH, rawW, rawH)
        val dx = bx - src.left
        val dy = by - src.top
        if (dx < 0 || dy < 0 || dx >= src.width || dy >= src.height) return null
        return drawnToGlass(dx, dy, rawW, rawH, rotation)
    }
}
