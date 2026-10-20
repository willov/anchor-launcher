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
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import com.android.launcher3.R

/**
 * Donate page, reached from the About screen. Anchor has no donation channel of its own yet, so this
 * points to the projects Anchor is built on and carries the same non-affiliation disclaimer as the
 * wallpaper credits screen.
 */
@Composable
fun AnchorDonateScreen(
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
        label = stringResource(R.string.donate),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.anchor_donate_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        PreferenceGroup {
            DonateTarget(
                title = "Lawnchair",
                description = stringResource(R.string.anchor_donate_lawnchair_desc),
                action = stringResource(R.string.anchor_donate_lawnchair_action),
                onClick = { openUrl("https://opencollective.com/lawnchair") },
            )
            DonateTarget(
                title = "GNOME",
                description = stringResource(R.string.anchor_donate_gnome_desc),
                action = stringResource(R.string.anchor_wallpaper_credits_gnome_donate),
                onClick = { openUrl("https://www.gnome.org/donate/") },
            )
        }

        Text(
            text = stringResource(R.string.anchor_donate_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun DonateTarget(
    title: String,
    description: String,
    action: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = action,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
