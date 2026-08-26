/*
 * Copyright (C) 2025 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.anchor

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import app.anchor.rotation.WallpaperStabilizationManager
import app.lawnchair.BlankActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the "Use Anchor wallpaper" flow: pick an image, import it to app storage, then set Anchor's
 * rotation-stable live wallpaper ([AnchorWallpaperService]) as the system wallpaper.
 *
 * The live wallpaper (not the old window-background drawable) is what renders the picked image now:
 * it lives on the system wallpaper SURFACE, so it stays pixel-perfect on pure rotation AND does not
 * get swept into the Shell's home-task screenshot-rotate on the app→home transition (the "snap").
 * Parallax is driven cross-process from the launcher via WallpaperManager.setWallpaperOffsets.
 *
 * Setting a live wallpaper cannot be done silently — it requires the system CHANGE_LIVE_WALLPAPER
 * confirm dialog — so [setAsLiveWallpaper] launches that; the user taps "Set wallpaper".
 */
object AnchorWallpaperPicker {

    private const val TAG = "AnchorWallpaper"

    /** The AnchorWallpaperService component in the CURRENT package (handles play/github flavors). */
    fun serviceComponent(context: Context): ComponentName =
        ComponentName(context.packageName, "app.anchor.wallpaper.AnchorWallpaperService")

    /** True if AnchorWallpaperService is the active system wallpaper. */
    fun isLiveWallpaperActive(context: Context): Boolean =
        runCatching {
            WallpaperManager.getInstance(context).wallpaperInfo?.component == serviceComponent(context)
        }.getOrDefault(false)

    /**
     * Launches the system live-wallpaper confirm dialog pre-targeted at [AnchorWallpaperService].
     * The user taps "Set wallpaper" to apply. Falls back to the generic live-wallpaper chooser if the
     * direct preview isn't available on this device.
     */
    fun setAsLiveWallpaper(activity: Activity) {
        val component = serviceComponent(activity)
        // The system live-wallpaper screen can't be skinned; a brief hint tells the user which
        // option to pick (Home vs Both) since that choice lives on that system screen.
        android.widget.Toast.makeText(
            activity,
            activity.getString(com.android.launcher3.R.string.anchor_wallpaper_set_hint),
            android.widget.Toast.LENGTH_LONG,
        ).show()
        val preview = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        }
        try {
            activity.startActivity(preview)
        } catch (e: Exception) {
            Log.w(TAG, "Direct live-wallpaper preview failed (${e.message}); opening chooser")
            runCatching {
                activity.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            }
        }
    }

    /**
     * Selects a BUNDLED wallpaper (from [app.anchor.wallpaper.BundledWallpapers]) and sets it as
     * Anchor's live wallpaper. Same tail as [launch] (import → CUSTOM source → set-live) but the
     * image comes from a raw resource instead of the photo picker.
     */
    fun selectBundled(activity: Activity, rawResId: Int) {
        CoroutineScope(Dispatchers.Main).launch {
            val ok = withContext(Dispatchers.IO) {
                WallpaperStabilizationManager.importBundledWallpaper(activity, rawResId)
            }
            if (!ok) return@launch
            AnchorPreferences(activity).wallpaperSource = AnchorPreferences.WALLPAPER_SOURCE_CUSTOM
            setAsLiveWallpaper(activity)
        }
    }

    /**
     * Full flow: pick an image → import to app storage → set Anchor's live wallpaper. Uses
     * [BlankActivity.startBlankActivityForResult] so it can be triggered from a plain context (e.g.
     * the home-screen long-press menu) without a pre-registered ActivityResultLauncher.
     */
    fun launch(activity: Activity) {
        // ACTION_PICK_IMAGES is the modern, permission-free photo picker (with a documents fallback).
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
        CoroutineScope(Dispatchers.Main).launch {
            val result = try {
                BlankActivity.startBlankActivityForResult(activity, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Photo picker failed: ${e.message}")
                return@launch
            }
            val uri = result.data?.data ?: return@launch
            val ok = withContext(Dispatchers.IO) {
                // Copy the image to app storage only; the LIVE wallpaper renders it. Don't also push it
                // via setBitmap — the live wallpaper covers home+lock, so setBitmap would be redundant.
                WallpaperStabilizationManager.importCustomWallpaper(
                    activity, uri, alsoSetSystemWallpaper = false,
                )
            }
            if (!ok) return@launch
            // CUSTOM = "Anchor wallpaper on". The image is rendered by our live wallpaper (set below),
            // NOT the window-background drawable — wallpaperStabilizationActive is false for CUSTOM, so
            // the launcher stays passthrough (keeps FLAG_SHOW_WALLPAPER) and the live wallpaper shows
            // through while the offset bridge drives its parallax.
            AnchorPreferences(activity).wallpaperSource = AnchorPreferences.WALLPAPER_SOURCE_CUSTOM
            setAsLiveWallpaper(activity)
        }
    }
}
