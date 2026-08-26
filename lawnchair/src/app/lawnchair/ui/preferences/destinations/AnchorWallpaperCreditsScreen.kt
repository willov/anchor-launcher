/*
 * Copyright (C) 2025 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.lawnchair.ui.preferences.destinations

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.anchor.wallpaper.BundledWallpapers
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import com.android.launcher3.R

/**
 * Wallpaper credits / attribution screen. Lists every bundled background with its artist, license,
 * and source (satisfying CC-BY-SA attribution), thanks the GNOME community with a donate link, and
 * carries the required non-affiliation disclaimer. Reachable from the wallpaper chooser and the About
 * screen.
 */
@Composable
fun AnchorWallpaperCreditsScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    PreferenceLayout(
        label = stringResource(R.string.anchor_wallpaper_credits_title),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.anchor_wallpaper_credits_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        PreferenceGroup {
            BundledWallpapers.all().forEach { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openUrl(entry.sourceUrl) }
                        .padding(16.dp),
                ) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(
                            R.string.anchor_wallpaper_credits_by,
                            entry.artist,
                            entry.license,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.anchor_wallpaper_credits_source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        PreferenceGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openUrl("https://www.gnome.org/donate/") }
                    .padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.anchor_wallpaper_credits_gnome_thanks),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.anchor_wallpaper_credits_gnome_donate),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Text(
            text = stringResource(R.string.anchor_wallpaper_credits_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}
