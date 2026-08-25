/*
 * Copyright (C) 2025 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.anchor.navigation

import android.os.Build
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import app.anchor.AnchorPreferences
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.LauncherState
import com.android.launcher3.Utilities
import com.android.launcher3.util.TouchController
import kotlin.math.abs

/**
 * Intercepts an upward swipe that starts in the bottom [TRIGGER_ZONE_FRACTION] of the screen and
 * opens the standard all-apps drawer — a system-Overview-style gesture that works from ANY row, not
 * only row 0.
 *
 * This is the bottom-edge mirror of [SwipeDownStatusBarController] (top-edge). It is registered
 * FIRST in [LawnchairLauncher.createTouchControllers] so it claims bottom-edge up-swipes before
 * [TwoRowSwipeTouchController]'s row navigation.
 *
 * The system gesture-navigation zones (home pill / back-swipe edges) are deliberately excluded so
 * those still win: we skip any touch flagged [Utilities.EDGE_NAV_BAR] and, on API 30+, any touch
 * that starts inside the reported system-gesture insets (left/right/bottom). This keeps home and
 * back gestures working — the swipe-up-to-all-apps only fires "outside the home button".
 *
 * Gated by [AnchorPreferences.bottomEdgeSwipeUpAllApps] (default on).
 */
class SwipeUpAllAppsController(
    private val launcher: LawnchairLauncher,
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
                // dy < 0 → moved up. Require a clear vertical swipe past slop.
                if (!intercepting && dy < -slop && abs(dy) > abs(dx)) {
                    intercepting = true
                    openAllApps()
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
        val prefs = AnchorPreferences(launcher)
        if (!prefs.bottomEdgeSwipeUpAllApps) return false
        if ((ev.edgeFlags and Utilities.EDGE_NAV_BAR) != 0) return false
        if (AbstractFloatingView.getTopOpenView(launcher) != null) return false
        if (!launcher.isInState(LauncherState.NORMAL)) return false

        val zoneFraction = prefs.bottomEdgeSwipeUpZonePercent / 100f
        if (zoneFraction <= 0f) return false
        val dragLayer = launcher.dragLayer
        val triggerTop = dragLayer.height * (1f - zoneFraction)
        if (ev.y < triggerTop) return false

        // Leave the system gesture-nav zones (home pill / back edges) to the system.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = dragLayer.rootWindowInsets
                ?.getInsets(WindowInsets.Type.systemGestures())
            if (insets != null) {
                val inLeftBack = ev.x < insets.left
                val inRightBack = ev.x > dragLayer.width - insets.right
                val inBottomHome = ev.y > dragLayer.height - insets.bottom
                if (inLeftBack || inRightBack || inBottomHome) return false
            }
        }
        return true
    }

    private fun openAllApps() {
        launcher.stateManager.goToState(LauncherState.ALL_APPS, true /* animated */)
    }
}
