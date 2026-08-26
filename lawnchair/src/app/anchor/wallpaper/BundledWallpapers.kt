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
 * Each background has a **light** and **dark** variant, stored as `res/raw/anchor_wp_<id>_l.jpg` and
 * `..._d.jpg` (names must be `[a-z0-9_]`). Raw resource ids are resolved by NAME at runtime (via
 * [android.content.res.Resources.getIdentifier]) so this file has **no compile-time dependency** on
 * the image files — images can be dropped into `res/raw/` later and [available] starts returning them,
 * no code change. Entries whose images aren't present are filtered out.
 *
 * Bundled images are GNOME backgrounds (Jakub Steiner, CC-BY-SA 3.0), shipped unmodified (the original
 * artwork, rasterized to JPEG from the upstream SVG/JXL). Anchor downscales/crops them at runtime for
 * display, so no modified derivative is distributed — attribution only (see the credits screen). This
 * is a per-asset ShareAlike carve-out from the repo's Apache-2.0 license.
 */
object BundledWallpapers {

    enum class Variant(val suffix: String) { LIGHT("l"), DARK("d") }

    /**
     * A shippable background with light + dark variants. [id] is the `res/raw` base name (no
     * `anchor_wp_` prefix, no variant suffix). [title] is user-facing; the rest is the CC-BY-SA
     * attribution.
     */
    data class Entry(
        val id: String,
        val title: String,
        val artist: String,
        val license: String,
        val sourceUrl: String,
    ) {
        fun rawName(variant: Variant): String = "anchor_wp_${id}_${variant.suffix}"

        fun rawResId(context: Context, variant: Variant): Int =
            context.resources.getIdentifier(rawName(variant), "raw", context.packageName)

        /** True if BOTH variants are bundled in this build. */
        fun isAvailable(context: Context): Boolean =
            rawResId(context, Variant.LIGHT) != 0 && rawResId(context, Variant.DARK) != 0
    }

    // Most GNOME backgrounds are JPEG XL upstream; a few are SVG. Point the "source" link at the
    // actual dark-variant file so attribution resolves to the specific original.
    private val svgBackgrounds = setOf("blobs", "drool", "map", "morphogenesis")

    private fun gnome(id: String, title: String): Entry {
        val ext = if (id in svgBackgrounds) "svg" else "jxl"
        return Entry(
            id = id,
            title = title,
            artist = "Jakub Steiner (GNOME project)",
            license = "CC-BY-SA 3.0",
            sourceUrl = "https://gitlab.gnome.org/GNOME/gnome-backgrounds/-/blob/main/backgrounds/$id-d.$ext",
        )
    }

    /**
     * The catalogue. Entries whose images aren't yet bundled are filtered out by [available], so this
     * list can be edited freely ahead of dropping the assets in.
     */
    private val catalogue: List<Entry> = listOf(
        gnome("adwaita", "Adwaita"),
        gnome("amber", "Amber"),
        gnome("blobs", "Blobs"),
        gnome("drool", "Drool"),
        gnome("fold", "Fold"),
    )

    /** Catalogue entries whose images are actually bundled in this build. */
    fun available(context: Context): List<Entry> = catalogue.filter { it.isAvailable(context) }

    /** All catalogue entries — used by the credits screen to attribute everything. */
    fun all(): List<Entry> = catalogue
}
