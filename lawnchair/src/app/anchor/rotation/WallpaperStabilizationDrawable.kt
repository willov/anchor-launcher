/*
 * Copyright (C) 2025 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.anchor.rotation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.Surface

/**
 * Window-background Drawable that renders the wallpaper bitmap with a counter-rotation so that
 * wallpaper pixels remain at the same glass pixel positions after a pure device rotation.
 *
 * Set as the DecorView background via [android.view.Window.setBackgroundDrawable].
 *
 * ## World-camera model
 *
 * The wallpaper is a fixed 2D world. The screen is a camera viewport into that world. The camera
 * position is a single pair of normalised **bitmap-space** coordinates:
 *
 * | Property  | Range  | Meaning                                                       |
 * |-----------|--------|---------------------------------------------------------------|
 * | [worldX]  | 0..1   | crop position along the bitmap X axis (srcLeft)               |
 * | [worldY]  | 0..1   | crop position along the bitmap Y axis (srcTop)                |
 *
 * The source crop is computed identically in both orientations: `srcLeft = worldX·scrollRoomX`,
 * `srcTop = worldY·scrollRoomY`. **The camera is never changed by rotation** — only
 * [displayRotation] changes, which alters only the counter-rotation canvas transform below. A pure
 * rotation therefore selects the exact same source pixels and the canvas places them at the same
 * glass positions: pixel-perfect by construction, no resync.
 *
 * ## Why the manager swaps axes per orientation (not this class)
 *
 * [draw] applies a counter-rotation so the bitmap appears upright after the system rotates the
 * window. For [Surface.ROTATION_90] the transform maps drawn point `(lx, ly)` to glass pixel
 * `(ly, rawW - lx)`, so bitmap-X ends up vertical on the glass and bitmap-Y horizontal. Therefore a
 * horizontal-on-glass gesture must move **bitmap-Y** in landscape and **bitmap-X** in portrait.
 * That orientation-dependent input mapping lives in [WallpaperStabilizationManager]; this class only
 * ever reads [worldX]/[worldY] as plain bitmap-space crop offsets.
 *
 * Overscrolling at page edges does not move the camera because the manager clamps [worldX]/[worldY]
 * to [0, 1].
 */
class WallpaperStabilizationDrawable : Drawable() {

    var wallpaperBitmap: Bitmap? = null
        set(value) { field = value; invalidateSelf() }

    /** Horizontal camera position in world space (0 = left edge, 1 = right edge). */
    var worldX: Float = 0f
        set(value) { if (field == value) return; field = value; invalidateSelf() }

    /** Vertical camera position in world space (0 = top edge, 1 = bottom edge). */
    var worldY: Float = 0f
        set(value) { if (field == value) return; field = value; invalidateSelf() }

    var displayRotation: Int = Surface.ROTATION_0
        set(value) { if (field == value) return; field = value; invalidateSelf() }

    /**
     * Portrait-canonical viewport dims (short = width, long = height), from the REAL screen size —
     * rotation-invariant. The crop MUST use these, not the drawable's [bounds]: bounds are the
     * window size and can lag behind [displayRotation] when the rotation happened while the launcher
     * was backgrounded (rotating inside another app, then returning home). Deriving the crop from
     * stale bounds produced a different crop size/offset → the wallpaper "jumped position/zoom" on
     * return. Set once by the manager (screen size is fixed for the session). 0 = fall back to bounds.
     */
    var rawShort: Int = 0
    var rawLong: Int = 0

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()

    override fun draw(canvas: Canvas) {
        val bmp = wallpaperBitmap ?: return
        val b = bounds
        if (b.isEmpty) return

        // rawW/rawH are always the portrait-canonical viewport dimensions, so the source crop is
        // identical across rotation for a given (worldX, worldY). Prefer the rotation-invariant real
        // screen dims (rawShort/rawLong) set by the manager — these never lag behind displayRotation.
        // Fall back to deriving from bounds only if they are unset. Using bounds directly is unsafe
        // when the rotation happened off-launcher: bounds can still be the previous orientation's size
        // on the first draw after resume, producing a wrong crop (the position/zoom snap on return).
        val landscape = displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270
        val rawW: Int
        val rawH: Int
        if (rawShort > 0 && rawLong > 0) {
            rawW = rawShort
            rawH = rawLong
        } else if (landscape) {
            rawW = b.height()
            rawH = b.width()
        } else {
            rawW = b.width()
            rawH = b.height()
        }

        // The crop is the SAME in both orientations for a given (worldX, worldY): worldX drives
        // srcLeft (bitmap-X), worldY drives srcTop (bitmap-Y). The counter-rotation canvas below is
        // what repositions those identical pixels onto the rotated glass — so a pure rotation
        // (camera unchanged) produces the exact same source crop and is pixel-perfect. The crop math
        // lives in WallpaperCropMath (pure, unit-tested for the rotation invariant).
        //
        // worldX/worldY are bitmap-space coordinates, not screen-space. The manager is responsible
        // for moving the correct one in response to a gesture (in landscape a horizontal gesture
        // must move worldY, because bitmap-Y is what appears horizontal on the rotated glass).
        val src = WallpaperCropMath.computeSrcRect(worldX, worldY, bmp.width, bmp.height, rawW, rawH)
        srcRect.set(src.left, src.top, src.right, src.bottom)
        dstRect.set(0, 0, rawW, rawH)

        canvas.save()
        when (displayRotation) {
            Surface.ROTATION_90  -> { canvas.translate(0f, rawW.toFloat()); canvas.rotate(-90f) }
            Surface.ROTATION_270 -> { canvas.translate(rawH.toFloat(), 0f); canvas.rotate(90f) }
            Surface.ROTATION_180 -> { canvas.translate(rawW.toFloat(), rawH.toFloat()); canvas.rotate(180f) }
        }
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
