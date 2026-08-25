/*
 * Copyright 2022, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.ui.preferences.destinations

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.lawnchair.LawnchairApp
import app.anchor.grid.GridSizeCaps
import app.lawnchair.data.iconoverride.IconOverrideRepository
import app.lawnchair.nexuslauncher.OverlayCallbackImpl
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences.customPreferenceAdapter
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.theme.color.ColorMode
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.FeedPreference
import app.lawnchair.ui.preferences.components.GestureHandlerPreference
import app.lawnchair.ui.preferences.components.HomeLayoutSettings
import app.lawnchair.ui.preferences.components.NavigationActionPreference
import app.lawnchair.ui.preferences.components.OverlayHandlerPreference
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import app.lawnchair.ui.preferences.components.controls.SliderPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.navigation.GridWizard
import app.lawnchair.ui.preferences.navigation.HomeScreenGrid
import app.lawnchair.util.collectAsStateBlocking
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.celllayout.CellPosMapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object HomeScreenRoutes {
    const val GRID = "grid"
    const val POPUP_EDITOR = "popup_editor"
}

@Composable
fun HomeScreenPreferences(
    modifier: Modifier = Modifier,
) {
    val prefs = preferenceManager()
    val prefs2 = preferenceManager2()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    PreferenceLayout(
        label = stringResource(id = R.string.home_screen_label),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        val lockHomeScreenAdapter = prefs2.lockHomeScreen.getAdapter()
        val showDeckLayout = prefs2.showDeckLayout.getAdapter().state.value

        // Anchor wallpaper/rotation/drawer state — hoisted so the items can be distributed across
        // the logically-grouped PreferenceGroups below (Wallpaper, Rotation, App drawer access)
        // rather than bundled under a single "Anchor" heading.
        val anchorPrefs = remember { app.anchor.AnchorPreferences(context) }
        var wallpaperSource by remember { mutableStateOf(anchorPrefs.wallpaperSource) }
        var customPath by remember { mutableStateOf(anchorPrefs.customWallpaperPath) }
        val pickImage = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri != null) {
                scope.launch(Dispatchers.IO) {
                    val ok = app.anchor.rotation.WallpaperStabilizationManager
                        .importCustomWallpaper(context, uri)
                    if (ok) {
                        anchorPrefs.wallpaperSource =
                            app.anchor.AnchorPreferences.WALLPAPER_SOURCE_CUSTOM
                        withContext(Dispatchers.Main) {
                            wallpaperSource = app.anchor.AnchorPreferences.WALLPAPER_SOURCE_CUSTOM
                            customPath = anchorPrefs.customWallpaperPath
                        }
                    }
                }
            }
        }

        if (showDeckLayout) {
            HomeLayoutSettings()
        }

        PreferenceGroup(heading = stringResource(id = R.string.general_label)) {
            val addIconToHomeAdapter = prefs.addIconToHome.getAdapter()
            val isDeckLayoutAdapter = prefs2.deckLayout.getAdapter()
            Item(
                "add_icon_to_home",
                !isDeckLayoutAdapter.state.value,
            ) {
                SwitchPreference(
                    checked = (!lockHomeScreenAdapter.state.value && addIconToHomeAdapter.state.value) || isDeckLayoutAdapter.state.value,
                    onCheckedChange = addIconToHomeAdapter::onChange,
                    label = stringResource(id = R.string.auto_add_shortcuts_label),
                    description = if (lockHomeScreenAdapter.state.value) stringResource(id = R.string.home_screen_locked) else null,
                    enabled = lockHomeScreenAdapter.state.value.not(),
                )
            }
            Item {
                GestureHandlerPreference(
                    adapter = prefs2.doubleTapGestureHandler.getAdapter(),
                    label = stringResource(id = R.string.gesture_double_tap),
                )
            }
            Item {
                SwitchPreference(
                    prefs.infiniteScrolling.getAdapter(),
                    label = stringResource(id = R.string.infinite_scrolling_label),
                    description = stringResource(id = R.string.infinite_scrolling_description),
                )
            }
        }
        PreferenceGroup(heading = stringResource(id = R.string.home_screen_actions)) {
            Item {
                ClickablePreference(
                    label = stringResource(id = R.string.remove_all_views_from_home_screen),
                    confirmationText = stringResource(id = R.string.remove_all_views_from_home_screen_desc),
                    onClick = {
                        scope.launch {
                            clearAllViewsFromHomeScreen(context, LauncherSettings.Favorites.CONTAINER_DESKTOP)
                        }
                    },
                )
            }
        }
        val feedAvailable = OverlayCallbackImpl.minusOneAvailable(LocalContext.current)
        val enableFeedAdapter = prefs2.enableFeed.getAdapter()
        PreferenceGroup(heading = stringResource(id = R.string.minus_one)) {
            Item {
                SwitchPreference(
                    adapter = enableFeedAdapter,
                    label = stringResource(id = R.string.minus_one_enable),
                    description = if (feedAvailable) null else stringResource(id = R.string.minus_one_unavailable),
                    enabled = feedAvailable,
                )
            }
            Item(
                key = "feed_pref",
                visible = feedAvailable && enableFeedAdapter.state.value,
            ) {
                FeedPreference()
            }
        }
        PreferenceGroup(heading = stringResource(R.string.style)) {
            Item { HomeScreenTextColorPreference() }
            Item {
                OverlayHandlerPreference(
                    adapter = prefs2.closingAppOverlay.getAdapter(),
                    label = stringResource(id = R.string.app_closing_animation),
                )
            }
        }
        PreferenceGroup(heading = stringResource(id = R.string.wallpaper)) {
            // ── Anchor rotation-stable wallpaper ─────────────────────────────────────────────────
            // Master toggle: ON = Anchor renders a picked image with rotation stabilization
            // (source = CUSTOM); OFF = the system shows the wallpaper normally (source = SYSTEM).
            val anchorWallpaperOn =
                wallpaperSource == app.anchor.AnchorPreferences.WALLPAPER_SOURCE_CUSTOM
            Item {
                SwitchPreference(
                    checked = anchorWallpaperOn,
                    onCheckedChange = { enabled ->
                        val newValue = if (enabled) {
                            app.anchor.AnchorPreferences.WALLPAPER_SOURCE_CUSTOM
                        } else {
                            app.anchor.AnchorPreferences.WALLPAPER_SOURCE_SYSTEM
                        }
                        wallpaperSource = newValue
                        anchorPrefs.wallpaperSource = newValue
                    },
                    label = stringResource(id = R.string.anchor_wallpaper_use_anchor_label),
                    description = stringResource(id = R.string.anchor_wallpaper_use_anchor_description),
                )
            }
            if (anchorWallpaperOn) {
                Item {
                    ClickablePreference(
                        label = stringResource(id = R.string.anchor_wallpaper_pick_image_label),
                        subtitle = if (customPath != null) {
                            stringResource(id = R.string.anchor_wallpaper_pick_image_set)
                        } else {
                            stringResource(id = R.string.anchor_wallpaper_pick_image_none)
                        },
                        onClick = {
                            pickImage.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                    )
                }
                // Wallpaper parallax strength — only meaningful with the stabilized custom wallpaper.
                Item {
                    Text(
                        text = stringResource(id = R.string.anchor_wallpaper_parallax_description),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = androidx.compose.ui.Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 8.dp,
                        ),
                    )
                }
                Item {
                    var parallax by remember { mutableStateOf(anchorPrefs.wallpaperParallaxPercent) }
                    val parallaxAdapter = customPreferenceAdapter(parallax) { newValue ->
                        parallax = newValue
                        anchorPrefs.wallpaperParallaxPercent = newValue
                    }
                    SliderPreference(
                        label = stringResource(id = R.string.anchor_wallpaper_parallax_label),
                        adapter = parallaxAdapter,
                        valueRange = app.anchor.AnchorPreferences.PARALLAX_PERCENT_MIN..
                            app.anchor.AnchorPreferences.PARALLAX_PERCENT_MAX,
                        step = 1,
                        showUnit = "%",
                    )
                }
                Item {
                    var useTest by remember { mutableStateOf(anchorPrefs.useTestWallpaper) }
                    val useTestAdapter = customPreferenceAdapter(useTest) { newValue ->
                        useTest = newValue
                        anchorPrefs.useTestWallpaper = newValue
                    }
                    SwitchPreference(
                        adapter = useTestAdapter,
                        label = stringResource(id = R.string.anchor_test_wallpaper_label),
                        description = stringResource(id = R.string.anchor_test_wallpaper_description),
                    )
                }
            }
            // ── System wallpaper behaviour ───────────────────────────────────────────────────────
            Item {
                SwitchPreference(
                    prefs.wallpaperScrolling.getAdapter(),
                    label = stringResource(id = R.string.wallpaper_scrolling_label),
                )
            }
            Item(
                "wallpaper_depth_effect",
                Utilities.ATLEAST_R,
            ) {
                SwitchPreference(
                    prefs2.wallpaperDepthEffect.getAdapter(),
                    label = stringResource(id = R.string.wallpaper_depth_effect_label),
                    description = stringResource(id = R.string.wallpaper_depth_effect_description),
                )
            }
            Item {
                SwitchPreference(
                    adapter = prefs2.showTopShadow.getAdapter(),
                    label = stringResource(id = R.string.show_sys_ui_scrim),
                )
            }
        }
        val columns by prefs.workspaceColumns.getAdapter()
        val rows by prefs.workspaceRows.getAdapter()
        PreferenceGroup(heading = stringResource(id = R.string.layout)) {
            Item {
                NavigationActionPreference(
                    label = stringResource(id = R.string.home_screen_grid),
                    destination = HomeScreenGrid,
                    subtitle = stringResource(id = R.string.x_by_y, columns, rows),
                )
            }
            Item {
                SliderPreference(
                    label = stringResource(id = R.string.anchor_workspace_spacing_label),
                    adapter = prefs2.workspaceSpacingDp.getAdapter(),
                    step = 2,
                    valueRange = 0..40,
                )
            }
            Item {
                SwitchPreference(
                    adapter = lockHomeScreenAdapter,
                    label = stringResource(id = R.string.home_screen_lock),
                    description = stringResource(id = R.string.home_screen_lock_description),
                )
            }
            Item {
                NavigationActionPreference(
                    label = stringResource(id = R.string.anchor_run_grid_wizard),
                    subtitle = stringResource(id = R.string.anchor_run_grid_wizard_desc),
                    destination = GridWizard,
                )
            }
        }
        val homeScreenLabelsAdapter = prefs2.showIconLabelsOnHomeScreen.getAdapter()
        var showLabelsOffDialog by remember { mutableStateOf(false) }
        var showLabelsOnDialog by remember { mutableStateOf(false) }
        var prevLabelsOn by remember { mutableStateOf(homeScreenLabelsAdapter.state.value) }
        var dialogAddColumn by remember { mutableStateOf(true) }
        var dialogIncreaseSpacing by remember { mutableStateOf(true) }
        var dialogRemoveColumn by remember { mutableStateOf(true) }
        var dialogReduceSpacing by remember { mutableStateOf(true) }
        LabelToggleDialogs(
            context = context,
            prefs = prefs,
            prefs2 = prefs2,
            showLabelsOffDialog = showLabelsOffDialog,
            showLabelsOnDialog = showLabelsOnDialog,
            dialogAddColumn = dialogAddColumn,
            dialogIncreaseSpacing = dialogIncreaseSpacing,
            dialogRemoveColumn = dialogRemoveColumn,
            dialogReduceSpacing = dialogReduceSpacing,
            onDialogAddColumnChange = { dialogAddColumn = it },
            onDialogIncreaseSpacingChange = { dialogIncreaseSpacing = it },
            onDialogRemoveColumnChange = { dialogRemoveColumn = it },
            onDialogReduceSpacingChange = { dialogReduceSpacing = it },
            onDismissLabelsOff = { showLabelsOffDialog = false },
            onDismissLabelsOn = { showLabelsOnDialog = false },
        )
        PreferenceGroup(heading = stringResource(id = R.string.icons)) {
            Item {
                SliderPreference(
                    label = stringResource(id = R.string.max_icon_size),
                    adapter = prefs2.homeIconSizeFactor.getAdapter(),
                    step = 0.05f,
                    valueRange = 0.5F..1.5F,
                    showAsPercentage = true,
                )
            }
            Item {
                SwitchPreference(
                    checked = homeScreenLabelsAdapter.state.value,
                    onCheckedChange = { newValue ->
                        homeScreenLabelsAdapter.onChange(newValue)
                        if (prevLabelsOn && !newValue) {
                            dialogAddColumn = true
                            dialogIncreaseSpacing = true
                            showLabelsOffDialog = true
                        } else if (!prevLabelsOn && newValue) {
                            dialogRemoveColumn = true
                            dialogReduceSpacing = true
                            showLabelsOnDialog = true
                        }
                        prevLabelsOn = newValue
                    },
                    label = stringResource(id = R.string.show_labels),
                )
            }
            Item {
                SwitchPreference(
                    adapter = prefs2.showIconLabelsOnHomeScreenFolder.getAdapter(),
                    label = stringResource(id = R.string.show_group_labels),
                )
            }
            Item(
                "workspace_label_size",
                homeScreenLabelsAdapter.state.value,
            ) {
                SliderPreference(
                    label = stringResource(id = R.string.label_size),
                    adapter = prefs2.homeIconLabelSizeFactor.getAdapter(),
                    step = 0.1f,
                    valueRange = 0.5F..1.5F,
                    showAsPercentage = true,
                )
            }
        }
        val overrideRepo = IconOverrideRepository.INSTANCE.get(LocalContext.current)
        val customIconsCount by remember { overrideRepo.observeCount() }.collectAsStateBlocking()
        if (customIconsCount > 0) {
            PreferenceGroup {
                Item {
                    ClickablePreference(
                        label = stringResource(id = R.string.reset_custom_icons),
                        confirmationText = stringResource(id = R.string.reset_custom_icons_confirmation),
                        onClick = { scope.launch { overrideRepo.deleteAll() } },
                    )
                }
            }
        }
        PreferenceGroup(heading = stringResource(id = R.string.popup_menu)) {
            Item { LauncherPopupPreferenceItem() }
        }
        val showStatusBarAdapter = prefs2.showStatusBar.getAdapter()
        PreferenceGroup(heading = stringResource(id = R.string.status_bar_label)) {
            Item {
                SwitchPreference(
                    adapter = showStatusBarAdapter,
                    label = stringResource(id = R.string.show_status_bar),
                )
            }
            Item(
                "dark_status_bar",
                showStatusBarAdapter.state.value,
            ) {
                SwitchPreference(
                    adapter = prefs2.darkStatusBar.getAdapter(),
                    label = stringResource(id = R.string.dark_status_bar_label),
                )
            }
            Item(
                "status_bar_clock",
                showStatusBarAdapter.state.value && LawnchairApp.isRecentsEnabled,
            ) {
                SwitchPreference(
                    adapter = prefs2.statusBarClock.getAdapter(),
                    label = stringResource(id = R.string.status_bar_clock_label),
                    description = stringResource(id = R.string.status_bar_clock_description),
                )
            }
        }
        PreferenceGroup(heading = stringResource(id = R.string.widget_button_text)) {
            Item {
                SwitchPreference(
                    adapter = prefs2.roundedWidgets.getAdapter(),
                    label = stringResource(id = R.string.force_rounded_widgets),
                )
            }
            Item {
                SwitchPreference(
                    adapter = prefs2.allowWidgetOverlap.getAdapter(),
                    label = stringResource(id = R.string.allow_widget_overlap),
                )
            }
            Item {
                SwitchPreference(
                    adapter = prefs2.widgetUnlimitedSize.getAdapter(),
                    label = stringResource(id = R.string.widget_unlimited_size_label),
                    description = stringResource(id = R.string.widget_unlimited_size_description),
                )
            }
            Item {
                SwitchPreference(
                    adapter = prefs2.forceWidgetResize.getAdapter(),
                    label = stringResource(id = R.string.force_widget_resize_label),
                    description = stringResource(id = R.string.force_widget_resize_description),
                )
            }
        }
        // ── Rotation (Anchor) ────────────────────────────────────────────────────────────────
        var transition by remember { mutableStateOf(anchorPrefs.rotationTransition) }
        PreferenceGroup(heading = stringResource(id = R.string.anchor_rotation_group_label)) {
            Item {
                val transitionAdapter = customPreferenceAdapter(transition) { newValue ->
                    transition = newValue
                    anchorPrefs.rotationTransition = newValue
                }
                ListPreference(
                    adapter = transitionAdapter,
                    entries = listOf(
                        ListPreferenceEntry(app.anchor.AnchorPreferences.TRANSITION_TRADITIONAL) {
                            stringResource(id = R.string.anchor_rotation_transition_traditional)
                        },
                        ListPreferenceEntry(app.anchor.AnchorPreferences.TRANSITION_INSTANT) {
                            stringResource(id = R.string.anchor_rotation_transition_instant)
                        },
                        ListPreferenceEntry(app.anchor.AnchorPreferences.TRANSITION_FADE) {
                            stringResource(id = R.string.anchor_rotation_transition_fade)
                        },
                    ),
                    label = stringResource(id = R.string.anchor_rotation_transition_label),
                    description = stringResource(id = R.string.anchor_rotation_transition_description),
                )
            }
            // Fade duration only applies to the Fade mode.
            if (transition == app.anchor.AnchorPreferences.TRANSITION_FADE) {
                Item {
                    var duration by remember { mutableStateOf(anchorPrefs.rotationFadeDurationMs) }
                    val durationAdapter = customPreferenceAdapter(duration) { newValue ->
                        duration = newValue
                        anchorPrefs.rotationFadeDurationMs = newValue
                    }
                    SliderPreference(
                        label = stringResource(id = R.string.anchor_rotation_fade_duration_label),
                        adapter = durationAdapter,
                        valueRange = app.anchor.AnchorPreferences.FADE_DURATION_MIN..
                            app.anchor.AnchorPreferences.FADE_DURATION_MAX,
                        step = 25,
                        showUnit = "ms",
                    )
                }
            }
        }

        // ── App drawer access (Anchor) ───────────────────────────────────────────────────────
        var bottomEdgeSwipeUp by remember {
            mutableStateOf(anchorPrefs.bottomEdgeSwipeUpAllApps)
        }
        PreferenceGroup(heading = stringResource(id = R.string.anchor_drawer_access_group_label)) {
            Item {
                val bottomEdgeSwipeUpAdapter = customPreferenceAdapter(bottomEdgeSwipeUp) { newValue ->
                    bottomEdgeSwipeUp = newValue
                    anchorPrefs.bottomEdgeSwipeUpAllApps = newValue
                }
                SwitchPreference(
                    adapter = bottomEdgeSwipeUpAdapter,
                    label = stringResource(id = R.string.anchor_bottom_edge_swipe_up_label),
                    description = stringResource(id = R.string.anchor_bottom_edge_swipe_up_description),
                )
            }
            if (bottomEdgeSwipeUp) {
                Item {
                    var zone by remember { mutableStateOf(anchorPrefs.bottomEdgeSwipeUpZonePercent) }
                    val zoneAdapter = customPreferenceAdapter(zone) { newValue ->
                        zone = newValue
                        anchorPrefs.bottomEdgeSwipeUpZonePercent = newValue
                    }
                    SliderPreference(
                        label = stringResource(id = R.string.anchor_bottom_edge_swipe_up_zone_label),
                        adapter = zoneAdapter,
                        valueRange = app.anchor.AnchorPreferences.BOTTOM_EDGE_ZONE_PERCENT_MIN..
                            app.anchor.AnchorPreferences.BOTTOM_EDGE_ZONE_PERCENT_MAX,
                        step = 1,
                        showUnit = "%",
                    )
                }
            }
        }
    }
}

/**
 * The two "adjust grid when toggling labels" dialogs, extracted so the main preference list stays
 * readable and the Icons group can live directly under the grid Layout group. Renders nothing when
 * both flags are false.
 */
@Composable
private fun LabelToggleDialogs(
    context: Context,
    prefs: PreferenceManager,
    prefs2: PreferenceManager2,
    showLabelsOffDialog: Boolean,
    showLabelsOnDialog: Boolean,
    dialogAddColumn: Boolean,
    dialogIncreaseSpacing: Boolean,
    dialogRemoveColumn: Boolean,
    dialogReduceSpacing: Boolean,
    onDialogAddColumnChange: (Boolean) -> Unit,
    onDialogIncreaseSpacingChange: (Boolean) -> Unit,
    onDialogRemoveColumnChange: (Boolean) -> Unit,
    onDialogReduceSpacingChange: (Boolean) -> Unit,
    onDismissLabelsOff: () -> Unit,
    onDismissLabelsOn: () -> Unit,
) {
    if (showLabelsOffDialog) {
        // Headroom check for "add a column": compute the fit-aware cap for the NEW (labels-off)
        // state against the spacing that WILL apply (the increase-spacing checkbox raises the
        // gap, which lowers the column cap). If columns are already at that cap, there is no
        // room to add one, so grey out the option.
        val factor = prefs2.homeIconSizeFactor.firstBlocking()
        val effectiveSpacing =
            (if (dialogIncreaseSpacing) 12 else prefs2.workspaceSpacingDp.firstBlocking()).toFloat()
        val addColumnCap = GridSizeCaps.compute(
            context, effectiveSpacing, factor, showLabels = false,
        ).maxColumns
        val canAddColumn = prefs.workspaceColumns.get() < addColumnCap
        if (!canAddColumn) onDialogAddColumnChange(false)
        AlertDialog(
            onDismissRequest = onDismissLabelsOff,
            title = { Text(stringResource(id = R.string.labels_off_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(id = R.string.labels_off_dialog_body))
                    Spacer(modifier = androidx.compose.ui.Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = dialogAddColumn,
                            onCheckedChange = onDialogAddColumnChange,
                            enabled = canAddColumn,
                        )
                        Text(
                            text = stringResource(id = R.string.labels_off_dialog_add_column),
                            color = if (canAddColumn) {
                                androidx.compose.material3.LocalContentColor.current
                            } else {
                                androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.38f)
                            },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dialogIncreaseSpacing, onCheckedChange = onDialogIncreaseSpacingChange)
                        Text(stringResource(id = R.string.labels_off_dialog_increase_spacing))
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    // Apply spacing first so the cap is computed against the spacing the user
                    // is about to have (a larger gap → smaller S → fewer cells fit).
                    if (dialogIncreaseSpacing) prefs2.workspaceSpacingDp.setBlocking(12)
                    if (dialogAddColumn) {
                        // Respect the fit-aware cap for the NEW (labels-off) state so the
                        // suggestion never pushes columns past where icons would overflow.
                        val targetSpacing = prefs2.workspaceSpacingDp.firstBlocking().toFloat()
                        val iconFactor = prefs2.homeIconSizeFactor.firstBlocking()
                        val cap = GridSizeCaps.compute(
                            context, targetSpacing, iconFactor, showLabels = false,
                        ).maxColumns
                        prefs.workspaceColumns.set(
                            (prefs.workspaceColumns.get() + 1).coerceAtMost(cap),
                        )
                    }
                    onDismissLabelsOff()
                }) { Text(stringResource(id = R.string.labels_off_dialog_apply)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onDismissLabelsOff) {
                    Text(stringResource(id = android.R.string.cancel))
                }
            },
        )
    }

    if (showLabelsOnDialog) {
        AlertDialog(
            onDismissRequest = onDismissLabelsOn,
            title = { Text(stringResource(id = R.string.labels_on_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(id = R.string.labels_on_dialog_body))
                    Spacer(modifier = androidx.compose.ui.Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dialogRemoveColumn, onCheckedChange = onDialogRemoveColumnChange)
                        Text(stringResource(id = R.string.labels_on_dialog_remove_column))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dialogReduceSpacing, onCheckedChange = onDialogReduceSpacingChange)
                        Text(stringResource(id = R.string.labels_on_dialog_reduce_spacing))
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (dialogRemoveColumn) prefs.workspaceColumns.set(maxOf(1, prefs.workspaceColumns.get() - 1))
                    if (dialogReduceSpacing) prefs2.workspaceSpacingDp.setBlocking(4)
                    onDismissLabelsOn()
                }) { Text(stringResource(id = R.string.labels_off_dialog_apply)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onDismissLabelsOn) {
                    Text(stringResource(id = android.R.string.cancel))
                }
            },
        )
    }
}

private fun clearAllViewsFromHomeScreen(context: Context, type: Int) {
    val launcherModel = LauncherAppState.getInstance(context).model
    val modelWriter = launcherModel.getWriter(
        verifyChanges = false,
        cellPosMapper = CellPosMapper.DEFAULT,
        owner = null,
    )
    val isViewsRemoved = modelWriter.clearAllHomeScreenViewsByType(type)
    if (isViewsRemoved) {
        launcherModel.forceReload()
        Toast.makeText(
            context,
            R.string.home_screen_all_views_removed_msg,
            Toast.LENGTH_SHORT,
        ).show()
    }
}

@Composable
fun HomeScreenTextColorPreference(
    modifier: Modifier = Modifier,
) {
    ListPreference(
        adapter = preferenceManager2().workspaceTextColor.getAdapter(),
        entries = ColorMode.entries(),
        label = stringResource(id = R.string.home_screen_text_color),
        modifier = modifier,
    )
}
