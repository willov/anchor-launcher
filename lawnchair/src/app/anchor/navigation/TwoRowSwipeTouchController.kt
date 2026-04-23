package app.anchor.navigation

import android.graphics.PointF
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.LauncherState
import com.android.launcher3.Utilities
import com.android.launcher3.touch.BothAxesSwipeDetector
import com.android.launcher3.util.TouchController
import kotlin.math.PI
import kotlin.math.absoluteValue

/**
 * TouchController that intercepts vertical swipes on the home screen and delegates them to
 * [TwoRowNavigationManager] to move between rows in the navigation matrix.
 *
 * Swipe DOWN → [TwoRowNavigationManager.navigateUp] (go to the row above).
 * Swipe UP   → [TwoRowNavigationManager.navigateDown] (go to the row below).
 *
 * On row 0 (bottom), only DOWN is intercepted — upward swipes pass through to Lawnchair all-apps.
 * On the top row, both directions are intercepted: UP navigates down, DOWN plays a bounce to
 * indicate there is no row above.
 * On middle rows, both directions are intercepted.
 *
 * Must be registered BEFORE Lawnchair's VerticalSwipeTouchController so it takes priority.
 */
class TwoRowSwipeTouchController(
    private val launcher: LawnchairLauncher,
    private val manager: TwoRowNavigationManager,
) : TouchController, BothAxesSwipeDetector.Listener {

    private val detector = BothAxesSwipeDetector(launcher, this)

    private var noIntercept = false
    private var triggered = false
    private var currentMillis = 0L
    private var currentVelocity = 0f
    private var prevDisplacementY = 0f

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            noIntercept = !canIntercept(ev)
            if (noIntercept) return false

            val canGoUp   = manager.activeRowIndex < manager.rowCount - 1
            val canGoDown = manager.activeRowIndex > 0
            // Intercept DOWN if: there's a row above to go to, or we're at the top of a multi-row
            // layout and need to bounce. Intercept UP if: there's a row below to go to.
            // If neither applies (single row), intercept nothing so UP passes to all-apps.
            val wantDown = canGoUp || canGoDown   // navigate up, or bounce at top
            val wantUp   = canGoDown              // navigate down (UP on row 0 → all-apps)
            val direction =
                (if (wantDown) BothAxesSwipeDetector.DIRECTION_DOWN else 0) or
                (if (wantUp)   BothAxesSwipeDetector.DIRECTION_UP   else 0)
            detector.setDetectableScrollConditions(direction, false)
        }
        if (noIntercept) return false
        onControllerTouchEvent(ev)
        return detector.isDraggingOrSettling
    }

    override fun onControllerTouchEvent(ev: MotionEvent): Boolean = detector.onTouchEvent(ev)

    private fun canIntercept(ev: MotionEvent): Boolean {
        if (manager.isTransitioning) return false
        if ((ev.edgeFlags and Utilities.EDGE_NAV_BAR) != 0) return false
        return AbstractFloatingView.getTopOpenView(launcher) == null &&
            launcher.isInState(LauncherState.NORMAL)
    }

    override fun onDragStart(start: Boolean) {
        triggered = false
        currentMillis = 0L
        currentVelocity = 0f
        prevDisplacementY = 0f
    }

    override fun onDrag(displacement: PointF, motionEvent: MotionEvent): Boolean {
        if (triggered) return true
        val velocity = computeVelocity(displacement.y - prevDisplacementY, motionEvent.eventTime)
        prevDisplacementY = displacement.y
        if (velocity.absoluteValue > TRIGGER_VELOCITY) {
            triggered = true
            // velocity > 0: finger moved down → navigate UP (to row above)
            // velocity < 0: finger moved up   → navigate DOWN (to row below)
            if (velocity > 0) {
                if (manager.activeRowIndex < manager.rowCount - 1) {
                    manager.navigateUp()
                } else {
                    bounceTopEdge()
                }
            } else if (manager.activeRowIndex > 0) {
                manager.navigateDown()
            } else {
                // Upward swipe at row 0: do not consume, let Lawnchair all-apps handle it.
                return false
            }
        }
        return true
    }

    override fun onDragEnd(velocity: PointF) {
        detector.finishedScrolling()
    }

    /** Animate a brief downward nudge to indicate there is no row above. */
    private fun bounceTopEdge() {
        val workspace = launcher.workspace
        val nudge = BOUNCE_NUDGE_DP * launcher.resources.displayMetrics.density
        workspace.animate().cancel()
        workspace.animate()
            .translationY(nudge)
            .setDuration(80)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                workspace.animate()
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(OvershootInterpolator(1.8f))
                    .start()
            }
            .start()
    }

    private fun computeVelocity(delta: Float, millis: Long): Float {
        val prevMillis = currentMillis
        currentMillis = millis
        val dt = (currentMillis - prevMillis).toFloat()
        val v = if (dt > 0f) delta / dt else 0f
        currentVelocity = if (currentVelocity.absoluteValue < 0.001f) {
            v
        } else {
            val alpha = dt / (DAMPENING_RC + dt)
            Utilities.mapRange(alpha, currentVelocity, v)
        }
        return currentVelocity
    }

    companion object {
        private const val TRIGGER_VELOCITY = 2.25f
        private const val BOUNCE_NUDGE_DP = 24f
        private val DAMPENING_RC = (1000f / (2f * PI.toFloat() * 10f))
    }
}
