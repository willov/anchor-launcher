/*
 * Copyright (C) 2026 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */
package com.android.launcher3.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Host-side (JVM) unit tests for [GridResizePlacement.dropEmptyEdges] — the pure per-axis remap that
 * backs Anchor's spatial-preserving grid resize. These run with `./gradlew :test...UnitTest` (no
 * device / instrumented harness) and lock the behaviour that regressed twice on-device:
 *  - over-compaction (rank-collapse) pulling items further than the removed line count, and
 *  - a bottom-right item reflowing up to the top-left when an axis was full.
 *
 * For the full DB-level migration the matching (currently dormant) instrumented tests live in
 * tests/multivalentTests/.../GridSizeMigrationTest.kt.
 */
class GridResizePlacementTest {

    private fun map(occupied: Set<Int>, target: Int) =
        GridResizePlacement.dropEmptyEdges(occupied, target)

    @Test
    fun emptyAxisReturnsEmptyMap() {
        assertThat(map(emptySet(), 4)).isEmpty()
    }

    @Test
    fun fitsAlready_isIdentity_preservingInteriorGaps() {
        // occupied {0,1,3} within 4 lines: nothing overflows → keep exact (gap at 2 stays).
        assertThat(map(setOf(0, 1, 3), 4)).containsExactlyEntriesIn(mapOf(0 to 0, 1 to 1, 3 to 3))
    }

    @Test
    fun growIsIdentity() {
        assertThat(map(setOf(0, 3), 6)).containsExactlyEntriesIn(mapOf(0 to 0, 3 to 3))
    }

    @Test
    fun shrink_dropsOneEdgeEmpty_keepsInteriorGap() {
        // {0,2,4} → 4 lines: need=1, empties {1,3}, bottom-first drops 3.
        // 0→0, 2→2, 4→3 (one dropped line below it). Interior gap at 1 preserved.
        assertThat(map(setOf(0, 2, 4), 4)).containsExactlyEntriesIn(mapOf(0 to 0, 2 to 2, 4 to 3))
    }

    @Test
    fun shrink_singleOffEdgeItem_slidesInByRemovedCount() {
        // lone item at 4, shrink to 4 lines → 3 (NOT 0). The repro for the old rank-collapse bug.
        assertThat(map(setOf(4), 4)).containsExactlyEntriesIn(mapOf(4 to 3))
    }

    @Test
    fun shrink_alternatesBottomThenTop_keepingBottomItemLow() {
        // The bottom-right repro on one axis: {0, 8} → 7 lines. need=2, empties 1..7.
        // Alternate bottom-first: drop 7, then 1 → {1,7}. 0→0; 8 has 2 dropped below → 6 (new bottom).
        assertThat(map(setOf(0, 8), 7)).containsExactlyEntriesIn(mapOf(0 to 0, 8 to 6))
    }

    @Test
    fun shrink_fullAxis_clampsOverflowToLastIndex() {
        // {0,1,2,3,4} → 4 lines: no empty line to drop. 0..3 keep; 4 clamps to 3 (collides — caller
        // drops the loser, but the MAP itself just clamps).
        assertThat(map(setOf(0, 1, 2, 3, 4), 4))
            .containsExactlyEntriesIn(mapOf(0 to 0, 1 to 1, 2 to 2, 3 to 3, 4 to 3))
    }

    @Test
    fun realDeviceRepro_rowsAndCols_5x11_to_4x9() {
        // The exact on-device layout (screen 0). Columns occupied {0,1,2,3,4}; rows {0,1,3,5,8,10}.
        // Cols 5→4: no empty col → 4 clamps to 3 (Gmail shifts left). Rows 11→9: drop {9,3}-ish band.
        val cols = map(setOf(0, 1, 2, 3, 4), 4)
        val rows = map(setOf(0, 1, 3, 5, 8, 10), 9)
        // Gmail source (4,10) → (3,8): bottom-right corner of the new 4×9 grid.
        assertThat(cols[4]).isEqualTo(3)
        assertThat(rows[10]).isEqualTo(8)
        // Top-left folder (0,0) stays.
        assertThat(cols[0]).isEqualTo(0)
        assertThat(rows[0]).isEqualTo(0)
        // Cluster rows compact up: 3→2, 5→4, 8→7 (matches the verified DB diff).
        assertThat(rows[3]).isEqualTo(2)
        assertThat(rows[5]).isEqualTo(4)
        assertThat(rows[8]).isEqualTo(7)
    }
}
