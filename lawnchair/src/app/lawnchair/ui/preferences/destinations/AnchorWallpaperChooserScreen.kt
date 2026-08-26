/*
 * Copyright (C) 2025 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.lawnchair.ui.preferences.destinations

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.anchor.AnchorWallpaperPicker
import app.anchor.wallpaper.BundledWallpapers
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.navigation.AnchorWallpaperCredits
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Anchor rotation-stable wallpaper chooser. Shows the bundled backgrounds (from [BundledWallpapers])
 * as a grid — tap one to set it as Anchor's live wallpaper — plus a "choose from photos" entry for a
 * user's own image, and a link to the wallpaper credits. All selections route through
 * [AnchorWallpaperPicker], so bundled and custom picks share the exact same rendering path.
 *
 * @param onCreditsClick invoked when the credits row is tapped. Defaults to navigating the preference
 * NavController to [AnchorWallpaperCredits] (the in-settings path); the standalone
 * [app.lawnchair.ui.preferences.AnchorWallpaperChooserActivity] passes its own handler so the screen
 * has no hard dependency on the preference NavController.
 */
@Composable
fun AnchorWallpaperChooserScreen(
    modifier: Modifier = Modifier,
    onCreditsClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val bundled = remember { BundledWallpapers.available(context) }
    // Read the preference NavController lazily — only when no explicit onCreditsClick was supplied — so
    // the standalone chooser activity (which has no NavController in its CompositionLocals) doesn't hit
    // LocalNavController's error() default.
    val fallbackNavController = if (onCreditsClick == null) LocalNavController.current else null
    val creditsClick = onCreditsClick ?: { fallbackNavController!!.navigate(AnchorWallpaperCredits) }

    PreferenceLayout(
        label = stringResource(R.string.anchor_wallpaper_chooser_title),
        modifier = modifier,
    ) {
        PreferenceGroup(
            heading = stringResource(R.string.anchor_wallpaper_chooser_bundled_heading),
            description = stringResource(R.string.anchor_wallpaper_chooser_bundled_desc),
        ) {
            // One unified grid of cards. "Pick your own" is the FIRST card so it stays reachable no
            // matter how many bundled backgrounds we ship; the bundled ones follow. Choosing your own
            // image is just another option in the same grid.
            val cells: List<@Composable (Modifier) -> Unit> = buildList {
                add { m ->
                    WallpaperCard(
                        title = stringResource(R.string.anchor_wallpaper_chooser_choose_photo),
                        subtitle = stringResource(R.string.anchor_wallpaper_chooser_choose_photo_desc),
                        modifier = m,
                        thumbnail = { thumbModifier -> PickYourOwnThumbnail(thumbModifier) },
                        onClick = { activity?.let { AnchorWallpaperPicker.launch(it) } },
                    )
                }
                bundled.forEach { entry ->
                    add { m ->
                        SplitWallpaperCard(
                            entry = entry,
                            modifier = m,
                            onPick = { variant ->
                                activity?.let {
                                    AnchorWallpaperPicker.selectBundled(
                                        it, entry.rawResId(it, variant),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            // Adaptive column count: aim for ~150dp-wide cards so the grid looks right in portrait
            // (usually 2 columns on a phone) and landscape / on a tablet (3–5 columns), instead of a
            // fixed count that gets too wide or too cramped depending on orientation and screen size.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columns = (maxWidth / 170.dp).toInt().coerceIn(2, 5)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    cells.chunked(columns).forEach { rowCells ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            rowCells.forEach { cell -> cell(Modifier.weight(1f)) }
                            // Pad the last row so tiles keep a consistent width, not stretched.
                            repeat(columns - rowCells.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        PreferenceGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { creditsClick() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.anchor_wallpaper_chooser_credits_link),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/** A card in the wallpaper grid: a square thumbnail with a title + subtitle below. */
@Composable
private fun WallpaperCard(
    title: String,
    subtitle: String,
    thumbnail: @Composable (Modifier) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            thumbnail(Modifier.fillMaxWidth())
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * A bundled-wallpaper card whose thumbnail is SPLIT: the light variant fills the left half, the dark
 * variant the right half (like the GNOME wallpaper picker). Tapping a half applies that variant.
 */
@Composable
private fun SplitWallpaperCard(
    entry: BundledWallpapers.Entry,
    onPick: (BundledWallpapers.Variant) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // Each half shows the CORRESPONDING half of its variant so the two join as one continuous
            // image (left half = left of the light image, right half = right of the dark image), like
            // the GNOME picker — not two centered copies.
            VariantHalf(
                entry = entry,
                variant = BundledWallpapers.Variant.LIGHT,
                align = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable { onPick(BundledWallpapers.Variant.LIGHT) },
            )
            VariantHalf(
                entry = entry,
                variant = BundledWallpapers.Variant.DARK,
                align = Alignment.CenterEnd,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable { onPick(BundledWallpapers.Variant.DARK) },
            )
        }
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
        Text(
            text = entry.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * One half of a split card: shows [variant] of [entry], cropped so the full square image is centered
 * within this half (each half is a window onto the middle of the wallpaper, so both sides read as the
 * same image in two tones).
 */
@Composable
private fun VariantHalf(
    entry: BundledWallpapers.Entry,
    variant: BundledWallpapers.Variant,
    align: Alignment,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = thumbCache["${entry.id}_${variant.suffix}"], entry.id, variant) {
        // Already cached from a previous open? produceState seeded `value` with it above; skip decode.
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            loadThumb(context, entry, variant)
        }
    }
    Box(modifier = modifier) {
        thumb?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = entry.title,
                // Crop scales the square image to fill this half-width slot's height, then the
                // horizontal alignment reveals the matching side (start = left of the image, end =
                // right), so the two halves of the card line up into one continuous picture.
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = align,
            )
        }
    }
}

// Process-level cache of decoded card thumbnails, keyed by "<id>_<variantSuffix>". The chooser decodes
// ~10 small thumbnails on open; caching them makes re-opening the chooser instant (no re-decode) and
// keeps the first open snappy since each decoded bitmap is tiny (~192px). Cleared only on process death.
private val thumbCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

private fun loadThumb(
    context: android.content.Context,
    entry: BundledWallpapers.Entry,
    variant: BundledWallpapers.Variant,
): Bitmap? {
    val key = "${entry.id}_${variant.suffix}"
    thumbCache[key]?.let { return it }
    return runCatching {
        // inSampleSize=16 turns the ~3072px source into ~192px — ample for a small card half.
        val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
        context.resources.openRawResource(entry.rawResId(context, variant)).use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()?.also { thumbCache[key] = it }
}

@Composable
private fun PickYourOwnThumbnail(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(0.5f),
        )
    }
}
