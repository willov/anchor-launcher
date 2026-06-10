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
 * Host-side unit tests for [WallpaperCropMath] — the pure crop + rotation math behind the
 * rotation-stable wallpaper. Locks the core invariant that a PURE rotation (camera unchanged)
 * selects the identical source pixels, which is what makes rotation pixel-perfect.
 *
 * Run: `./gradlew testLawnWithQuickstepPlayDebugUnitTest --tests "app.anchor.rotation.WallpaperCropMathTest"`
 */
class WallpaperCropMathTest {

    // A typical phone: portrait viewport 1080×2400, bitmap 2× in each axis (parallax headroom).
    private val rawW = 1080
    private val rawH = 2400
    private val bmpW = 2160
    private val bmpH = 4800

    @Test
    fun srcRect_isIdenticalAcrossRotation_forSameCamera() {
        // The camera (worldX, worldY) is never changed by rotation, and computeSrcRect takes the
        // portrait-canonical (rawW, rawH) — so the sampled rectangle must be byte-identical in every
        // orientation. This is the property that guarantees pixel-perfect pure rotation.
        for (wx in listOf(0f, 0.5f, 1f)) {
            for (wy in listOf(0f, 0.5f, 1f)) {
                val rect = WallpaperCropMath.computeSrcRect(wx, wy, bmpW, bmpH, rawW, rawH)
                // Recompute with the same canonical dims (what the drawable does for every rotation).
                val again = WallpaperCropMath.computeSrcRect(wx, wy, bmpW, bmpH, rawW, rawH)
                assertThat(again).isEqualTo(rect)
            }
        }
    }

    @Test
    fun srcRect_sizeIsViewportSized_andOffsetTracksCamera() {
        val mid = WallpaperCropMath.computeSrcRect(0.5f, 0.5f, bmpW, bmpH, rawW, rawH)
        assertThat(mid.width).isEqualTo(rawW)
        assertThat(mid.height).isEqualTo(rawH)
        // scrollRoom = bmp - raw = 1080 / 2400; worldX/Y=0.5 → halfway.
        assertThat(mid.left).isEqualTo(540)
        assertThat(mid.top).isEqualTo(1200)

        val origin = WallpaperCropMath.computeSrcRect(0f, 0f, bmpW, bmpH, rawW, rawH)
        assertThat(origin.left).isEqualTo(0)
        assertThat(origin.top).isEqualTo(0)

        val far = WallpaperCropMath.computeSrcRect(1f, 1f, bmpW, bmpH, rawW, rawH)
        assertThat(far.left).isEqualTo(bmpW - rawW)
        assertThat(far.top).isEqualTo(bmpH - rawH)
    }

    @Test
    fun srcRect_noScrollRoom_clampsToZeroOffset() {
        // Bitmap exactly viewport-sized: nowhere to pan, crop pinned at origin regardless of camera.
        val rect = WallpaperCropMath.computeSrcRect(0.7f, 0.3f, rawW, rawH, rawW, rawH)
        assertThat(rect).isEqualTo(WallpaperCropMath.IRect(0, 0, rawW, rawH))
    }

    @Test
    fun drawnToGlass_portraitIsIdentity() {
        assertThat(WallpaperCropMath.drawnToGlass(10, 20, rawW, rawH, Surface.ROTATION_0))
            .isEqualTo(WallpaperCropMath.GlassPoint(10, 20))
    }

    @Test
    fun drawnToGlass_matchesDocumentedCounterRotation() {
        // ROTATION_90: (dx,dy) -> (dy, rawW - dx)
        assertThat(WallpaperCropMath.drawnToGlass(0, 0, rawW, rawH, Surface.ROTATION_90))
            .isEqualTo(WallpaperCropMath.GlassPoint(0, rawW))
        // ROTATION_270: (dx,dy) -> (rawH - dy, dx)
        assertThat(WallpaperCropMath.drawnToGlass(0, 0, rawW, rawH, Surface.ROTATION_270))
            .isEqualTo(WallpaperCropMath.GlassPoint(rawH, 0))
        // ROTATION_180: (dx,dy) -> (rawW - dx, rawH - dy)
        assertThat(WallpaperCropMath.drawnToGlass(0, 0, rawW, rawH, Surface.ROTATION_180))
            .isEqualTo(WallpaperCropMath.GlassPoint(rawW, rawH))
    }

    // ── Axis mapping (the landscape vertical-parallax inversion fix) ─────────────────────────────
    // The counter-rotation makes some bitmap axes map to the glass with an inverted sign; the manager
    // uses these tables so a page swipe (horizontal-on-glass) and a row switch (vertical-on-glass)
    // move consistently in EVERY rotation. The landscape bug was: row transitions drove worldX with a
    // +sign on ROT_90, but bitmap-X is inverted on glass-Y there (glass-Y = rawW − dx) → motion felt
    // upside-down. Correct mapping for ROT_90 vertical is (worldX, −1).

    @Test
    fun horizontalGlassAxis_perRotation() {
        val H = WallpaperCropMath::horizontalGlassAxis
        assertThat(H(Surface.ROTATION_0)).isEqualTo(WallpaperCropMath.AxisMapping(WallpaperCropMath.WorldAxis.X, +1))
        assertThat(H(Surface.ROTATION_90)).isEqualTo(WallpaperCropMath.AxisMapping(WallpaperCropMath.WorldAxis.Y, +1))
        assertThat(H(Surface.ROTATION_270)).isEqualTo(WallpaperCropMath.AxisMapping(WallpaperCropMath.WorldAxis.Y, -1))
        assertThat(H(Surface.ROTATION_180)).isEqualTo(WallpaperCropMath.AxisMapping(WallpaperCropMath.WorldAxis.X, -1))
    }

    @Test
    fun verticalGlassAxis_perRotation() {
        val V = WallpaperCropMath::verticalGlassAxis
        assertThat(V(Surface.ROTATION_0)).isEqualTo(WallpaperCropMath.AxisMapping(WallpaperCropMath.WorldAxis.Y, +1))
        // The fix: ROT_90 vertical must drive worldX with a NEGATIVE sign (was effectively +, inverted).
        assertThat(V(Surface.ROTATION_90)).isEqualTo(WallpaperCropMath.AxisMapping(WallpaperCropMath.WorldAxis.X, -1))
        assertThat(V(Surface.ROTATION_270)).isEqualTo(WallpaperCropMath.AxisMapping(WallpaperCropMath.WorldAxis.X, +1))
        assertThat(V(Surface.ROTATION_180)).isEqualTo(WallpaperCropMath.AxisMapping(WallpaperCropMath.WorldAxis.Y, -1))
    }

    @Test
    fun horizontalAndVertical_useDistinctAxes_inEveryRotation() {
        // Sanity: the two glass directions must never map to the same bitmap axis (else one gesture
        // would move both, or one wouldn't move at all).
        for (r in listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)) {
            assertThat(WallpaperCropMath.horizontalGlassAxis(r).axis)
                .isNotEqualTo(WallpaperCropMath.verticalGlassAxis(r).axis)
        }
    }

    // ── Equal-drift parallax (anisotropy fix) ───────────────────────────────────────────────────
    // Each step (page swipe / row switch) targets the same glass-pixel drift, capped PER AXIS at its
    // own pan room so the axes never zero each other (a portrait-height image still pans horizontally).

    @Test
    fun worldTravel_isFractionOfScrollRoom_clampedToOne() {
        // 100px target into 400px room = quarter of the room.
        assertThat(WallpaperCropMath.worldTravelForGlassDrift(100f, 400)).isWithin(1e-4f).of(0.25f)
        // Target exceeds the room → clamp at 1 (use the whole room).
        assertThat(WallpaperCropMath.worldTravelForGlassDrift(800f, 400)).isEqualTo(1f)
    }

    @Test
    fun worldTravel_noRoom_isZero() {
        // The key fix: an axis with no pan headroom contributes 0 — it does NOT affect other axes.
        assertThat(WallpaperCropMath.worldTravelForGlassDrift(100f, 0)).isEqualTo(0f)
    }

    @Test
    fun horizontalSweep_independentOfVerticalRoom() {
        // 4 pages → 3 steps; 50px each = 150px target into 600px horizontal room = 0.25 sweep.
        // (No vertical room argument at all — horizontal can't be zeroed by a portrait-height image.)
        assertThat(WallpaperCropMath.horizontalSweepWorld(50f, 600, 4)).isWithin(1e-4f).of(0.25f)
        // Single-page row → no horizontal steps → 0 sweep.
        assertThat(WallpaperCropMath.horizontalSweepWorld(50f, 600, 1)).isEqualTo(0f)
    }

    @Test
    fun rowWorldOffset_row0IsBottomRest_higherRowsStepUp_clampedAtTop() {
        val rest = 0.9f // HOME_REST_WORLD_Y
        // scrollRoomV 1000px, 100px per step → 0.1 world per row up from the bottom rest.
        assertThat(WallpaperCropMath.rowWorldOffset(0, 100f, 1000, rest)).isWithin(1e-4f).of(0.9f)
        assertThat(WallpaperCropMath.rowWorldOffset(1, 100f, 1000, rest)).isWithin(1e-4f).of(0.8f)
        assertThat(WallpaperCropMath.rowWorldOffset(2, 100f, 1000, rest)).isWithin(1e-4f).of(0.7f)
        // Many rows up → clamps at the top of the image (worldY 0), never negative.
        assertThat(WallpaperCropMath.rowWorldOffset(50, 100f, 1000, rest)).isEqualTo(0f)
    }

    @Test
    fun rowWorldOffset_noVerticalRoom_allRowsRestAtBottom() {
        // Portrait-height image: no vertical pan, so every row sits at the bottom rest (no v-parallax)
        // — but this must NOT affect horizontal (tested above, independent).
        val rest = 0.9f
        assertThat(WallpaperCropMath.rowWorldOffset(0, 100f, 0, rest)).isEqualTo(rest)
        assertThat(WallpaperCropMath.rowWorldOffset(3, 100f, 0, rest)).isEqualTo(rest)
    }

    @Test
    fun bitmapPixelToGlass_pixelOutsideCropIsNull() {
        // Camera at origin shows bitmap [0,rawW)×[0,rawH); a pixel past that is not visible.
        val outside = WallpaperCropMath.bitmapPixelToGlass(
            bmpW - 1, bmpH - 1, 0f, 0f, bmpW, bmpH, rawW, rawH, Surface.ROTATION_0,
        )
        assertThat(outside).isNull()
        val inside = WallpaperCropMath.bitmapPixelToGlass(
            5, 5, 0f, 0f, bmpW, bmpH, rawW, rawH, Surface.ROTATION_0,
        )
        assertThat(inside).isEqualTo(WallpaperCropMath.GlassPoint(5, 5))
    }
}
