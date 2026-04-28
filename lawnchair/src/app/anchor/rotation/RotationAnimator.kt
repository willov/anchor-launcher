package app.anchor.rotation

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import app.anchor.AnchorPreferences
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.util.DisplayController

/**
 * Controls the rotation transition animation based on the user's [AnchorPreferences.rotationTransition]
 * setting:
 *
 * - TRADITIONAL: nothing — the system plays its default rotation animation.
 * - INSTANT: suppresses the system rotation animation with ROTATION_ANIMATION_JUMPCUT; no overlay.
 * - CROSSFADE: jumpcut + fades a full-screen overlay to the wallpaper's dominant colour while the
 *   grid transposes invisibly, then fades back out once icons are rebound.
 *
 * Call [setup] from LawnchairLauncher.setupViews(). Call [onWorkspaceRebound] from
 * LawnchairLauncher.finishBindingItems() so the crossfade fade-out begins once icons are bound.
 */
class RotationAnimator(private val launcher: LawnchairLauncher) {

    private var overlayView: View? = null
    private var pendingFadeOut = false

    fun setup() {
        val transition = AnchorPreferences(launcher).rotationTransition

        when (transition) {
            AnchorPreferences.TRANSITION_TRADITIONAL -> return
            AnchorPreferences.TRANSITION_INSTANT -> {
                setWindowJumpCut()
                return
            }
            AnchorPreferences.TRANSITION_CROSSFADE -> {
                setWindowJumpCut()
                overlayView = View(launcher).apply {
                    alpha = 0f
                    visibility = View.GONE
                }
                launcher.dragLayer.addView(
                    overlayView,
                    FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
                )
                DisplayController.INSTANCE.get(launcher).addChangeListener { _, _, flags ->
                    if (flags and DisplayController.CHANGE_ROTATION == 0) return@addChangeListener
                    launcher.runOnUiThread { onRotationDetected() }
                }
            }
        }
    }

    private fun setWindowJumpCut() {
        val window = launcher.window ?: return
        val attrs = window.attributes
        attrs.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
        window.attributes = attrs
    }

    private fun onRotationDetected() {
        val overlay = overlayView ?: return
        overlay.animate().cancel()
        overlay.setBackgroundColor(getWallpaperColor())
        // Snap to fully opaque immediately so no intermediate icon positions are ever visible.
        // The fade-OUT after finishBindingItems provides the smooth reveal.
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        pendingFadeOut = true
    }

    /** Call from LawnchairLauncher.finishBindingItems() once icons are rebound after rotation. */
    fun onWorkspaceRebound() {
        if (!pendingFadeOut) return
        pendingFadeOut = false
        val overlay = overlayView ?: return
        overlay.animate()
            .alpha(0f)
            .setDuration(FADE_OUT_MS)
            .withEndAction { overlay.visibility = View.GONE }
            .start()
    }

    private fun getWallpaperColor(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return Color.BLACK
        return try {
            android.app.WallpaperManager.getInstance(launcher)
                .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
                ?.primaryColor?.toArgb()
                ?: Color.BLACK
        } catch (_: Exception) {
            Color.BLACK
        }
    }

    companion object {
        private const val FADE_OUT_MS = 250L
    }
}
