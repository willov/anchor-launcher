/*
 * Copyright (C) 2026 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */
package app.anchor.rotation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import android.view.Surface
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction
import com.android.launcher3.provider.LauncherDbUtils.dropTable
import com.android.launcher3.provider.LauncherDbUtils.tableExists

/**
 * One-time migration flipping the workspace DB to **portrait-canonical** coordinates.
 *
 * ## Why this exists
 *
 * Historically Anchor stored *display* coordinates: on every rotation the DB was rewritten so the
 * stored cells matched the orientation currently on screen. That reactive rewrite is what made
 * rotation cost ~0.9–1.9s (rewrite ~all rows, then forceReload + a full LoaderTask), and it is what
 * made the coordinates orientation-dependent — the root of the data-loss class where the recorded
 * rotation and the on-disk coordinates could disagree.
 *
 * The pre-computed transpose stores coordinates in ONE canonical frame (portrait) forever, and
 * derives display coordinates at read time via [GridTransposeHelper]. Rotation then writes nothing
 * at all.
 *
 * Existing installs hold coordinates in whatever frame was last active, so they must be reverse
 * mapped to portrait exactly once. That is this class.
 *
 * ## Safety properties (all three are required, see the Pixel daily-driver constraint)
 *
 * 1. **Atomic** — the coordinate flip and the marker that records it are committed in a single
 *    [SQLiteTransaction]. A process kill either leaves the DB fully migrated and marked, or fully
 *    untouched and unmarked. It can never leave coordinates in one frame with a marker claiming
 *    another — precisely the desync that caused the historical data loss.
 * 2. **Idempotent** — gated on a marker so a repeated run is a cheap no-op. Re-running can never
 *    double-apply a rotation.
 * 3. **Recoverable** — the pre-migration rows are copied to [BACKUP_TABLE] before any mutation, so a
 *    bad outcome is one SQL statement from being undone.
 *
 * ## Why the marker lives in the DB and not in SharedPreferences
 *
 * The v2 data-loss bug was a DB/pref desync: `last_rotation` lived in prefs while the coordinates
 * lived in the DB, and a process kill between the two writes left them permanently inconsistent.
 * Putting "has this DB been converted" in a *separate* store would rebuild exactly that hazard. The
 * marker is therefore a row in a table inside the same DB, written inside the same transaction as
 * the coordinate flip, so the DB is always self-describing.
 */
object PortraitCanonicalMigration {

    private const val TAG = "AnchorRotation"

    /** Table holding the migration marker. One row, one column. */
    private const val MARKER_TABLE = "anchor_coord_frame"

    /** Pre-migration copy of [Favorites.TABLE_NAME], kept for rollback. */
    const val BACKUP_TABLE = "favorites_pre_precompute"

    /**
     * Bumped only if the canonical frame's meaning ever changes. Version 1 = "cellX/cellY/spanX/spanY
     * are portrait-canonical".
     */
    private const val CANONICAL_VERSION = 1

    /**
     * Converts the workspace to portrait-canonical coordinates if it is not already.
     *
     * @param fromRotation the frame the stored coordinates are currently in — the launcher's
     *   persisted last rotation ([AnchorTransposeHook.getLastRotation]). For a DB already in
     *   portrait ([Surface.ROTATION_0]) the coordinate pass is a no-op, but the marker is still
     *   written so later opens skip the work.
     * @param portraitCols portrait-canonical column count (never the swapped landscape value).
     * @param portraitRows portrait-canonical row count.
     * @return true if this call performed the migration, false if it was already done.
     */
    @JvmStatic
    fun migrateIfNeeded(
        context: Context,
        db: SQLiteDatabase,
        fromRotation: Int,
        portraitCols: Int,
        portraitRows: Int,
    ): Boolean {
        if (isMigrated(db)) return false

        // A grid we cannot reason about — refuse rather than guess. Leaving the DB unmigrated is
        // always safe: the marker stays absent and the next open retries with better values.
        if (portraitCols <= 0 || portraitRows <= 0) {
            Log.w(TAG, "PortraitCanonicalMigration: skipped, invalid grid " +
                "${portraitCols}x$portraitRows")
            return false
        }

        Log.d(TAG, "PortraitCanonicalMigration: converting from rotation $fromRotation " +
            "(portrait grid ${portraitCols}x$portraitRows)")

        try {
            SQLiteTransaction(db).use { t ->
                // Rollback copy FIRST, inside the same transaction, so it is either present with the
                // pre-migration rows or absent along with the migration itself.
                backupFavorites(db)

                val converted = convertToPortraitCanonical(db, fromRotation, portraitCols, portraitRows)
                writeMarker(db)

                t.commit()
                Log.d(TAG, "PortraitCanonicalMigration: done, $converted item(s) converted")
            }
            return true
        } catch (e: Exception) {
            // Transaction rolled back; the marker was never written, so the next open retries.
            Log.e(TAG, "PortraitCanonicalMigration: FAILED, DB left untouched", e)
            return false
        }
    }

    /**
     * Reverse-maps every desktop item from [fromRotation] back to the portrait frame. Returns the
     * number of rows actually changed.
     *
     * Only [Favorites.CONTAINER_DESKTOP] items are touched: items inside folders use
     * folder-relative coordinates that do not rotate, and the hotseat is not used by Anchor.
     */
    private fun convertToPortraitCanonical(
        db: SQLiteDatabase,
        fromRotation: Int,
        portraitCols: Int,
        portraitRows: Int,
    ): Int {
        // Portrait and 180 share the grid dimensions, and portrait is already canonical, so only a
        // genuine non-zero frame needs rewriting. 180 DOES need it — it is a real flip, not a no-op.
        if (fromRotation == Surface.ROTATION_0) return 0

        var changed = 0
        db.query(
            Favorites.TABLE_NAME,
            arrayOf(Favorites._ID, Favorites.CELLX, Favorites.CELLY, Favorites.SPANX, Favorites.SPANY),
            "${Favorites.CONTAINER} = ?",
            arrayOf(Favorites.CONTAINER_DESKTOP.toString()),
            null, null, null,
        ).use { c ->
            val idxId = c.getColumnIndexOrThrow(Favorites._ID)
            val idxCellX = c.getColumnIndexOrThrow(Favorites.CELLX)
            val idxCellY = c.getColumnIndexOrThrow(Favorites.CELLY)
            val idxSpanX = c.getColumnIndexOrThrow(Favorites.SPANX)
            val idxSpanY = c.getColumnIndexOrThrow(Favorites.SPANY)

            while (c.moveToNext()) {
                val id = c.getLong(idxId)
                val cellX = c.getInt(idxCellX)
                val cellY = c.getInt(idxCellY)
                val spanX = c.getInt(idxSpanX)
                val spanY = c.getInt(idxSpanY)

                // Spans first: the position reverse is span-aware and needs the DISPLAY spans, which
                // is what the row currently holds.
                val (portraitSpanX, portraitSpanY) =
                    GridTransposeHelper.reverseSpanToPortrait(spanX, spanY, fromRotation)

                // Span-aware reverse: for a multi-cell widget the portrait top-left is a DIFFERENT
                // corner of the bounding box than the rotated top-left. Using the plain cell reverse
                // here would push cellY+spanY past numRows and get the widget re-homed by
                // LoaderCursor.checkItemPlacement.
                val (portraitCol, portraitRow) = GridTransposeHelper.reverseWidgetPositionToPortrait(
                    cellX, cellY, spanX, spanY, portraitCols, portraitRows, fromRotation,
                )

                if (portraitCol != cellX || portraitRow != cellY ||
                    portraitSpanX != spanX || portraitSpanY != spanY
                ) {
                    db.execSQL(
                        "UPDATE ${Favorites.TABLE_NAME} SET ${Favorites.CELLX} = ?, " +
                            "${Favorites.CELLY} = ?, ${Favorites.SPANX} = ?, ${Favorites.SPANY} = ? " +
                            "WHERE ${Favorites._ID} = ?",
                        arrayOf<Any>(portraitCol, portraitRow, portraitSpanX, portraitSpanY, id),
                    )
                    changed++
                }
            }
        }
        return changed
    }

    /** Copies the live favorites rows to [BACKUP_TABLE], replacing any previous copy. */
    private fun backupFavorites(db: SQLiteDatabase) {
        dropTable(db, BACKUP_TABLE)
        db.execSQL("CREATE TABLE $BACKUP_TABLE AS SELECT * FROM ${Favorites.TABLE_NAME}")
    }

    /** True when this DB has already been converted to portrait-canonical coordinates. */
    @JvmStatic
    fun isMigrated(db: SQLiteDatabase): Boolean {
        if (!tableExists(db, MARKER_TABLE)) return false
        return db.rawQuery("SELECT version FROM $MARKER_TABLE LIMIT 1", null).use { c ->
            c.moveToFirst() && c.getInt(0) >= CANONICAL_VERSION
        }
    }

    private fun writeMarker(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS $MARKER_TABLE (version INTEGER PRIMARY KEY)")
        db.execSQL("DELETE FROM $MARKER_TABLE")
        db.execSQL("INSERT INTO $MARKER_TABLE (version) VALUES ($CANONICAL_VERSION)")
    }

    /**
     * Restores the pre-migration rows from [BACKUP_TABLE] and clears the marker, returning the DB to
     * its state before [migrateIfNeeded] ran. Debug/recovery only — not called in normal operation.
     *
     * @return true if a backup existed and was restored.
     */
    @JvmStatic
    fun rollback(db: SQLiteDatabase): Boolean {
        if (!tableExists(db, BACKUP_TABLE)) return false
        SQLiteTransaction(db).use { t ->
            db.execSQL("DELETE FROM ${Favorites.TABLE_NAME}")
            db.execSQL("INSERT INTO ${Favorites.TABLE_NAME} SELECT * FROM $BACKUP_TABLE")
            dropTable(db, MARKER_TABLE)
            t.commit()
        }
        Log.w(TAG, "PortraitCanonicalMigration: rolled back to pre-migration coordinates")
        return true
    }
}
