/*
 * Copyright (C) 2026 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */
package com.android.launcher3.model

/**
 * Pure, Android-free placement math for Anchor's spatial-preserving grid resize.
 *
 * Extracted from [GridSizeMigrationLogic] so the behaviour that twice regressed (over-compaction,
 * then bottom-right items reflowing top-left) can be locked down by fast JVM unit tests with no
 * device or instrumented-test harness. [GridSizeMigrationLogic.dropEmptyEdgesMap] delegates here, so
 * production runs exactly this code.
 */
object GridResizePlacement {

    /**
     * Builds an old-index -> new-index remap for ONE axis when the grid is resized to [target] lines.
     *
     * Policy:
     *  - If the occupied content already fits within [target], the map is the identity — interior
     *    gaps are preserved, nothing moves.
     *  - If it overflows by `need` lines, drop `need` EMPTY lines, chosen by ALTERNATING between the
     *    high end (bottom/right) and the low end (top/left) of the empty set, bottom/right first.
     *    Each occupied line then shifts toward 0 by the number of dropped lines below it. This keeps
     *    content above the removed band top/left-anchored and content below it bottom/right-anchored.
     *  - A line still past the edge afterwards (a genuinely full axis, no empty line left to drop) is
     *    CLAMPED to the last valid index ([target] - 1). The caller decides what to do when two lines
     *    clamp onto the same index (Anchor drops the loser rather than relocating it).
     *
     * @param occupied the set of occupied line indices on this axis (columns or rows)
     * @param target the new number of lines on this axis (must be >= 1)
     * @return a map from each occupied index to its new index
     */
    fun dropEmptyEdges(occupied: Set<Int>, target: Int): Map<Int, Int> {
        if (occupied.isEmpty()) return emptyMap()
        val maxExtent = occupied.max() + 1 // exclusive extent of occupied content
        val need = maxExtent - target
        if (need <= 0) {
            // Fits already — keep exact indices (preserve interior gaps).
            return occupied.associateWith { it }
        }
        // Empty lines within [0, maxExtent), ascending. Pick `need` by alternating from the high end
        // (bottom/right) then the low end (top/left), bottom/right first.
        val emptyLines = (0 until maxExtent).filter { it !in occupied }
        val dropped = sortedSetOf<Int>()
        var lo = 0
        var hi = emptyLines.size - 1
        var fromBottom = true
        while (dropped.size < need && lo <= hi) {
            if (fromBottom) {
                dropped.add(emptyLines[hi]); hi--
            } else {
                dropped.add(emptyLines[lo]); lo++
            }
            fromBottom = !fromBottom
        }
        // Shift toward 0 by the count of dropped lines below, then clamp inside the new bound so a
        // still-overflowing line (full axis) lands at the edge instead of being relocated.
        return occupied.associateWith { line ->
            (line - dropped.count { it < line }).coerceAtMost(target - 1)
        }
    }

    /**
     * Pure placement decision for re-homing a workspace item whose stored cell is out of the current
     * grid bounds — the math behind `LoaderCursor.rehomeOutOfBoundsItem`, extracted so it can be
     * locked down by JVM unit tests (see [GridResizePlacement] rationale above).
     *
     * Backs the fix for the rotation data-loss bug: a transpose could leave DB coordinates in a frame
     * that didn't match the loaded grid, so an item's cellY exceeded numRows. Instead of silently
     * deleting it (the old `checkItemPlacement` behaviour), we clamp/re-home it into a valid cell.
     *
     * Policy:
     *  1. If the item's span is larger than the grid itself, it can never be placed → return null.
     *  2. Clamp the top-left so the span fits: `x in [0, countX - spanX]`, `y in [0, countY - spanY]`.
     *  3. If that clamped region is vacant, use it.
     *  4. Otherwise scan row-major for the first vacant region that fits the span (matches
     *     `GridOccupancy.findVacantCell`). Return null only if the screen is genuinely full.
     *
     * @param cellX/cellY the item's stored (possibly out-of-bounds) top-left
     * @param spanX/spanY the item's size in cells
     * @param countX/countY the current grid dimensions
     * @param occupied cells already taken on this screen, as (col, row) pairs
     * @return the re-homed (col, row) top-left, or null if the item cannot be placed at all
     */
    fun rehomeCell(
        cellX: Int, cellY: Int,
        spanX: Int, spanY: Int,
        countX: Int, countY: Int,
        occupied: Set<Pair<Int, Int>>,
    ): Pair<Int, Int>? {
        if (spanX > countX || spanY > countY || spanX < 1 || spanY < 1) return null

        fun regionVacant(x: Int, y: Int): Boolean {
            for (dx in 0 until spanX) {
                for (dy in 0 until spanY) {
                    if ((x + dx) to (y + dy) in occupied) return false
                }
            }
            return true
        }

        val clampedX = cellX.coerceIn(0, countX - spanX)
        val clampedY = cellY.coerceIn(0, countY - spanY)
        if (regionVacant(clampedX, clampedY)) return clampedX to clampedY

        // Row-major scan for the first vacant region that fits.
        for (y in 0..(countY - spanY)) {
            for (x in 0..(countX - spanX)) {
                if (regionVacant(x, y)) return x to y
            }
        }
        return null
    }
}
