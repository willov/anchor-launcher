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
import app.lawnchair.ui.preferences.destinations.AnchorWallpaperChooserScreen
import app.lawnchair.ui.theme.EdgeToEdge
import app.lawnchair.ui.theme.LawnchairTheme

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
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, AnchorWallpaperChooserActivity::class.java)
    }
}
