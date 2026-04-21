package app.anchor.navigation

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import app.anchor.applist.AnchorDrawerSheet
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.LauncherState
import com.android.launcher3.Utilities
import com.android.launcher3.util.TouchController
import kotlin.math.abs

/**
 * Detects an inward swipe from the leading-edge zone (rightmost or leftmost [ZONE_FRACTION] of
 * the screen) and opens [AnchorDrawerSheet].
 *
 * The zone is large enough to be easily hit (~28 % of screen width) yet distinct enough from a
 * normal page-scroll gesture: we only intercept once we observe a clear inward horizontal swipe
 * with a velocity above [MIN_VELOCITY_DP]. Once intercepted, the workspace scroll is suppressed.
 */
class DrawerSwipeTouchController(
    private val launcher: LawnchairLauncher,
    private val sheet: AnchorDrawerSheet,
) : TouchController {

    private var velocityTracker: VelocityTracker? = null
    private var startX = 0f
    private var startY = 0f
    private var intercepting = false
    private var noIntercept = false

    private val slop = ViewConfiguration.get(launcher).scaledTouchSlop.toFloat()
    private val minVelocity = MIN_VELOCITY_DP * launcher.resources.displayMetrics.density
    private val openDisplacement = OPEN_DISPLACEMENT_DP * launcher.resources.displayMetrics.density

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                recycleTracker()
                startX = ev.x; startY = ev.y
                intercepting = false
                noIntercept = !canStartGesture(ev)
                if (!noIntercept) {
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(ev)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (noIntercept) return false
                velocityTracker?.addMovement(ev)
                if (!intercepting) {
                    val inward = inwardDisplacement(ev.x - startX)
                    val lateral = abs(ev.y - startY)
                    if (inward > slop * 2 && inward > lateral * 1.5f) {
                        intercepting = true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                recycleTracker()
                intercepting = false
            }
        }
        return intercepting
    }

    override fun onControllerTouchEvent(ev: MotionEvent): Boolean {
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f
                val inwardV = inwardVelocity(vx)
                val inwardD = inwardDisplacement(ev.x - startX)
                if (inwardV > minVelocity || inwardD > openDisplacement) {
                    sheet.open()
                }
                recycleTracker()
                intercepting = false
            }
            MotionEvent.ACTION_CANCEL -> {
                recycleTracker()
                intercepting = false
            }
        }
        return true
    }

    private fun canStartGesture(ev: MotionEvent): Boolean {
        if (sheet.isOpen) return false
        if ((ev.edgeFlags and Utilities.EDGE_NAV_BAR) != 0) return false
        if (AbstractFloatingView.getTopOpenView(launcher) != null) return false
        if (!launcher.isInState(LauncherState.NORMAL)) return false
        val w = launcher.dragLayer.width
        return when (sheet.position) {
            AnchorDrawerSheet.DrawerPosition.RIGHT -> ev.x > w * (1f - ZONE_FRACTION)
            AnchorDrawerSheet.DrawerPosition.LEFT  -> ev.x < w * ZONE_FRACTION
        }
    }

    private fun inwardDisplacement(dx: Float) = when (sheet.position) {
        AnchorDrawerSheet.DrawerPosition.RIGHT -> -dx
        AnchorDrawerSheet.DrawerPosition.LEFT  ->  dx
    }

    private fun inwardVelocity(vx: Float) = when (sheet.position) {
        AnchorDrawerSheet.DrawerPosition.RIGHT -> -vx
        AnchorDrawerSheet.DrawerPosition.LEFT  ->  vx
    }

    private fun recycleTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    companion object {
        private const val ZONE_FRACTION        = 0.28f
        private const val MIN_VELOCITY_DP      = 400f
        private const val OPEN_DISPLACEMENT_DP = 60f
    }
}
