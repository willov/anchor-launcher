package app.lawnchair.ui.preferences.destinations

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.anchor.AnchorPreferences
import app.anchor.grid.GridSizeCaps
import app.lawnchair.preferences.asPreferenceAdapter
import app.lawnchair.preferences.customPreferenceAdapter
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.GridOverridesPreview
import app.lawnchair.ui.preferences.components.controls.SliderPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreenGridPreferences(
    modifier: Modifier = Modifier,
) {
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val scrollState = rememberScrollState()
    PreferenceLayout(
        label = stringResource(id = R.string.home_screen_grid),
        modifier = modifier,
        isExpandedScreen = true,
        scrollState = if (isPortrait) null else scrollState,
    ) {
        val prefs = preferenceManager()
        val prefs2 = preferenceManager2()
        val context = LocalContext.current
        val columnsAdapter = prefs.workspaceColumns.getAdapter()
        val rowsAdapter = prefs.workspaceRows.getAdapter()
        val increaseMaxGridSize = prefs.workspaceIncreaseMaxGridSize.getAdapter()

        val originalColumns = remember { columnsAdapter.state.value }
        val originalRows = remember { rowsAdapter.state.value }
        val columns = rememberSaveable { mutableIntStateOf(originalColumns) }
        val rows = rememberSaveable { mutableIntStateOf(originalRows) }

        // "Populate grid": temporarily fill the preview's empty cells with sample icons so the user
        // can judge a grid shape while shaping it. Render-only — never persisted, real DB untouched.
        var populateGrid by rememberSaveable { mutableStateOf(false) }

        // The populate flag is a global render-only switch; make sure it never leaks to other
        // previews (icon pack, dock) by clearing it when this screen leaves composition.
        DisposableEffect(Unit) {
            onDispose { app.anchor.grid.AnchorPreviewPopulator.setEnabled(false) }
        }

        if (isPortrait) {
            GridOverridesPreview(populateGrid = populateGrid) {
                copy(numColumns = columns.intValue, numRows = rows.intValue)
            }
        }

        // Orientation- + fit-aware caps. Because the grid transposes M×N → N×M on rotation and
        // cells are square (S derived from the SHORT screen edge), columns map to the short
        // physical side and rows to the long side. Past the fit cap, icons/labels are guaranteed
        // to overflow, so clamp the sliders there. The "increase max grid size" power-user toggle
        // lifts the cap (and falls back to the legacy flat range) for users who accept overflow.
        val spacingDp = prefs2.workspaceSpacingDp.getAdapter().state.value
        val iconFactor = prefs2.homeIconSizeFactor.getAdapter().state.value
        val showLabels = prefs2.showIconLabelsOnHomeScreen.getAdapter().state.value
        val caps = remember(spacingDp, iconFactor, showLabels) {
            GridSizeCaps.compute(context, spacingDp.toFloat(), iconFactor, showLabels)
        }
        val maxColumns = if (increaseMaxGridSize.state.value) 30 else caps.maxColumns
        val maxRows = if (increaseMaxGridSize.state.value) 30 else caps.maxRows

        // Keep the live slider value within the (possibly lowered) cap.
        if (columns.intValue > maxColumns) columns.intValue = maxColumns
        if (rows.intValue > maxRows) rows.intValue = maxRows

        // Link rows ↔ columns (like linked width/height in a design tool). When linked, moving one
        // slider moves the other along the screen's aspect ratio so the grid keeps the sweet-spot
        // proportions. Default ON; persisted in AnchorPreferences.
        val anchorPrefs = remember { AnchorPreferences(context) }
        var linked by rememberSaveable { mutableStateOf(anchorPrefs.linkGridDimensions) }

        PreferenceGroup {
            Item {
                SwitchPreference(
                    checked = linked,
                    onCheckedChange = {
                        linked = it
                        anchorPrefs.linkGridDimensions = it
                    },
                    label = stringResource(id = R.string.anchor_link_grid_dimensions),
                    description = stringResource(id = R.string.anchor_link_grid_dimensions_desc),
                )
            }
            Item {
                SliderPreference(
                    label = stringResource(id = R.string.columns),
                    adapter = customPreferenceAdapter(columns.intValue) { newCols ->
                        columns.intValue = newCols
                        if (linked) {
                            rows.intValue = GridSizeCaps.linkedRows(context, newCols)
                                .coerceIn(3, maxRows.coerceAtLeast(3))
                        }
                    },
                    step = 1,
                    valueRange = 3..maxColumns.coerceAtLeast(3),
                )
            }
            Item {
                SliderPreference(
                    label = stringResource(id = R.string.rows),
                    adapter = customPreferenceAdapter(rows.intValue) { newRows ->
                        rows.intValue = newRows
                        if (linked) {
                            columns.intValue = GridSizeCaps.linkedColumns(context, newRows)
                                .coerceIn(3, maxColumns.coerceAtLeast(3))
                        }
                    },
                    step = 1,
                    valueRange = 3..maxRows.coerceAtLeast(3),
                )
            }
            if (isPortrait) {
                Item {
                    SwitchPreference(
                        checked = populateGrid,
                        onCheckedChange = { populateGrid = it },
                        label = stringResource(id = R.string.anchor_populate_grid),
                        description = stringResource(id = R.string.anchor_populate_grid_desc),
                    )
                }
            }
        }

        val navController = LocalNavController.current
        val applyOverrides = {
            prefs.batchEdit {
                columnsAdapter.onChange(columns.intValue)
                rowsAdapter.onChange(rows.intValue)
            }
            LauncherAppState.getIDP(context).onPreferencesChanged(context)
            navController.popBackStack()
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .padding(horizontal = 16.dp),
        ) {
            Button(
                onClick = { applyOverrides() },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxWidth(),
                enabled = columns.intValue != originalColumns || rows.intValue != originalRows,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(text = stringResource(id = R.string.action_apply))
            }
        }
    }
}
