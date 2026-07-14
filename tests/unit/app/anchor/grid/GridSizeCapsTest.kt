/*
 * Copyright (C) 2026 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */
package app.anchor.grid

import app.anchor.grid.GridSizeCaps.Density
import app.anchor.grid.GridSizeCaps.DeviceInput
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Host-side unit tests for the pure grid-sizing math ([GridSizeCaps.recommendPure] /
 * [GridSizeCaps.computePure]). These lock the core invariants of the grid setup wizard:
 *
 *  - the chosen ICON SIZE is preserved across every density (density changes count, not icon size);
 *  - the recommended grid never OVERFLOWS the screen;
 *  - the densities are DISTINCT (spacious < balanced ≤ dense columns);
 *  - the manual slider caps (computePure) are CONSISTENT with what recommendPure can produce.
 *
 * Run: `./gradlew testLawnWithQuickstepPlayDebugUnitTest --tests "app.anchor.grid.GridSizeCapsTest"`
 */
class GridSizeCapsTest {

    // A Pixel-class phone: 1080×2424 @ 2.625 density, 152px top cutout, 63px bottom nav.
    private val pixel = DeviceInput(
        widthPx = 1080,
        heightPx = 2424,
        insetLeft = 0,
        insetTop = 152,
        insetRight = 0,
        insetBottom = 63,
        density = 2.625f,
    )

    // Base icon dp (mirror of GridSizeCaps.ICON_BASE_DP) for computing the desired icon px.
    private fun desiredIconPx(input: DeviceInput, factor: Float) =
        Math.round(48f * factor * input.density)

    /** The cell width the launcher will produce for a recommendation (mirrors the override's S). */
    private fun cellPx(input: DeviceInput, rec: GridSizeCaps.Recommendation): Int {
        val pPx = maxOf(input.insetBottom, input.insetLeft, input.insetRight) +
            Math.round(4f * input.density)
        val usableShort = minOf(input.widthPx, input.heightPx) - 2 * pPx
        val gPx = Math.round(rec.gapDp * input.density)
        return (usableShort - (rec.columns - 1) * gPx) / rec.columns
    }

    @Test
    fun iconSizeIsPreservedAcrossDensities() {
        // For a given icon factor, the cell at every density must still fit the FULL desired icon —
        // i.e. the launcher's `icon = min(cellFit, desired)` never has to shrink the icon.
        for (factor in listOf(0.7f, 0.9f, 1.0f, 1.2f)) {
            for (labels in listOf(false, true)) {
                val icon = desiredIconPx(pixel, factor)
                for (d in Density.entries) {
                    val rec = GridSizeCaps.recommendPure(pixel, factor, labels, d)
                    val cell = cellPx(pixel, rec)
                    assertThat(cell).isAtLeast(icon)
                }
            }
        }
    }

    @Test
    fun recommendedGridNeverOverflowsScreen() {
        for (factor in listOf(0.7f, 0.9f, 1.0f, 1.2f, 1.5f)) {
            for (labels in listOf(false, true)) {
                for (d in Density.entries) {
                    val rec = GridSizeCaps.recommendPure(pixel, factor, labels, d)
                    val cell = cellPx(pixel, rec)
                    val gPx = Math.round(rec.gapDp * pixel.density)
                    // Vertical: grid height must fit the raw screen, clearing the bottom nav bar.
                    val gridH = rec.rows * cell + (rec.rows - 1) * gPx
                    assertThat(gridH).isAtMost(pixel.heightPx)
                    // Horizontal: grid width must fit the short side.
                    val gridW = rec.columns * cell + (rec.columns - 1) * gPx
                    assertThat(gridW).isAtMost(pixel.widthPx)
                }
            }
        }
    }

    @Test
    fun densitiesAreDistinct() {
        // Spacious has strictly fewer columns than dense; balanced sits between (inclusive).
        for (factor in listOf(0.9f, 1.0f)) {
            for (labels in listOf(false, true)) {
                val sp = GridSizeCaps.recommendPure(pixel, factor, labels, Density.SPACIOUS).columns
                val ba = GridSizeCaps.recommendPure(pixel, factor, labels, Density.BALANCED).columns
                val de = GridSizeCaps.recommendPure(pixel, factor, labels, Density.DENSE).columns
                assertThat(sp).isLessThan(de)
                assertThat(ba).isAtLeast(sp)
                assertThat(ba).isAtMost(de)
            }
        }
    }

    @Test
    fun sliderCapsAllowTheDenseRecommendation() {
        // The manual slider caps (computePure) must be ≥ the wizard's densest recommendation, so the
        // user can always manually set what the wizard suggested.
        for (factor in listOf(0.7f, 0.9f, 1.0f, 1.2f)) {
            for (labels in listOf(false, true)) {
                val caps = GridSizeCaps.computePure(pixel, factor, labels)
                val dense = GridSizeCaps.recommendPure(pixel, factor, labels, Density.DENSE)
                assertThat(caps.maxColumns).isAtLeast(dense.columns)
                assertThat(caps.maxRows).isAtLeast(dense.rows)
            }
        }
    }

    @Test
    fun sliderRowCapDoesNotOverflow() {
        // The manual row cap must itself fit the screen at the densest cell (the tallest per-row
        // case) — the user can't drag rows into an overflowing grid.
        for (factor in listOf(0.7f, 0.9f, 1.0f, 1.2f)) {
            for (labels in listOf(false, true)) {
                val caps = GridSizeCaps.computePure(pixel, factor, labels)
                // Reconstruct the densest cell the way computePure does.
                val dense = GridSizeCaps.recommendPure(pixel, factor, labels, Density.DENSE)
                val cell = cellPx(pixel, dense)
                val gPx = Math.round(dense.gapDp * pixel.density)
                val gridH = caps.maxRows * cell + (caps.maxRows - 1) * gPx
                assertThat(gridH).isAtMost(pixel.heightPx)
            }
        }
    }

    @Test
    fun biggerIconAllowsFewerColumns() {
        // Larger icon → fewer columns fit (the icon-size ceiling on density).
        val small = GridSizeCaps.recommendPure(pixel, 0.7f, false, Density.DENSE).columns
        val large = GridSizeCaps.recommendPure(pixel, 1.4f, false, Density.DENSE).columns
        assertThat(large).isLessThan(small)
    }
}
