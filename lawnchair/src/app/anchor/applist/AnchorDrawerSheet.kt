package app.anchor.applist

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import app.anchor.AnchorPreferences
import app.lawnchair.LawnchairLauncher
import kotlin.math.abs

/**
 * Side-sheet that slides in from the left or right edge and hosts [AnchorAppDrawerView].
 *
 * On phones (screenWidthDp < 600) the sheet covers the full screen width. On tablets it covers
 * ~62 % of the screen width so the workspace remains partially visible behind the scrim.
 *
 * Call [setup] from LawnchairLauncher.setupViews(). Open/close via [open] / [close].
 */
class AnchorDrawerSheet(private val launcher: LawnchairLauncher) {

    enum class DrawerPosition { LEFT, RIGHT }

    var position: DrawerPosition = DrawerPosition.RIGHT
        private set

    var isOpen: Boolean = false
        private set

    private lateinit var scrimView: View
    private lateinit var sheetView: SheetView
    private lateinit var drawerContent: AnchorAppDrawerView

    fun setup() {
        position = DrawerPosition.RIGHT

        // Scrim — full-screen dim behind the sheet; tap to close
        scrimView = View(launcher).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { close() }
        }
        launcher.dragLayer.addView(scrimView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Sheet
        // colorBackground resolves to #fff in Lawnchair's theme; always use a dark surface
        // so white drawer text/icons are visible regardless of system light/dark mode.
        val bgColor = 0xFF1C1B1F.toInt()

        drawerContent = AnchorAppDrawerView(launcher).also {
            it.onAppLaunched = { close() }
        }

        sheetView = SheetView(launcher).apply {
            setBackgroundColor(bgColor)
            addView(drawerContent, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        launcher.dragLayer.addView(
            sheetView,
            FrameLayout.LayoutParams(computeSheetWidth(), MATCH_PARENT),
        )

        // DragLayer doesn't honour FrameLayout child gravity reliably, so we set the
        // left margin explicitly once the drag layer has been measured.
        sheetView.post {
            if (position == DrawerPosition.RIGHT) {
                val lp = sheetView.layoutParams as FrameLayout.LayoutParams
                lp.leftMargin = launcher.dragLayer.width - sheetView.width
                sheetView.requestLayout()
            }
            sheetView.translationX = offScreenX()
        }
        sheetView.visibility = View.GONE
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        drawerContent.loadApps()

        sheetView.translationX = offScreenX()
        sheetView.visibility = View.VISIBLE
        scrimView.visibility = View.VISIBLE

        sheetView.animate()
            .translationX(0f)
            .setDuration(ANIM_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        scrimView.animate()
            .alpha(SCRIM_ALPHA)
            .setDuration(ANIM_MS)
            .start()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false

        sheetView.animate()
            .translationX(offScreenX())
            .setDuration(ANIM_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { sheetView.visibility = View.GONE }
            .start()
        scrimView.animate()
            .alpha(0f)
            .setDuration(ANIM_MS)
            .withEndAction { scrimView.visibility = View.GONE }
            .start()
    }

    private fun offScreenX(): Float {
        val w = sheetView.width.takeIf { it > 0 }?.toFloat() ?: computeSheetWidth().toFloat()
        return if (position == DrawerPosition.RIGHT) w else -w
    }

    private fun computeSheetWidth(): Int {
        val screenWidthDp = launcher.resources.configuration.screenWidthDp
        val fraction = if (screenWidthDp >= 600) 0.62f else 1.0f
        val layerWidth = launcher.dragLayer.width.takeIf { it > 0 }
            ?: launcher.resources.displayMetrics.widthPixels
        return (layerWidth * fraction).toInt()
    }

    // ── Inner sheet view — intercepts outward swipes to close ─────────────────

    private inner class SheetView(context: Context) : FrameLayout(context) {

        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var startX = 0f
        private var startY = 0f
        private var intercepting = false

        // Consume all touches so they never reach DragLayer's TouchControllers or the
        // system status-bar-expand gesture recogniser while the drawer is open.
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            super.dispatchTouchEvent(ev)
            return true
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.x; startY = ev.y; intercepting = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - startX
                    val dy = ev.y - startY
                    if (!intercepting && isOutward(dx) && abs(dx) > slop && abs(dx) > abs(dy)) {
                        intercepting = true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (intercepting && ev.actionMasked == MotionEvent.ACTION_UP) {
                        if (abs(ev.x - startX) > width * CLOSE_FRACTION) close()
                    }
                    intercepting = false
                }
            }
            return intercepting
        }

        // Consume all events while intercepting so children don't also react
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_UP && intercepting) {
                if (abs(ev.x - startX) > width * CLOSE_FRACTION) close()
                intercepting = false
            }
            return true
        }

        private fun isOutward(dx: Float) = when (position) {
            DrawerPosition.RIGHT -> dx > 0f
            DrawerPosition.LEFT  -> dx < 0f
        }
    }

    companion object {
        private const val ANIM_MS       = 300L
        private const val SCRIM_ALPHA   = 0.5f
        private const val CLOSE_FRACTION = 0.20f
    }
}
