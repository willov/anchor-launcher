/*
 * Copyright (C) 2026 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.anchor.grid

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/**
 * Orientation- and fit-aware caps for the home-screen grid sliders.
 *
 * Anchor uses square cells whose size S is derived from the SHORT physical screen edge so it is
 * identical in both orientations (see InvariantDeviceProfile.withDimensionsOverride). Because the
 * grid transposes M×N → N×M on rotation, the same cell size must hold a column in portrait and a
 * row in landscape. That makes a single per-orientation "columns can go to 20" slider wrong:
 *
 *   - The SHORT physical side can only hold a few cells before each cell S shrinks below the size
 *     needed to render an icon (and label). Past that point icons are GUARANTEED to overflow /
 *     hide labels — there is no point letting the slider go there.
 *   - The LONG physical side can hold more cells (its budget is larger), but it is still bounded.
 *
 * This object computes the max viable cell count per axis from pure geometry, mirroring the cell-fit
 * math in withDimensionsOverride, so the slider's valueRange can be clamped just below the
 * guaranteed-overflow boundary. Kept as a pure helper (one screen-size read) so it is unit-testable
 * and merge-isolated in the anchor/ package.
 */
object GridSizeCaps {

    /** Absolute floor for the desired icon: it must not drop below this. Mirrors minIconPx (24dp). */
    private const val MIN_ICON_DP = 24f

    /** Base home icon size in dp before the user's size factor is applied (matches profile base). */
    private const val ICON_BASE_DP = 48f

    /**
     * Icon size as a FRACTION of the screen's short side that the desired icon is floored to (before
     * the user's factor). The plain dp base ([ICON_BASE_DP]) is density-relative, so on a physically
     * large but low-density screen (e.g. a tablet at density 1.5) a 48dp icon is a tiny fraction of
     * the display — icons look postage-stamp small and the recommender packs far too many columns.
     * Flooring the desired icon at `shortSide × this × factor` makes sizing SCREEN-relative on such
     * screens, matching the visual proportion a normal-density phone already gets from the dp base.
     *
     * Tuned to 0.11 so it is a no-op on typical phones (their native dp icon already ≳ 11% of the
     * short side, so `max(dp, frac)` keeps the dp value — zero change to existing phone grids) but
     * lifts large low-density tablets from ~6% up to ~11% (icons ≈ 2× larger, ~5 columns not ~9).
     */
    private const val ICON_TARGET_FRACTION = 0.11f

    /** Label stripe (text + drawable pad) reserved BELOW the icon when labels are on (one-sided). */
    private const val LABEL_BUDGET_DP = 18f

    /** Safety margin between icon edge and cell boundary (mirrors safetyPx, 4dp). */
    private const val SAFETY_DP = 4f

    /** Extra grid inset beyond system insets, mirrors P = shortSideInset + 4dp. */
    private const val P_EXTRA_DP = 4f

    /** Absolute hard ceiling per axis regardless of fit (sanity bound). */
    private const val ABSOLUTE_MAX = 20

    /** Absolute hard floor per axis. */
    private const val ABSOLUTE_MIN = 3

    /** Max cell gap (dp). Matches the workspace-spacing slider's upper bound. */
    private const val MAX_GAP_DP = 40

    /**
     * Minimum inter-icon gap the DENSEST recommendation must still leave, so even "dense" has some
     * breathing room and icons never look squished/edge-to-edge. The column cap reserves this.
     */
    private const val MIN_DENSE_GAP_DP = 10

    data class Caps(val maxColumns: Int, val maxRows: Int)

    /** A coupled grid recommendation: column/row counts plus the cell gap (dp) for even spacing. */
    data class Recommendation(val columns: Int, val rows: Int, val gapDp: Int)

    /**
     * Density preference for the recommendation engine. It controls BOTH levers so the words mean
     * what they say:
     *   - [columnsBelowCap]: how many columns below the fit cap (fewer cols → bigger cells).
     *   - [gapDp]: the cell gap. Spacious uses a larger gap (real breathing room between icons),
     *     dense a tighter one. Previously the gap was fixed, so "spacious" only meant bigger icons,
     *     not more space — which didn't read as spacious.
     */
    enum class Density(val columnsBelowCap: Int) {
        // Density picks the COLUMN COUNT (offset below the fit cap) — distinct by construction, so
        // they never collapse. The GAP is then COMPUTED to distribute leftover space evenly (icons
        // equally spaced on both axes, gap ≈ edge margin), not a fixed per-density constant.
        SPACIOUS(2),
        BALANCED(1),
        DENSE(0),
    }

    /**
     * Recommend a coupled columns×rows grid for the given icon size, label state, spacing and
     * density feel. This is the engine behind the first-run grid onboarding AND the "recommend a
     * grid" affordance in settings — both must agree, so the coupling lives here, not in the UI.
     *
     * Strategy:
     *   1. Compute the per-axis fit caps (compute()).
     *   2. Columns = columnCap − density.columnsBelowCap (clamped to ≥ ABSOLUTE_MIN). The short
     *      side is the binding constraint, so density is expressed on columns.
     *   3. Rows = round(columns × screenAspect), clamped to the row cap. Matching the screen's
     *      physical aspect makes cell density feel even in both axes and lands naturally on the
     *      4×9 / 5×11 sweet spots on a typical ~20:9 phone.
     */
    /**
     * Raw device geometry needed by the pure sizing math. Extracted so [recommendPure]/[computePure]
     * can be unit-tested on the host JVM without an Android Context.
     */
    data class DeviceInput(
        val widthPx: Int,
        val heightPx: Int,
        val insetLeft: Int,
        val insetTop: Int,
        val insetRight: Int,
        val insetBottom: Int,
        val density: Float,
    )

    fun recommend(
        context: Context,
        spacingDp: Float, // ignored — the gap is computed for even spacing (kept for call symmetry)
        iconSizeFactor: Float,
        showLabels: Boolean,
        density: Density,
    ): Recommendation = recommendPure(deviceInput(context), iconSizeFactor, showLabels, density)

    /** Pure, Context-free recommendation math (unit-testable). See [recommend] for the wrapper. */
    fun recommendPure(
        input: DeviceInput,
        iconSizeFactor: Float,
        showLabels: Boolean,
        density: Density,
    ): Recommendation {
        val density2 = input.density
        val wPx = input.widthPx
        val hPx = input.heightPx
        val insTop = input.insetTop
        val insBottom = input.insetBottom
        val insLeft = input.insetLeft
        val insRight = input.insetRight
        val screenAspect = max(wPx, hPx).toFloat() / min(wPx, hPx).toFloat()
        val shortRawPx = min(wPx, hPx)
        val longRawPx = max(wPx, hPx)

        // P MUST match the override exactly, or recommend() assumes bigger cells than the launcher
        // actually produces and the icon then shrinks to fit (the "icon not locked" bug). The
        // override's P = (max short-side inset across all supported orientations) + 4dp. In LANDSCAPE
        // the short side is top/bottom, carrying the status-bar inset — which on a gesture-nav phone
        // is ~the portrait bottom-nav-bar height. Device-verified: override P=74px = bottomNav(63) +
        // 4dp(11). So use the bottom inset (the largest of nav/status we can see) as that proxy.
        val shortSideInsetPx = maxOf(insBottom, insLeft, insRight)
        val pPx = shortSideInsetPx + Math.round(P_EXTRA_DP * density2)

        // The user's chosen icon size is SACRED in the wizard — density must NEVER shrink it. So the
        // cell must always be at least the icon plus its minimum padding (label stripe if labels on,
        // else a small safety margin). The override would otherwise do icon = min(cellFit, desired)
        // and silently shrink the icon when cells get tight; the wizard avoids ever handing it such
        // a grid by guaranteeing S ≥ minCell here.
        // Screen-relative floor (see ICON_TARGET_FRACTION): the dp base is density-relative and goes
        // tiny on big low-density tablets, so floor the desired icon at a fraction of the short side.
        // On phones the dp value already exceeds this, so max() keeps it → no change to phone grids.
        val dpIconPx = max(MIN_ICON_DP, ICON_BASE_DP * iconSizeFactor) * density2
        val fracIconPx = shortRawPx * ICON_TARGET_FRACTION * iconSizeFactor
        val desiredIconPx = Math.round(max(dpIconPx, fracIconPx))
        val minPadPx = if (showLabels) {
            Math.round((LABEL_BUDGET_DP + SAFETY_DP) * density2)
        } else {
            Math.round(2f * SAFETY_DP * density2)
        }
        val minCellPx = desiredIconPx + minPadPx
        val usableShortPx = shortRawPx - 2 * pPx

        // 1) COLUMN COUNT per density: cap − offset (dense=cap, balanced=cap−1, spacious=cap−2).
        //    The cap is the most columns that fit while keeping the icon at its chosen size AND
        //    leaving at least a MINIMUM inter-column gap. Using a zero-gap cap (the old bug) packed
        //    icons edge-to-edge at the dense end, so they looked squished even though technically
        //    "fitting". Solve n from: usable ≥ n*minCell + (n−1)*minGap + 2*minGap (edges get a gap
        //    too) ⇒ n ≤ (usable + minGap) / (minCell + minGap) with an extra edge-gap reserved.
        val minGapPx = Math.round(MIN_DENSE_GAP_DP * density2)
        val columnCap = ((usableShortPx - minGapPx + minGapPx) / (minCellPx + minGapPx))
            .coerceIn(ABSOLUTE_MIN, ABSOLUTE_MAX)
        val columns = (columnCap - density.columnsBelowCap).coerceIn(ABSOLUTE_MIN, columnCap)

        // 2) EVEN-SPACING GAP. Cells are minCell-sized (icon + min pad); the LEFTOVER short-side
        //    space becomes the gap, split into (cols+1) parts so spacing is even between columns and
        //    shares with the edges. Because columns were capped so minCell fits at zero gap, the
        //    leftover is ≥ 0 and the icon is ALWAYS preserved — denser grids simply get a smaller
        //    gap (down to 0), never a smaller icon.
        val slackPx = (usableShortPx - columns * minCellPx).coerceAtLeast(0)
        val rawGapPx = slackPx / (columns + 1)
        // Cap the gap at ~half the cell so a big gap doesn't dwarf the icons / edge margin.
        val gapCapPx = (minCellPx * 0.5f).toInt()
        val gapDp = Math.round(minOf(rawGapPx, gapCapPx) / density2).coerceIn(0, MAX_GAP_DP)
        // Final S from the clamped gap, even-floored to match the override. Guaranteed ≥ minCell
        // (any slack the gap-cap left over inflates S, which only makes the cell roomier).
        val gPxFinal = Math.round(gapDp * density2)
        var sPx = ((usableShortPx - (columns - 1) * gPxFinal) / columns).coerceAtLeast(minCellPx)
        sPx = (sPx / 2) * 2

        // 3) ROWS: aspect-matched, accepted as long as the grid physically fits the raw long side
        //    with a small clearance for the bottom nav bar (the grid centres in the raw dimension,
        //    so the top cutout is absorbed by centring — only the nav bar constrains). Take the
        //    aspect-target rows if they fit; otherwise step down until they do. Device-verified: at
        //    dense 5×S≈178px, 11 rows leaves ~100px margin each side and looks better than 10, but
        //    the old fixed budget capped it at 10 — this fit-check honours the extra row.
        // True no-overflow bound: the grid centres in the raw long dimension, then the override's
        // Phase-4 inset compensation subtracts the TOP cutout from the top padding — so the grid
        // fits (top row clears the cutout) iff the centred top margin ≥ the cutout, i.e.
        // gridH ≤ longRaw − 2 × maxInset. Take the aspect-matched rows, then step down until they
        // satisfy this. (This is the same bound computePure uses, so the slider cap agrees.)
        val edgeClearancePx = max(insTop, insBottom) + Math.round(P_EXTRA_DP * density2)
        var rows = Math.round(columns * screenAspect).coerceAtLeast(ABSOLUTE_MIN)
        while (rows > ABSOLUTE_MIN &&
            rows * sPx + (rows - 1) * gPxFinal > longRawPx - 2 * edgeClearancePx
        ) {
            rows--
        }
        rows = rows.coerceIn(ABSOLUTE_MIN, ABSOLUTE_MAX)

        return Recommendation(columns, rows, gapDp)
    }

    /** Physical aspect ratio of the display (long side / short side), always ≥ 1. */
    fun screenAspect(context: Context): Float {
        val (wPx, hPx, _) = realScreenMetrics(context)
        return max(wPx, hPx).toFloat() / min(wPx, hPx).toFloat()
    }

    /** Metrics for a faithful, density-independent grid preview. */
    data class PreviewMetrics(val aspect: Float, val iconFractionOfShortSide: Float)

    /**
     * The icon's size as a FRACTION of the screen's short side, for the given icon factor. This is
     * fixed by the icon-size choice and does NOT depend on the grid density — so a preview that draws
     * icons at this fraction of its width keeps them constant across densities, matching reality.
     */
    fun previewMetrics(context: Context, iconSizeFactor: Float): PreviewMetrics {
        val density = context.resources.displayMetrics.density
        val (wPx, hPx, _) = realScreenMetrics(context)
        val shortRawPx = min(wPx, hPx).toFloat()
        // Mirror the screen-relative floor from recommendPure so the preview matches the applied grid.
        val dpIconPx = max(MIN_ICON_DP, ICON_BASE_DP * iconSizeFactor) * density
        val fracIconPx = shortRawPx * ICON_TARGET_FRACTION * iconSizeFactor
        val desiredIconPx = max(dpIconPx, fracIconPx)
        return PreviewMetrics(
            aspect = max(wPx, hPx).toFloat() / shortRawPx,
            iconFractionOfShortSide = (desiredIconPx / shortRawPx).coerceIn(0.05f, 0.4f),
        )
    }

    /** Rows that keep the screen aspect for a given column count (portrait-canonical: rows = long). */
    fun linkedRows(context: Context, columns: Int): Int =
        Math.round(columns * screenAspect(context)).coerceAtLeast(ABSOLUTE_MIN)

    /** Columns that keep the screen aspect for a given row count. */
    fun linkedColumns(context: Context, rows: Int): Int =
        Math.round(rows / screenAspect(context)).coerceAtLeast(ABSOLUTE_MIN)

    /**
     * @param spacingDp     workspace cell gap (PreferenceManager2.workspaceSpacingDp)
     * @param iconSizeFactor home icon size factor (homeIconSizeFactor); the base icon is ~48dp, so
     *                       the desired icon is ~48*factor. Larger desired icon → bigger min cell →
     *                       fewer cells fit.
     * @param showLabels    whether home-screen labels are on (adds label budget to the min cell).
     */
    fun compute(
        context: Context,
        spacingDp: Float,
        iconSizeFactor: Float,
        showLabels: Boolean,
    ): Caps = computePure(deviceInput(context), iconSizeFactor, showLabels)

    /**
     * Pure, Context-free slider-cap math (unit-testable). The manual grid-slider caps MUST match
     * what the wizard's recommend() can produce — otherwise the wizard suggests e.g. 5×11 dense but
     * the slider won't let you set it. The DENSE recommendation *is* the maximum grid (most columns,
     * most rows), so the caps are simply that recommendation — consistent by construction, and it
     * inherits recommend()'s exact icon-preserving + no-overflow guarantees. See [compute].
     */
    fun computePure(input: DeviceInput, iconSizeFactor: Float, showLabels: Boolean): Caps {
        // Columns: the DENSE recommendation is the most columns (icon-preserving) that fit.
        val dense = recommendPure(input, iconSizeFactor, showLabels, Density.DENSE)
        // Rows: allow up to the MOST rows that physically fit at that dense cell — which is ≥ the
        // dense recommendation's aspect-matched row count, so the slider both (a) always permits the
        // wizard's suggestion and (b) lets the user add extra rows beyond the aspect target, without
        // ever overflowing. Same true no-overflow bound (gridH ≤ longRaw − 2×maxInset) as recommend.
        val density = input.density
        val longRawPx = max(input.widthPx, input.heightPx)
        val shortRawPx = min(input.widthPx, input.heightPx)
        val pPx = maxOf(input.insetBottom, input.insetLeft, input.insetRight) +
            Math.round(P_EXTRA_DP * density)
        val gPx = Math.round(dense.gapDp * density)
        val cellPx = (shortRawPx - 2 * pPx - (dense.columns - 1) * gPx) / dense.columns
        val edgeClearancePx = max(input.insetTop, input.insetBottom) + Math.round(P_EXTRA_DP * density)
        val budgetPx = longRawPx - 2 * edgeClearancePx
        val maxRows = ((budgetPx + gPx) / (cellPx + gPx)).coerceIn(ABSOLUTE_MIN, ABSOLUTE_MAX)
        return Caps(maxColumns = dense.columns, maxRows = maxRows)
    }

    /** Read [DeviceInput] from a live Context. */
    private fun deviceInput(context: Context): DeviceInput {
        val (wPx, hPx, ins) = realScreenMetrics(context)
        return DeviceInput(
            widthPx = wPx,
            heightPx = hPx,
            insetLeft = ins.left,
            insetTop = ins.top,
            insetRight = ins.right,
            insetBottom = ins.bottom,
            density = context.resources.displayMetrics.density,
        )
    }

    /** Width px, height px, and the system-bar + cutout insets (Rect l/t/r/b) of the full display. */
    private fun realScreenMetrics(context: Context): Triple<Int, Int, Rect> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(WindowManager::class.java)
            if (wm != null) {
                val metrics = wm.maximumWindowMetrics
                val b = metrics.bounds
                val wi = metrics.windowInsets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                return Triple(b.width(), b.height(), Rect(wi.left, wi.top, wi.right, wi.bottom))
            }
        }
        val dm = context.resources.displayMetrics
        return Triple(dm.widthPixels, dm.heightPixels, Rect())
    }
}
