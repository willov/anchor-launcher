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
 * ([pageWorld], [rowWorld]) — stored by ROLE and mapped to bitmap axes at push time:
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
    // IMPORTANT: we push BITMAP-SPACE camera coordinates (see bitmapCamera()), NOT glass-space
    // offsets. Bitmap-space is rotation-invariant: a pure device rotation never changes them, and the
    // engine's crop is computed only from (worldX, worldY) + the rotation-invariant portrait-canonical
    // viewport, so it selects the identical source pixels in both orientations → pixel-perfect rotation.
    // The glass→bitmap axis mapping lives HERE, applied to gesture deltas at the moment they happen.
    private var liveWallpaperActiveCached: Boolean? = null
    // The camera is stored by ROLE, not by bitmap axis.
    //
    // The two roles have genuinely different semantics: the PAGE axis is a free scroll that needs
    // headroom in both directions and rests at one end, while the ROW axis is anchored — row 0 sits at
    // HOME_REST_WORLD_Y and rows climb from there. Storing those as
    // liveWorldX/liveWorldY meant the rest positions were bound to bitmap axes, but WHICH bitmap axis
    // each role drives flips with rotation (the counter-rotation maps horizontal-on-glass to bitmap-Y
    // at ROTATION_90). So in landscape page scrolling inherited the ROW rest of 0.9 and had only 0.10
    // of room in one direction — half a sweep — then stalled. Keying by role makes that impossible:
    // each role keeps its own rest and range no matter how the device is held.
    private var pageWorld = PAGE_REST_WORLD      // free scroll axis (page swipes)
    private var rowWorld = HOME_REST_WORLD_Y     // row switches; row 0 rests at the image centre
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
        if (liveWallpaperActiveCached == true) {
            pushLiveOffsets()
        }
        return liveWallpaperActiveCached!!
    }

    /**
     * Push the current BITMAP-SPACE camera (see [bitmapCamera]) to the live wallpaper engine, if
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
            val (bx, by) = bitmapCamera()
            // NB: do NOT call setWallpaperOffsetSteps here. It declares the offset GRID, so the
            // framework quantises the value to the nearest multiple — stamping 0.5 snapped our 0.10
            // to 0.5 and caused the very jump it was added to prevent. The engine distinguishes our
            // offsets from the system's by the system's own step=-1 sentinel instead.
            // Publish what we are about to send so the engine can verify the offset is ours.
            // Launcher3's WallpaperOffsetInterpolator and the system both push to this same token
            // from other threads, so no suppression flag can reliably win that race. Matching on the
            // value closes it deterministically.
            lastPushedCamera = bx to by
            wallpaperManager.setWallpaperOffsets(token, bx, by)
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
     * [0,1], which maps to the real bitmap pan room — a LARGE world, ~13 swipes/axis at COVER 2.0).
     *
     * CONSTANT, UNIFORM motion (no page-count scaling, no ease-out): one full row SWEEP (page 0 → last
     * page, `offset` 0→1) advances the camera by exactly [LIVE_STEP_PER_SWEEP], REGARDLESS of how many
     * pages the row has. So the wallpaper moves the same total distance across any row, and — because
     * `offset` moves linearly with the finger — each swipe within the row contributes an equal share.
     * A ROW switch ([onRowTransition]) advances the perpendicular axis by the SAME [LIVE_STEP_PER_SWEEP],
     * so a horizontal sweep and a vertical sweep move the wallpaper an equal distance → equal H/V feel.
     * (The old model multiplied by (pagesInRow−1) to make ONE PAGE = one step, which front-loaded the
     * whole budget onto the first swipe on multi-page rows and pegged the camera at the edge; dropping
     * that multiplier makes it a smooth, constant pan across the entire row instead.)
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
        // Constant motion: a full row sweep (delta sums to 1.0 across the whole page range) advances the
        // camera by LIVE_STEP_PER_SWEEP — independent of page count. No (pagesInRow−1) scaling, so every
        // swipe on a 2- or 4-page row moves the wallpaper the same uniform fraction.
        val step = delta * LIVE_STEP_PER_SWEEP
        // Page scroll moves the PAGE role in canonical space; direction is applied at push time by
        // bitmapCamera() along with the axis swap, so this stays rotation-agnostic.
        val bef = pageWorld
        pageWorld = (pageWorld + step).coerceIn(0f, 1f)
        pushLiveOffsets()
    }

    /**
     * Maps the role-space camera to the BITMAP-space (x, y) the engine consumes.
     *
     * This is the one place the rotation mapping lives. In portrait the page role drives bitmap-X and
     * the row role drives bitmap-Y; at ROTATION_90/270 the counter-rotation swaps them. Sign handling
     * stays with the callers that move each role, so this is a pure axis swap.
     */
    private fun bitmapCamera(): Pair<Float, Float> =
        WallpaperCropMath.roleToBitmapCamera(pageWorld, rowWorld, currentRotation())

    /**
     * Called by [app.anchor.navigation.TwoRowNavigationManager] when a row transition starts.
     * Animates the vertical-on-glass camera axis to the target row's canonical world position over the
     * same duration as the workspace slide, so the wallpaper glides with the row switch. Row 0 (home)
     * rests at the bottom; the top row is at the top. Each row-step advances the axis by exactly
     * [LIVE_STEP_PER_SWEEP] — the SAME constant a full horizontal row-sweep uses — so vertical and
     * horizontal motion are equal (equal-feel parallax), clamped to the world edge.
     *
     * Axis: a vertical-on-glass move maps to a (camera axis, sign) per rotation via
     * [WallpaperCropMath.verticalGlassAxis] — bitmap-Y in portrait, bitmap-X in landscape, sign-mirrored
     * so higher rows always drag the wallpaper the same way on glass. We animate ONLY the mapped axis's
     * absolute value to the target; because the mapping is applied to the target here (not re-applied on
     * rotation), a pure rotation mid-rest leaves the camera untouched.
     */
    fun onRowTransition(
        toRow: Int,
        totalRows: Int,
        durationMs: Long,
        pagesInTargetRow: Int = 2,
    ) {
        if (!isLiveWallpaperActive()) return
        // Row 0 rests at HOME_REST_WORLD_Y (image bottom); each row up subtracts one step. This is a
        // property of the ROW role, stored as such so it can no longer leak into page scrolling when
        // rotation swaps which bitmap axis each role drives.
        // Match the row step to what ONE PAGE SWIPE moves on the row being entered.
        //
        // Both paths use LIVE_STEP_PER_SWEEP, but they measure a "sweep" differently: a row switch is
        // one ROW, while a page sweep is the WHOLE row of pages (offset 0→1). So on a 3-page row a
        // single swipe moves half a step and a row switch moves a full one — the wallpaper visibly
        // travels twice as far vertically as horizontally. Scaling the row step by the same
        // 1/(pages−1) a page swipe gets makes one row switch cover the same distance as one swipe.
        //
        // Uses min() so the row step is only ever SHRUNK to meet the pages, never stretched: a 2-page
        // row would otherwise scale to a full step and re-introduce the mismatch in the other
        // direction on rows that have more pages.
        val pageStep = LIVE_STEP_PER_SWEEP / (pagesInTargetRow - 1).coerceAtLeast(1)

        // Also cap the step so ALL rows fit the room available above the rest position. Rows are
        // capped at MAX_ROWS (5), and with 2-page rows the row step is at its largest (0.2), so
        // 4-5 rows would otherwise need 0.6-0.8 of the 0.5 available and the top rows would simply
        // stop moving. Shrinking the step keeps every row reachable; it costs a slightly smaller
        // vertical drift than horizontal in exactly that configuration, which is a better failure
        // than not moving at all. (Rows with 3+ pages already have a small enough step to fit.)
        val rowsAbove = (totalRows - 1).coerceAtLeast(1)
        val fitStep = CAMERA_REST_ROOM / rowsAbove
        val rowStep = minOf(LIVE_STEP_PER_SWEEP, pageStep, fitStep)
        val canonical = (HOME_REST_WORLD_Y - toRow * rowStep).coerceIn(0f, HOME_REST_WORLD_Y)

        // rowWorld is ALWAYS stored canonically (row 0 = HOME_REST_WORLD_Y). The rotation mirror is
        // applied once, at push time in bitmapCamera(), together with the axis swap — mirroring the
        // target here instead made the stored value silently change meaning per rotation, so the first
        // row switch after launch lurched a long way and every later one was fine. Keeping one
        // representation and converting only at the boundary removes that whole class of bug.
        val target = canonical
        val current = rowWorld
        vGlassAnimator?.cancel()
        if (kotlin.math.abs(current - target) < 0.001f) {
            rowWorld = target.coerceIn(0f, 1f)
            pushLiveOffsets()
            return
        }
        vGlassAnimator = ValueAnimator.ofFloat(current, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                rowWorld = (anim.animatedValue as Float).coerceIn(0f, 1f)
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
    /**
     * Re-asserts the current camera on the engine.
     *
     * A wallpaper engine is destroyed and recreated across a lock/unlock, and the new instance starts
     * from its own hardcoded rest (worldX=0, worldY=HOME_REST_WORLD_Y) in RAW BITMAP space. The
     * launcher stores the camera by role and applies the rotation mirror at push time, so those two
     * only agree in portrait — at ROTATION_90/180/270 the fresh engine begins from a different crop
     * than the launcher believes is showing. Nothing corrected it until the next gesture, so the first
     * swipe after an unlock jumped as the engine snapped to the real position.
     *
     * Pushing the camera on resume makes the engine's starting crop match the launcher's state, so the
     * first gesture moves by its own delta like any other.
     */
    fun resyncEngineCamera() {
        if (!isLiveWallpaperActive()) return
        pushLiveOffsets()
    }

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
        /**
         * Rest position of BOTH camera roles: the middle of the pannable window.
         *
         * The window is already centred in the bitmap (see the engine's baseX/baseY), so 0.5 puts the
         * IMAGE CENTRE at the viewport centre at rest. That matters for user photos — a centred
         * subject was landing 300px left and 240px high of where you'd expect, because the page role
         * rested at 0 (window start) and the row role at 0.9 (near window end).
         *
         * Centring does NOT reduce total travel: the pannable window is the same size either way, it
         * is just entered from the middle, so each axis gets half the range in each direction instead
         * of all of it in one. A full page sweep costs LIVE_STEP_PER_SWEEP (0.2) regardless of page
         * count, so 0.5 each way is ample on that axis at any number of pages.
         */
        private const val CAMERA_REST_WORLD = 0.5f

        /** Room each role has on either side of [CAMERA_REST_WORLD]. */
        private const val CAMERA_REST_ROOM = CAMERA_REST_WORLD

        private const val HOME_REST_WORLD_Y = CAMERA_REST_WORLD

        /**
         * Rest position of the PAGE role. Page 0 sits at one end so the whole range is available to
         * scroll into — unlike the ROW role, which is anchored near the image bottom.
         */
        private const val PAGE_REST_WORLD = CAMERA_REST_WORLD

        /**
         * The last camera Anchor pushed, read by [app.anchor.wallpaper.AnchorWallpaperService] to
         * confirm an incoming offset originated here (same process, so this is a direct read).
         * Launcher3's WallpaperOffsetInterpolator and the system push to the same token from other
         * threads, so matching on the value is what makes "is this ours" deterministic.
         */
        @Volatile
        @JvmStatic
        var lastPushedCamera: Pair<Float, Float>? = null


        // How many full navigation SWEEPS fill the world (edge to edge) before the camera caps. A
        // "sweep" is one full row scroll (page 0 → last page) OR one row switch. This is the SINGLE
        // SOURCE OF TRUTH shared with the engine: the slider is expressed as per-swipe drift, and the
        // engine multiplies it by this to get the total world travel (effTravel). At 5, ~5 swipes reach
        // the edge — the design target of "~5-6 swipes, then a clean clamp". Independent of bitmap size:
        // the bitmap sizing only sets how many PIXELS a sweep spans, not how many sweeps there are.
        const val SWEEPS_TO_FILL_WORLD = 5

        /** Extra parallax headroom baked into a rasterized SVG so small slider changes need no re-render. */
        private const val SVG_HEADROOM = 1.5f

        /**
         * Ceiling on the rasterized SVG's edge length. Rendering needs a full ARGB buffer of this size
         * and PNG must hold the whole bitmap to compress, so this bounds the transient spike inside the
         * wallpaper process: 3072² ≈ 36MB, versus 81MB if a 30% slider were honoured literally.
         */
        private const val SVG_MAX_RENDER_PX = 3072

        // Live-wallpaper camera advance per full navigation SWEEP, in bitmap-space world units [0,1].
        // BOTH a horizontal sweep and a row switch advance their axis by this constant, so horizontal
        // and vertical motion are EQUAL. Each role is clamped to [0,1], which maps to the real
        // bitmap pan room.
        //
        // Motion is CONSTANT and uniform: onScrollOffset accumulates delta*LIVE_STEP_PER_SWEEP with no
        // page-count scaling, so every swipe on a 2- or 4-page row moves the wallpaper the same fraction,
        // and the first swipes are essentially equal to the later ones (until the edge clamp). A user who
        // keeps swiping past the last sweep just stays pinned at the world edge — a clean stop, no jump.
        private const val LIVE_STEP_PER_SWEEP = 1f / SWEEPS_TO_FILL_WORLD

        // The same bitmap is rendered in BOTH orientations (portrait-canonical), so to cover the screen
        // without stretching it must be at least the screen's LARGER side in both dimensions. The
        // bitmap target is now derived per-device in [decodeDownscaled] as (crop + parallax travel)
        // per axis rather than a fixed multiple of screenMax — see the long note there. There is no
        // separate memory cap: the target is already the minimum that covers the screen and the
        // slider's full travel, so clamping below it could only produce an uncovered wallpaper.

        /**
         * How a picked image measures up against what this screen needs, so the picker can warn before
         * committing to it. Uses the same numbers [decodeDownscaled] will apply, so the warning can
         * never disagree with what actually happens.
         *
         * @param upscaleFactor how much the image must be blown up just to FILL the screen. 1 or less
         *   means it covers natively. Above ~1.3 the softness is usually visible on a wallpaper.
         * @param losesParallax true when the image is too small to leave any pan room at the current
         *   parallax setting, so parallax will be inactive.
         */
        data class SourceFit(val upscaleFactor: Float, val losesParallax: Boolean)

        /** Measures [uri] against this screen without decoding the pixels (bounds-only decode). */
        fun measureSourceFit(context: Context, uri: android.net.Uri): SourceFit? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
            }.getOrNull()
            return sourceFitFor(context, bounds.outWidth, bounds.outHeight)
        }

        /**
         * [measureSourceFit] for a bundled raw resource. The bundled art is not automatically big
         * enough either — the 3072² assets fall short of a large tablet's requirement — so the picker
         * warns about our own images on the same terms as a user's photo.
         */
        fun measureSourceFit(context: Context, rawResId: Int): SourceFit? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                context.resources.openRawResource(rawResId).use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
            }.getOrNull()
            return sourceFitFor(context, bounds.outWidth, bounds.outHeight)
        }

        private fun sourceFitFor(context: Context, w: Int, h: Int): SourceFit? {
            if (w <= 0 || h <= 0) return null

            val (sw, sh) = realScreenSize(context)
            val screenMin = minOf(sw, sh)
            val screenMax = maxOf(sw, sh)
            val fillScale = maxOf(screenMin / w.toFloat(), screenMax / h.toFloat())

            // Pan room left once the image is scaled to fill (mirrors decodeDownscaled's cap).
            val scale = maxOf(fillScale, 1f)
            val room = minOf(w * scale - screenMin, h * scale - screenMax)
            val parallaxPercent = AnchorPreferences(context).wallpaperParallaxPercent
            val wantsParallax = parallaxPercent > 0
            return SourceFit(
                upscaleFactor = fillScale,
                losesParallax = wantsParallax && room < 1f,
            )
        }

        /**
         * What fraction of the wallpaper image is visible on screen at the given [parallaxPercent],
         * as (widthFraction, heightFraction) in (0,1]. Because the engine crops 1:1 (one bitmap pixel
         * per screen pixel), the decoded bitmap size alone decides the framing — and that size is
         * (crop + parallax travel) per axis, exactly as [decodeDownscaled] computes it. So more
         * parallax literally means a more zoomed-in wallpaper.
         *
         * Exposed so the settings slider can show the user that trade live instead of hiding it; it
         * shares this one formula with the decoder so the two can never disagree.
         */
        fun visibleFractionFor(context: Context, parallaxPercent: Int): Pair<Float, Float> {
            val (sw, sh) = realScreenSize(context)
            val screenMin = minOf(sw, sh)
            val screenMax = maxOf(sw, sh)
            val travelPx = (parallaxPercent / 100f) * screenMin * SWEEPS_TO_FILL_WORLD
            return (screenMin / (screenMin + travelPx)) to (screenMax / (screenMax + travelPx))
        }

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
            val screenMin = minOf(sw, sh)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return BitmapFactory.decodeFile(path)

            // How big does the bitmap actually need to be?
            //
            // The engine crops srcW=rawW, srcH=rawH — ONE BITMAP PIXEL PER SCREEN PIXEL, blitted to a
            // dest of the same size, so there is NO scaling at draw time. That means the decoded bitmap
            // size ALONE decides how much of the artwork you see: a 3072² bitmap on a 1200×1920 tablet
            // shows only 39%×62% of the image. That is the "too zoomed" report.
            //
            // The crop is ALWAYS portrait-canonical: the engine takes rawW×rawH = (screenMin×screenMax)
            // of the bitmap and counter-ROTATES it onto the panel. It never selects a landscape-shaped
            // region — see AnchorWallpaperService.drawFrame, where rawW=min(w,h) and rawH=max(w,h)
            // regardless of rotation. So the bitmap does NOT need to be square; it needs to contain a
            // screenMin×screenMax rect plus pan room on each axis. (Sizing it square was an earlier
            // mistake here that threw away ~26% of the horizontal framing and ~8MB for nothing.)
            //
            // This mirrors what AOSP does in InvariantDeviceProfile.setWallpaperDimension: it walks
            // every supported bounds and takes a PER-AXIS max, arriving at a landscape wallpaper
            // (2880×1920 on this tablet) rather than a square one. Same principle, different crop model:
            // AOSP adds parallax room on X only because stock launchers pan only horizontally, whereas
            // Anchor's 2D navigation pans both axes, so we add the (equal) travel budget to each.
            //
            // The old code multiplied screenMax by a fixed COVER_FACTOR and then only ever downscaled
            // when srcMin EXCEEDED it (`if (srcMin > coverTarget)`). A 3072² source sits under that
            // threshold on both test devices (3072 < 3264 and < 4120), so it was never scaled at all
            // and the full 3072² got cropped 1:1 — that is the "too zoomed" bug. We now scale TOWARD
            // the target, so framing is a property of the DEVICE and the parallax setting, not an
            // accident of how big the source file happens to be.
            val parallaxPercent = AnchorPreferences(context).wallpaperParallaxPercent
            val travelPx = (parallaxPercent / 100f) * screenMin * SWEEPS_TO_FILL_WORLD

            // Required bitmap extent per axis: the crop plus that axis's pan room. Equal travel on both
            // axes keeps horizontal and vertical parallax symmetric (the engine's `travel = min(roomX,
            // roomY)` budget assumes this).
            val requiredW = screenMin + travelPx
            val requiredH = screenMax + travelPx

            // COVER both requirements: scale so BOTH axes reach their target, i.e. the larger of the two
            // per-axis ratios.
            val idealScale = maxOf(requiredW / srcW.toFloat(), requiredH / srcH.toFloat())

            // ...but NEVER upscale past what it takes to fill the screen. Parallax headroom is a
            // nice-to-have; sharpness is not. Blowing a 640×480 photo up 5.25× to manufacture pan room
            // would produce a blurry 32MB bitmap — worse on both axes than simply having less parallax.
            //
            // So the ceiling is `fillScale`: the scale that just covers the crop. Above that we are
            // inventing pixels purely for parallax, which is never worth it. A small image therefore
            // keeps its native detail and the engine's `travel = min(roomX, roomY)` naturally shrinks
            // the pan range to whatever room is left — down to zero (parallax silently off) for an
            // image at or below screen size. An image smaller than the screen still gets upscaled by
            // fillScale, because not filling the screen is not an option.
            val fillScale = maxOf(screenMin / srcW.toFloat(), screenMax / srcH.toFloat())
            val coverScale = minOf(idealScale, maxOf(fillScale, 1f))

            // Coverage always wins over the memory cap: an uncovered wallpaper (visible gap / stretch)
            // is worse than the memory it saves. So the cap is applied only to the extent that it does
            // not pull either axis under its requirement — for a very elongated source (a 9:20 photo)
            // that means the long side is allowed past the cap rather than starving the short one.
            val scale = coverScale

            val targetW = (srcW * scale).toInt().coerceAtLeast(1)

            // inSampleSize halves only while the result stays AT OR ABOVE the target, so it lands on
            // the nearest power-of-2 that is still >= targetW. That alone can leave a very large
            // intermediate: a 4096 source aimed at 2520 keeps sample=1 (4096/2 = 2048 < 2520), so the
            // decoder would allocate the FULL 4096² bitmap — 64MB in ARGB_8888 — before we scale it
            // down. In a wallpaper service, which the system kills first under memory pressure, that
            // transient spike is the real constraint (far more than APK size or steady-state usage).
            //
            // inScaled + inDensity/inTargetDensity makes BitmapFactory apply the remaining fractional
            // scale DURING decode, so the oversized intermediate is never allocated: peak ≈ the final
            // bitmap instead of the full source. Combined with inSampleSize for the power-of-2 part,
            // the decode goes straight to (approximately) the size we want.
            var sample = 1
            while (srcW / (sample * 2) >= targetW) sample *= 2
            val sampledW = srcW / sample
            // ARGB_8888, NOT RGB_565.
            //
            // 565 was chosen to halve bitmap memory on the assumption that an opaque background needs no
            // alpha and 16 bits is "enough" for a wallpaper. It is not: these GNOME backgrounds are broad
            // SMOOTH GRADIENTS, which is the single worst case for 16-bit colour. 565 gives green only 64
            // levels and red/blue only 32, so a gradient that ramps over hundreds of screen pixels gets
            // quantised into a handful of visibly stepped bands — "the shading is incorrect".
            //
            // Why this was previously mis-ruled-out: 565→888 expansion does NOT truncate (which would
            // leave R/B on multiples of 8). Android/the GPU BIT-REPLICATE the high bits into the low bits
            // (r8 = r5<<3 | r5>>2), producing values like 41 and 57 that are not multiples of 8 — so a
            // "R%8" test on a screenshot looks clean even for a genuinely 565 source. The surviving
            // fingerprint is GREEN: g8 = g6<<2 | g6>>4 is always ≡0 (mod 4). Measured on-device in
            // wallpaper-only regions: G%4 ≈ 0.72 vs 0.02 in the source — a 33× enrichment.
            //
            // The tablet shows it far worse than the Pixel because its Mali GPU composites the 565 buffer
            // without dithering, while the Pixel dithers and smears the steps. Same code, same file, and
            // the tablet's squarer viewport also crops tighter, magnifying each band.
            //
            // Cost: 18MB → 36MB for the 3072² world. That is the correct trade for the app's single
            // most visible surface, and is what the system's own ImageWallpaper holds anyway.
            // inDither additionally smooths any residual quantisation on the decode path.
            // createScaledBitmap below preserves this config.
            val decoded = BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inDither = true
                    // Fold the leftover fractional scale into the decode itself (see note above).
                    // Guarded: only when we actually need to shrink further, and never when the
                    // sampled size already matches, to avoid a pointless resample.
                    if (sampledW > targetW) {
                        inScaled = true
                        inDensity = sampledW
                        inTargetDensity = targetW
                    }
                },
            ) ?: return null

            // Final exact-size pass. With inScaled above the decode already lands on (or within a
            // rounding pixel of) the target, so this is usually a no-op — it remains as the safety net
            // for that rounding and for the upscale case, which inScaled's density ratio does not
            // cover. (This used to early-return on `decoded.width <= targetW`, i.e. downscale-only,
            // which combined with the old floor-only coverTarget test to leave a 3072² source
            // completely unscaled.)
            if (decoded.width == targetW) return decoded
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
                if (isSvgResource(context, rawResId)) {
                    // Vector source: rasterize at exactly the size THIS device needs instead of
                    // shipping a fixed-resolution raster that is too small for a large tablet and
                    // wastefully large for a phone. A vector never upscales, so these wallpapers are
                    // pixel-exact on any screen at any parallax setting.
                    if (!rasterizeSvgTo(context, rawResId, dest)) return false
                } else {
                    context.resources.openRawResource(rawResId).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                AnchorPreferences(context).customWallpaperPath = dest.absolutePath
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to import bundled wallpaper: ${e.message}")
                false
            }
        }

        /** True if the raw resource is an SVG (sniffed from the bytes, not the extension). */
        private fun isSvgResource(context: Context, rawResId: Int): Boolean = runCatching {
            context.resources.openRawResource(rawResId).use { input ->
                val head = ByteArray(512)
                val n = input.read(head)
                if (n <= 0) return false
                val text = String(head, 0, n, Charsets.UTF_8)
                text.contains("<svg") || text.contains("<?xml")
            }
        }.getOrDefault(false)

        /**
         * Renders an SVG raw resource into [dest] as a PNG at the size this device needs.
         *
         * Size comes from the same per-axis requirement [decodeDownscaled] uses (crop + parallax
         * travel), so the rasterized file is exactly big enough — no upscaling on a large tablet, no
         * wasted pixels on a phone. Rendered as PNG so the flat-shape artwork stays lossless; the file
         * is app-private and transient, so its size on disk does not matter.
         */
        private fun rasterizeSvgTo(context: Context, rawResId: Int, dest: File): Boolean = runCatching {
            val (sw, sh) = realScreenSize(context)
            val screenMin = minOf(sw, sh)
            val screenMax = maxOf(sw, sh)

            // Size for the CURRENT parallax setting, plus headroom for a moderate increase.
            //
            // Sizing to PARALLAX_PERCENT_MAX would make one render valid for every setting, but it is
            // not free: rendering needs a full ARGB buffer of the target size, and PNG has to hold the
            // whole bitmap to compress it. At max on this tablet that is 3720² = 53MB transient, inside
            // the wallpaper process — the first one the system kills under memory pressure. (Measured:
            // native heap spiked to 59MB during the render, settling back to 29MB.)
            //
            // A vector re-renders cheaply, so the better trade is to size for the current setting with
            // some slack and re-render if the user moves the slider well beyond it. SVG_HEADROOM covers
            // ordinary slider nudges without a re-render.
            val parallaxPercent = AnchorPreferences(context).wallpaperParallaxPercent
            val travelPx =
                (parallaxPercent * SVG_HEADROOM / 100f) * screenMin * SWEEPS_TO_FILL_WORLD

            // The SVGs are square; render the larger per-axis requirement so both are satisfied. Capped
            // so the transient render buffer stays bounded no matter how high the slider goes — at the
            // cap the wallpaper simply pans over slightly fewer source pixels, which is invisible,
            // whereas an unbounded buffer risks the wallpaper process being killed mid-render.
            val target = (maxOf(screenMin, screenMax) + travelPx).toInt()
                .coerceIn(1, SVG_MAX_RENDER_PX)

            val svg = context.resources.openRawResource(rawResId).use { input ->
                com.caverock.androidsvg.SVG.getFromInputStream(input)
            }
            svg.documentWidth.let { if (it <= 0f) svg.setDocumentWidth(target.toFloat()) }
            svg.documentHeight.let { if (it <= 0f) svg.setDocumentHeight(target.toFloat()) }

            val bmp = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val scale = target / (if (svg.documentWidth > 0f) svg.documentWidth else target.toFloat())
            canvas.scale(scale, scale)
            svg.renderToCanvas(canvas)

            dest.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bmp.recycle()
            Log.d(TAG, "Rasterized bundled SVG to ${target}x$target")
            true
        }.getOrElse {
            Log.w(TAG, "SVG rasterize failed: ${it.message}")
            false
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
