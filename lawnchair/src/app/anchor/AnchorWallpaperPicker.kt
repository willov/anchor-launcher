/*
 * Copyright (C) 2025 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.anchor

import android.app.Activity
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
 * Launches the system photo picker so the user can choose a custom image for the stabilized Anchor
 * background, then imports it (copies to app storage + sets it as the system wallpaper) and selects
 * the Custom wallpaper source.
 *
 * Uses [BlankActivity.startBlankActivityForResult] so it can be triggered from a plain context (e.g.
 * the home-screen long-press menu) without a pre-registered ActivityResultLauncher.
 */
object AnchorWallpaperPicker {

    private const val TAG = "AnchorWallpaper"

    fun launch(activity: Activity) {
        Log.d(TAG, "picker.launch()")
        // ACTION_PICK_IMAGES is the modern, permission-free photo picker (with a documents fallback).
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
        CoroutineScope(Dispatchers.Main).launch {
            val result = try {
                BlankActivity.startBlankActivityForResult(activity, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Photo picker failed: ${e.message}")
                return@launch
            }
            val uri = result.data?.data
            Log.d(TAG, "picker result: code=${result.resultCode} uri=$uri")
            if (uri == null) return@launch
            val ok = withContext(Dispatchers.IO) {
                WallpaperStabilizationManager.importCustomWallpaper(activity, uri)
            }
            Log.d(TAG, "import ok=$ok → set source=custom")
            if (ok) {
                AnchorPreferences(activity).wallpaperSource =
                    AnchorPreferences.WALLPAPER_SOURCE_CUSTOM
                // The launcher's onResume → reapplyIfChanged() applies it without a restart.
            }
        }
    }
}
