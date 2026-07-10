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
 * Host-side (JVM) unit tests for [GridResizePlacement.rehomeCell] — the pure placement decision that
 * backs `LoaderCursor.rehomeOutOfBoundsItem`. This is the fix for the rotation data-loss bug: a
 * transpose could leave a workspace item's stored cell out of the loaded grid bounds, and the old
 * loader silently deleted it. Now it is clamped/re-homed into a valid cell instead. These tests lock
 * that behaviour without a device.
 *
 * `LoaderCursor` delegates to exactly this function, so production runs the tested code.
 */
class GridResizeRehomeTest {

    private fun rehome(
        cellX: Int, cellY: Int,
        spanX: Int = 1, spanY: Int = 1,
        countX: Int, countY: Int,
        occupied: Set<Pair<Int, Int>> = emptySet(),
    ) = GridResizePlacement.rehomeCell(cellX, cellY, spanX, spanY, countX, countY, occupied)

    @Test
    fun inBoundsAndVacant_isUnchanged() {
        // Already valid — clamp is a no-op, cell is returned as-is.
        assertThat(rehome(2, 3, countX = 5, countY = 9)).isEqualTo(2 to 3)
    }

    @Test
    fun bottomHalfOverflow_clampsUpIntoGrid() {
        // The core bug: a full-portrait item at row 8 loaded into a grid with only 5 rows.
        // Old behaviour deleted it. Now it clamps to the last valid row (countY-1 = 4).
        assertThat(rehome(2, 8, countX = 5, countY = 5)).isEqualTo(2 to 4)
    }

    @Test
    fun columnOverflow_clampsLeftIntoGrid() {
        assertThat(rehome(7, 1, countX = 5, countY = 9)).isEqualTo(4 to 1)
    }

    @Test
    fun negativeCoords_clampToZero() {
        assertThat(rehome(-3, -1, countX = 5, countY = 9)).isEqualTo(0 to 0)
    }

    @Test
    fun clampedCellOccupied_findsNextVacantRowMajor() {
        // Clamp target (2,4) is taken; row-major scan finds the first free cell (0,0).
        assertThat(rehome(2, 8, countX = 5, countY = 5, occupied = setOf(2 to 4)))
            .isEqualTo(0 to 0)
    }

    @Test
    fun clampedCellOccupied_skipsOccupiedPrefix() {
        // Clamp target for (9,9) in a 5x5 grid is (4,4). Occupy it plus the row-major prefix
        // (0,0) and (1,0), so the scan must skip those and land on the first free cell (2,0).
        assertThat(rehome(9, 9, countX = 5, countY = 5, occupied = setOf(4 to 4, 0 to 0, 1 to 0)))
            .isEqualTo(2 to 0)
    }

    @Test
    fun genuinelyFullScreen_returnsNull() {
        // Every cell of a 2x2 grid occupied → cannot place → null (caller then discards).
        val full = setOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)
        assertThat(rehome(5, 5, countX = 2, countY = 2, occupied = full)).isNull()
    }

    @Test
    fun spanLargerThanGrid_returnsNull() {
        // A 3-wide item can never fit a 2-column grid.
        assertThat(rehome(0, 0, spanX = 3, spanY = 1, countX = 2, countY = 5)).isNull()
    }

    @Test
    fun multiCellWidget_clampsSoWholeSpanFits() {
        // 2x2 widget whose stored top-left (4,8) would overflow: top-left clamps to
        // (countX-spanX, countY-spanY) = (3,3) so all four cells stay in bounds.
        assertThat(rehome(4, 8, spanX = 2, spanY = 2, countX = 5, countY = 5)).isEqualTo(3 to 3)
    }

    @Test
    fun multiCellWidget_scanRespectsSpanCollision() {
        // 2x2 widget, clamp target (3,3) collides with an occupied cell inside its footprint.
        // Row-major scan must find a 2x2 region with no occupied cell → (0,0).
        assertThat(
            rehome(4, 8, spanX = 2, spanY = 2, countX = 5, countY = 5, occupied = setOf(4 to 4)),
        ).isEqualTo(0 to 0)
    }
}
