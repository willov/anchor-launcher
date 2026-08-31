/*
 * Copyright (C) 2026 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */
package app.anchor.rotation

import android.view.Surface
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Host-side unit tests for [GridTransposeHelper] — the pure transpose math behind spatial
 * stability. Locks the invariant the whole product rests on: an item keeps the same physical glass
 * position in every orientation, and the four rotations are four DISTINCT coordinate frames.
 *
 * These tests exist because two of the four frames cannot be exercised on a phone:
 *  - ROTATION_180 is never delivered to a portrait-capable app (`mAllowAllRotations=false` on the
 *    Pixel test device), so upside-down is unreachable by physically turning the device.
 *  - The span-aware widget mapping is only exercised by a multi-cell item in a specific corner.
 * The reachable frames (0/90/270) were additionally verified end-to-end against a real device DB;
 * see the pre-computed-transpose work. This file is the safety net for the rest.
 *
 * Run: `./gradlew testLawnWithQuickstepPlayDebugUnitTest --tests "app.anchor.rotation.GridTransposeHelperTest"`
 */
class GridTransposeHelperTest {

    // The device the end-to-end baseline was measured on: 5 columns × 11 rows in portrait.
    private val cols = 5
    private val rows = 11

    private val allRotations = listOf(
        Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270,
    )

    // ---------------------------------------------------------------------------------------------
    // Round-trip: forward then reverse must recover the original cell, in every frame.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun remapThenReverse_recoversOriginalCell_forEveryRotationAndCell() {
        for (rotation in allRotations) {
            for (col in 0 until cols) {
                for (row in 0 until rows) {
                    val (dCol, dRow) = GridTransposeHelper.remapCoordinates(
                        col, row, cols, rows, rotation,
                    )
                    val (backCol, backRow) = GridTransposeHelper.reverseMapToPortrait(
                        dCol, dRow, cols, rows, rotation,
                    )
                    assertThat("r$rotation ($col,$row) -> ($dCol,$dRow) -> ($backCol,$backRow)")
                        .isEqualTo("r$rotation ($col,$row) -> ($dCol,$dRow) -> ($col,$row)")
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The four frames are genuinely distinct — the core of the "icons stay at a static glass pixel"
    // model. If any two rotations produced the same cell for the same item, one of the four
    // orientations would be laying icons out in the wrong place.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun allFourRotations_produceDistinctCells_forAnAsymmetricItem() {
        // (1,2) is asymmetric in both axes, so no two frames can coincide by symmetry.
        val mapped = allRotations.map { GridTransposeHelper.remapCoordinates(1, 2, cols, rows, it) }
        assertThat(mapped.toSet()).hasSize(4)
    }

    @Test
    fun rotation180_isNotIdentity_andIsNotEitherLandscape() {
        val portrait = GridTransposeHelper.remapCoordinates(1, 2, cols, rows, Surface.ROTATION_0)
        val flipped = GridTransposeHelper.remapCoordinates(1, 2, cols, rows, Surface.ROTATION_180)
        val land90 = GridTransposeHelper.remapCoordinates(1, 2, cols, rows, Surface.ROTATION_90)
        val land270 = GridTransposeHelper.remapCoordinates(1, 2, cols, rows, Surface.ROTATION_270)

        // 180 is a real flip, not a no-op: this is the branch that cannot be reached on the phone.
        assertThat(flipped).isNotEqualTo(portrait)
        assertThat(flipped).isNotEqualTo(land90)
        assertThat(flipped).isNotEqualTo(land270)
        // (cols-1-col, rows-1-row) = (3, 8)
        assertThat(flipped).isEqualTo(Pair(3, 8))
    }

    @Test
    fun rotation180_appliedTwice_isIdentity() {
        for (col in 0 until cols) {
            for (row in 0 until rows) {
                val (x, y) = GridTransposeHelper.remapCoordinates(
                    col, row, cols, rows, Surface.ROTATION_180,
                )
                // 180 keeps the grid dimensions, so it can legitimately be applied to its own output.
                val (x2, y2) = GridTransposeHelper.remapCoordinates(
                    x, y, cols, rows, Surface.ROTATION_180,
                )
                assertThat(Pair(x2, y2)).isEqualTo(Pair(col, row))
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Frames observed end-to-end on the device, pinned here so a refactor cannot silently change them.
    // These exact values came from a real 5x11 workspace DB before/after physical rotations.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun matchesDeviceObservedMapping_rotation90() {
        // Counter-clockwise physical turn reports ROTATION_90: newCol = row, newRow = cols-1-col.
        assertThat(GridTransposeHelper.remapCoordinates(1, 0, cols, rows, Surface.ROTATION_90))
            .isEqualTo(Pair(0, 3))
        assertThat(GridTransposeHelper.remapCoordinates(2, 0, cols, rows, Surface.ROTATION_90))
            .isEqualTo(Pair(0, 2))
        assertThat(GridTransposeHelper.remapCoordinates(0, 2, cols, rows, Surface.ROTATION_90))
            .isEqualTo(Pair(2, 4))
    }

    @Test
    fun matchesDeviceObservedMapping_rotation270() {
        // Clockwise physical turn reports ROTATION_270: newCol = rows-1-row, newRow = col.
        assertThat(GridTransposeHelper.remapCoordinates(1, 0, cols, rows, Surface.ROTATION_270))
            .isEqualTo(Pair(10, 1))
        assertThat(GridTransposeHelper.remapCoordinates(2, 0, cols, rows, Surface.ROTATION_270))
            .isEqualTo(Pair(10, 2))
        assertThat(GridTransposeHelper.remapCoordinates(0, 2, cols, rows, Surface.ROTATION_270))
            .isEqualTo(Pair(8, 0))
    }

    // ---------------------------------------------------------------------------------------------
    // Grid dimensions: 90/270 transpose the grid, 0/180 keep it.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun dimensionsForRotation_transposeOnlyOnQuarterTurns() {
        assertThat(GridTransposeHelper.dimensionsForRotation(cols, rows, Surface.ROTATION_0))
            .isEqualTo(Pair(cols, rows))
        assertThat(GridTransposeHelper.dimensionsForRotation(cols, rows, Surface.ROTATION_180))
            .isEqualTo(Pair(cols, rows))
        assertThat(GridTransposeHelper.dimensionsForRotation(cols, rows, Surface.ROTATION_90))
            .isEqualTo(Pair(rows, cols))
        assertThat(GridTransposeHelper.dimensionsForRotation(cols, rows, Surface.ROTATION_270))
            .isEqualTo(Pair(rows, cols))
    }

    @Test
    fun everyMappedCell_staysInsideTheRotatedGrid() {
        for (rotation in allRotations) {
            val (gridCols, gridRows) =
                GridTransposeHelper.dimensionsForRotation(cols, rows, rotation)
            for (col in 0 until cols) {
                for (row in 0 until rows) {
                    val (x, y) = GridTransposeHelper.remapCoordinates(col, row, cols, rows, rotation)
                    assertThat(x in 0 until gridCols).isTrue()
                    assertThat(y in 0 until gridRows).isTrue()
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Widgets. A multi-cell item's portrait top-left is NOT the top-left after rotation — a different
    // corner of the bounding box becomes the new origin. Getting this wrong pushes cellY+spanY past
    // numRows, and LoaderCursor then re-homes (and PERSISTS) the widget somewhere else.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun widgetSpans_swapOnQuarterTurnsOnly() {
        assertThat(GridTransposeHelper.spanForRotation(5, 9, Surface.ROTATION_0))
            .isEqualTo(Pair(5, 9))
        assertThat(GridTransposeHelper.spanForRotation(5, 9, Surface.ROTATION_180))
            .isEqualTo(Pair(5, 9))
        assertThat(GridTransposeHelper.spanForRotation(5, 9, Surface.ROTATION_90))
            .isEqualTo(Pair(9, 5))
        assertThat(GridTransposeHelper.spanForRotation(5, 9, Surface.ROTATION_270))
            .isEqualTo(Pair(9, 5))
    }

    @Test
    fun matchesDeviceObservedWidgetMapping() {
        // The real 5x9 widget from the measured workspace, at portrait (0,2).
        // ROTATION_90:  newCol = row = 2,          newRow = cols-col-spanX = 5-0-5 = 0
        assertThat(
            GridTransposeHelper.remapWidgetPosition(0, 2, 5, 9, cols, rows, Surface.ROTATION_90),
        ).isEqualTo(Pair(2, 0))
        // ROTATION_270: newCol = rows-row-spanY = 11-2-9 = 0, newRow = col = 0
        assertThat(
            GridTransposeHelper.remapWidgetPosition(0, 2, 5, 9, cols, rows, Surface.ROTATION_270),
        ).isEqualTo(Pair(0, 0))
    }

    @Test
    fun widgetRoundTrip_recoversPortraitOrigin_forEveryRotation() {
        val spanX = 5
        val spanY = 9
        for (rotation in allRotations) {
            for (col in 0..(cols - spanX)) {
                for (row in 0..(rows - spanY)) {
                    val (dCol, dRow) = GridTransposeHelper.remapWidgetPosition(
                        col, row, spanX, spanY, cols, rows, rotation,
                    )
                    val (dSpanX, dSpanY) =
                        GridTransposeHelper.spanForRotation(spanX, spanY, rotation)
                    val (backCol, backRow) = GridTransposeHelper.reverseWidgetPositionToPortrait(
                        dCol, dRow, dSpanX, dSpanY, cols, rows, rotation,
                    )
                    assertThat("r$rotation ($backCol,$backRow)")
                        .isEqualTo("r$rotation ($col,$row)")
                }
            }
        }
    }

    @Test
    fun widgetStaysInsideRotatedGrid_forEveryRotation() {
        val spanX = 5
        val spanY = 9
        for (rotation in allRotations) {
            val (gridCols, gridRows) =
                GridTransposeHelper.dimensionsForRotation(cols, rows, rotation)
            val (dSpanX, dSpanY) = GridTransposeHelper.spanForRotation(spanX, spanY, rotation)
            for (col in 0..(cols - spanX)) {
                for (row in 0..(rows - spanY)) {
                    val (x, y) = GridTransposeHelper.remapWidgetPosition(
                        col, row, spanX, spanY, cols, rows, rotation,
                    )
                    // This is the invariant whose violation makes checkItemPlacement re-home the
                    // widget and persist the damage.
                    assertThat("r$rotation fits: ${x >= 0 && y >= 0 &&
                        x + dSpanX <= gridCols && y + dSpanY <= gridRows}")
                        .isEqualTo("r$rotation fits: true")
                }
            }
        }
    }

    @Test
    fun singleCellWidget_agreesWithPlainCellMapping() {
        // remapWidgetPosition must reduce to remapCoordinates when the item is 1x1.
        for (rotation in allRotations) {
            for (col in 0 until cols) {
                for (row in 0 until rows) {
                    assertThat(
                        GridTransposeHelper.remapWidgetPosition(
                            col, row, 1, 1, cols, rows, rotation,
                        ),
                    ).isEqualTo(
                        GridTransposeHelper.remapCoordinates(col, row, cols, rows, rotation),
                    )
                }
            }
        }
    }
}
