package app.anchor.rotation

import android.content.Context
import android.content.SharedPreferences
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
    fun afterInitGrid(idp: InvariantDeviceProfile, rotation: Int) {
        // At this call site idp.numColumns/numRows are still the portrait-canonical values just read
        // from dbGridInfo (initGrid sets them immediately before calling us, pre-swap). Capture them
        // as the authoritative portrait dims for beforeConfigChanged to use — see the cache comment.
        portraitColsCache = idp.numColumns
        portraitRowsCache = idp.numRows
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
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

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
