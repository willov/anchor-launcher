package app.anchor.applist

import android.content.ComponentName
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.anchor.AnchorPreferences

/**
 * Alphabetical app grid with an [AlphabetIndexView] scrubber strip on the side.
 * Grid column count defaults to [defaultCols] if [AnchorPreferences.drawerColumns] is 0.
 * Designed to be hosted inside [AnchorDrawerSheet]; the sheet provides the background.
 */
class AnchorAppDrawerView(context: Context) : FrameLayout(context) {

    private val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as
        android.content.pm.LauncherApps

    private val alphabetIndex = AlphabetIndexView(context)
    private val recyclerView  = RecyclerView(context)
    private val adapter       = AppGridAdapter(context)
    private lateinit var gridLayoutManager: GridLayoutManager

    var onAppLaunched: (() -> Unit)? = null

    private val scrubberWidthPx = (28 * resources.displayMetrics.density).toInt()

    init {
        val cols = resolvedCols()

        gridLayoutManager = GridLayoutManager(context, cols).also { lm ->
            lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) =
                    if (adapter.currentList.getOrNull(position) is DrawerItem.Header) cols else 1
            }
        }

        recyclerView.layoutManager = gridLayoutManager
        recyclerView.adapter = adapter
        recyclerView.setHasFixedSize(true)
        addView(
            recyclerView,
            LayoutParams(MATCH_PARENT, MATCH_PARENT).also { it.marginEnd = scrubberWidthPx },
        )

        addView(alphabetIndex, LayoutParams(scrubberWidthPx, MATCH_PARENT, Gravity.END))

        alphabetIndex.onLetterSelected = { letter ->
            adapter.letterPositions[letter]?.let { pos ->
                gridLayoutManager.scrollToPositionWithOffset(pos, 0)
            }
        }

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val first = gridLayoutManager.findFirstVisibleItemPosition()
                val last  = gridLayoutManager.findLastVisibleItemPosition()
                if (first < 0) return
                alphabetIndex.visibleLetters = adapter.getVisibleLetters(first, last)
            }
        })

        adapter.onAppClick = { entry ->
            launcherApps.startMainActivity(entry.componentName, entry.user, null, null)
            onAppLaunched?.invoke()
        }
    }

    fun loadApps() {
        val raw = launcherApps.getActivityList(null, Process.myUserHandle())
        val entries = raw
            .map { info ->
                AppEntry(
                    label         = info.label.toString(),
                    icon          = info.getIcon(resources.displayMetrics.densityDpi),
                    componentName = info.componentName,
                    user          = info.user,
                )
            }
            .sortedWith(compareBy { it.label.lowercase() })

        val items = buildSectionedList(entries)
        adapter.submitList(items)
        alphabetIndex.letters = items.filterIsInstance<DrawerItem.Header>().map { it.letter }

        // Trigger initial visible-letters update
        recyclerView.post {
            val first = gridLayoutManager.findFirstVisibleItemPosition()
            val last  = gridLayoutManager.findLastVisibleItemPosition()
            if (first >= 0) alphabetIndex.visibleLetters = adapter.getVisibleLetters(first, last)
        }
    }

    private fun resolvedCols(): Int {
        val pref = AnchorPreferences(context).drawerColumns
        if (pref in 1..10) return pref
        val screenWidthDp = resources.configuration.screenWidthDp
        return if (screenWidthDp >= 600) 5 else 4
    }

    private fun buildSectionedList(entries: List<AppEntry>): List<DrawerItem> {
        val result = mutableListOf<DrawerItem>()
        var currentLetter = ""
        for (entry in entries) {
            val letter = entry.label.firstOrNull()?.uppercaseChar()?.let {
                if (it.isLetter()) it.toString() else "#"
            } ?: "#"
            if (letter != currentLetter) {
                currentLetter = letter
                result.add(DrawerItem.Header(letter))
            }
            result.add(DrawerItem.App(entry))
        }
        return result
    }
}

// ─── Data model ──────────────────────────────────────────────────────────────

data class AppEntry(
    val label: String,
    val icon: Drawable,
    val componentName: ComponentName,
    val user: UserHandle,
)

sealed interface DrawerItem {
    data class Header(val letter: String) : DrawerItem
    data class App(val entry: AppEntry) : DrawerItem
}

// ─── Adapter ─────────────────────────────────────────────────────────────────

private class AppGridAdapter(private val context: Context) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onAppClick: ((AppEntry) -> Unit)? = null
    var currentList: List<DrawerItem> = emptyList()
        private set

    /** Pre-computed: position → section letter for O(1) visible-range lookup. */
    private var positionSection: Array<String> = emptyArray()

    /** Header letter → first position in the list. */
    val letterPositions = mutableMapOf<String, Int>()

    fun submitList(list: List<DrawerItem>) {
        currentList = list
        positionSection = Array(list.size) { i ->
            when (val item = list[i]) {
                is DrawerItem.Header -> item.letter
                is DrawerItem.App    -> {
                    // walk back to find the nearest header
                    var j = i - 1
                    while (j >= 0 && list[j] !is DrawerItem.Header) j--
                    if (j >= 0) (list[j] as DrawerItem.Header).letter else ""
                }
            }
        }
        letterPositions.clear()
        list.forEachIndexed { i, item ->
            if (item is DrawerItem.Header) letterPositions[item.letter] = i
        }
        notifyDataSetChanged()
    }

    fun getVisibleLetters(firstPos: Int, lastPos: Int): Set<String> {
        if (positionSection.isEmpty()) return emptySet()
        val lo = firstPos.coerceIn(0, positionSection.lastIndex)
        val hi = lastPos.coerceIn(0, positionSection.lastIndex)
        val result = mutableSetOf<String>()
        for (i in lo..hi) {
            val s = positionSection[i]
            if (s.isNotEmpty()) result.add(s)
        }
        return result
    }

    override fun getItemCount() = currentList.size

    override fun getItemViewType(position: Int) = when (currentList[position]) {
        is DrawerItem.Header -> TYPE_HEADER
        is DrawerItem.App    -> TYPE_APP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = when (viewType) {
        TYPE_HEADER -> HeaderVH(makeHeaderView(parent.context))
        else        -> AppVH(makeAppCell(parent.context))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = currentList[position]) {
            is DrawerItem.Header -> (holder as HeaderVH).bind(item.letter)
            is DrawerItem.App    -> (holder as AppVH).bind(item.entry, onAppClick)
        }
    }

    private fun makeHeaderView(ctx: Context) = TextView(ctx).apply {
        val dp = ctx.resources.displayMetrics.density
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
        setTextColor(0xAAFFFFFF.toInt())
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun makeAppCell(ctx: Context): LinearLayout {
        val dp = ctx.resources.displayMetrics.density
        val iconSize = (48 * dp).toInt()
        val cellPad = (8 * dp).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(cellPad, cellPad, cellPad, cellPad)
            layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

            addView(ImageView(ctx).apply {
                tag = "icon"
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).also {
                    it.gravity = Gravity.CENTER_HORIZONTAL
                }
            })
            addView(TextView(ctx).apply {
                tag = "label"
                textSize = 11f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                    it.topMargin = (4 * dp).toInt()
                }
            })
        }
    }

    private class HeaderVH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        fun bind(letter: String) { (itemView as TextView).text = letter }
    }

    private class AppVH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        fun bind(entry: AppEntry, onClick: ((AppEntry) -> Unit)?) {
            val cell = itemView as LinearLayout
            cell.findViewWithTag<ImageView>("icon").setImageDrawable(entry.icon)
            cell.findViewWithTag<TextView>("label").text = entry.label
            cell.setOnClickListener { onClick?.invoke(entry) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_APP    = 1
    }
}
