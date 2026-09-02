package app.anchor.rotation

import android.content.ContentValues
import android.content.Context
import android.provider.BaseColumns
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites.CELLX
import com.android.launcher3.LauncherSettings.Favorites.CELLY
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP
import com.android.launcher3.LauncherSettings.Favorites.SPANX
import com.android.launcher3.LauncherSettings.Favorites.SPANY

/**
 * Remaps workspace item coordinates in the DB so icons stay at the same physical screen position
 * across rotation.
 *
 * Called from [AnchorTransposeHook.beforeConfigChanged] for the pre-precompute-transpose reactive
 * rewrite path (a DB that has not yet been converted to portrait-canonical storage). Once the DB is
 * portrait-canonical this path is not used — see [PortraitCanonicalMigration] and [CoordFrame].
 *
 * Coordinate convention: the DB stores *display* coordinates for the current orientation. On
 * rotation, we reverse-map through portrait and forward-map to the new orientation in one pass.
 */
object RotationTransposeManager {

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

                // Reverse display spans to portrait-canonical spans first (just a swap for
                // 90°/270°). These are needed by the span-aware position reverse below.
                val (portraitSpanX, portraitSpanY) = GridTransposeHelper.reverseSpanToPortrait(
                    spanX, spanY, fromRotation,
                )

                // Span-aware reverse: recovers the portrait top-left corner of the widget. A plain
                // single-cell reverse rotation would only be correct for 1×1 items — for multi-cell
                // widgets the portrait top-left maps to a *different* corner of the bounding box in
                // the rotated orientation, causing cellY+spanY > numRows and checkItemPlacement
                // removing the widget from the DB.
                val (portraitCol, portraitRow) = GridTransposeHelper.reverseWidgetPositionToPortrait(
                    cellX, cellY, spanX, spanY, portraitCols, portraitRows, fromRotation,
                )

                // Span-aware forward mapping to the new orientation.
                val (newCellX, newCellY) = GridTransposeHelper.remapWidgetPosition(
                    portraitCol, portraitRow, portraitSpanX, portraitSpanY,
                    portraitCols, portraitRows, toRotation,
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
