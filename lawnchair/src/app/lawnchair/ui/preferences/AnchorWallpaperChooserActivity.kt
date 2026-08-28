/*
 * Copyright (C) 2025 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.lawnchair.ui.preferences

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import app.anchor.AnchorPreferences
import app.lawnchair.ui.preferences.destinations.AnchorWallpaperChooserScreen
import app.lawnchair.ui.theme.EdgeToEdge
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R

/**
 * Standalone host for the Anchor wallpaper chooser.
 *
 * ## Why this exists (not just a route in [PreferenceActivity])
 *
 * The chooser used to be reached via `PreferenceActivity.createIntent(AnchorWallpaperChooser)`, which
 * inflates the ENTIRE preference Compose NavHost (every settings destination, window-size-class +
 * display-feature calculation, the full nav graph). On the low-end test tablet that cold inflation cost
 * ~1.5s per open and got slower when the launcher process was churned by the wallpaper `recreate()`.
 * The chooser's own content is trivial (a 5-entry grid, ~50ms of thumbnail decode), so paying the whole
 * settings scaffold to show it was the bottleneck.
 *
 * This activity hosts ONLY [AnchorWallpaperChooserScreen] under [LawnchairTheme] — no NavHost, no
 * settings graph — so it opens fast and consistently. All entry points (long-press "Wallpaper", the
 * Home Screen settings row, the onboarding dialog) launch this via [createIntent].
 *
 * ## Consent gate (also fixes a state-ordering bug)
 *
 * If Anchor is not yet the wallpaper source, a consent dialog is shown BEFORE the grid: "manage your
 * wallpaper with Anchor?". Confirming sets `wallpaperSource = CUSTOM` up front, so by the time the user
 * picks a background the source is already established. Entering the picker while the source was still
 * SYSTEM and only flipping it during the pick left the launcher's live-wallpaper state (offset-bridge /
 * Launcher3 offset suppression) set up in the wrong order, which showed up as choppy parallax until a
 * settings round-trip re-established it. Setting the source first bypasses that entirely.
 *
 * The credits screen is still reached through the normal settings route (it isn't perf-sensitive), so
 * the chooser passes an [onCreditsClick] that launches [PreferenceActivity] at the credits destination.
 */
class AnchorWallpaperChooserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LawnchairTheme {
                EdgeToEdge()
                val context = LocalContext.current
                // Anchor already managing the wallpaper? Then no consent needed — show the grid.
                val alreadyEnabled = remember {
                    AnchorPreferences(context).wallpaperSource ==
                        AnchorPreferences.WALLPAPER_SOURCE_CUSTOM
                }
                var consented by remember { mutableStateOf(alreadyEnabled) }

                if (consented) {
                    AnchorWallpaperChooserScreen(
                        onCreditsClick = {
                            startActivity(
                                PreferenceActivity.createIntent(
                                    this,
                                    app.lawnchair.ui.preferences.navigation.AnchorWallpaperCredits,
                                ),
                            )
                        },
                    )
                } else {
                    // Gate: strongly encourage Anchor before showing backgrounds. Confirm sets the source
                    // to CUSTOM (Anchor on) FIRST, so the pick that follows operates from the correct,
                    // already-established state. Cancel falls through to the STOCK system wallpaper picker
                    // so the user can still set a wallpaper (just not via Anchor) — encourage, don't force.
                    AlertDialog(
                        // Widen beyond Material3's ~280dp default so the two full-width action buttons
                        // ("Use Anchor (rotation-stable)" / "Use system (not rotation-stable)") have room
                        // to render on one line. usePlatformDefaultWidth=false lets the widthIn modifier
                        // take effect; capped at 360dp so it doesn't span edge-to-edge on tablets.
                        modifier = Modifier.widthIn(min = 320.dp, max = 360.dp),
                        properties = DialogProperties(usePlatformDefaultWidth = false),
                        onDismissRequest = { openStockWallpaperPicker(); finish() },
                        title = { Text(stringResource(R.string.anchor_wallpaper_gate_title)) },
                        text = { Text(stringResource(R.string.anchor_wallpaper_gate_message)) },
                        // Two prominent, full-width buttons stacked so the recommended Anchor option is
                        // visually dominant (filled/primary) and the system option is secondary (outlined).
                        // Both go in confirmButton so they stack; the AlertDialog's own button row would
                        // otherwise place them side-by-side as small text buttons.
                        confirmButton = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        AnchorPreferences(context).wallpaperSource =
                                            AnchorPreferences.WALLPAPER_SOURCE_CUSTOM
                                        consented = true
                                    },
                                ) {
                                    GateButtonLabel(
                                        stringResource(R.string.anchor_wallpaper_gate_use_anchor),
                                    )
                                }
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { openStockWallpaperPicker(); finish() },
                                ) {
                                    GateButtonLabel(
                                        stringResource(R.string.anchor_wallpaper_gate_use_system),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * Launch the stock system wallpaper picker (the same [Intent.ACTION_SET_WALLPAPER] Launcher3's
     * long-press "Wallpaper" used before Anchor hijacked it). Used on the consent-gate cancel path so a
     * user who declines Anchor can still set a normal wallpaper.
     */
    private fun openStockWallpaperPicker() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_SET_WALLPAPER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, AnchorWallpaperChooserActivity::class.java)
    }
}

/**
 * Single-line button label that shrinks to fit rather than wrapping. The gate's two action labels
 * ("Use Anchor (rotation-stable)" / "Use system (not rotation-stable)") are long enough to wrap to two
 * lines on narrower screens (seen on Pixel); the widened dialog gives most of the room, and this
 * autosize (down to 12sp) is the safety net so the full wording always stays on one line.
 */
@Composable
private fun GateButtonLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Visible,
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 14.sp),
    )
}
