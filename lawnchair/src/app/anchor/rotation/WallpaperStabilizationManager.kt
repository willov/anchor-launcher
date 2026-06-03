/*
 * Copyright (C) 2025 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.anchor.rotation

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.animation.ValueAnimator
import app.anchor.AnchorPreferences
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.util.DisplayController
import java.io.File
import java.util.concurrent.Executors

/**
 * Keeps the wallpaper spatially stable across device rotations.
 *
 * ## World-camera model
 *
 * The wallpaper is a fixed 2D world. The screen is a camera viewport, positioned by a single pair
 * of normalised coordinates on the [WallpaperStabilizationDrawable]: [WallpaperStabilizationDrawable.worldX]
 * (horizontal) and [WallpaperStabilizationDrawable.worldY] (vertical). Both are always interpreted
 * relative to the physical screen axes regardless of orientation — the drawable's counter-rotation
 * handles the bitmap-axis swap.
 *
 * - **Horizontal gestures (page scroll)** move [worldX] by a continuous delta ([onScrollOffset]).
 * - **Vertical gestures (row transitions)** animate [worldY] to the target row's position
 *   ([onRowTransition]).
 * - **Rotation never touches the camera.** The rotation listener only updates
 *   [WallpaperStabilizationDrawable.displayRotation]. A pure rotation therefore shows the exact same
 *   world pixels at the same glass positions — pixel-perfect by construction, no resync, no locks,
 *   no drift.
 *
 * The [WallpaperStabilizationDrawable] is set as the window background (DecorView background) so it
 * draws before the workspace's transparent composite pass.
 *
 * Wallpaper bitmap is cached to the app's private files directory so that permission resets
 * (e.g. from `adb install -r`) don't cause a black background.
 *
 * Call [setup] from LawnchairLauncher.setupViews(), [destroy] from onDestroy().
 */
class WallpaperStabilizationManager(private val launcher: LawnchairLauncher) {

    private val wallpaperManager = WallpaperManager.getInstance(launcher)
    private val executor = Executors.newSingleThreadExecutor()

    private var drawable: WallpaperStabilizationDrawable? = null

    // Signature of the wallpaper config currently applied (source + custom path + test flag), so
    // reapplyIfChanged() can detect a change made in Settings and re-activate without a full restart.
    private var appliedSignature: String? = null
    private var listenersRegistered = false

    // Wallpaper bitmap cached in private app storage — no permission required to read it back.
    private val cacheFile = File(launcher.filesDir, "wallpaper_stab_cache.jpg")

    // Blocks scroll-driven camera updates while a row-switch animation is in progress so the
    // workspace's transient page reports during the slide don't move the horizontal camera.
    private var isRowTransitioning = false
    // Last row-relative scroll offset seen, used to compute the per-frame horizontal delta.
    private var lastScrollOffset = Float.NaN
    private var rowAnimator: ValueAnimator? = null

    private val rotationListener = DisplayController.DisplayInfoChangeListener { _, info, flags ->
        if (flags and DisplayController.CHANGE_ROTATION != 0) {
            launcher.runOnUiThread {
                val d = drawable ?: return@runOnUiThread
                // Rotation only changes how the camera maps to glass — never the camera position.
                d.displayRotation = info.rotation
                launcher.window?.setBackgroundDrawable(d)
            }
        }
    }

    private val wallpaperChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            cacheFile.delete()
            loadWallpaperAsync()
        }
    }

    fun setup() {
        val prefs = AnchorPreferences(launcher)
        appliedSignature = configSignature(prefs)
        // Only stabilize when a bitmap we can render is available (custom image, system-stabilized
        // power-user mode, or the debug test pattern). In the default System source we leave the
        // window's FLAG_SHOW_WALLPAPER intact so the real wallpaper shows and rotates normally —
        // never black, no permission, no custom drawable.
        if (!prefs.wallpaperStabilizationActive) {
            return
        }
        activate(prefs)
    }

    private fun activate(prefs: AnchorPreferences) {
        val initialRotation = currentRotation()
        val rowCount = prefs.rowCount
        val d = WallpaperStabilizationDrawable().apply {
            displayRotation = initialRotation
            worldX = 0f
            worldY = rowOffsetForRow(0, rowCount)
        }

        // Remove FLAG_SHOW_WALLPAPER so the system wallpaper surface is not composited behind our
        // window — our window-background drawable becomes the only wallpaper visible.
        launcher.window?.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        launcher.window?.setBackgroundDrawable(d)
        drawable = d

        if (!listenersRegistered) {
            DisplayController.INSTANCE.get(launcher).addChangeListener(rotationListener)
            launcher.registerReceiver(
                wallpaperChangedReceiver,
                IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
            )
            listenersRegistered = true
        }

        loadWallpaperAsync()
    }

    /**
     * Called from [LawnchairLauncher.onResume]. If the wallpaper source / custom image / test flag
     * changed in Settings since we last applied, re-evaluate and apply without requiring a full
     * launcher restart. Switching INTO a stabilized source activates the drawable; switching OUT
     * (back to System) restores FLAG_SHOW_WALLPAPER so the real wallpaper shows again.
     */
    fun reapplyIfChanged() {
        val prefs = AnchorPreferences(launcher)
        val sig = configSignature(prefs)
        if (sig == appliedSignature) return
        appliedSignature = sig
        if (prefs.wallpaperStabilizationActive) {
            activate(prefs)
        } else {
            // Switched back to System: drop our drawable and let the system composite the wallpaper.
            drawable = null
            launcher.window?.setBackgroundDrawable(null)
            launcher.window?.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        }
    }

    private fun configSignature(prefs: AnchorPreferences): String =
        "${prefs.wallpaperSource}|${prefs.customWallpaperPath}|${prefs.useTestWallpaper}"

    /**
     * Called by [app.anchor.navigation.TwoRowNavigationManager] before a row-switch animation
     * starts (after cancelling any prior animation). Blocks scroll-driven camera updates so the
     * workspace's transient page reports during the slide don't move the horizontal axis.
     */
    fun freezeOffset() {
        isRowTransitioning = true
    }

    /**
     * Called at the end of the row-switch enter animation. Re-enables scroll-driven camera updates.
     * The horizontal world position is preserved across the transition — switching rows is a
     * vertical navigation and must not move you horizontally. We reset the delta-tracking baseline
     * so the first scroll on the new row produces no jump.
     */
    fun unfreezeOffset() {
        isRowTransitioning = false
        lastScrollOffset = Float.NaN
    }

    /**
     * Called from Workspace scroll callbacks with the per-row relative offset (0..1 within the
     * current row's allowed page range). Moves the camera horizontally (on the glass) by the change
     * in that offset, scaled by [HORIZONTAL_PARALLAX] — a continuous, gesture-driven delta. Never
     * assigns an absolute page position, so there is no "page baseline" to snap back to.
     *
     * Axis: [worldX]/[worldY] are BITMAP-space axes. The drawable's counter-rotation maps bitmap-Y
     * to the horizontal glass axis in landscape, so a horizontal gesture moves [worldY] in landscape
     * and [worldX] in portrait. This is the only place the orientation axis-swap lives.
     *
     * Blocked during a row-switch animation.
     */
    fun onScrollOffset(offset: Float) {
        if (isRowTransitioning) return
        val d = drawable ?: return
        if (lastScrollOffset.isNaN()) {
            lastScrollOffset = offset
            return
        }
        val delta = offset - lastScrollOffset
        if (delta == 0f) return
        lastScrollOffset = offset
        val step = delta * HORIZONTAL_PARALLAX
        if (isLandscape()) {
            d.worldY = (d.worldY + step).coerceIn(0f, 1f)  // bitmap-Y is horizontal-on-glass
        } else {
            d.worldX = (d.worldX + step).coerceIn(0f, 1f)  // bitmap-X is horizontal-on-glass
        }
    }

    /**
     * Called by [app.anchor.navigation.TwoRowNavigationManager] when a row transition starts.
     * Animates the camera vertically (on the glass) to the target row's canonical world position
     * over the same duration as the workspace slide. [toRow] and [totalRows] determine the target.
     *
     * Axis: a vertical-on-glass move is bitmap-Y in portrait ([worldY]) and bitmap-X in landscape
     * ([worldX]) — the counterpart of the swap in [onScrollOffset].
     */
    fun onRowTransition(toRow: Int, totalRows: Int, durationMs: Long) {
        val d = drawable ?: return
        val target = rowOffsetForRow(toRow, totalRows)
        rowAnimator?.cancel()
        val landscape = isLandscape()
        val current = if (landscape) d.worldX else d.worldY
        if (kotlin.math.abs(current - target) < 0.005f) return
        rowAnimator = ValueAnimator.ofFloat(current, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                if (landscape) d.worldX = v else d.worldY = v
            }
            start()
        }
    }

    /**
     * Called from [LawnchairLauncher.finishBindingItems] after rotation/rebind. Clears transient
     * transition state and resets the scroll delta baseline. The camera position is left untouched
     * — it is already correct (rotation is pixel-perfect by construction).
     */
    fun resetForWorkspaceReady() {
        isRowTransitioning = false
        lastScrollOffset = Float.NaN
    }

    fun destroy() {
        rowAnimator?.cancel()
        launcher.window?.setBackgroundDrawable(null)
        launcher.window?.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        drawable = null
        try {
            DisplayController.INSTANCE.get(launcher).removeChangeListener(rotationListener)
            launcher.unregisterReceiver(wallpaperChangedReceiver)
        } catch (_: Exception) {}
    }

    private fun currentRotation(): Int =
        DisplayController.INSTANCE.get(launcher).info.rotation

    // Reads the rotation the drawable is currently rendering for (kept current by rotationListener),
    // so this is a plain field read — onScrollOffset calls it every scroll frame, so we must not do
    // a DisplayController/Dagger lookup here. Falls back to a live lookup only before the drawable
    // exists. Keyed on Surface.ROTATION_* (not aspect ratio) to match WallpaperStabilizationDrawable.
    private fun isLandscape(): Boolean {
        val rotation = drawable?.displayRotation ?: currentRotation()
        return rotation == android.view.Surface.ROTATION_90 ||
            rotation == android.view.Surface.ROTATION_270
    }

    private fun loadWallpaperAsync() {
        val prefs = AnchorPreferences(launcher)
        val useTest = prefs.useTestWallpaper
        val source = prefs.wallpaperSource
        val customPath = prefs.customWallpaperPath
        executor.execute {
            val bitmap = when {
                useTest -> generateTestWallpaper()
                source == AnchorPreferences.WALLPAPER_SOURCE_CUSTOM ->
                    customPath?.let { loadImageFile(it) }
                // Power-user / github-nightly path: read the real system wallpaper (needs
                // MANAGE_EXTERNAL_STORAGE). Falls back to the cached copy if the read is denied.
                source == AnchorPreferences.WALLPAPER_SOURCE_SYSTEM_STABILIZED ->
                    readWallpaperBitmap() ?: readCachedWallpaper()
                else -> null
            }
            if (bitmap == null) {
                Log.w(TAG, "No wallpaper bitmap available for source=$source (custom=$customPath)")
            } else {
                launcher.runOnUiThread { drawable?.wallpaperBitmap = bitmap }
            }
        }
    }

    private fun loadImageFile(path: String): Bitmap? {
        return try {
            BitmapFactory.decodeFile(path)?.also {
                if (it == null) Log.w(TAG, "Custom wallpaper decode failed: $path")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Custom wallpaper read failed: ${e.message}")
            null
        }
    }


    /**
     * Generates a test wallpaper bitmap with a 2D colour gradient and labelled grid lines.
     * Width = 2160 (2 portrait screens) → 1080px portrait scroll travel across a 2-page row.
     * Height = 3600 (1.5× a 2400px screen) → 1200px landscape parallax travel.
     */
    private fun generateTestWallpaper(): Bitmap {
        val w = 2160
        val h = 3600
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // 2D gradient background: hue (H) varies left→right, value (V) varies top→bottom
        val paint = Paint()
        for (x in 0 until w) {
            val hue = 180f + (x.toFloat() / w) * 120f  // cyan(180) → yellow(300)
            val colTop = Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.85f))
            val colBot = Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.25f))
            paint.shader = LinearGradient(x.toFloat(), 0f, x.toFloat(), h.toFloat(),
                colTop, colBot, Shader.TileMode.CLAMP)
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), paint)
        }
        paint.shader = null

        // Grid lines every 600px (Y) and 540px (X = half screen width)
        val gridPaint = Paint().apply {
            color = Color.WHITE; strokeWidth = 3f; alpha = 130; style = Paint.Style.STROKE
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 72f; typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(6f, 2f, 2f, Color.BLACK)
        }

        for (y in 0..h step 600) {
            canvas.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), gridPaint)
            if (y < h) canvas.drawText("y=$y", 40f, (y + 80).toFloat(), textPaint)
        }
        for (x in 0..w step 540) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), gridPaint)
        }

        // Yellow outline showing one physical screen height (2400px) — the "no-parallax" zone
        val markerPaint = Paint().apply {
            color = Color.YELLOW; strokeWidth = 8f; alpha = 200; style = Paint.Style.STROKE
        }
        canvas.drawRect(20f, 20f, (w - 20).toFloat(), 2380f, markerPaint)
        textPaint.apply { color = Color.YELLOW; textSize = 60f }
        canvas.drawText("▲ one screen height (2400px)", 40f, 2360f, textPaint)

        return bmp
    }

    /**
     * Reads the real system wallpaper bitmap from WallpaperManager. Only used by the
     * [AnchorPreferences.WALLPAPER_SOURCE_SYSTEM_STABILIZED] power-user source.
     *
     * **Permission:** `WallpaperManager.getDrawable()` requires `MANAGE_EXTERNAL_STORAGE` (or the
     * privileged-only `READ_WALLPAPER_INTERNAL`) on Android 13+ — and from Android 14 it may only
     * return the *default* wallpaper. `MANAGE_EXTERNAL_STORAGE` is declared in the github/nightly
     * flavour manifests but NOT in the Play build (it is Play-policy-hostile). So this path works
     * only for self-built github/nightly variants with "All files access" granted. For the Play
     * build, use [AnchorPreferences.WALLPAPER_SOURCE_CUSTOM] (the permission-free photo picker).
     *
     * On success, also writes the bitmap to [cacheFile] so future launches survive a permission
     * reset (e.g. from `adb install -r`).
     */
    private fun readWallpaperBitmap(): Bitmap? {
        return try {
            val d = wallpaperManager.drawable
            if (d == null) {
                Log.w(TAG, "WallpaperManager.getDrawable() returned null")
                return null
            }
            val bmp = when (d) {
                is BitmapDrawable -> d.bitmap
                else -> {
                    val w = d.intrinsicWidth.takeIf { it > 0 } ?: return null
                    val h = d.intrinsicHeight.takeIf { it > 0 } ?: return null
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                        val canvas = Canvas(b)
                        d.setBounds(0, 0, w, h)
                        d.draw(canvas)
                    }
                }
            }
            if (bmp != null) cacheWallpaper(bmp)
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "WallpaperManager read failed (${e.javaClass.simpleName}): ${e.message}")
            null
        }
    }

    private fun cacheWallpaper(bitmap: Bitmap) {
        try {
            cacheFile.outputStream().buffered().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write wallpaper cache: ${e.message}")
        }
    }

    private fun readCachedWallpaper(): Bitmap? {
        if (!cacheFile.exists()) return null
        return try {
            BitmapFactory.decodeFile(cacheFile.path).also {
                if (it == null) Log.w(TAG, "Cache file exists but decoding failed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read wallpaper cache: ${e.message}")
            null
        }
    }

    /**
     * Maps a row index to a vertical world position ([worldY]).
     * Row 0 (bottom) → near 1.0 (bottom of image); top row → near 0.0 (top of image).
     * ROW_MARGIN pads both ends so there is always room for future effects (e.g. tilt parallax).
     */
    private fun rowOffsetForRow(rowIndex: Int, totalRows: Int): Float {
        val t = if (totalRows > 1) 1.0f - rowIndex.toFloat() / (totalRows - 1) else 1.0f
        return ROW_MARGIN + t * (1.0f - 2 * ROW_MARGIN)
    }

    companion object {
        private const val TAG = "AnchorWallpaper"
        // Fraction of the wallpaper height reserved as buffer above/below the row range.
        private const val ROW_MARGIN = 0.1f
        // How far the horizontal camera travels (in world fraction) per full row-of-pages sweep.
        // 1.0 = a full left→right scroll across the row moves the camera across the whole remaining
        // wallpaper width. Tune <1.0 for gentler parallax.
        private const val HORIZONTAL_PARALLAX = 1.0f

        /**
         * Copies the user-picked image [uri] into app-private storage and points
         * [AnchorPreferences.customWallpaperPath] at it. Photo-picker URIs grant read access without
         * any permission. Returns true on success. Call on a background thread.
         *
         * When [alsoSetSystemWallpaper] is true, the same image is also set as the actual system
         * wallpaper (home + lock) via [WallpaperManager.setBitmap] — a normal `SET_WALLPAPER`
         * permission, auto-granted, Play-safe. This makes the chosen image appear everywhere (Anchor
         * home, lock screen, recents, all-apps blur), not just on the Anchor workspace, so the
         * stabilized background and the real wallpaper stay in sync. We can SET the wallpaper even
         * though we can't READ it back.
         */
        fun importCustomWallpaper(
            context: Context,
            uri: android.net.Uri,
            alsoSetSystemWallpaper: Boolean = true,
        ): Boolean {
            return try {
                val dest = File(context.filesDir, "custom_wallpaper.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: return false
                AnchorPreferences(context).customWallpaperPath = dest.absolutePath
                if (alsoSetSystemWallpaper) {
                    try {
                        val bmp = BitmapFactory.decodeFile(dest.absolutePath)
                        if (bmp != null) {
                            WallpaperManager.getInstance(context).setBitmap(bmp)
                        }
                    } catch (e: Exception) {
                        // Non-fatal: the Anchor background still works even if setting the system
                        // wallpaper fails (e.g. device policy restriction).
                        Log.w(TAG, "Failed to set system wallpaper: ${e.message}")
                    }
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to import custom wallpaper: ${e.message}")
                false
            }
        }
    }
}
