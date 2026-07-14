/*
 * Copyright (C) 2026 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.anchor.grid

import android.content.Context
import com.android.launcher3.CellLayout
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import java.util.function.Consumer

/**
 * "Populate grid" preview helper. When enabled, fills the empty cells of each grid-preview screen
 * with sample icons so the user can visually judge a grid shape before committing — purely for the
 * settings preview, never persisted.
 *
 * Architecture ("clone, populate, drop"): the synthetic items are CLONES of the user's own bound
 * workspace icons (so their bitmaps are already loaded — no icon-loading code, no LauncherApps
 * query), placed into the empty cells with new cell coordinates. They are handed straight to the
 * renderer's inflate callback and never written to the model or the database. When the preview is
 * dismissed the clones are garbage-collected. The real workspace is untouched.
 *
 * The enabled flag is a render-only toggle owned by the grid-preferences screen; it is read by the
 * core [com.android.launcher3.preview.LauncherPreviewRenderer] at bind time via [isEnabled].
 */
object AnchorPreviewPopulator {

    @Volatile
    private var enabled = false

    @JvmStatic
    fun isEnabled(): Boolean = enabled

    /** Toggle population for subsequently-created previews. Render-only; nothing is persisted. */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /**
     * Fill every empty cell of each preview screen with a clone of one of the bound on-screen items.
     *
     * @param screens     screenId → CellLayout for the preview (already populated with real items)
     * @param boundItems  the real items just bound onto these screens (sample source)
     * @param inflateAndAdd the renderer's per-item inflate-and-place callback
     */
    @JvmStatic
    fun fillEmptyCells(
        screens: Map<Int, CellLayout>,
        boundItems: List<ItemInfo>,
        inflateAndAdd: Consumer<ItemInfo>,
        context: Context,
    ) {
        // Sample pool: real app icons to fill empty cells. Only items with a FULL-RES bitmap qualify
        // — folder contents (and items after a model reload) often carry BitmapInfo.LOW_RES_INFO,
        // which renders as a flat solid-colour circle (the "solid circles" bug). Top-level placed
        // icons are rendered and thus full-res, so prefer those. If none qualify, upgrade what we
        // have via the IconCache so we still show real icons rather than placeholders.
        val iconCache = com.android.launcher3.LauncherAppState.getInstance(context).iconCache
        val pool = ArrayList<WorkspaceItemInfo>()
        for (item in boundItems) {
            when (item) {
                is WorkspaceItemInfo -> if (!item.bitmap.isNullOrLowRes) pool.add(item)
                is FolderInfo -> for (c in item.getAppContents()) if (!c.bitmap.isNullOrLowRes) pool.add(c)
            }
        }
        if (pool.isEmpty()) {
            // Fallback: upgrade a few items to full-res so we never render solid-colour placeholders.
            for (item in boundItems) {
                val src = when (item) {
                    is WorkspaceItemInfo -> item
                    is FolderInfo -> item.getAppContents().firstOrNull()
                    else -> null
                } ?: continue
                val upgraded = WorkspaceItemInfo(src)
                runCatching {
                    iconCache.getTitleAndIcon(
                        upgraded,
                        com.android.launcher3.icons.cache.CacheLookupFlag.DEFAULT_LOOKUP_FLAG,
                    )
                }
                if (!upgraded.bitmap.isNullOrLowRes) pool.add(upgraded)
                if (pool.size >= 12) break
            }
        }
        if (pool.isEmpty()) return // nothing usable — leave empty rather than show placeholders
        pool.shuffle()

        // Occupancy uses CellLayout.isOccupied() — the ACTUAL added views — which is authoritative
        // because every real item (icons, folders) was inflated+added with markCellsAsOccupied
        // BEFORE this runs. An earlier model-coordinate occupancy map was tried but it reserved
        // cells for items bound to OTHER screens / items that never render here, leaving a
        // real-looking cell like (0,0) reserved-but-empty. View occupancy avoids that.
        var poolIndex = 0
        for ((screenId, cellLayout) in screens) {
            // Occupancy from the ACTUAL child views' layout params. CellLayout.isOccupied() is NOT
            // reliably populated in the preview renderer (views get added without marking mOccupied
            // — verified: childCount=3 but isOccupied=0 everywhere), which made the populator overlay
            // sample icons on top of real ones. Reading each child's CellLayoutLayoutParams cellX/Y +
            // span is authoritative.
            val occupied = HashSet<Long>()
            val cellChildren = cellLayout.shortcutsAndWidgets
            for (i in 0 until cellChildren.childCount) {
                val lp = cellChildren.getChildAt(i).layoutParams
                if (lp is com.android.launcher3.celllayout.CellLayoutLayoutParams) {
                    for (dx in 0 until maxOf(1, lp.cellHSpan)) {
                        for (dy in 0 until maxOf(1, lp.cellVSpan)) {
                            occupied.add((lp.cellX + dx).toLong() shl 32 or (lp.cellY + dy).toLong())
                        }
                    }
                }
            }
            for (y in 0 until cellLayout.countY) {
                for (x in 0 until cellLayout.countX) {
                    if (occupied.contains(x.toLong() shl 32 or y.toLong())) continue
                    val source = pool[poolIndex % pool.size]
                    poolIndex++
                    val clone = WorkspaceItemInfo(source).apply {
                        container = CONTAINER_DESKTOP
                        this.screenId = screenId
                        cellX = x
                        cellY = y
                        spanX = 1
                        spanY = 1
                    }
                    inflateAndAdd.accept(clone)
                    occupied.add(x.toLong() shl 32 or y.toLong())
                }
            }
        }
    }
}
