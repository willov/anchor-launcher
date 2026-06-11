package app.lawnchair.allapps.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.RelativeLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import app.anchor.AnchorPreferences
import app.anchor.applist.AlphabetIndexView
import app.lawnchair.search.LawnchairSearchUiDelegate
import com.android.launcher3.R
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.allapps.LauncherAllAppsContainerView

class SearchContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LauncherAllAppsContainerView(context, attrs, defStyleAttr) {

    private val letterIndex = AlphabetIndexView(context)
    private val appsUpdateListener = AllAppsStore.OnUpdateListener {
        post {
            refreshLetterList()
            updateIndexHighlight()
        }
    }

    override fun createSearchUiDelegate() = LawnchairSearchUiDelegate(this)

    override fun onFinishInflate() {
        super.onFinishInflate()
        setupLetterIndex()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        getAppsStore().removeUpdateListener(appsUpdateListener)
    }

    private fun setupLetterIndex() {
        val widthPx = (AlphabetIndexView.STRIP_WIDTH_DP * resources.displayMetrics.density).toInt()
        val lp = RelativeLayout.LayoutParams(widthPx, RelativeLayout.LayoutParams.WRAP_CONTENT)
        lp.addRule(RelativeLayout.ALIGN_PARENT_END)
        lp.addRule(RelativeLayout.ALIGN_TOP, R.id.all_apps_header)
        lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        addView(letterIndex, lp)

        // Inset the strip's letters below the search bar so the top letters don't collide with it.
        // Computed from the search view's bottom edge relative to the strip's top (both share this
        // parent), since heights aren't known until layout.
        val gapPx = (8 * resources.displayMetrics.density).toInt()
        val applyTopInset = {
            val search = getSearchView()
            if (search != null && search.height > 0) {
                val topInset = (search.bottom - letterIndex.top + gapPx).coerceAtLeast(0)
                if (letterIndex.paddingTop != topInset) {
                    letterIndex.setPadding(0, topInset, 0, gapPx)
                }
            }
        }
        getSearchView()?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyTopInset() }
        letterIndex.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyTopInset() }
        post { applyTopInset() }

        // Reserving right padding so icons don't scroll behind the strip is handled centrally in
        // ActivityAllAppsContainerView.applyAdapterSideAndBottomPaddings() — doing it here would be
        // clobbered every time the core re-applies its own padding (on insets/search-state changes).

        letterIndex.onLetterSelected = { letter ->
            val rv = getMainAppsRecyclerView()
            val lm = rv?.layoutManager as? LinearLayoutManager
            if (lm != null) {
                val target = getMainAppsList().fastScrollerSections
                    .lastOrNull { it.sectionName.toString() == letter }
                if (target != null) {
                    // Smooth-scroll the section to the top edge rather than snapping. Re-aiming
                    // mid-drag just retargets the running scroller, so dragging down the rail
                    // glides through the list instead of jumping.
                    val scroller = object : LinearSmoothScroller(rv.context) {
                        override fun getVerticalSnapPreference() = SNAP_TO_START
                        // ms-per-pixel; smaller = faster.
                        override fun calculateSpeedPerPixel(dm: android.util.DisplayMetrics) =
                            12f / dm.densityDpi
                    }
                    scroller.targetPosition = target.position
                    lm.startSmoothScroll(scroller)
                }
            }
        }

        getMainAppsRecyclerView()?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateIndexHighlight()
        })

        getAppsStore().addUpdateListener(appsUpdateListener)

        // Initial population if apps are already loaded
        post {
            refreshLetterList()
            updateIndexHighlight()
        }
    }

    private fun refreshLetterList() {
        letterIndex.letters = getMainAppsList().fastScrollerSections
            .map { it.sectionName.toString() }
            .distinct()
    }

    private fun updateIndexHighlight() {
        val active = AnchorPreferences(context).drawerLetterScroller && !isSearching
        letterIndex.visibility = if (active) View.VISIBLE else View.GONE
        // Hide the thumb scrollbar when the letter index takes its place
        mFastScroller.visibility = if (active) View.INVISIBLE else if (showFastScroller) View.VISIBLE else View.INVISIBLE
        if (!active) return

        val lm = getMainAppsRecyclerView()?.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last  = lm.findLastVisibleItemPosition()
        if (first < 0) return
        val sections = getMainAppsList().fastScrollerSections
        val visibleNames = sections
            .filter { it.position <= last }
            .mapNotNull { info ->
                val next = sections.getOrNull(sections.indexOf(info) + 1)
                if (next == null || next.position > first) info.sectionName.toString() else null
            }
            .toSet()
        letterIndex.visibleLetters = visibleNames
    }
}

