package app.anchor.navigation

import android.graphics.PointF
import android.view.MotionEvent
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
 * [TwoRowNavigationManager] to switch between the icon row and the widget row.
 *
 * Must be registered BEFORE Lawnchair's VerticalSwipeTouchController and AllAppsSwipeController
 * so it takes priority over them for row-switching swipes.
 *
 * [isDrawerOpen] is queried on each gesture; vertical swipes are ignored while the drawer is open.
 */
class TwoRowSwipeTouchController(
    private val launcher: LawnchairLauncher,
    private val manager: TwoRowNavigationManager,
    private val isDrawerOpen: () -> Boolean = { false },
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
            detector.setDetectableScrollConditions(
                BothAxesSwipeDetector.DIRECTION_UP or BothAxesSwipeDetector.DIRECTION_DOWN,
                false,
            )
        }
        if (noIntercept) return false
        onControllerTouchEvent(ev)
        return detector.isDraggingOrSettling
    }

    override fun onControllerTouchEvent(ev: MotionEvent): Boolean = detector.onTouchEvent(ev)

    private fun canIntercept(ev: MotionEvent): Boolean {
        if (isDrawerOpen()) return false
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
            if (velocity < 0) manager.navigateUp() else manager.navigateDown()
        }
        return true
    }

    override fun onDragEnd(velocity: PointF) {
        detector.finishedScrolling()
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
        private val DAMPENING_RC = (1000f / (2f * PI.toFloat() * 10f))
    }
}
