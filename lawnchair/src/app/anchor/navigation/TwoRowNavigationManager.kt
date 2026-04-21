package app.anchor.navigation

import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.Animator
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import app.lawnchair.LawnchairLauncher

/**
 * Manages the 2D navigation matrix: an icon row (Lawnchair's normal workspace) stacked above
 * a widget row. Vertical swipe navigates between the two rows. Each row remembers its own
 * horizontal page position independently.
 *
 * Layout: both rows are full-screen siblings in the DragLayer. The widget row starts off-screen
 * below (translationY = +height). Switching rows animates both views simultaneously.
 *
 *   Row.ICON   → workspace at translationY=0, widget row at translationY=+height
 *   Row.WIDGET → workspace at translationY=-height, widget row at translationY=0
 */
class TwoRowNavigationManager(private val launcher: LawnchairLauncher) {

    enum class Row { ICON, WIDGET }

    var activeRow: Row = Row.ICON
        private set

    private var iconRowPage: Int = 0
    private var widgetRowPage: Int = 0

    private lateinit var widgetRowView: FrameLayout

    fun setup() {
        widgetRowView = FrameLayout(launcher).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            visibility = View.INVISIBLE
            setBackgroundColor(Color.TRANSPARENT)
            // Stub — widget row content will be added here in a future step
            addView(TextView(launcher).apply {
                text = "Widget Row"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 20f
            })
        }
        launcher.dragLayer.addView(widgetRowView)

        // Position off-screen below once the workspace has its dimensions
        launcher.workspace.post {
            widgetRowView.translationY = launcher.workspace.height.toFloat()
        }
    }

    /** Called from LawnchairLauncher.onPageEndTransition() to track per-row page positions. */
    fun onWorkspacePageSettled(page: Int) {
        when (activeRow) {
            Row.ICON   -> iconRowPage   = page
            Row.WIDGET -> widgetRowPage = page
        }
    }

    /** Swipe up — switch to widget row. No-op if already there. */
    fun navigateUp() {
        if (activeRow == Row.WIDGET) return
        activeRow = Row.WIDGET
        animateTransition(Row.WIDGET)
    }

    /** Swipe down — switch to icon row. No-op if already there. */
    fun navigateDown() {
        if (activeRow == Row.ICON) return
        activeRow = Row.ICON
        animateTransition(Row.ICON)
    }

    /** The page the given row was last on. */
    fun pageForRow(row: Row): Int = when (row) {
        Row.ICON   -> iconRowPage
        Row.WIDGET -> widgetRowPage
    }

    private fun animateTransition(targetRow: Row) {
        val workspace = launcher.workspace
        val rowHeight = workspace.height.toFloat()

        val (workspaceTarget, widgetTarget) = when (targetRow) {
            Row.ICON   -> Pair(0f, rowHeight)
            Row.WIDGET -> Pair(-rowHeight, 0f)
        }

        widgetRowView.visibility = View.VISIBLE

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(workspace, View.TRANSLATION_Y, workspaceTarget),
                ObjectAnimator.ofFloat(widgetRowView, View.TRANSLATION_Y, widgetTarget),
            )
            duration = ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            if (targetRow == Row.ICON) {
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        widgetRowView.visibility = View.INVISIBLE
                    }
                })
            }
        }.start()
    }

    companion object {
        private const val ANIM_DURATION_MS = 300L
    }
}
