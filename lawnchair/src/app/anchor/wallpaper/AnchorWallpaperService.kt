/*
 * Copyright (C) 2025 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.anchor.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import app.anchor.AnchorPreferences
import app.anchor.rotation.WallpaperCropMath
import app.anchor.rotation.WallpaperStabilizationManager

/**
 * Rotation-stable live wallpaper for Anchor's background.
 *
 * ## Why this exists
 *
 * Anchor's original stabilized background is drawn as the launcher **window-background** drawable.
 * That works for pure rotation, but on the "rotate inside another app → return home" path the system
 * Shell rotates a *screenshot of the whole home task* (which includes our window-background) — so the
 * background visibly snaps. Device logs proved the system leaves the real **wallpaper surface** static
 * (`flags=0x0`) during that same transition. Rendering our background from a WallpaperService puts it
 * on that static wallpaper surface, so it should not snap.
 *
 * ## Rotation model
 *
 * Unlike the window-background drawable, a wallpaper engine is handed a surface that is **already in
 * the current orientation and upright** (portrait 1200×1920 at ROTATION_0; landscape 1920×1200 at
 * ROTATION_90), and it gets an `onSurfaceChanged` on every rotation with the new dims + rotation
 * (verified on device). So the engine reproduces the SAME on-glass result the drawable produced: it
 * computes the crop from the **portrait-canonical** viewport (rotation-invariant) via
 * [WallpaperCropMath.computeSrcRect], then applies the SAME counter-rotation canvas transform the
 * drawable uses — because that transform maps the portrait-canonical destination onto the physical
 * panel, which for the engine IS the surface. Result: a pure rotation selects the same source pixels
 * and lands them at the same glass position → pixel-perfect by construction.
 *
 * This milestone renders at the HOME REST camera (page-0 left edge, bottom row). Parallax / row
 * transitions (driven cross-process via WallpaperManager.setWallpaperOffsets) come next.
 */
class AnchorWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = AnchorEngine()

    private inner class AnchorEngine : WallpaperService.Engine() {

        private var bitmap: Bitmap? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val srcRect = Rect()
        private val dstRect = Rect()

        // Path + mtime the current bitmap was decoded from, so we can detect a re-picked image and
        // reload without recreating the engine (the picker overwrites custom_wallpaper.jpg in place).
        private var loadedPath: String? = null
        private var loadedMtime = 0L

        // Glass-space parallax offsets in [0,1], pushed by the launcher via
        // WallpaperManager.setWallpaperOffsets and delivered to onOffsetsChanged:
        //   hGlass = horizontal-on-glass position (page scroll within the active row): 0 = left.
        //   vGlass = vertical-on-glass position (active row): 0 = top row, 1 = bottom/home row.
        // These are ORIENTATION-INDEPENDENT intents; the engine maps them to the correct bitmap axis
        // for its current rotation in drawFrame (the axis-swap lives here because the engine reliably
        // knows its own rotation). Rest at home: left edge, bottom row.
        private var hGlass = 0f
        private var vGlass = 1f

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            reloadBitmapIfChanged()
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            hGlass = xOffset.coerceIn(0f, 1f)
            vGlass = yOffset.coerceIn(0f, 1f)
            val f = surfaceHolder.surfaceFrame
            drawFrame(surfaceHolder, f.width(), f.height())
        }

        /**
         * (Re)decode the picked image if the file changed since the last load. Decodes SYNCHRONOUSLY:
         * this is only ever called from lifecycle events (onCreate / onSurfaceCreated /
         * onVisibilityChanged), never the per-frame parallax path, and only actually decodes when the
         * file changed (a rare wallpaper switch). Decoding synchronously means the NEXT drawn frame is
         * already the new image — decoding on a background thread and swapping afterwards showed the
         * OLD image for a frame first (the "flash the yellow before the blue"). The new bitmap replaces
         * the old only once fully decoded, so there is never a blank/black frame either.
         */
        private fun reloadBitmapIfChanged() {
            val path = AnchorPreferences(this@AnchorWallpaperService).customWallpaperPath
            val mtime = path?.let { runCatching { java.io.File(it).lastModified() }.getOrDefault(0L) } ?: 0L
            if (bitmap != null && path == loadedPath && mtime == loadedMtime) return
            val ctx = displayContext ?: this@AnchorWallpaperService
            val decoded = path?.let {
                runCatching { WallpaperStabilizationManager.decodeDownscaled(ctx, it) }
                    .onFailure { e -> Log.w(TAG, "wallpaper decode failed: ${e.message}") }
                    .getOrNull()
            }
            // Only replace the shown bitmap once the new one is ready (keep the old one on a decode
            // failure rather than blanking to black).
            if (decoded != null) {
                bitmap = decoded
                loadedPath = path
                loadedMtime = mtime
            } else {
                Log.w(TAG, "No custom wallpaper bitmap to render (path=$path)")
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            // Each surface (a separate Engine is created for the home AND the lock screen when the
            // user sets the wallpaper on "Both") must be drawn on creation — otherwise a surface whose
            // onSurfaceChanged/onVisibilityChanged ordering differs can stay black.
            reloadBitmapIfChanged()
            val f = holder.surfaceFrame
            drawFrame(holder, f.width(), f.height())
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            // The system keeps OLD engine instances alive and cycles visibility between them on a
            // switch; each holds the image from when it was created. Reload the CURRENT file before
            // drawing so an old instance never paints its stale bitmap (the blue→yellow→blue flash).
            reloadBitmapIfChanged()
            val f = holder.surfaceFrame
            drawFrame(holder, f.width(), f.height())
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            reloadBitmapIfChanged()
            drawFrame(holder, width, height)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                // Picking a new image overwrites the file in place without recreating the engine;
                // reload it when we next become visible so the change shows.
                reloadBitmapIfChanged()
                val f = surfaceHolder.surfaceFrame
                drawFrame(surfaceHolder, f.width(), f.height())
                // If the surface wasn't ready yet (empty frame), the draw above no-op'd and we'd show a
                // stale/black buffer. Retry on the next frame(s) once the surface has real dimensions.
                if (f.width() <= 0 || f.height() <= 0) {
                    surfaceHolder.let { h ->
                        (displayContext ?: this@AnchorWallpaperService).mainExecutor.execute {
                            val ff = h.surfaceFrame
                            drawFrame(h, ff.width(), ff.height())
                        }
                    }
                }
            }
        }

        private fun currentRotation(): Int =
            (displayContext ?: this@AnchorWallpaperService).display?.rotation ?: Surface.ROTATION_0

        private fun drawFrame(holder: SurfaceHolder, width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            // Hardware canvas composites the (scaled) bitmap blit on the GPU. The software lockCanvas()
            // path did a CPU bilinear resample of a ~2000px bitmap every frame (~225ms/frame → parallax
            // lag). lockHardwareCanvas() drops that to sub-millisecond.
            // Ensure the bitmap is loaded on EVERY draw path. A fresh Engine instance (the system
            // creates a new one after the set-wallpaper preview closes) may receive onOffsetsChanged /
            // onVisibilityChanged before onCreate/onSurfaceCreated ran on it — if we drew then with a
            // null bitmap we'd paint black over the good frame ("flash then black").
            if (bitmap == null) reloadBitmapIfChanged()
            val bmp = bitmap
            if (bmp == null) {
                // Genuinely no image to show — leave the previous frame untouched rather than flashing
                // black. (Only happens if the picked file is missing/undecodable.)
                Log.w(TAG, "drawFrame: no bitmap, skipping (${width}x$height)")
                return
            }
            val canvas = holder.lockHardwareCanvas()
            if (canvas == null) { Log.w(TAG, "drawFrame: lockHardwareCanvas null (${width}x$height)"); return }
            try {
                val rotation = currentRotation()

                // Portrait-canonical viewport (short = width, long = height). The surface dims are the
                // full screen in the current orientation, so min/max recover the rotation-invariant
                // portrait dims — exactly what WallpaperStabilizationDrawable used (rawShort/rawLong).
                val rawW = minOf(width, height)
                val rawH = maxOf(width, height)

                // Map the glass-space parallax intents to bitmap-space (worldX/worldY) for the current
                // rotation. worldX drives the bitmap-X crop (srcLeft), worldY the bitmap-Y crop (srcTop).
                // In landscape the counter-rotation makes bitmap-Y appear horizontal on glass, so a
                // horizontal-on-glass intent (hGlass) must move worldY there — hence the axis-swap.
                val hAxis = WallpaperCropMath.horizontalGlassAxis(rotation)
                val vAxis = WallpaperCropMath.verticalGlassAxis(rotation)
                var worldX = 0f
                var worldY = 0f
                // A negative sign means the glass direction is inverted relative to the bitmap axis, so
                // mirror the [0,1] intent to 1−intent for that axis.
                fun assign(axis: WallpaperCropMath.AxisMapping, intent: Float) {
                    val v = if (axis.sign < 0) 1f - intent else intent
                    when (axis.axis) {
                        WallpaperCropMath.WorldAxis.X -> worldX = v
                        WallpaperCropMath.WorldAxis.Y -> worldY = v
                    }
                }
                assign(hAxis, hGlass)
                assign(vAxis, vGlass)

                // Equal-feel parallax: pan BOTH bitmap axes over the SAME glass-pixel budget so a full
                // horizontal sweep and a full vertical sweep move the wallpaper the same distance. Using
                // each axis's raw scroll-room instead (as WallpaperCropMath.computeSrcRect does) makes
                // the imbalanced-aspect bitmap pan much further on one axis (e.g. a 2112² bitmap in a
                // 1200×1920 viewport → 912px horizontal room vs 192px vertical → vertical felt way
                // stronger). The shared budget is capped by the smaller axis room so neither axis runs
                // off the bitmap. The pannable window is centred in each axis's room.
                val srcW = rawW.coerceAtMost(bmp.width)
                val srcH = rawH.coerceAtMost(bmp.height)
                val roomX = (bmp.width - srcW).coerceAtLeast(0)
                val roomY = (bmp.height - srcH).coerceAtLeast(0)
                val travel = minOf(roomX, roomY)
                val baseX = (roomX - travel) / 2
                val baseY = (roomY - travel) / 2
                val srcLeft = (baseX + (worldX * travel)).toInt().coerceIn(0, roomX)
                val srcTop = (baseY + (worldY * travel)).toInt().coerceIn(0, roomY)
                srcRect.set(srcLeft, srcTop, srcLeft + srcW, srcTop + srcH)
                dstRect.set(0, 0, rawW, rawH)

                // Apply the SAME counter-rotation transform WallpaperStabilizationDrawable.draw() uses.
                // It maps the portrait-canonical dst rect onto the physical panel; here the panel is the
                // surface, so the upright result matches the old window-background render exactly.
                canvas.save()
                when (rotation) {
                    Surface.ROTATION_90 -> { canvas.translate(0f, rawW.toFloat()); canvas.rotate(-90f) }
                    Surface.ROTATION_270 -> { canvas.translate(rawH.toFloat(), 0f); canvas.rotate(90f) }
                    Surface.ROTATION_180 -> { canvas.translate(rawW.toFloat(), rawH.toFloat()); canvas.rotate(180f) }
                }
                canvas.drawBitmap(bmp, srcRect, dstRect, paint)
                canvas.restore()
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    private companion object {
        const val TAG = "AnchorWallpaperSvc"
    }
}
