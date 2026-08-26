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

    override fun onCreate() {
        super.onCreate()
        // Pre-decode the wallpaper into the PROCESS-LEVEL cache as soon as the file changes, BEFORE any
        // engine needs to draw. This is what kills the switch flash: previously the (single) engine
        // decoded the new image synchronously inside onVisibilityChanged/drawFrame — a ~300ms main-thread
        // decode during which the surface still showed the STALE buffer (the n-1 flash). By watching the
        // file and decoding ahead of time, the next draw (whether from the same engine becoming visible
        // again, or a system-resurrected engine after CHANGE_LIVE_WALLPAPER) finds the new bitmap ALREADY
        // decoded in the cache → instant correct frame, no stale window.
        primeCacheDecode(AnchorPreferences(this).customWallpaperPath, "onCreate")
        startFileObserver()
    }

    override fun onDestroy() {
        fileObserver?.stopWatching()
        fileObserver = null
        super.onDestroy()
    }

    private var fileObserver: android.os.FileObserver? = null

    private fun startFileObserver() {
        val path = AnchorPreferences(this).customWallpaperPath ?: return
        val file = java.io.File(path)
        val dir = file.parentFile ?: return
        // Watch the directory (watching a file that gets replaced/renamed misses events). Filter to our
        // file's name. CLOSE_WRITE fires when the launcher finishes writing the new image.
        fileObserver = object : android.os.FileObserver(
            dir.absolutePath,
            CLOSE_WRITE or MOVED_TO or MODIFY,
        ) {
            override fun onEvent(event: Int, relPath: String?) {
                if (relPath == null || relPath != file.name) return
                // Decode off the main thread into the shared cache; ready before the engine draws.
                decodeExecutor.execute { primeCacheDecode(path, "fileObserver") }
            }
        }.also { it.startWatching() }
    }

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
         * Adopt the newest bitmap from the PROCESS-LEVEL cache. The cache is kept current by the
         * service's FileObserver (which pre-decodes off-thread the instant the launcher overwrites the
         * image), so this is normally a cheap pointer swap with no decode — that is what removes the
         * ~300ms decode stall that used to show the stale n-1 frame on the switch.
         *
         * Fallback: if the cache is empty at this exact moment (first-ever draw, or the FileObserver
         * hasn't fired yet), decode synchronously so we never draw black. That synchronous path only
         * happens on a genuine cold start, not on a switch.
         */
        private fun reloadBitmapIfChanged() {
            val path = AnchorPreferences(this@AnchorWallpaperService).customWallpaperPath
            val mtime = path?.let { runCatching { java.io.File(it).lastModified() }.getOrDefault(0L) } ?: 0L
            // Already showing the current file? nothing to do.
            if (bitmap != null && path == loadedPath && mtime == loadedMtime) return
            // Prefer the pre-decoded cache when it holds the current file's mtime.
            val cached = peekCachedBitmap()
            if (cached != null && cachedMtimeValue() == mtime) {
                bitmap = cached
                loadedPath = path
                loadedMtime = mtime
                return
            }
            // Cache miss (cold start / observer not yet fired): decode now so we never draw black, and
            // populate the cache so sibling engines skip the decode.
            primeCacheDecode(path, "engineFallback")
            val fresh = peekCachedBitmap()
            if (fresh != null) {
                bitmap = fresh
                loadedPath = path
                loadedMtime = cachedMtimeValue()
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
            // Reload the current file on EVERY draw path — not just when bitmap == null.
            // Root cause of the n-1 switch flash (device logs, 2026-08-26): on a switch the system does
            // NOT cleanly destroy old engine instances. It keeps an OLD instance (e.g. E2) alive and
            // later makes it visible again to render home. That instance still holds its previously
            // decoded bitmap (the n-1/n-2 image). The system fires a spontaneous drawFrame on it BEFORE
            // onVisibilityChanged(true) runs — and since that engine's `bitmap` is non-null, the old
            // `if (bitmap == null)` guard skipped the reload and painted the STALE bitmap for one frame
            // (the flash). reloadBitmapIfChanged() is a cheap mtime stat that only actually decodes when
            // the file changed, so calling it every draw is safe even on the per-frame parallax path.
            reloadBitmapIfChanged()
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

    /**
     * Decode [path] into the process-level cache if it isn't already cached at the file's current mtime.
     * Thread-safe; may run on the main thread (onCreate) or the decode executor (FileObserver). Only the
     * newest (path, mtime) wins, so a rapid double-switch settles on the latest image.
     */
    private fun primeCacheDecode(path: String?, reason: String) {
        if (path == null) return
        val mtime = runCatching { java.io.File(path).lastModified() }.getOrDefault(0L)
        synchronized(cacheLock) {
            if (cachedBitmap != null && path == cachedPath && mtime == cachedMtime) return
        }
        val decoded = runCatching { WallpaperStabilizationManager.decodeDownscaled(this, path) }
            .onFailure { e -> Log.w(TAG, "prime decode failed ($reason): ${e.message}") }
            .getOrNull() ?: return
        synchronized(cacheLock) {
            // Guard against a race where a newer decode already landed while we were decoding.
            val newer = cachedMtime > mtime && cachedPath == path
            if (!newer) {
                cachedBitmap = decoded
                cachedPath = path
                cachedMtime = mtime
                Log.i(TAG, "cache primed ($reason) mtime=$mtime")
            }
        }
    }

    private companion object {
        const val TAG = "AnchorWallpaperSvc"

        // Process-level decoded-bitmap cache, shared across all Engine instances in this process. The
        // system keeps multiple engine instances alive across a switch; caching here means whichever one
        // draws next gets the already-decoded new image with no per-instance re-decode stall.
        private val cacheLock = Any()
        @Volatile private var cachedBitmap: Bitmap? = null
        @Volatile private var cachedPath: String? = null
        @Volatile private var cachedMtime: Long = 0L

        private val decodeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        /** The current cached bitmap, or null if nothing decoded yet. */
        fun peekCachedBitmap(): Bitmap? = synchronized(cacheLock) { cachedBitmap }
        fun cachedMtimeValue(): Long = synchronized(cacheLock) { cachedMtime }
    }
}
