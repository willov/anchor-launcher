/*
 * Copyright (C) 2025 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.anchor.rotation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.View
import kotlin.math.max

/**
 * Full-screen view that renders the wallpaper bitmap with a counter-rotation applied so that
 * wallpaper pixels remain at the same glass pixel positions after device rotation.
 *
 * The view is placed at z=0 in DragLayer (behind all workspace content) and covers the system
 * wallpaper entirely. It draws from a portrait-canonical bitmap: the visible crop is determined
 * by [xOffset] (0=leftmost, 1=rightmost), and canvas rotate+translate counter-rotates the
 * content so the same physical pixels appear at the same glass positions.
 *
 * Canvas transform derivation (y-axis points down in Android screen coords):
 *   - canvas.rotate(-90°) maps drawn (x,y) → (y, −x)
 *   - canvas.translate(0, rawW) then shifts → (y, rawW − x)
 *   This satisfies the spatial-stability mapping from CLAUDE.md:
 *   portrait (px,py) → landscape window (py, rawW − px)  [ROTATION_90, 90° CW]
 *
 * IMPORTANT: canvas.rotate must be called BEFORE canvas.translate. With preconcat semantics
 * (each new op is left-multiplied), the physical position is:
 *   Physical = T × R × P   [T applied last, R first]
 * so rotate appears first in code and translate second.
 *
 * Per-frame rendering uses [Canvas.drawBitmap] — no Bitmap allocation on scroll or rotation.
 */
class WallpaperStabilizationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** The full portrait-canonical wallpaper bitmap (may be wider than screen for parallax). */
    var wallpaperBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Canonical parallax scroll position, orientation-independent. */
    var xOffset: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Current display rotation (Surface.ROTATION_0/90/180/270). */
    var displayRotation: Int = Surface.ROTATION_0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()

    override fun onDraw(canvas: Canvas) {
        val bmp = wallpaperBitmap ?: return

        // Portrait canonical dimensions: rawW × rawH.
        // When in landscape the view's short side is rawW and long side is rawH.
        val rawW: Int  // portrait screen width
        val rawH: Int  // portrait screen height
        when (displayRotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                rawW = height  // landscape view height = portrait width
                rawH = width   // landscape view width  = portrait height
            }
            else -> {
                rawW = width
                rawH = height
            }
        }

        Log.d(TAG, "onDraw: view=${width}×${height} pos=($left,$top,$right,$bottom) " +
                "rawW=$rawW rawH=$rawH rotation=$displayRotation clip=${canvas.clipBounds}")

        // Horizontal crop into the portrait bitmap based on current parallax offset.
        val scrollRoom = max(0, bmp.width - rawW)
        val srcLeft = (xOffset * scrollRoom).toInt().coerceIn(0, scrollRoom)
        srcRect.set(srcLeft, 0, srcLeft + rawW, bmp.height.coerceAtMost(rawH))
        // Destination rect in portrait coordinate space — filled by the canvas transform.
        dstRect.set(0, 0, rawW, rawH)

        // DEBUG: fill entire view blue so we can see the view's actual footprint
        canvas.drawColor(Color.BLUE)

        canvas.save()

        // Apply counter-rotation so drawing into dstRect (portrait space) maps portrait pixel
        // (px, py) to the correct glass position after device rotation.
        //
        // Spatial-stability mappings (from CLAUDE.md proof):
        //   ROTATION_90  (90° CW):  landscape(lx,ly) = portrait(rawW−ly, lx)
        //     → want drawn(x,y) → physical(y, rawW−x)
        //     → rotate(−90°) then translate(0, rawW)
        //   ROTATION_270 (90° CCW): landscape(lx,ly) = portrait(ly, rawH−lx)
        //     → want drawn(x,y) → physical(rawH−y, x)  [wait — need to rederive]
        //     → rotate(+90°) then translate(rawH, 0)
        //   ROTATION_180:
        //     → want drawn(x,y) → physical(rawW−x, rawH−y)
        //     → rotate(180°) then translate(rawW, rawH)
        //
        // Key: with Android's preconcat semantics, physical = T(R(P)).
        // So rotate() is called BEFORE translate() in code.
        when (displayRotation) {
            Surface.ROTATION_90 -> {
                canvas.rotate(-90f)                    // (x,y) → (y,−x)
                canvas.translate(0f, rawW.toFloat())   // (y,−x) → (y, rawW−x) ✓
            }
            Surface.ROTATION_270 -> {
                canvas.rotate(90f)                     // (x,y) → (−y, x)
                canvas.translate(rawH.toFloat(), 0f)   // (−y,x) → (rawH−y, x) ✓
            }
            Surface.ROTATION_180 -> {
                canvas.rotate(180f)                    // (x,y) → (−x,−y)
                canvas.translate(rawW.toFloat(), rawH.toFloat())  // → (rawW−x, rawH−y) ✓
            }
            // ROTATION_0: no transform
        }

        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
        canvas.restore()
    }

    private companion object {
        const val TAG = "AnchorWallpaper"
    }
}
