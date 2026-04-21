package app.anchor.rotation

import android.content.Context
import android.content.SharedPreferences
import android.view.Surface
import com.android.launcher3.InvariantDeviceProfile
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

        // Derive portrait-canonical dims from the CURRENT IDP state.
        // If we were previously in landscape the dims are already swapped — un-swap.
        val wasLandscape = oldRotation == Surface.ROTATION_90 || oldRotation == Surface.ROTATION_270
        val portraitCols = if (wasLandscape) idp.numRows    else idp.numColumns
        val portraitRows = if (wasLandscape) idp.numColumns else idp.numRows

        prefs.edit().putInt(KEY_LAST_ROTATION, newRotation).apply()

        // Post the DB remap to MODEL_EXECUTOR first, then call forceReload() on the main thread.
        // forceReload sets mModelLoaded=false so that subsequent startLoader() calls post a
        // LoaderTask instead of binding from the stale in-memory model. The LoaderTask will be
        // enqueued AFTER our remap task and will therefore read the transposed coordinates.
        MODEL_EXECUTOR.execute {
            RotationTransposeManager.transposeWorkspaceDirect(
                context, portraitCols, portraitRows, oldRotation, newRotation,
            )
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
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val tmp = idp.numColumns
            idp.numColumns = idp.numRows
            idp.numRows = tmp
        }
    }

    /** Returns the last persisted rotation, defaulting to ROTATION_0. */
    @JvmStatic
    fun getLastRotation(context: Context): Int =
        prefs(context).getInt(KEY_LAST_ROTATION, Surface.ROTATION_0)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
