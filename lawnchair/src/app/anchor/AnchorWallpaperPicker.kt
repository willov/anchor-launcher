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
     * Launches the system live-wallpaper confirm dialog pre-targeted at [AnchorWallpaperService] and
     * suspends until the user answers it. Returns true only if they actually tapped "Set wallpaper"
     * (`RESULT_OK`) — false if they backed out, dismissed it, or it could not be shown at all.
     *
     * The dialog is REQUIRED every time, even when Anchor is already the active engine and only the
     * image underneath it is changing — it is Android's consent gate for a live wallpaper component,
     * not a one-time permission grant. Going through the system flow on every switch is also what some
     * OEMs (verified: Samsung) need in order to (re-)establish the wallpaper surface's visibility;
     * skipping it and just overwriting the file leaves the surface half-attached and home blanks to
     * black on the next resume. So the caller must not commit any state
     * ([AnchorPreferences.wallpaperSource]) before this resolves — otherwise backing out still leaves
     * Anchor "set" from the app's own point of view, pointing at an engine the system was never told
     * to (re)activate.
     */
    suspend fun setAsLiveWallpaper(activity: Activity): Boolean {
        maybeShowPortraitLockNotice(activity)
        val component = serviceComponent(activity)
        val preview = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        }
        // Diagnostic: an intermittent report of the system confirm screen not appearing at all on a
        // FIRST attempt (the retry then works). Two candidates we cannot yet separate — Android
        // swallowing the start as a background-activity launch, or the result coming straight back as
        // CANCELED. Log the elapsed time and the code: a near-instant CANCELED means the dialog was
        // never really shown, whereas a slow one means the user actually saw and dismissed it.
        val startedAt = android.os.SystemClock.elapsedRealtime()
        // Diagnostic 2: an intermittent report of the screen not returning to landscape after the
        // confirm screen's forced portrait. The system's confirm activity is hardcoded
        // screenOrientation=portrait with no configChanges (verified in LiveWallpapersPicker's own
        // manifest), so the rotation itself is unavoidable; the open question is what does or does not
        // restore it afterwards. Capture the rotation SETTING and the actual DISPLAY rotation on both
        // sides of the call: if the setting still reads landscape while the display reads portrait,
        // the preference survived and simply was not re-applied, which is a very different problem
        // from the setting having been overwritten.
        val rotBefore = rotationSnapshot(activity)
        val confirmed = try {
            val code = BlankActivity.startBlankActivityForResult(activity, preview).resultCode
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
            Log.i(TAG, "CHANGE_LIVE_WALLPAPER result=$code after ${elapsed}ms")
            Log.i(TAG, "rotation before=$rotBefore after=${rotationSnapshot(activity)}")
            if (code != Activity.RESULT_OK && elapsed < SUSPICIOUSLY_FAST_DISMISS_MS) {
                Log.w(
                    TAG,
                    "Confirm screen returned in ${elapsed}ms - too fast to have been seen; " +
                        "the system likely never showed it",
                )
            }
            code == Activity.RESULT_OK
        } catch (e: Exception) {
            Log.w(TAG, "Direct live-wallpaper preview failed (${e.message}); opening chooser")
            runCatching {
                BlankActivity.startBlankActivityForResult(
                    activity, Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER),
                )
            }.getOrNull()?.resultCode == Activity.RESULT_OK
        }

        return confirmed
    }

    /**
     * Below this, a non-OK result from the system confirm screen cannot plausibly be a real user
     * dismissal - nobody reads and rejects a wallpaper prompt that fast - so it points at the screen
     * never having been shown at all. Diagnostic only; nothing branches on it.
     */
    private const val SUSPICIOUSLY_FAST_DISMISS_MS = 400L

    /**
     * Logs the rotation state on the first resume after a wallpaper was set — the moment the user is
     * back on home and would notice the screen not having returned to landscape. Read-only: it must
     * NOT clear [AnchorPreferences.pendingWallpaperRestart], which the launcher's own restart path and
     * the wallpaper-changed receiver both still need.
     *
     * Diagnostic for an intermittent report that the screen sometimes stays portrait after the system
     * confirm screen forced it there. Pair the line this prints with the "rotation before=... after=..."
     * line from [setAsLiveWallpaper]: together they show whether the rotation SETTING survived the
     * whole flow, and whether the DISPLAY ever came back to match it.
     */
    @JvmStatic
    fun logRotationAfterWallpaperSetIfPending(context: Context) {
        val prefs = AnchorPreferences(context)
        if (!prefs.pendingWallpaperRestart && !prefs.pendingWallpaperHeal) return
        Log.i(TAG, "resume after wallpaper set: ${rotationSnapshot(context)}")
    }

    /**
     * "setting=N,display=M" for the diagnostics above. `setting` is Settings.System.USER_ROTATION —
     * the orientation the user has locked the device to — and `display` is what the screen is actually
     * showing. They are normally equal; the interesting case is when they diverge.
     */
    private fun rotationSnapshot(context: Context): String {
        val setting = runCatching {
            android.provider.Settings.System.getInt(
                context.contentResolver, android.provider.Settings.System.USER_ROTATION,
            )
        }.getOrDefault(-1)
        val display = runCatching {
            com.android.launcher3.util.DisplayController.INSTANCE.get(context).info.rotation
        }.getOrDefault(-1)
        return "setting=$setting,display=$display"
    }

    /**
     * Closes the standalone wallpaper chooser once a pick has fully landed, so the user returns to
     * whatever was under it (home, or Settings) rather than a stale chooser grid that would otherwise
     * linger foreground and flash away on the next Home press. The system preview ran in ITS OWN task,
     * so this never affected it.
     *
     * Gated on the concrete chooser class: the same flow is ALSO invoked from the grid wizard and other
     * in-settings hosts, where finishing the caller would tear down onboarding mid-flow.
     *
     * Called only AFTER the staged commit, never before — the commit still needs this Activity as its
     * Context, and a cancelled or failed pick must leave the chooser open so the user can try again.
     */
    private fun finishStandaloneChooser(activity: Activity) {
        if (activity.javaClass.name == "app.lawnchair.ui.preferences.AnchorWallpaperChooserActivity") {
            activity.finish()
        }
    }

    /**
     * Shown once, right before launching the system CHANGE_LIVE_WALLPAPER confirm screen, if the
     * device is currently in landscape — some OEMs' confirm screen
     * (`com.android.wallpaper.livepicker.LiveWallpaperChange` on stock/Pixel) is hardcoded
     * `screenOrientation="portrait"`, forcing a visible system-driven rotation for that screen only.
     */
    private suspend fun maybeShowPortraitLockNotice(activity: Activity) {
        val prefs = AnchorPreferences(activity)
        if (prefs.wallpaperPortraitLockNoticeSuppressed) return
        val isLandscape = activity.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (!isLandscape) return
        // Cancellable: if the activity goes away while this dialog is up the coroutine is cancelled
        // rather than left suspended forever holding an Activity reference. (confirmSmallImage below
        // still uses the plain suspendCoroutine form — pre-existing, left alone here.)
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            fun finish() {
                if (cont.isActive) cont.resume(Unit)
            }
            val dialog = android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.anchor_wallpaper_portrait_lock_title)
                .setMessage(R.string.anchor_wallpaper_portrait_lock_message)
                .setPositiveButton(R.string.anchor_wallpaper_portrait_lock_ok) { _, _ -> finish() }
                .setNegativeButton(R.string.anchor_wallpaper_portrait_lock_never) { _, _ ->
                    prefs.wallpaperPortraitLockNoticeSuppressed = true
                    finish()
                }
                .setOnCancelListener { finish() }
                .show()
            cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
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

            // Import to a STAGING file, not the live one: the running wallpaper engine watches the
            // live file and would repaint the moment it changed — applying the new image before the
            // user has confirmed anything. Nothing touches the live wallpaper until they confirm.
            val staged = withContext(Dispatchers.IO) {
                WallpaperStabilizationManager.importBundledWallpaper(activity, rawResId)
            } ?: return@launch
            commitIfConfirmed(activity, staged)
        }
    }

    /**
     * Shows the system confirm dialog and, ONLY if the user actually confirms, promotes the staged
     * image over the live wallpaper file and turns Anchor's wallpaper source on. Backing out deletes
     * the staging file and leaves every piece of live state — the rendered wallpaper, the stored path,
     * the source pref — exactly as it was.
     *
     * This ordering is the whole point: the running engine watches the LIVE file, so writing there
     * before confirmation would apply the change regardless of what the user then chose.
     */
    private suspend fun commitIfConfirmed(activity: Activity, staged: java.io.File): Boolean {
        if (!setAsLiveWallpaper(activity)) {
            withContext(Dispatchers.IO) {
                WallpaperStabilizationManager.discardStagedWallpaper(activity)
            }
            return false
        }
        val prefs = AnchorPreferences(activity)
        // Arm the one-shot flags BEFORE the promote, not after.
        //
        // commitStagedWallpaper writes the live wallpaper file, and that write makes the system
        // broadcast ACTION_WALLPAPER_CHANGED. LawnchairLauncher.anchorWallpaperChangedReceiver keys off
        // pendingWallpaperRestart to tell OUR OWN set apart from a foreign wallpaper change — so if the
        // broadcast lands before the flag is set, it takes the "not ours" branch, can fire the
        // wallpaper-was-reset prompt at the user, and leaves the flag armed to be consumed by some
        // later unrelated recreate.
        //
        // Arming first is safe precisely because it is paired with the rollback below: the flags must
        // never be armed on mere CONFIRMATION (that would restart the launcher for a change that never
        // landed), but arming them immediately before a promote we are about to perform, and clearing
        // them if that promote fails, keeps them true to what actually happened.
        //
        // Setting the live wallpaper from within our running app leaves Samsung's wallpaper-surface
        // visibility in a state that blanks to black on the next Home press (a clean cold-start does
        // not). pendingWallpaperRestart makes the launcher restart on its next resume, reproducing that
        // clean state; pendingWallpaperHeal then re-asserts wallpaper visibility once so WM re-dispatches
        // it to the engine, otherwise home scroll is janky until a real recents->home/unlock transition.
        // Both are consumed in LawnchairLauncher.
        prefs.pendingWallpaperRestart = true
        prefs.pendingWallpaperHeal = true
        val committed = withContext(Dispatchers.IO) {
            WallpaperStabilizationManager.commitStagedWallpaper(activity, staged)
        }
        if (!committed) {
            // The promote failed, so the live wallpaper file and customWallpaperPath are untouched and
            // nothing was applied. Roll the flags back — there is no change for them to settle — and
            // drop the staged bytes rather than leaving them to be picked up by a later PREVIEW engine
            // (AnchorWallpaperService reads the staging file in preview mode).
            prefs.pendingWallpaperRestart = false
            prefs.pendingWallpaperHeal = false
            withContext(Dispatchers.IO) {
                WallpaperStabilizationManager.discardStagedWallpaper(activity)
            }
            return false
        }
        // CUSTOM = "Anchor wallpaper on". The image is rendered by our live wallpaper; the launcher
        // stays passthrough (keeps FLAG_SHOW_WALLPAPER) so it shows through while the offset bridge
        // drives its parallax.
        prefs.wallpaperSource = AnchorPreferences.WALLPAPER_SOURCE_CUSTOM
        finishStandaloneChooser(activity)
        return true
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

            // Staged, not live — see commitIfConfirmed. Copy to app storage only; the LIVE wallpaper
            // renders it, so no setBitmap here (it covers home+lock already).
            val staged = withContext(Dispatchers.IO) {
                WallpaperStabilizationManager.importCustomWallpaper(activity, uri)
            } ?: return@launch
            commitIfConfirmed(activity, staged)
        }
    }
}
