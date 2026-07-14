package app.lawnchair.ui.preferences.destinations

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.anchor.AnchorPreferences
import app.anchor.grid.AnchorPreviewPopulator
import app.anchor.grid.GridSizeCaps
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.GridOverridesPreview
import app.lawnchair.ui.preferences.components.layout.PreferenceScaffold
import com.android.launcher3.LauncherAppState
import kotlinx.coroutines.launch
import com.android.launcher3.R
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking

/**
 * Anchor first-launch / on-demand grid & icon setup wizard.
 *
 * Steps (top to bottom, with a live populated preview that reacts as you go):
 *   1. Labels on/off
 *   2. Icon size — a Small↔Large slider (no numbers), backed by [homeIconSizeFactor]
 *   3. Density (Spacious / Balanced / Dense)
 *
 * The preview is the same [GridOverridesPreview] + populate-grid pipeline used by the grid settings
 * screen, so it shows real app icons at real size for the chosen icon size and labels. Because the
 * preview's IDP reads the PERSISTED icon-size/label prefs, the wizard writes those two live as the
 * user adjusts them (cheap reloadGrid, no DB migration). The columns×rows are computed from the
 * density choice and applied only on Finish — that is the single change that triggers a grid
 * migration, so it is deferred until the user commits.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GridWizardScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = preferenceManager()
    val prefs2 = preferenceManager2()
    val navController = LocalNavController.current

    // Persisted-live state (drives the preview through the IDP).
    val labelsAdapter = prefs2.showIconLabelsOnHomeScreen.getAdapter()
    var showLabels by remember { mutableStateOf(labelsAdapter.state.value) }
    var iconFactor by remember { mutableFloatStateOf(prefs2.homeIconSizeFactor.firstBlocking()) }

    // Density is wizard-local; the resulting grid is applied on Finish.
    var density by remember { mutableStateOf(GridSizeCaps.Density.BALANCED) }

    // The wizard turns the preview's populate mode on; restore it off on leave so it can't leak.
    DisposableEffect(Unit) {
        AnchorPreviewPopulator.setEnabled(true)
        onDispose { AnchorPreviewPopulator.setEnabled(false) }
    }

    // Recompute the recommended grid (cols, rows, AND the even-spacing gap) whenever an input
    // changes, and persist the gap SYNCHRONOUSLY in the same pass. The preview's IDP reads
    // workspaceSpacingDp for its cell gap (which determines S, hence the icon size). Writing the gap
    // here — before the preview's keyed rebuild reads the pref — keeps them in lockstep. Doing it in
    // a LaunchedEffect instead lagged one step behind, so the preview used the PREVIOUS density's
    // gap → the same "balanced" rendered with a different gap/icon depending on slide direction.
    val recommendation = remember(showLabels, iconFactor, density) {
        val rec = GridSizeCaps.recommend(context, 0f, iconFactor, showLabels, density)
        prefs2.workspaceSpacingDp.setBlocking(rec.gapDp)
        rec
    }

    // Paged wizard: each step owns the full screen. Crucially the FULL grid preview only appears on
    // the last (Grid) page — the launcher preview renders at full screen size, so giving it its own
    // page avoids it bleeding behind the other controls (the bug with stacking it above a column).
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    PreferenceScaffold(
        label = stringResource(id = R.string.anchor_grid_wizard_title),
        isExpandedScreen = true,
        backArrowVisible = true,
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                userScrollEnabled = false, // drive via Back/Next so each step is deliberate
            ) { page ->
                when (page) {
                    0 -> WallpaperStep(context)
                    1 -> CellStep(
                        showLabels = showLabels,
                        onLabels = { showLabels = it; prefs2.showIconLabelsOnHomeScreen.setBlocking(it) },
                        iconFactor = iconFactor,
                        onIconFactor = { iconFactor = it; prefs2.homeIconSizeFactor.setBlocking(it) },
                        recommendation = recommendation,
                    )
                    else -> GridStep(
                        density = density,
                        onDensity = { density = it }, // gap persisted synchronously in recommendation remember
                        showLabels = showLabels,
                        iconFactor = iconFactor,
                        recommendation = recommendation,
                    )
                }
            }

            // Back / Next | Finish bar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (pagerState.currentPage > 0) {
                    OutlinedButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(id = R.string.anchor_grid_wizard_back)) }
                }
                val isLast = pagerState.currentPage == 2
                Button(
                    onClick = {
                        if (isLast) {
                            // Persist icon size + labels (already written live) and apply the grid.
                            prefs.workspaceColumns.set(recommendation.columns)
                            prefs.workspaceRows.set(recommendation.rows)
                            AnchorPreferences(context).gridOnboardingShown = true
                            LauncherAppState.getIDP(context).onPreferencesChanged(context)
                            // popBackStack no-ops when the wizard is the start/only destination
                            // (launched standalone from the first-launch gate). Fall back to
                            // finishing the host activity so "Use this grid" always dismisses.
                            if (!navController.popBackStack()) {
                                (context as? android.app.Activity)?.finish()
                            }
                        } else {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        stringResource(
                            id = if (isLast) R.string.anchor_grid_onboarding_apply
                            else R.string.anchor_grid_wizard_next,
                        ),
                    )
                }
            }
        }
    }
}

/** Step 1 — wallpaper choice. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WallpaperStep(context: Context) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(id = R.string.anchor_grid_wizard_wallpaper_heading),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(text = stringResource(id = R.string.anchor_grid_wizard_wallpaper_desc))
        Button(
            onClick = {
                (context as? android.app.Activity)?.let { app.anchor.AnchorWallpaperPicker.launch(it) }
            },
            shapes = ButtonDefaults.shapes(),
        ) { Text(stringResource(id = R.string.anchor_grid_wizard_wallpaper_pick)) }
    }
}

/** Step 2 — one cell: labels + icon size + a real-size example cell. */
@Composable
private fun CellStep(
    showLabels: Boolean,
    onLabels: (Boolean) -> Unit,
    iconFactor: Float,
    onIconFactor: (Float) -> Unit,
    recommendation: GridSizeCaps.Recommendation,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(id = R.string.anchor_grid_wizard_icon_heading),
            style = MaterialTheme.typography.titleLarge,
        )
        // Big, centred example cell so the user judges one cell first.
        ExampleCell(showLabels = showLabels, iconFactor = iconFactor, recommendation = recommendation)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(id = R.string.show_labels), modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = showLabels, onCheckedChange = onLabels)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(id = R.string.anchor_icon_size_small))
            Slider(
                value = iconFactor,
                onValueChange = onIconFactor,
                valueRange = 0.5f..1.3f,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text(text = stringResource(id = R.string.anchor_icon_size_large))
        }
    }
}

/** Step 3 — grid density with the full populated preview (owns the whole page). */
@Composable
private fun GridStep(
    density: GridSizeCaps.Density,
    onDensity: (GridSizeCaps.Density) -> Unit,
    showLabels: Boolean,
    iconFactor: Float,
    recommendation: GridSizeCaps.Recommendation,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Faithful Compose preview: draws the actual columns × rows at the LOCKED icon size and the
        // real gap, in a phone-shaped card, scaled uniformly. Replaces the old full-launcher
        // AndroidView preview, which scaled by grid height and so made dense icons look BIGGER than
        // spacious (backwards from reality). This draws the true geometry, so it always matches.
        GridPreview(
            recommendation = recommendation,
            iconFactor = iconFactor,
            showLabels = showLabels,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        ) {
            Text(text = stringResource(id = R.string.anchor_grid_density_spacious))
            Slider(
                value = densityToSlider(density),
                onValueChange = { onDensity(sliderToDensity(it)) },
                valueRange = 0f..2f,
                steps = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text(text = stringResource(id = R.string.anchor_grid_density_dense))
        }
    }
}

/**
 * Faithful, self-contained grid preview. Draws a phone-shaped card (real screen aspect) and lays
 * out columns × rows sample icons at the LOCKED icon size (base 48dp × factor) with the real gap,
 * centred, scaled uniformly to the card. Because it draws the true geometry (not a scaled full
 * launcher), the icon size stays constant across densities exactly like the real home screen —
 * denser just means more, more-tightly-packed icons, never bigger ones.
 */
@Composable
private fun GridPreview(
    recommendation: GridSizeCaps.Recommendation,
    iconFactor: Float,
    showLabels: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cols = recommendation.columns
    val rows = recommendation.rows
    // Real device metrics: the icon occupies a FIXED fraction of the screen's short side, set by the
    // icon-size factor — it does NOT depend on column count. Drawing the icon at that same fraction
    // of the card width keeps it constant across densities (the whole point). aspect = long/short.
    val metrics = remember { GridSizeCaps.previewMetrics(context, iconFactor) }
    val aspect = metrics.aspect
    val iconFracOfShort = metrics.iconFractionOfShortSide // e.g. 210px / 1080px

    // The user's REAL installed app icons, so the preview looks like their actual phone. Loaded
    // once, capped, drawn in order across the grid (cycling if more cells than apps). Falls back to
    // this app's own icon if the query returns nothing.
    val iconBitmaps = remember {
        val list = try {
            val la = context.getSystemService(android.content.pm.LauncherApps::class.java)
            val dm = context.resources.displayMetrics.densityDpi
            la?.getActivityList(null, android.os.Process.myUserHandle())
                ?.take(60)
                ?.mapNotNull { info ->
                    runCatching {
                        val d = info.getIcon(dm)
                        d.toBitmap(
                            width = maxOf(1, d.intrinsicWidth),
                            height = maxOf(1, d.intrinsicHeight),
                        ).asImageBitmap()
                    }.getOrNull()
                }
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
        list ?: listOf(
            context.packageManager.getApplicationIcon(context.applicationInfo)
                .let { it.toBitmap(maxOf(1, it.intrinsicWidth), maxOf(1, it.intrinsicHeight)) }
                .asImageBitmap(),
        )
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val availW = maxWidth
        val availH = maxHeight
        val cardW: Dp
        val cardH: Dp
        if (availW.value * aspect <= availH.value) {
            cardW = availW
            cardH = availW * aspect
        } else {
            cardH = availH
            cardW = availH / aspect
        }
        Box(
            modifier = Modifier
                .size(cardW, cardH)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                // FIXED icon size (fraction of card width), independent of cols/rows.
                val iconPx = w * iconFracOfShort
                // Columns are laid out with even gaps in the leftover width (cols+1 gaps).
                val hGap = ((w - cols * iconPx) / (cols + 1)).coerceAtLeast(0f)
                // Rows: same icon size vertically; even vertical gaps, grid centred.
                val vGap = hGap
                val gridH = rows * iconPx + (rows + 1) * vGap
                val topOffset = ((h - gridH) / 2f).coerceAtLeast(0f)
                var idx = 0
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val left = hGap + c * (iconPx + hGap)
                        val top = topOffset + vGap + r * (iconPx + vGap)
                        drawImage(
                            image = iconBitmaps[idx % iconBitmaps.size],
                            dstOffset = IntOffset(left.toInt(), top.toInt()),
                            dstSize = IntSize(iconPx.toInt(), iconPx.toInt()),
                        )
                        idx++
                    }
                }
            }
        }
    }
}

/**
 * A single home-screen cell: one app icon + (optionally) a label, sized from the icon-size factor.
 *
 * IMPORTANT: this must NOT call InvariantDeviceProfile.getDeviceProfile() — in the preference
 * screen the WindowManagerProxy is null and that path NPEs (it needs a launcher window context).
 * Instead we size the icon directly from the factor (base 48dp × factor), which is faithful for an
 * example cell and crash-free. Reacts live as the user adjusts the icon-size slider / labels.
 */
@Composable
private fun ExampleCell(
    showLabels: Boolean,
    iconFactor: Float,
    recommendation: GridSizeCaps.Recommendation,
) {
    val context = LocalContext.current
    // Base home icon ≈ 48dp (matches GridSizeCaps.ICON_BASE_DP); scale by the user's factor.
    val iconSizeDp = (48f * iconFactor).dp
    val labelSizeSp = 13.sp

    // Sample icon: the app's own icon, always available without a model/LauncherApps query.
    val iconBitmap = remember {
        val d = context.packageManager.getApplicationIcon(context.applicationInfo)
        d.toBitmap(width = maxOf(1, d.intrinsicWidth), height = maxOf(1, d.intrinsicHeight))
            .asImageBitmap()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Image(
            bitmap = iconBitmap,
            contentDescription = null,
            modifier = Modifier.size(iconSizeDp),
        )
        if (showLabels) {
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = stringResource(id = R.string.anchor_grid_wizard_example_label),
                fontSize = labelSizeSp,
                maxLines = 1,
            )
        }
    }
}

/** Slider position (0=Spacious, 1=Balanced, 2=Dense) for a density. */
private fun densityToSlider(d: GridSizeCaps.Density): Float = when (d) {
    GridSizeCaps.Density.SPACIOUS -> 0f
    GridSizeCaps.Density.BALANCED -> 1f
    GridSizeCaps.Density.DENSE -> 2f
}

private fun sliderToDensity(v: Float): GridSizeCaps.Density = when (Math.round(v)) {
    0 -> GridSizeCaps.Density.SPACIOUS
    2 -> GridSizeCaps.Density.DENSE
    else -> GridSizeCaps.Density.BALANCED
}
