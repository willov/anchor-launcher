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
}
