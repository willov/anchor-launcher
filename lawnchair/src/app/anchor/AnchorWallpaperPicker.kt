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
import com.android.launcher3.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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
     * Turn Anchor's wallpaper OFF: if [AnchorWallpaperService] is the active system wallpaper, clear it
     * so the system reverts to its default wallpaper. Without this, toggling Anchor off only flips the
     * pref while OUR live wallpaper stays the active system wallpaper — but now with the launcher's
     * offset bridge disabled, so it renders black AND leaves the compositor in a degraded (choppy) state.
     * Clearing restores a real system wallpaper and removes the orphaned live engine.
     *
     * WallpaperManager.clear() reverts to the built-in default (we can't restore the user's *previous*
     * wallpaper — the system doesn't expose it — but a real wallpaper beats a black live-wallpaper).
     */
    fun disableAnchorWallpaper(context: Context) {
        if (!isLiveWallpaperActive(context)) return
        runCatching { WallpaperManager.getInstance(context).clear() }
            .onFailure { Log.w(TAG, "Failed to clear Anchor live wallpaper: ${it.message}") }
        // Same Samsung quirk as setting: clearing the wallpaper from within the running launcher leaves
        // the wallpaper surface in a half-attached state → home blanks to black. Mark the one-shot
        // restart so the launcher recreates on its next resume and lands on the clean reverted state.
        AnchorPreferences(context).pendingWallpaperRestart = true
    }

    /**
     * Launches the system live-wallpaper confirm dialog pre-targeted at [AnchorWallpaperService].
     * The user taps "Set wallpaper" to apply. Falls back to the generic live-wallpaper chooser if the
     * direct preview isn't available on this device.
     */
    fun setAsLiveWallpaper(activity: Activity) {
        val component = serviceComponent(activity)
        // Setting the live wallpaper from within our running app leaves Samsung's wallpaper-surface
        // visibility in a state that blanks to black on the next Home press (a clean cold-start does
        // not). Mark a one-shot restart so the launcher restarts on its next resume, reproducing that
        // clean state. Consumed in LawnchairLauncher.onResume.
        AnchorPreferences(activity).apply {
            pendingWallpaperRestart = true
            // After the restart, re-assert wallpaper visibility once so WM re-dispatches it to the
            // engine — otherwise home scroll is janky until a real recents→home/unlock transition.
            pendingWallpaperHeal = true
        }
        val preview = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        }
        try {
            activity.startActivity(preview)
            // The system live-wallpaper preview now runs in ITS OWN task (com.android.wallpaper /
            // livepicker), independent of ours, so finishing our activity doesn't affect it. Finish the
            // standalone chooser now so that when the user taps "Set wallpaper" and the preview dismisses,
            // they return straight to whatever was under it (home, or Settings if opened from there) — NOT
            // to the now-stale chooser grid, which otherwise lingers foreground and flashes away on the
            // next Home press. Gate on the concrete chooser class: the same picker flow is ALSO invoked
            // from the grid wizard (GridWizardScreen) and other in-settings hosts, where finishing the
            // caller would tear down onboarding mid-flow. Only the dedicated chooser is safe to auto-close.
            if (activity.javaClass.name ==
                "app.lawnchair.ui.preferences.AnchorWallpaperChooserActivity"
            ) {
                activity.finish()
            }
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
            // Our own art gets the same check as a user's photo: the bundled images are 3072², which
            // is short of what a large tablet needs, so they can be upscaled and lose parallax too.
            val fit = withContext(Dispatchers.IO) {
                WallpaperStabilizationManager.measureSourceFit(activity, rawResId)
            }
            if (fit != null && (fit.upscaleFactor > UPSCALE_WARN_THRESHOLD || fit.losesParallax)) {
                if (!confirmSmallImage(activity, fit)) return@launch
            }

            val ok = withContext(Dispatchers.IO) {
                WallpaperStabilizationManager.importBundledWallpaper(activity, rawResId)
            }
            if (!ok) return@launch
            AnchorPreferences(activity).wallpaperSource = AnchorPreferences.WALLPAPER_SOURCE_CUSTOM
            applyLiveWallpaper(activity)
        }
    }

    /**
     * Apply the (already-imported) image as the live wallpaper by ALWAYS going through the system
     * CHANGE_LIVE_WALLPAPER flow — even on a switch when our engine is already the active wallpaper.
     * The system flow is required on some OEMs (verified: Samsung) to (re-)establish the wallpaper
     * surface's visibility; skipping it (just overwriting the file) leaves the surface half-attached and
     * home blanks to black on the next resume.
     *
     * On a switch the system tears down and resurrects the engine, and the resurrected engine used to
     * paint a stale/mid-decode buffer for one frame (the n-1 flash). That is now prevented at the source
     * inside [app.anchor.wallpaper.AnchorWallpaperService]: a FileObserver pre-decodes the new image into
     * a process-level cache the instant the caller overwrites the file, so the resurrected engine's first
     * frame is already the new image — no teardown gap, no flash.
     */
    private fun applyLiveWallpaper(activity: Activity) {
        setAsLiveWallpaper(activity)
    }

    /**
     * Above this, the upscale needed just to fill the screen is enough to look soft on a wallpaper.
     * Below it we stay quiet — a hair of upscaling is not worth interrupting the user for.
     */
    private const val UPSCALE_WARN_THRESHOLD = 1.3f

    /**
     * Asks the user whether to use an image that will be upscaled and/or loses parallax. Returns true
     * to proceed. Suspends until they answer, so the caller can simply bail on "pick another".
     */
    private suspend fun confirmSmallImage(
        activity: Activity,
        fit: WallpaperStabilizationManager.Companion.SourceFit,
    ): Boolean = kotlin.coroutines.suspendCoroutine { cont ->
        val res = activity.resources
        val reasons = buildList {
            if (fit.upscaleFactor > UPSCALE_WARN_THRESHOLD) {
                add(res.getString(R.string.anchor_wallpaper_small_reason_upscale))
            }
            if (fit.losesParallax) {
                add(res.getString(R.string.anchor_wallpaper_small_reason_parallax))
            }
        }
        var resumed = false
        fun finish(value: Boolean) {
            if (!resumed) { resumed = true; cont.resume(value) }
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.anchor_wallpaper_small_title)
            .setMessage(
                res.getString(
                    R.string.anchor_wallpaper_small_message,
                    reasons.joinToString("\n\n• ", prefix = "• "),
                ),
            )
            .setPositiveButton(R.string.anchor_wallpaper_small_use_anyway) { _, _ -> finish(true) }
            .setNegativeButton(R.string.anchor_wallpaper_small_pick_another) { _, _ -> finish(false) }
            .setOnCancelListener { finish(false) }
            .show()
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

            // Warn before committing to an image that is too small for this screen. We cap upscaling
            // at "just fills the screen" (see decodeDownscaled), so a small image stays as sharp as it
            // can be but has no pan room left — parallax goes quiet. Better to say so up front than to
            // let the user wonder why their wallpaper looks soft or stopped moving.
            val fit = withContext(Dispatchers.IO) {
                WallpaperStabilizationManager.measureSourceFit(activity, uri)
            }
            if (fit != null && (fit.upscaleFactor > UPSCALE_WARN_THRESHOLD || fit.losesParallax)) {
                if (!confirmSmallImage(activity, fit)) return@launch
            }

            val ok = withContext(Dispatchers.IO) {
                // Copy the image to app storage only; the LIVE wallpaper renders it. Don't also push it
                // via setBitmap — the live wallpaper covers home+lock, so setBitmap would be redundant.
                WallpaperStabilizationManager.importCustomWallpaper(
                    activity, uri, alsoSetSystemWallpaper = false,
                )
            }
            if (!ok) return@launch
            // CUSTOM = "Anchor wallpaper on". The image is rendered by our live wallpaper (set below);
            // the launcher stays passthrough (keeps FLAG_SHOW_WALLPAPER) so the live wallpaper shows
            // through while the offset bridge drives its parallax.
            AnchorPreferences(activity).wallpaperSource = AnchorPreferences.WALLPAPER_SOURCE_CUSTOM
            applyLiveWallpaper(activity)
        }
    }
}
