/*
 * Copyright (C) 2025 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.anchor.rotation

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import android.os.Build
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.animation.ValueAnimator
import app.anchor.AnchorPreferences
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.util.DisplayController
import java.io.File

/**
 * Drives Anchor's rotation-stable wallpaper, which is rendered by the
 * [app.anchor.wallpaper.AnchorWallpaperService] live wallpaper. This manager owns the parallax
 * **camera** and pushes it to the engine cross-process.
 *
 * ## World-camera model
 *
 * The wallpaper is a fixed 2D world; the screen is a camera viewport at a single normalised position
 * ([liveWorldX], [liveWorldY]), both interpreted in BITMAP space (rotation-invariant):
 *
 * - **Horizontal gestures (page scroll)** accumulate a delta into the horizontal-on-glass axis
 *   ([onScrollOffset]) — the wallpaper is a world you pan through, so rights add up across rows until
 *   you reach the world edge.
 * - **Vertical gestures (row transitions)** animate the vertical-on-glass axis to the target row's
 *   canonical position ([onRowTransition]).
 * - **Rotation never touches the camera.** Because bitmap-space coordinates are rotation-invariant and
 *   the engine recomputes its crop from them against the portrait-canonical viewport, a pure rotation
 *   selects the identical source pixels → pixel-perfect by construction.
 *
 * The glass↔bitmap axis+sign map ([WallpaperCropMath.horizontalGlassAxis] /
 * [WallpaperCropMath.verticalGlassAxis]) is applied to gesture deltas/targets at input time, never
 * re-applied on rotation.
 *
 * Call [setup] from LawnchairLauncher.setupViews(), [destroy] from onDestroy().
 */
class WallpaperStabilizationManager(private val launcher: LawnchairLauncher) {

    private val wallpaperManager = WallpaperManager.getInstance(launcher)

    // Signature of the wallpaper config currently applied (source + custom path), so
    // reapplyIfChanged() can detect a change made in Settings and re-activate without a full restart.
    private var appliedSignature: String? = null

    // Blocks scroll-driven camera updates while a row-switch animation is in progress so the
    // workspace's transient page reports during the slide don't move the horizontal camera.
    private var isRowTransitioning = false
    // Blocks scroll-driven camera updates during a rotation rebind. On rotation Launcher3 tears down
    // and rebinds the workspace, emitting transient onScrollOffset callbacks BEFORE the grid settles at
    // the active row's page. Without this guard, those transient offsets are diffed against the stale
    // pre-rotation lastScrollOffset baseline and the spurious delta shifts the wallpaper camera — the
    // wallpaper then isn't pixel-stable across that rotation (seen intermittently on non-page-0 pages;
    // "correct on retry" because by then the workspace is already settled so the diff is ~0). Set at
    // the START of the config change; cleared in resetForWorkspaceReady() once the rebind has settled.
    private var isRotationRebinding = false
    // Last row-relative scroll offset seen, used to compute the per-frame horizontal delta.
    private var lastScrollOffset = Float.NaN

    // --- Live-wallpaper (AnchorWallpaperService) offset bridge ---
    // When our live wallpaper is the active system wallpaper, parallax is driven cross-process by
    // pushing the camera position via WallpaperManager.setWallpaperOffsets → the engine's
    // onOffsetsChanged.
    //
    // IMPORTANT: we push BITMAP-SPACE camera coordinates (liveWorldX/liveWorldY), NOT glass-space
    // offsets. Bitmap-space is rotation-invariant: a pure device rotation never changes them, and the
    // engine's crop is computed only from (worldX, worldY) + the rotation-invariant portrait-canonical
    // viewport, so it selects the identical source pixels in both orientations → pixel-perfect rotation.
    // The glass→bitmap axis mapping lives HERE, applied to gesture deltas at the moment they happen.
    private var liveWallpaperActiveCached: Boolean? = null
    private var liveWorldX = 0f          // bitmap-space camera X [0,1] (page scroll)
    private var liveWorldY = HOME_REST_WORLD_Y  // bitmap-space camera Y [0,1] (row; bottom/home rest)
    private var vGlassAnimator: ValueAnimator? = null

    /**
     * True when AnchorWallpaperService is the current system wallpaper. Cached because it is read on
     * every scroll frame; [refreshLiveWallpaperState] must be called whenever the wallpaper may have
     * changed (setup, onResume) so the cache reflects a wallpaper set AFTER launcher startup.
     */
    private fun isLiveWallpaperActive(): Boolean {
        liveWallpaperActiveCached?.let { return it }
        return refreshLiveWallpaperState()
    }

    /**
     * Re-query whether our live wallpaper is active and update the cache + Launcher3 offset
     * suppression. Called from [setup] and [reapplyIfChanged] (onResume) so setting our wallpaper
     * after the launcher is already running is picked up. Returns the fresh value.
     */
    fun refreshLiveWallpaperState(): Boolean {
        // Passthrough is gated on the PREF (source == custom), NOT on wallpaperManager.wallpaperInfo:
        // the pref is set synchronously by the picker, whereas wallpaperInfo lags by a resume or two
        // after the system commits the wallpaper. If we waited for wallpaperInfo, the first frame on
        // return-to-home would draw the opaque window background (black) before passthrough kicked in
        // (the "works only on the 2nd resume / press home twice" symptom). The pref is authoritative
        // for "the user chose the Anchor wallpaper", so enable passthrough immediately from it.
        val prefCustom =
            AnchorPreferences(launcher).wallpaperSource == AnchorPreferences.WALLPAPER_SOURCE_CUSTOM
        // The offset bridge / Launcher3 suppression is keyed on the system actually running our engine.
        val active = runCatching {
            wallpaperManager.wallpaperInfo?.component?.className ==
                "app.anchor.wallpaper.AnchorWallpaperService"
        }.getOrDefault(false)
        // The launcher theme (BaseLauncherTheme) already sets windowBackground=transparent +
        // windowShowWallpaper=true, so the window is a wallpaper passthrough by default — no runtime
        // flag/background manipulation needed (and toggling FLAG_SHOW_WALLPAPER at runtime actively
        // broke the wallpaper). We only drive parallax offsets + Launcher3 suppression here.
        liveWallpaperActiveCached = active || prefCustom
        com.android.launcher3.util.WallpaperOffsetInterpolator.sAnchorSuppressSystemOffsets =
            liveWallpaperActiveCached!!
        if (liveWallpaperActiveCached == true) {
            pushLiveOffsets()
        }
        return liveWallpaperActiveCached!!
    }

    /**
     * Push the current BITMAP-SPACE camera (liveWorldX, liveWorldY) to the live wallpaper engine, if
     * the window token is up. These are rotation-invariant; the engine uses them directly (no remap),
     * so a pure rotation is pixel-perfect.
     */
    // Coalesced offset pusher: setWallpaperOffsets is a SYNCHRONOUS cross-process Binder call. Called on
    // the main thread every scroll frame it stalls the WHOLE home scroll (icons + parallax) — verified on
    // device: skipping it made scroll smooth, and the jank was UI-thread-bound (GPU ~3ms). Launcher3's own
    // WallpaperOffsetInterpolator runs this on UI_HELPER_EXECUTOR for the same reason. We post the Binder
    // round-trip to that helper thread and coalesce to the LATEST offset (removeCallbacks before posting)
    // so a slow Binder call can't build a backlog of stale offsets.
    private val uiHelperHandler = com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR.handler
    @Volatile private var pendingToken: android.os.IBinder? = null
    private val pushOffsetsRunnable = Runnable {
        // Runs on the UI-helper thread. Only the Binder call is done here; the window token is captured
        // on the MAIN thread (decorView access is not thread-safe and throws "Window couldn't find
        // content container view" when the window is EXITING). Guard the whole body so a torn-down
        // wallpaper (toggle-off / recreate) can never crash this background thread.
        runCatching {
            val token = pendingToken ?: return@runCatching
            wallpaperManager.setWallpaperOffsets(token, liveWorldX, liveWorldY)
        }
    }

    private fun pushLiveOffsets() {
        // Capture the token on the main thread (safe); skip if the window is gone/exiting.
        pendingToken = runCatching { launcher.window?.decorView?.windowToken }.getOrNull() ?: return
        uiHelperHandler.removeCallbacks(pushOffsetsRunnable)
        uiHelperHandler.post(pushOffsetsRunnable)
    }

    fun setup() {
        val prefs = AnchorPreferences(launcher)
        appliedSignature = configSignature(prefs)
        // When our live wallpaper is the active system wallpaper, parallax is driven cross-process by
        // the offset bridge (onScrollOffset/onRowTransition → setWallpaperOffsets). Suppress Launcher3's
        // built-in WallpaperOffsetInterpolator so it doesn't ALSO write the token (two writers alternate
        // frame-to-frame → flicker/jank + it stomps our yOffset back to 0.5). The launcher theme already
        // passes the wallpaper through (transparent windowBackground + windowShowWallpaper), so we only
        // wire up the parallax offset bridge here (in refreshLiveWallpaperState).
        refreshLiveWallpaperState()
    }

    /**
     * Called from [LawnchairLauncher.onResume]. If our live wallpaper was set/unset via the system
     * wallpaper picker since we last applied, re-evaluate so the offset bridge / Launcher3 suppression
     * reflect the current wallpaper without requiring a full launcher restart.
     */
    fun reapplyIfChanged() {
        // Re-check whether our live wallpaper is active every resume — it can be set/unset via the
        // system wallpaper picker without any change to the launcher's own pref signature below.
        refreshLiveWallpaperState()
        val prefs = AnchorPreferences(launcher)
        val sig = configSignature(prefs)
        if (sig == appliedSignature) return
        appliedSignature = sig
    }

    private fun configSignature(prefs: AnchorPreferences): String {
        // The custom image is always saved to the same path (custom_wallpaper.jpg, overwritten on
        // each pick), so the path alone never changes — include the file's mtime so RE-picking a
        // different image is detected as a change and reapplyIfChanged() reloads it.
        val customMtime = prefs.customWallpaperPath
            ?.let { runCatching { File(it).lastModified() }.getOrDefault(0L) } ?: 0L
        return "${prefs.wallpaperSource}|${prefs.customWallpaperPath}|$customMtime"
    }

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
     * current row's allowed page range).
     *
     * The wallpaper is a fixed WORLD you pan through. The horizontal-on-glass camera axis ACCUMULATES
     * the swipe as a delta — every right-swipe moves you further right in the world and they ADD UP
     * across rows (so "up, right, down, right" ends up twice as far right as a single "right"; rows
     * don't reset your horizontal world position). Up/down (row switches) move the vertical axis and
     * never touch the horizontal one. You only stop at the true edge of the world (worldX clamped to
     * [0,1]).
     *
     * Each page-step moves worldX by [LIVE_HORIZONTAL_SWEEP_WORLD] (a SMALL fraction of the world) so
     * many page-steps across rows accumulate smoothly before reaching the edge.
     *
     * Rotation stays pixel-perfect: at rest (no active swipe) there is no delta, so a pure rotation
     * leaves worldX/worldY untouched → identical crop. The glass→bitmap axis+sign map is applied to
     * the DELTA only, for the current rotation, never re-mapping the absolute position on rotation.
     *
     * Blocked during a row-switch animation and during a rotation rebind.
     */
    fun onScrollOffset(offset: Float) {
        if (isRowTransitioning || isRotationRebinding) return
        if (!isLiveWallpaperActive()) return
        if (lastScrollOffset.isNaN()) {
            lastScrollOffset = offset
            return
        }
        val delta = offset - lastScrollOffset
        if (delta == 0f) return
        lastScrollOffset = offset
        val step = delta * LIVE_HORIZONTAL_SWEEP_WORLD
        val m = WallpaperCropMath.horizontalGlassAxis(currentRotation())
        applyLiveCameraDelta(m, step)
        pushLiveOffsets()
    }

    private fun applyLiveCameraDelta(m: WallpaperCropMath.AxisMapping, step: Float) {
        val s = m.sign * step
        when (m.axis) {
            WallpaperCropMath.WorldAxis.X -> liveWorldX = (liveWorldX + s).coerceIn(0f, 1f)
            WallpaperCropMath.WorldAxis.Y -> liveWorldY = (liveWorldY + s).coerceIn(0f, 1f)
        }
    }

    /** Set one axis of the live-wallpaper camera to an absolute value. */
    private fun setLiveWorldAxis(axis: WallpaperCropMath.WorldAxis, value: Float) {
        val v = value.coerceIn(0f, 1f)
        when (axis) {
            WallpaperCropMath.WorldAxis.X -> liveWorldX = v
            WallpaperCropMath.WorldAxis.Y -> liveWorldY = v
        }
    }

    /**
     * Called by [app.anchor.navigation.TwoRowNavigationManager] when a row transition starts.
     * Animates the vertical-on-glass camera axis to the target row's canonical world position over the
     * same duration as the workspace slide, so the wallpaper glides with the row switch. Row 0 (home)
     * rests at the bottom; the top row is at the top. Row spacing is uniform so one row-step moves a
     * comparable fraction to one page-step (equal-feel parallax).
     *
     * Axis: a vertical-on-glass move maps to a (camera axis, sign) per rotation via
     * [WallpaperCropMath.verticalGlassAxis] — bitmap-Y in portrait, bitmap-X in landscape, sign-mirrored
     * so higher rows always drag the wallpaper the same way on glass. We animate ONLY the mapped axis's
     * absolute value to the target; because the mapping is applied to the target here (not re-applied on
     * rotation), a pure rotation mid-rest leaves the camera untouched.
     */
    fun onRowTransition(toRow: Int, totalRows: Int, durationMs: Long) {
        if (!isLiveWallpaperActive()) return
        // Canonical vertical-world target (portrait-Y sense): row 0 rests at HOME_REST_WORLD_Y
        // (bottom); each row up subtracts one step, clamped to the top. Then map to the CURRENT
        // rotation's world axis + sign.
        val canonical = (HOME_REST_WORLD_Y - toRow * LIVE_ROW_STEP_WORLD).coerceIn(0f, HOME_REST_WORLD_Y)
        val m = WallpaperCropMath.verticalGlassAxis(currentRotation())
        val target = if (m.sign < 0) 1f - canonical else canonical
        val current = when (m.axis) {
            WallpaperCropMath.WorldAxis.X -> liveWorldX
            WallpaperCropMath.WorldAxis.Y -> liveWorldY
        }
        vGlassAnimator?.cancel()
        if (kotlin.math.abs(current - target) < 0.001f) {
            setLiveWorldAxis(m.axis, target)
            pushLiveOffsets()
            return
        }
        vGlassAnimator = ValueAnimator.ofFloat(current, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                setLiveWorldAxis(m.axis, anim.animatedValue as Float)
                pushLiveOffsets()
            }
            start()
        }
    }

    /**
     * Called from [LawnchairLauncher.onConfigurationChanged] at the START of a rotation, before
     * Launcher3 tears down and rebinds the workspace. Freezes scroll-driven camera updates and clears
     * the delta baseline so the transient onScrollOffset callbacks emitted during the rebind can't apply
     * a spurious delta to the camera (which showed up as the wallpaper shifting instead of staying
     * pixel-stable across rotation on non-page-0 pages). Lifted in [resetForWorkspaceReady] once the
     * rebind settles.
     */
    fun beginRotationRebind() {
        isRotationRebinding = true
        lastScrollOffset = Float.NaN
    }

    /**
     * Called from [LawnchairLauncher.finishBindingItems] after rotation/rebind. Clears transient
     * transition state and resets the scroll delta baseline. The camera position is left untouched
     * — it is already correct (rotation is pixel-perfect by construction).
     */
    fun resetForWorkspaceReady() {
        isRowTransitioning = false
        isRotationRebinding = false
        lastScrollOffset = Float.NaN
    }

    /**
     * One-shot heal for the post-set choppy home scroll (Samsung, verified). After our live wallpaper
     * is (re)set from the foreground and the launcher recreates, the wallpaper engine's surface is left
     * out-of-sync with the launcher's compositing group: the launcher's RenderThread stalls dequeuing
     * the wallpaper buffer and the UI thread blocks in postAndWait every scroll frame (~50% janky). A
     * real recents→home or screen unlock heals it — device traces show both do the SAME thing: WM
     * re-dispatches wallpaper visibility to the engine (`dispatchHomeVisibilityChanged`) via a
     * `WALLPAPER_INTRA_OPEN` transition. A plain app→home or an opaque activity does NOT (they don't
     * change the wallpaper target), which is why an earlier transparent-activity bounce failed.
     *
     * We reproduce that redispatch invisibly: toggle the launcher window's FLAG_SHOW_WALLPAPER OFF,
     * let WM process it for one frame (so it is NOT coalesced back to a no-op — a synchronous off→on in
     * the same frame does nothing, which is why the prior synchronous attempt failed), then re-add it.
     * WM sees the wallpaper target drop and re-acquire, recomputes wallpaper visibility, and re-latches
     * the engine surface — same as the unlock path. Gated by [AnchorPreferences.pendingWallpaperHeal]
     * so it runs exactly once after a set, never on ordinary resumes.
     */
    fun healWallpaperVisibilityIfPending() {
        val prefs = AnchorPreferences(launcher)
        if (!prefs.pendingWallpaperHeal) return
        // Only meaningful when our live wallpaper is the active/target wallpaper (passthrough mode).
        if (!isLiveWallpaperActive()) { prefs.pendingWallpaperHeal = false; return }
        prefs.pendingWallpaperHeal = false
        val window = launcher.window ?: return
        val decor = window.decorView
        // Drop the wallpaper target this frame…
        window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        // …then re-acquire it after WM has processed the removal (next frame). Two posts to be safe:
        // the first lets the relayout with the flag cleared reach WM; the second re-adds it so WM runs
        // the wallpaper-target recompute + engine redispatch.
        decor.post {
            decor.post {
                launcher.window?.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                // Re-push the current camera so the engine draws its first post-heal frame correctly.
                pushLiveOffsets()
            }
        }
    }

    fun destroy() {
        vGlassAnimator?.cancel()
    }

    // Reads the current display rotation. onScrollOffset calls this every scroll frame, but
    // DisplayController.info is a cheap field read (kept current by the framework), so this is fine.
    private fun currentRotation(): Int =
        DisplayController.INSTANCE.get(launcher).info.rotation

    companion object {
        private const val TAG = "AnchorWallpaper"
        // worldY of the home row (row 0) at rest: near the BOTTOM of the image, matching the engine's
        // HOME_REST_WORLD_Y and the lock-screen crop.
        private const val ROW_MARGIN = 0.1f
        private const val HOME_REST_WORLD_Y = 1f - ROW_MARGIN

        // Live-wallpaper camera travel, in bitmap-space world units [0,1]. worldX/worldY ∈ [0,1] map to
        // the FULL parallax budget the engine allows (AnchorPreferences.wallpaperParallaxPercent, 0 =
        // off). The horizontal axis ACCUMULATES swipe deltas (onScrollOffset), so these constants set
        // how much of the world one navigation STEP consumes — i.e. how many steps accumulate before you
        // reach the world edge:
        //   LIVE_HORIZONTAL_SWEEP_WORLD: worldX added per FULL row page-sweep (offset 0→1 = one page
        //     step in a 2-page row). At 0.25, ~4 page-steps fill the world, so movement ACCUMULATES
        //     across rows — "up, right, down, right" lands twice as far right as a single "right".
        //   LIVE_ROW_STEP_WORLD: worldY added per row switch (kept larger; few rows exist).
        private const val LIVE_HORIZONTAL_SWEEP_WORLD = 0.25f
        private const val LIVE_ROW_STEP_WORLD = 0.5f

        // The same bitmap is rendered in BOTH orientations (portrait-canonical), so to cover the
        // screen without stretching it must be at least the screen's LARGER side in both dimensions.
        // COVER_FACTOR adds a little headroom for parallax; HARD_CAP bounds memory on big sources.
        // (Larger = more parallax room but more memory → swipe lag; ~1.1×screen is a good balance.)
        private const val COVER_FACTOR = 1.1f
        private const val HARD_CAP_FACTOR = 2

        /**
         * The FULL display size (including the status/nav bar areas) — this is what the wallpaper crop
         * is computed against. `resources.displayMetrics` excludes the system bars on modern Android,
         * which would make the system-set wallpaper ~1–2 % off and read as a slight lock-screen zoom.
         */
        private fun realScreenSize(context: Context): Pair<Int, Int> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = context.getSystemService(WindowManager::class.java)
                if (wm != null) {
                    val b = wm.maximumWindowMetrics.bounds
                    return b.width() to b.height()
                }
            }
            val dm = context.resources.displayMetrics
            return dm.widthPixels to dm.heightPixels
        }

        /**
         * Decodes [path] scaled so it covers the screen in both axes (no stretch when rotated) with a
         * little parallax headroom, while staying small enough to resample smoothly every scroll frame.
         * A 4096² GNOME wallpaper or a large photo is power-of-2 pre-sampled then exact-scaled.
         */
        fun decodeDownscaled(context: Context, path: String): Bitmap? {
            val (sw, sh) = realScreenSize(context)
            val screenMax = maxOf(sw, sh)
            val coverTarget = (screenMax * COVER_FACTOR).toInt()   // smaller side should reach this
            val hardCap = screenMax * HARD_CAP_FACTOR              // larger side at most this

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return BitmapFactory.decodeFile(path)

            val srcMin = minOf(srcW, srcH)
            val srcMax = maxOf(srcW, srcH)
            // Downscale toward coverage (only if it wouldn't upscale), then clamp the long side.
            var scale = if (srcMin > coverTarget) coverTarget.toFloat() / srcMin else 1f
            if (srcMax * scale > hardCap) scale = hardCap.toFloat() / srcMax

            val targetW = (srcW * scale).toInt().coerceAtLeast(1)
            var sample = 1
            while (srcW / (sample * 2) >= targetW) sample *= 2
            val decoded = BitmapFactory.decodeFile(
                path, BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null

            if (decoded.width <= targetW) return decoded
            val finalScale = targetW.toFloat() / decoded.width
            val out = Bitmap.createScaledBitmap(
                decoded, targetW, (decoded.height * finalScale).toInt().coerceAtLeast(1), true,
            )
            if (out != decoded) decoded.recycle()
            return out
        }

        /**
         * The rectangle of [src] that the home screen shows at rest (row 0, page 0): worldX = 0
         * (page-0 left edge), worldY = 1 − [ROW_MARGIN] (bottom row). Passed to
         * [WallpaperManager.setBitmap] as the `visibleCropHint` so the system displays EXACTLY this
         * region on the lock screen — matching Anchor's home render — instead of applying its own
         * center-crop/scale (which shifted the region and zoomed it).
         */
        fun homeRestCropRect(src: Bitmap, context: Context): Rect {
            val (sw, sh) = realScreenSize(context)
            val screenW = minOf(sw, sh)   // portrait width
            val screenH = maxOf(sw, sh)   // portrait height
            val scrollRoomY = maxOf(0, src.height - screenH)
            val srcTop = ((1f - ROW_MARGIN) * scrollRoomY).toInt().coerceIn(0, scrollRoomY)
            val cropW = minOf(screenW, src.width)
            val cropH = minOf(screenH, src.height)
            return Rect(0, srcTop, cropW, srcTop + cropH)
        }

        /**
         * Imports a BUNDLED wallpaper from a raw resource (mirror of [importCustomWallpaper] for a
         * URI). Copies the raw bytes to `custom_wallpaper.jpg` and points
         * [AnchorPreferences.customWallpaperPath] at it, so a bundled pick reuses the exact same
         * rendering path as a user-picked image. Call on a background thread.
         */
        fun importBundledWallpaper(context: Context, rawResId: Int): Boolean {
            return try {
                val dest = File(context.filesDir, "custom_wallpaper.jpg")
                context.resources.openRawResource(rawResId).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                AnchorPreferences(context).customWallpaperPath = dest.absolutePath
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to import bundled wallpaper: ${e.message}")
                false
            }
        }

        /**
         * Copies the user-picked image [uri] into app-private storage and points
         * [AnchorPreferences.customWallpaperPath] at it. Photo-picker URIs grant read access without
         * any permission. Returns true on success. Call on a background thread.
         *
         * When [alsoSetSystemWallpaper] is true, the same image is also set as the actual system
         * wallpaper (home + lock) via [WallpaperManager.setBitmap] — a normal `SET_WALLPAPER`
         * permission, auto-granted, Play-safe — with an explicit visibleCropHint = the exact rectangle
         * Anchor shows on the home screen at rest, so the lock screen matches the home render.
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
                        val src = decodeDownscaled(context, dest.absolutePath)
                        if (src != null) {
                            val cropHint = homeRestCropRect(src, context)
                            val wm = WallpaperManager.getInstance(context)
                            // Desired size = the crop's size, so the system renders the cropHint region
                            // 1:1 instead of scaling it up and showing the top of it (which made lock
                            // look zoomed-in and shifted up vs home).
                            wm.suggestDesiredDimensions(cropHint.width(), cropHint.height())
                            wm.setBitmap(
                                src,
                                cropHint,
                                true, // allowBackup
                                WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
                            )
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
