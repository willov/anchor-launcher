package app.anchor.navigation

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewConfiguration
import app.anchor.AnchorPreferences
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.LauncherState
import com.android.launcher3.Utilities
import com.android.launcher3.util.TouchController
import kotlin.math.abs

/**
 * Intercepts a downward swipe from the top [TRIGGER_ZONE_FRACTION] of the screen.
 *
 * Behaviour depends on [AnchorPreferences.statusBarSwipeAction]:
 *
 *   [AnchorPreferences.SWIPE_NOTIFICATIONS] (default) — expands the notification shade.
 *     On phones: any swipe → notifications.
 *     On tablets (screenWidthDp ≥ 600): left half → notifications, right half → quick settings.
 *
 *   [AnchorPreferences.SWIPE_NEXT_ROW] — navigates to the next row of screens instead, cycling
 *     upward through rows. Notification expansion is suppressed while this mode is active.
 *     This is the recommended setting when using multiple screen rows so that the top-edge swipe
 *     gesture feels consistent with the spatial layout (swipe down = go to the row above).
 *
 * Registered before [TwoRowSwipeTouchController] so it has first claim on top-edge swipes.
 */
class SwipeDownStatusBarController(
    private val launcher: LawnchairLauncher,
    private val navigationManager: TwoRowNavigationManager? = null,
) : TouchController {

    private val slop = ViewConfiguration.get(launcher).scaledTouchSlop.toFloat()
    private var startX = 0f
    private var startY = 0f
    private var intercepting = false
    private var noIntercept = false

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x; startY = ev.y
                intercepting = false
                noIntercept = !canIntercept(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (noIntercept) return false
                val dy = ev.y - startY
                val dx = ev.x - startX
                if (!intercepting && dy > slop && dy > abs(dx)) {
                    intercepting = true
                    handleSwipeDown(startX)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                intercepting = false
            }
        }
        if (noIntercept) return false
        return intercepting
    }

    override fun onControllerTouchEvent(ev: MotionEvent): Boolean = true

    private fun canIntercept(ev: MotionEvent): Boolean {
        if ((ev.edgeFlags and Utilities.EDGE_NAV_BAR) != 0) return false
        if (AbstractFloatingView.getTopOpenView(launcher) != null) return false
        if (!launcher.isInState(LauncherState.NORMAL)) return false
        val triggerHeight = launcher.dragLayer.height * TRIGGER_ZONE_FRACTION
        return ev.y < triggerHeight
    }

    private fun handleSwipeDown(touchX: Float) {
        val nav = navigationManager
        // When multiple rows are active, swipe-down is the row-navigation gesture on the main
        // workspace — mirror that here so the status-bar zone feels consistent.
        // When single-row, respect the statusBarSwipeAction preference.
        val navigateRows = nav != null && (
            nav.rowCount > 1 ||
            AnchorPreferences(launcher).statusBarSwipeAction == AnchorPreferences.SWIPE_NEXT_ROW
        )
        if (navigateRows && nav != null) {
            if (nav.activeRowIndex < nav.rowCount - 1) nav.navigateUp() else nav.bounceTopEdge()
            return
        }
        expandStatusBar(touchX)
    }

    @SuppressLint("WrongConstant")
    private fun expandStatusBar(touchX: Float) {
        val isTablet = launcher.resources.configuration.screenWidthDp >= 600
        val openQuickSettings = isTablet && touchX > launcher.dragLayer.width / 2f
        try {
            val sbClass = Class.forName("android.app.StatusBarManager")
            val method = if (openQuickSettings) "expandSettingsPanel" else "expandNotificationsPanel"
            sbClass.getMethod(method).apply { isAccessible = true }
                .invoke(launcher.getSystemService("statusbar"))
        } catch (_: Exception) {
            // Accessibility fallback — only fires if reflection fails (rare on modern ROMs)
            val action = if (openQuickSettings)
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            app.lawnchair.gestures.handlers.GestureWithAccessibilityHandler.onTrigger(
                launcher,
                if (openQuickSettings) com.android.launcher3.R.string.quick_settings_a11y_hint
                else com.android.launcher3.R.string.notifications_fallback_a11y_hint,
                action,
            )
        }
    }

    companion object {
        private const val TRIGGER_ZONE_FRACTION = 0.15f
    }
}
