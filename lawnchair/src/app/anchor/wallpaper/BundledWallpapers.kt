/*
 * Copyright (C) 2025 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.anchor.wallpaper

import android.content.Context

/**
 * Registry of the wallpapers Anchor ships in-app — the single source of truth for the background
 * chooser and the credits/tribute screen.
 *
 * Each entry points at a `res/raw/anchor_wp_<id>.jpg` image (names must be `[a-z0-9_]`). The raw
 * resource id is resolved by NAME at runtime (via [android.content.res.Resources.getIdentifier]) so
 * this file has **no compile-time dependency** on the image files — the images can be dropped into
 * `res/raw/` later and [available] will start returning them, with no code change. Entries whose
 * image isn't present yet are simply filtered out.
 *
 * Bundled images are cropped GNOME backgrounds (Jakub Steiner, CC-BY-SA 3.0). The crop is a derivative
 * work, itself CC-BY-SA 3.0, and MUST be attributed as "modified" — see the credits screen. This is a
 * per-asset ShareAlike carve-out from the repo's Apache-2.0 license.
 */
object BundledWallpapers {

    /**
     * A shippable wallpaper. [rawName] is the `res/raw` file base name (no extension). [title] is the
     * user-facing name; the remaining fields are the attribution required by CC-BY-SA.
     */
    data class Entry(
        val rawName: String,
        val title: String,
        val artist: String,
        val license: String,
        val sourceUrl: String,
        val modified: Boolean = true,
    ) {
        fun rawResId(context: Context): Int =
            context.resources.getIdentifier(rawName, "raw", context.packageName)
    }

    /**
     * The catalogue. Images are added to `res/raw/` as they are cropped; entries whose file is not yet
     * present are filtered out by [available], so this list can be complete before the assets land.
     */
    private val catalogue: List<Entry> = listOf(
        Entry(
            rawName = "anchor_wp_morphogenesis",
            title = "Morphogenesis",
            artist = "Jakub Steiner",
            license = "CC-BY-SA 3.0",
            sourceUrl = "https://gitlab.gnome.org/GNOME/gnome-backgrounds",
        ),
        Entry(
            rawName = "anchor_wp_blobs",
            title = "Blobs",
            artist = "Jakub Steiner",
            license = "CC-BY-SA 3.0",
            sourceUrl = "https://gitlab.gnome.org/GNOME/gnome-backgrounds",
        ),
        Entry(
            rawName = "anchor_wp_map",
            title = "Map",
            artist = "Jakub Steiner",
            license = "CC-BY-SA 3.0",
            sourceUrl = "https://gitlab.gnome.org/GNOME/gnome-backgrounds",
        ),
    )

    /** Catalogue entries whose image is actually bundled in this build. */
    fun available(context: Context): List<Entry> =
        catalogue.filter { it.rawResId(context) != 0 }

    /** All catalogue entries, present or not — used by the credits screen to attribute everything. */
    fun all(): List<Entry> = catalogue
}
