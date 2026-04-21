package app.anchor.rotation

import android.content.ContentValues
import android.content.Context
import android.provider.BaseColumns
import android.view.Surface
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites.CELLX
import com.android.launcher3.LauncherSettings.Favorites.CELLY
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP
import com.android.launcher3.LauncherSettings.Favorites.SPANX
import com.android.launcher3.LauncherSettings.Favorites.SPANY
import com.android.launcher3.util.DisplayController
import com.android.launcher3.util.Executors.MODEL_EXECUTOR

/**
 * Listens for device rotation and remaps workspace item coordinates so icons stay at the same
 * physical screen position they occupied before rotation.
 *
 * This class is kept for use as a standalone listener (e.g. in tests or legacy attach path).
 * The primary production path goes through [AnchorTransposeHook] which is called directly from
 * [com.android.launcher3.InvariantDeviceProfile.onConfigChanged].
 *
 * Coordinate convention: the DB always stores *display* coordinates for the current orientation.
 * On rotation, we reverse-map through portrait and forward-map to the new orientation in one pass.
 */
class RotationTransposeManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun attach() {
        DisplayController.INSTANCE.get(context).addChangeListener { _, info, flags ->
            if (flags and DisplayController.CHANGE_ROTATION == 0) return@addChangeListener
            val newRotation = info.rotation
            val oldRotation = prefs.getInt(KEY_LAST_ROTATION, Surface.ROTATION_0)
            if (newRotation == oldRotation) return@addChangeListener
            prefs.edit().putInt(KEY_LAST_ROTATION, newRotation).apply()

            val idp = InvariantDeviceProfile.INSTANCE[context]
            MODEL_EXECUTOR.execute {
                transposeWorkspaceDirect(context, idp.numColumns, idp.numRows, oldRotation, newRotation)
                LauncherAppState.getInstance(context).model.forceReload()
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "anchor_rotation"
        private const val KEY_LAST_ROTATION = "last_rotation"

        /**
         * Remaps all workspace item coordinates from [fromRotation] to [toRotation].
         * Caller is responsible for running this on MODEL_EXECUTOR and calling forceReload() after.
         */
        @JvmStatic
        fun transposeWorkspaceDirect(
            context: Context,
            portraitCols: Int,
            portraitRows: Int,
            fromRotation: Int,
            toRotation: Int,
        ) {
            val dbController = LauncherAppState.getInstance(context).model.modelDbController

            val cursor = dbController.query(
                arrayOf(BaseColumns._ID, CELLX, CELLY, SPANX, SPANY),
                "$CONTAINER = ?",
                arrayOf(CONTAINER_DESKTOP.toString()),
                null,
            )

            cursor.use { c ->
                val idxId    = c.getColumnIndexOrThrow(BaseColumns._ID)
                val idxCellX = c.getColumnIndexOrThrow(CELLX)
                val idxCellY = c.getColumnIndexOrThrow(CELLY)
                val idxSpanX = c.getColumnIndexOrThrow(SPANX)
                val idxSpanY = c.getColumnIndexOrThrow(SPANY)

                while (c.moveToNext()) {
                    val id    = c.getLong(idxId)
                    val cellX = c.getInt(idxCellX)
                    val cellY = c.getInt(idxCellY)
                    val spanX = c.getInt(idxSpanX)
                    val spanY = c.getInt(idxSpanY)

                    val (portraitCol, portraitRow) = GridTransposeHelper.reverseMapToPortrait(
                        cellX, cellY, portraitCols, portraitRows, fromRotation,
                    )
                    val (newCellX, newCellY) = GridTransposeHelper.remapCoordinates(
                        portraitCol, portraitRow, portraitCols, portraitRows, toRotation,
                    )

                    val (portraitSpanX, portraitSpanY) = GridTransposeHelper.reverseSpanToPortrait(
                        spanX, spanY, fromRotation,
                    )
                    val (newSpanX, newSpanY) = GridTransposeHelper.spanForRotation(
                        portraitSpanX, portraitSpanY, toRotation,
                    )

                    if (newCellX != cellX || newCellY != cellY || newSpanX != spanX || newSpanY != spanY) {
                        val values = ContentValues(4).apply {
                            put(CELLX, newCellX)
                            put(CELLY, newCellY)
                            put(SPANX, newSpanX)
                            put(SPANY, newSpanY)
                        }
                        dbController.update(values, "${BaseColumns._ID} = ?", arrayOf(id.toString()))
                    }
                }
            }
        }
    }
}
