package app.anchor.rotation

import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import app.anchor.AnchorPreferences
import app.lawnchair.LawnchairLauncher

/**
 * Controls the rotation transition animation based on the user's [AnchorPreferences.rotationTransition]
 * setting. Three modes:
 *
 * - **Traditional**: nothing — the system plays its default rotation animation (exposes the grid
 *   reflow flash; kept only for users who prefer it).
 * - **Instant**: suppresses the system rotation animation with ROTATION_ANIMATION_JUMPCUT; no
 *   masking. The grid rebind is briefly visible but there is no animation.
 * - **Fade**: jumpcut + instantly hides the workspace icons, rebinds the grid hidden, then fades
 *   them back in over the pixel-stable wallpaper. No colour fill — the wallpaper is the backdrop.
 *   (The hide is instant, not a fade-out: a fade-out leaves the workspace briefly visible, letting
 *   the system's pre-transpose reflow flash through. The smoothness is in the fade-in on reveal.)
 *
 * The fade-in duration is [AnchorPreferences.rotationFadeDurationMs].
 *
 * The mode and duration are read **fresh from prefs on every rotation** (in [refreshModeFromPrefs])
 * so a settings change takes effect on the next rotation without a relaunch. The window jump-cut and
 * the overlay view are one-time setup.
 *
 * Hiding the workspace (rather than only covering it) is deliberate: Launcher3's page rebind/reorder
 * moves icons across — and slightly beyond — the screen, so a covering overlay alone lets icons flash
 * past its edges. With the workspace at alpha 0, nothing animates visibly.
 *
 * Rotation is detected via [notifyConfigChanging], called from LawnchairLauncher.onConfigurationChanged
 * BEFORE super() so the fade-out starts in the same frame as the layout change.
 *
 * After [onWorkspaceRebound], [Workspace.isPageScrollSettled] is polled every [POLL_MS] ms. Only
 * after [STABLE_POLLS_NEEDED] consecutive "settled" readings is the workspace faded back in.
 */
class RotationAnimator(private val launcher: LawnchairLauncher) {

    /** True if the active mode masks the rebind (Fade). Refreshed each rotation. */
    private var masking = false
    private var fadeDurationMs = AnchorPreferences.FADE_DURATION_DEFAULT.toLong()

    // Transparent state-holder that hosts the settle-poll timer (and gates onWorkspaceRebound).
    private var overlayView: View? = null
    private var lastOrientation = android.content.res.Configuration.ORIENTATION_UNDEFINED
    private var waitingForPageSettle = false

    // Polls Workspace.isPageScrollSettled() every POLL_MS; reveals after STABLE_POLLS_NEEDED
    // consecutive "all quiet" readings — handles multiple rapid Launcher3 rebinds robustly.
    private val pollSettleRunnable = object : Runnable {
        var stableCount = 0
        override fun run() {
            if (!waitingForPageSettle) return
            // Wait for BOTH the workspace scroller AND any row-switch animation to finish, so the
            // post-rotation page parking is never visible mid-reveal.
            if (!launcher.workspace.isPageScrollSettled() ||
                launcher.twoRowNavigationManager.isTransitioning) {
                stableCount = 0
                overlayView?.postDelayed(this, POLL_MS)
                return
            }
            if (++stableCount < STABLE_POLLS_NEEDED) {
                overlayView?.postDelayed(this, POLL_MS)
                return
            }
            stableCount = 0
            waitingForPageSettle = false
            reveal()
        }
    }

    // Ultimate safety net if finishBindingItems never arrives or polling never settles.
    private val safetyReveal = Runnable { waitingForPageSettle = false; reveal() }

    fun setup() {
        // Traditional wants the system rotation animation; every other mode jump-cuts it. We apply the
        // jump-cut and create the timer overlay for the non-Traditional modes so switching between
        // Instant/Fade later takes effect without a relaunch (decided in refreshModeFromPrefs).
        if (AnchorPreferences(launcher).rotationTransition == AnchorPreferences.TRANSITION_TRADITIONAL) {
            return
        }
        setWindowJumpCut()
        overlayView = View(launcher).apply {
            alpha = 0f
            visibility = View.GONE
        }
        launcher.dragLayer.addView(
            overlayView,
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        // Rotation is detected exclusively via notifyConfigChanging (called before
        // super.onConfigurationChanged). No DisplayController listener — adding one fires a second
        // onRotationDetected() asynchronously mid-rebind, resetting settle state.
    }

    private fun refreshModeFromPrefs() {
        val prefs = AnchorPreferences(launcher)
        masking = prefs.rotationTransition == AnchorPreferences.TRANSITION_FADE
        fadeDurationMs = prefs.rotationFadeDurationMs.toLong()
    }

    private fun setWindowJumpCut() {
        val window = launcher.window ?: return
        val attrs = window.attributes
        attrs.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
        window.attributes = attrs
    }

    private fun onRotationDetected() {
        val overlay = overlayView ?: return
        overlay.removeCallbacks(safetyReveal)
        overlay.removeCallbacks(pollSettleRunnable)
        overlay.visibility = View.VISIBLE  // hosts the settle-poll timer
        // Hide the icons INSTANTLY (not a fade-out). A fade-out leaves the workspace partially
        // visible for a few frames, which lets the system's pre-transpose reflow flash through
        // before alpha reaches 0. An instant hide masks the rebind cleanly; the smoothness comes
        // from the fade-IN on reveal. alpha (not visibility) so the workspace still measures/lays out.
        val ws = launcher.workspace
        ws.animate().cancel()
        ws.alpha = 0f
        waitingForPageSettle = false
        pollSettleRunnable.stableCount = 0
        overlay.postDelayed(safetyReveal, SAFETY_REVEAL_TIMEOUT_MS)
    }

    /**
     * Called from [LawnchairLauncher.finishBindingItems] once icons are rebound after rotation.
     * (Re)starts polling [Workspace.isPageScrollSettled]; reveals once fully stable. Launcher3 fires
     * this more than once per rotation; each call restarts the poll while the workspace stays hidden.
     */
    fun onWorkspaceRebound() {
        if (!masking) return
        val overlay = overlayView ?: return
        overlay.removeCallbacks(safetyReveal)
        overlay.removeCallbacks(pollSettleRunnable)
        if (overlay.visibility != View.VISIBLE) return
        waitingForPageSettle = true
        pollSettleRunnable.stableCount = 0
        overlay.postDelayed(safetyReveal, SAFETY_REVEAL_TIMEOUT_MS)
        overlay.postDelayed(pollSettleRunnable, POLL_MS)
    }

    /** Fade the icons back in once the rebind has settled. */
    private fun reveal() {
        val ws = launcher.workspace
        ws.animate().cancel()
        ws.animate().alpha(1f).setDuration(fadeDurationMs).start()
        overlayView?.visibility = View.GONE
    }

    /**
     * Called from [LawnchairLauncher.onConfigurationChanged] BEFORE super(), so the fade-out starts
     * in the same frame as the view relayout — masking the rebind immediately.
     */
    fun notifyConfigChanging(newConfig: android.content.res.Configuration) {
        val orientation = newConfig.orientation
        if (orientation == lastOrientation) return
        lastOrientation = orientation
        refreshModeFromPrefs()
        if (masking) onRotationDetected()
    }

    companion object {
        // Polling interval for isPageScrollSettled checks after rebind.
        private const val POLL_MS = 50L
        // Consecutive "settled" polls required before revealing. The workspace is hidden the whole
        // time, so this only needs to bridge Launcher3's first rebind and its deferred second rebind
        // (~200 ms). Each rebind restarts the poll, so 5×50ms = 250ms of stability is ample.
        private const val STABLE_POLLS_NEEDED = 5
        // Ultimate fallback if rebind never completes.
        private const val SAFETY_REVEAL_TIMEOUT_MS = 1500L
    }
}
