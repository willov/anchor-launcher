package app.anchor.rotation

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.Surface
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.util.DisplayController
import com.android.launcher3.util.Executors.MODEL_EXECUTOR

/**
 * Called from [InvariantDeviceProfile.onConfigChanged] to transpose workspace DB coordinates
 * before Lawnchair re-initialises the grid, and to swap [InvariantDeviceProfile.numColumns] /
 * [InvariantDeviceProfile.numRows] so the grid is N×M in landscape (spatial transpose).
 *
 * Lifecycle:
 *  1. [InvariantDeviceProfile.onConfigChanged] calls [beforeConfigChanged] — this enqueues the
 *     DB coordinate remap on MODEL_EXECUTOR using the *current* (pre-initGrid) portrait dims.
 *  2. IDP calls initGrid(), which resets numColumns/numRows to portrait values from XML.
 *  3. [InvariantDeviceProfile.onConfigChanged] calls [afterInitGrid] — this swaps the dims if
 *     the new rotation is landscape, so DeviceProfile is built with the correct N×M size.
 *  4. [beforeConfigChanged] calls model.forceReload() on the main thread (after enqueuing the
 *     remap) so mModelLoaded becomes false. The IDP listener's rebindCallbacks therefore posts a
 *     LoaderTask to MODEL_EXECUTOR *after* our remap task — so the reload sees transposed coords.
 */
object AnchorTransposeHook {

    private const val PREFS_NAME        = "anchor_rotation"
    private const val KEY_LAST_ROTATION = "last_rotation"

    // Authoritative portrait-canonical grid dims, captured in [afterInitGrid] from dbGridInfo BEFORE
    // the landscape swap. [beforeConfigChanged] must NOT derive these by un-swapping idp.numColumns/
    // numRows: afterInitGrid runs on EVERY onConfigChanged (including rotations while the launcher is
    // backgrounded by another app, e.g. YouTube/camera), so the live idp dims can be in an
    // orientation that no longer matches KEY_LAST_ROTATION. Un-swapping against a stale oldRotation
    // then yields the wrong portrait dims and the DB remap transposes incorrectly — the "screen
    // sometimes rotated wrong after rotating inside another app" bug. These cached values are always
    // the true portrait dims regardless of the live swap state.
    @Volatile private var portraitColsCache = 0
    @Volatile private var portraitRowsCache = 0

    // The launcher window's OWN authoritative orientation, recorded by the launcher itself from its
    // Activity.onConfigurationChanged (see [onLauncherConfigurationChanged]). This is the ONLY reliable
    // signal for "did the LAUNCHER window actually rotate", and it is correctly ordered because the
    // launcher records it before it re-triggers the transpose.
    //
    // Why not read launcher.resources.configuration.orientation directly inside beforeConfigChanged:
    // the transpose is driven by DisplayController.CHANGE_ROTATION, which fires ~75ms BEFORE the
    // launcher Activity's own onConfigurationChanged updates its config. So a direct read there sees the
    // STALE (pre-rotation) orientation and wrongly skips a real rotation. And the raw DISPLAY rotation
    // is wrong the other way: another app (YouTube/Tapo) forcing landscape rotates the DISPLAY while the
    // portrait-locked launcher window never rotates — transposing then corrupts the layout. The
    // launcher's own recorded orientation is correct in BOTH cases (auto-rotate, manual rotate-button,
    // and other-app forced rotation). Initialised to ROTATION_0-equivalent (portrait) as a safe default.
    @Volatile private var launcherIsLandscape = false
    @Volatile private var launcherOrientationKnown = false

    /**
     * Called from [com.android.launcher3.Launcher.onConfigurationChanged] with the launcher's OWN new
     * configuration — the authoritative orientation of the home window. Records it, then (if the
     * orientation actually changed the landscape state) re-drives the transpose via onConfigChanged so
     * beforeConfigChanged/afterInitGrid run with the correct, up-to-date launcher orientation.
     *
     * This is what makes a real rotation (auto-rotate OR the manual rotate-suggestion button) transpose
     * correctly, while an other-app forced rotation — which never delivers a landscape config to the
     * locked launcher — never triggers a transpose.
     */
    @JvmStatic
    fun onLauncherConfigurationChanged(context: Context, newConfig: Configuration) {
        val nowLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        val changed = !launcherOrientationKnown || nowLandscape != launcherIsLandscape
        launcherIsLandscape = nowLandscape
        launcherOrientationKnown = true
        if (changed) {
            // Re-run the config path now that the launcher orientation is authoritative and current.
            LauncherAppState.getIDP(context).onConfigChanged(context)
        }
    }

    /**
     * True only when the requested display rotation matches the LAUNCHER WINDOW's own actual
     * orientation — i.e. the launcher really is at that orientation, so transposing to it is valid.
     * Uses the launcher's recorded orientation ([onLauncherConfigurationChanged]) when available. If it
     * has not been recorded yet (the launcher hasn't fired its own config change this process), fall
     * back to READING the launcher activity's current configuration orientation directly — that read is
     * reliable EXCEPT during the launcher's own onConfigurationChanged (the ~75ms window where it lags),
     * and in this fallback the launcher is NOT rotating (if it were, onLauncherConfigurationChanged
     * would have recorded it), so the read is accurate. Only when there is no launcher activity at all
     * (true cold start, before any window exists) do we allow the transpose — the initial grid is built
     * correctly from the display and there is no existing layout to corrupt.
     *
     * NOTE: the earlier `!known -> return true` fallback was a data-loss hole: when another app (Tapo)
     * forced the DISPLAY to landscape while the launcher window stayed portrait and had not yet recorded
     * its orientation, this returned true, afterInitGrid swapped the grid to N×M, and the loader
     * rehomed every now-out-of-bounds icon — corrupting the layout with no rotation of our own.
     */
    @JvmStatic
    fun launcherWillRotate(context: Context): Boolean {
        val displayRotation = DisplayController.INSTANCE.get(context).info.rotation
        val displayIsLandscape =
            displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270
        val launcherLandscape = if (launcherOrientationKnown) {
            launcherIsLandscape
        } else {
            val launcher = Launcher.ACTIVITY_TRACKER.getCreatedContext<Launcher>()
                ?: return true // genuine cold start: no window yet, no layout to corrupt
            launcher.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        return displayIsLandscape == launcherLandscape
    }

    // ---------------------------------------------------------------------------------------------
    // Active display frame — the ONE rotation decision per config change.
    // ---------------------------------------------------------------------------------------------
    //
    // [launcherWillRotate] reads LIVE state (DisplayController's current rotation plus the launcher's
    // recorded orientation), so two calls a few milliseconds apart can legitimately disagree — a
    // rotation settling in between flips the answer. That is tolerable today because the only
    // consumer that matters is the grid-dimension swap in [afterInitGrid]. It stops being tolerable
    // once the LOADER also depends on the decision: if the read side picks portrait coordinates while
    // afterInitGrid swapped the grid to landscape N×M (or vice versa), every item whose cell exceeds
    // the active bounds is fed to LoaderCursor.checkItemPlacement, which re-homes it AND PERSISTS the
    // correction — the same permanent layout loss as the v2 data-loss bug, reached through a new door.
    //
    // So the decision is made exactly ONCE per config change, here, and every consumer reads the
    // stored value instead of re-evaluating the predicate. One decision, many consumers.
    //
    // [activeRotation] is the Surface rotation constant whose coordinate frame the workspace is
    // currently laid out in. It is ROTATION_0 whenever the launcher window is not itself rotated,
    // regardless of what the display is doing — an other-app forced landscape leaves this at the
    // launcher's own frame, which is precisely the gate that stops a foreign rotation from
    // reinterpreting our coordinates.
    @Volatile private var activeRotation = Surface.ROTATION_0

    /**
     * Recomputes and stores the active display frame. Called at the single point in
     * [InvariantDeviceProfile.initGrid] where the grid is (re)built, immediately before the
     * dimension swap, so the stored value and the swapped dims are decided together and cannot drift.
     *
     * Returns the frame that was stored.
     */
    private fun updateActiveRotation(context: Context, rotation: Int): Int {
        // Only adopt a rotated frame when the LAUNCHER WINDOW itself is at that rotation. When the
        // display is rotated by another app while our window stays put, the workspace is still laid
        // out in the launcher's own frame, so the coordinate frame must not move with the display.
        activeRotation = if (launcherWillRotate(context)) rotation else Surface.ROTATION_0
        return activeRotation
    }

    /**
     * The Surface rotation constant whose coordinate frame the workspace is currently laid out in.
     *
     * This is the authority for "which display frame are we in" for every consumer — the grid
     * dimension swap and (from Phase 2 onward) the loader's portrait→display coordinate mapping.
     * Consumers MUST read this rather than calling [launcherWillRotate] themselves, so they cannot
     * disagree with each other. See the comment on [activeRotation].
     */
    @JvmStatic
    fun getActiveRotation(): Int = activeRotation

    /**
     * True when the workspace is currently laid out in a landscape (transposed N×M) frame.
     * Convenience wrapper over [getActiveRotation] for the dimension-swap consumers.
     */
    @JvmStatic
    fun isLandscapeFrame(): Boolean =
        activeRotation == Surface.ROTATION_90 || activeRotation == Surface.ROTATION_270

    /**
     * True when a rotation should be driven by the LAUNCHER's own onConfigurationChanged rather than by
     * the DisplayController's CHANGE_ROTATION callback — which is whenever a launcher activity exists.
     * The DisplayController path fires ~75ms earlier with a stale launcher orientation; letting it also
     * run onConfigChanged produced a redundant SECOND full grid rebind per rotation (the ~0.5s reload
     * ran twice → ~1.8s + a flash of the pre-transpose grid). Suppressing it there and letting the
     * launcher drive the single rebind restores the fast, single-reload rotation.
     *
     * - Genuine rotation (auto / manual button): the launcher gets its own config change and drives the
     *   one correct transpose+rebind. Skipping the DisplayController path avoids the wasted first rebind.
     * - Other-app forced rotation (Tapo/YouTube, launcher backgrounded): the launcher window does not
     *   rotate, so there is nothing to rebuild now; onResume/reconcileOnResume handles any real delta on
     *   return. Skipping here is correct (and avoids the data-loss transpose entirely).
     * - No launcher activity at all (cold start / headless): return false so the original
     *   DisplayController-driven path still runs and builds the initial grid.
     */
    @JvmStatic
    fun launcherDrivesRotation(context: Context): Boolean =
        Launcher.ACTIVITY_TRACKER.getCreatedContext<Launcher>() is app.lawnchair.LawnchairLauncher

    /**
     * Called at the START of [InvariantDeviceProfile.onConfigChanged], before initGrid().
     * At this point numColumns/numRows reflect the *previous* orientation state.
     */
    @JvmStatic
    fun beforeConfigChanged(context: Context, idp: InvariantDeviceProfile) {
        val prefs = prefs(context)
        val oldRotation = prefs.getInt(KEY_LAST_ROTATION, Surface.ROTATION_0)
        val newRotation = DisplayController.INSTANCE.get(context).info.rotation

        if (oldRotation == newRotation) return

        // PRE-COMPUTED TRANSPOSE: once the DB is portrait-canonical, rotation must not touch it.
        //
        // The loader now derives display coordinates from the stored portrait ones on every read, so
        // the reactive rewrite below is not merely redundant — it is actively WRONG. It would write
        // display-frame coordinates back into a DB the reader treats as portrait-canonical, and the
        // next read would transpose those *again*. Measured on the 6×9 test tablet: a DB left in the
        // landscape frame while marked canonical puts 13 of 35 items out of bounds, and
        // checkItemPlacement re-homes and PERSISTS every one of them.
        //
        // The two mechanisms are mutually exclusive: either the DB tracks the display frame and is
        // rewritten on rotation (the old reactive path), or it holds one canonical frame and the
        // reader maps it (the pre-computed path). This is the switch between them.
        //
        // Skipping here removes both the DB rewrite AND the forceReload/LoaderTask below — the
        // ~0.9–1.9s that rotation used to cost. KEY_LAST_ROTATION is still advanced so the recorded
        // rotation keeps describing reality for the migration and for reconcileOnResume.
        if (PortraitCanonicalMigration.isLiveDbCanonical()) {
            prefs.edit().putInt(KEY_LAST_ROTATION, newRotation).apply()
            return
        }

        // Only transpose when the requested display rotation matches the LAUNCHER window's own
        // orientation (see launcherWillRotate). If they disagree, another app forced the display
        // rotation (YouTube/Tapo landscape) while the launcher window did not follow — transposing then
        // rewrites the DB for an orientation the grid never uses, and the loader compacts every
        // now-out-of-bounds icon and persists it, permanently destroying the layout. Do NOT advance
        // KEY_LAST_ROTATION here, so the recorded rotation keeps matching the (untouched) DB.
        //
        // Ordering note: on a genuine rotation this DisplayController-driven path fires ~75ms BEFORE the
        // launcher records its new orientation, so launcherWillRotate is still false here and we skip —
        // the transpose is then correctly re-driven from Launcher.onConfigurationChanged
        // (onLauncherConfigurationChanged) once the launcher orientation is authoritative.
        if (!launcherWillRotate(context)) return

        // Only transpose for rotations that occur while the launcher is actually visible.
        //
        // DisplayController fires CHANGE_ROTATION for EVERY display rotation, including those
        // driven by other apps while the launcher is backgrounded (e.g. YouTube going fullscreen,
        // the camera app forcing landscape). Transposing then is both wasted work and the source
        // of a data-loss race: if the process is trimmed after we advance KEY_LAST_ROTATION but
        // before the DB coordinate rewrite commits, the recorded rotation and the on-disk
        // coordinates disagree permanently. On the next load, LoaderCursor.checkItemPlacement
        // culls every item whose cellY exceeds the (now mismatched) numRows — the "bottom half of
        // a full page vanished forever" bug.
        //
        // By skipping entirely when not visible AND leaving KEY_LAST_ROTATION unchanged, the DB
        // and the recorded rotation stay consistent. When the launcher next becomes visible and a
        // rotation settles, beforeConfigChanged sees the real delta and performs the remap once,
        // correctly. afterInitGrid still swaps numColumns/numRows for the current rotation, so the
        // live grid is right regardless; only the persistent coordinate rewrite is deferred.
        val launcher = Launcher.ACTIVITY_TRACKER.getCreatedContext<Launcher>()
        if (launcher == null || !launcher.isStarted) return

        // Portrait-canonical dims. Prefer the cache captured in afterInitGrid (always the true
        // portrait dims from dbGridInfo, independent of the live swap state). Only if the cache is
        // not yet populated (no initGrid has run this process) fall back to un-swapping the live IDP
        // dims against oldRotation — correct on a cold start where they haven't drifted yet.
        val portraitCols: Int
        val portraitRows: Int
        if (portraitColsCache > 0 && portraitRowsCache > 0) {
            portraitCols = portraitColsCache
            portraitRows = portraitRowsCache
        } else {
            val wasLandscape =
                oldRotation == Surface.ROTATION_90 || oldRotation == Surface.ROTATION_270
            portraitCols = if (wasLandscape) idp.numRows else idp.numColumns
            portraitRows = if (wasLandscape) idp.numColumns else idp.numRows
        }

        // Post the DB remap to MODEL_EXECUTOR, then call forceReload() on the main thread.
        // forceReload sets mModelLoaded=false so that subsequent startLoader() calls post a
        // LoaderTask instead of binding from the stale in-memory model. The LoaderTask will be
        // enqueued AFTER our remap task and will therefore read the transposed coordinates.
        //
        // KEY_LAST_ROTATION is advanced INSIDE the executor task, AFTER transposeWorkspaceDirect
        // has committed its DB writes — not before. This makes the state update atomic with the
        // coordinate rewrite: if the process dies mid-task, the recorded rotation still matches the
        // (un-rewritten) DB, so the next load is self-consistent and no items are culled.
        MODEL_EXECUTOR.execute {
            RotationTransposeManager.transposeWorkspaceDirect(
                context, portraitCols, portraitRows, oldRotation, newRotation,
            )
            prefs.edit().putInt(KEY_LAST_ROTATION, newRotation).commit()
        }
        LauncherAppState.getInstance(context).model.forceReload()
    }

    /**
     * Called AFTER initGrid() inside [InvariantDeviceProfile.onConfigChanged].
     * At this point numColumns/numRows have been reset to the portrait XML values.
     * Swaps them if the display is in landscape so DeviceProfile uses the N×M grid.
     */
    @JvmStatic
    fun afterInitGrid(context: Context, idp: InvariantDeviceProfile, rotation: Int) {
        // At this call site idp.numColumns/numRows are still the portrait-canonical values just read
        // from dbGridInfo (initGrid sets them immediately before calling us, pre-swap). Capture them
        // as the authoritative portrait dims for beforeConfigChanged to use — see the cache comment.
        // (Always cached, regardless of the swap gate below.)
        portraitColsCache = idp.numColumns
        portraitRowsCache = idp.numRows
        // Decide the active coordinate frame ONCE, here, and store it. Every other consumer (the swap
        // below, and from Phase 2 the loader's coordinate mapping) reads the stored value via
        // [getActiveRotation] rather than re-evaluating launcherWillRotate, so they cannot disagree.
        //
        // Only swap to the landscape grid when the LAUNCHER WINDOW is actually landscape. The display
        // can be landscape because ANOTHER app forced it (YouTube/Tapo) while our locked home window
        // stays portrait; swapping then builds a landscape N×M grid for a portrait render and feeds the
        // loader a numRows/numColumns that disagrees with the stored coordinates, so checkItemPlacement
        // treats the bottom band as out-of-bounds and the layout gets compacted/persisted.
        // updateActiveRotation applies exactly that gate, collapsing a foreign rotation to ROTATION_0.
        updateActiveRotation(context, rotation)
        if (isLandscapeFrame()) {
            val tmp = idp.numColumns
            idp.numColumns = idp.numRows
            idp.numRows = tmp
        }
    }

    /**
     * Reconcile a rotation that settled while the launcher was backgrounded. Called from
     * [com.android.launcher3.Launcher.onResume].
     *
     * The DisplayController's CHANGE_ROTATION listener fires onConfigChanged for rotations that
     * happen inside other apps (YouTube/camera going landscape, then back), but beforeConfigChanged
     * defers the DB coordinate remap while the launcher isn't visible (data-loss safety). The comment
     * there assumes a later rotation event will trigger the deferred remap once visible — but if the
     * display is ALREADY at its final rotation when the launcher resumes, no further event fires, so
     * the remap never runs: the grid keeps the previous orientation's coordinates while the screen is
     * at a different rotation ("screen sometimes rotated wrong after rotating inside another app").
     *
     * This forces the reconciliation: if the persisted last-rotation differs from the current display
     * rotation now that we ARE visible, run onConfigChanged so beforeConfigChanged performs the
     * transpose with the correct old→new delta. If they already match, it is a cheap no-op.
     */
    @JvmStatic
    fun reconcileOnResume(context: Context) {
        // Only reconcile when the display rotation matches the LAUNCHER window's own orientation (its
        // recorded config). If they disagree, the mismatch is another app's forced orientation (e.g.
        // returning from YouTube/Tapo landscape while the launcher window is still portrait) — NOT a
        // rotation the launcher followed. Reconciling then would transpose for an orientation the grid
        // never adopts, whose out-of-bounds coordinates the loader compacts and persists (permanent
        // layout loss).
        if (!launcherWillRotate(context)) return
        val lastRotation = prefs(context).getInt(KEY_LAST_ROTATION, Surface.ROTATION_0)
        val currentRotation = DisplayController.INSTANCE.get(context).info.rotation
        if (lastRotation != currentRotation) {
            LauncherAppState.getIDP(context).onConfigChanged(context)
        }
    }

    /** Returns the last persisted rotation, defaulting to ROTATION_0. */
    @JvmStatic
    fun getLastRotation(context: Context): Int =
        prefs(context).getInt(KEY_LAST_ROTATION, Surface.ROTATION_0)

    /**
     * Portrait-canonical grid dimensions as (columns, rows), or null if no initGrid has run yet this
     * process.
     *
     * Callers that need the TRUE portrait grid must use this rather than reading
     * `idp.numColumns`/`idp.numRows`: those are swapped to N×M by [afterInitGrid] whenever the
     * launcher is in landscape, so a caller reading them in landscape gets the transposed pair. Any
     * coordinate math done against the swapped dims produces a wrong mapping — for the
     * portrait-canonical migration that would mean reverse-mapping the whole workspace through the
     * wrong grid and scrambling every position.
     */
    @JvmStatic
    fun getPortraitGrid(): Pair<Int, Int>? =
        if (portraitColsCache > 0 && portraitRowsCache > 0) {
            Pair(portraitColsCache, portraitRowsCache)
        } else {
            null
        }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
