package app.anchor.navigation

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import app.anchor.AnchorPreferences
import app.lawnchair.LawnchairLauncher

/**
 * Manages the N-row navigation matrix: workspace screens are partitioned into rows, each backed
 * by real workspace pages. Vertical swipe moves between rows; horizontal swipe navigates pages
 * within the current row. Each row remembers its own horizontal page position independently.
 *
 * Row 0 is the bottom icon row. Rows 1..N-1 are above it, each typically holding a full-screen
 * widget canvas. [rowCount] is stored in [AnchorPreferences.rowCount] and defaults to 2.
 *
 * Screen membership for rows 1..N-1 is persisted via [AnchorPreferences.getRowScreenIds].
 * Row 0 implicitly owns all screens not claimed by higher rows.
 *
 *   Swipe DOWN → navigate to the row above (higher index)
 *   Swipe UP   → navigate to the row below (lower index)
 *   Swipe UP on row 0 → passes through to Lawnchair all-apps (not intercepted here)
 */
class TwoRowNavigationManager(private val launcher: LawnchairLauncher) {

    /** Index of the currently visible row. 0 = bottom icon row, 1..N-1 = rows above. */
    var activeRowIndex: Int = 0
        private set

    /** Total number of rows. Mirrors [AnchorPreferences.rowCount]. */
    var rowCount: Int = 2
        private set

    /** True while a row-switch animation is in progress. Touch controller checks this. */
    var isTransitioning: Boolean = false
        private set

    // Per-row: ordered screen IDs (workspace page order) and current page within that row.
    private val rowScreenIds  = ArrayList<MutableList<Int>>()  // index = row, value = screen IDs
    private val rowPageIndex  = ArrayList<Int>()               // current page within each row

    private val prefs by lazy { AnchorPreferences(launcher) }
    private var initialized = false
    private var currentDragOverlay: TwoRowDragOverlay? = null

    private val handler = Handler(Looper.getMainLooper())
    private val clearTransitionFlag = Runnable { isTransitioning = false }

    fun setup() { /* deferred to onWorkspacePageSettled */ }

    // ── Convenience accessors used by SwipeDownStatusBarController ───────────────────────────────

    /** Navigate one row up (toward higher index). No-op if already at the top or transitioning. */
    fun navigateUp() {
        if (activeRowIndex >= rowCount - 1 || isTransitioning || !initialized) return
        val from = activeRowIndex
        activeRowIndex++
        animateRowTransition(from, activeRowIndex)
    }

    /** Navigate one row down (toward lower index). No-op if already at row 0 or transitioning. */
    fun navigateDown() {
        if (activeRowIndex <= 0 || isTransitioning || !initialized) return
        val from = activeRowIndex
        activeRowIndex--
        animateRowTransition(from, activeRowIndex)
    }

    // ── Drag handling ───────────────────────────────────────────────────────────────────────────

    /** Called when a drag begins. Adds the row-switch overlay and releases scroll clamping. */
    fun onDragStarted() {
        if (!initialized) return
        // Release the page-range clamp so Launcher3 can create new screens at either edge of the
        // current row during drag. We re-adopt any new screens when the drag ends.
        launcher.workspace.setAllowedPageRange(0, Int.MAX_VALUE)

        val dtb = launcher.getDropTargetBar()
        val buttonHeight = dtb.measuredHeight.takeIf { it > 0 }
            ?: (80 * launcher.resources.displayMetrics.density).toInt()
        val pageCounts = rowScreenIds.map { it.size.coerceAtLeast(1) }
        val overlay = TwoRowDragOverlay(
            launcher, activeRowIndex, rowCount, pageCounts, buttonHeight,
        ) { targetRowIndex ->
            currentDragOverlay?.let { if (it.parent != null) launcher.dragLayer.removeView(it) }
            currentDragOverlay = null
            if (targetRowIndex > activeRowIndex) navigateUp() else navigateDown()
        }
        launcher.dragLayer.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        currentDragOverlay = overlay
    }

    /** Called on every drag-move with the icon's Y coordinate in DragLayer space. */
    fun onDragMoved(dragY: Float) {
        currentDragOverlay?.onDragMoved(dragY)
    }

    /** Called when the drag ends (drop or cancel). Adopts any new screens then re-clamps. */
    fun onDragEnded() {
        currentDragOverlay?.let { if (it.parent != null) launcher.dragLayer.removeView(it) }
        currentDragOverlay = null
        // Pick up screens Launcher3 created during the drag and assign them to the current row.
        adoptNewScreens()
        updateScrollRange(activeRowIndex)
    }

    // ── Page tracking ────────────────────────────────────────────────────────────────────────────

    /**
     * Called from [LawnchairLauncher.finishBindingItems] and [LawnchairLauncher.onPageEndTransition].
     * Triggers lazy initialization on the first call, then tracks the per-row page position.
     */
    fun onWorkspacePageSettled(workspacePage: Int) {
        if (!initialized) {
            initialize()
            return
        }
        val screenId = launcher.workspace.getScreenIdForPageIndex(workspacePage)
        if (screenId < 0) return  // EXTRA_EMPTY_SCREEN or invalid

        for (r in rowScreenIds.indices) {
            val idx = rowScreenIds[r].indexOf(screenId)
            if (idx >= 0) {
                // Only record horizontal-scroll position for the active row. Events that fire
                // for a non-active row (e.g. Launcher3 completing a snap animation to a page
                // from a previous session, or abortScrollerAnimation firing during a transition)
                // are ignored so they cannot clobber the saved position of the row the user
                // last visited.
                if (r == activeRowIndex) rowPageIndex[r] = idx
                return
            }
        }
        // Screen is unknown — Launcher3 created it (e.g. drop on a new empty page).
        // Adopt it into the active row.
        adoptNewScreens()
    }

    // ── Animation ────────────────────────────────────────────────────────────────────────────────

    private fun animateRowTransition(fromRow: Int, toRow: Int) {
        val workspace = launcher.workspace
        val height    = workspace.height.toFloat()

        // Snapshot the from-row's current horizontal position before the animation changes
        // anything. activeRowIndex is already toRow at this point (updated by navigateUp/Down
        // before calling us), so onWorkspacePageSettled would ignore any fromRow events — but
        // we capture it here explicitly as belt-and-suspenders.
        val fromScreenId = workspace.getScreenIdForPageIndex(workspace.currentPage)
        val fromIds = rowScreenIds.getOrNull(fromRow)
        if (fromIds != null && fromScreenId >= 0) {
            val idx = fromIds.indexOf(fromScreenId)
            if (idx >= 0) rowPageIndex[fromRow] = idx
        }

        // Going UP (higher index): current exits DOWN, new enters from ABOVE
        // Going DOWN (lower index): current exits UP,   new enters from BELOW
        val goingUp         = toRow > fromRow
        val exitTranslation  = if (goingUp)  height else -height
        val enterTranslation = -exitTranslation

        isTransitioning = true
        handler.removeCallbacks(clearTransitionFlag)
        handler.postDelayed(clearTransitionFlag, PHASE_MS * 2 + 100)

        workspace.animate().cancel()
        workspace.animate()
            .translationY(exitTranslation)
            .setDuration(PHASE_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                updateScrollRange(toRow)
                workspace.setCurrentPage(targetWorkspacePage(toRow))
                workspace.translationY = enterTranslation
                workspace.animate()
                    .translationY(0f)
                    .setDuration(PHASE_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        handler.removeCallbacks(clearTransitionFlag)
                        isTransitioning = false
                    }
                    .start()
            }
            .start()
    }

    private fun targetWorkspacePage(rowIndex: Int): Int {
        val ids     = rowScreenIds.getOrNull(rowIndex) ?: return 0
        val pageIdx = rowPageIndex.getOrElse(rowIndex) { 0 }
        val screenId = ids.getOrElse(pageIdx) { ids.firstOrNull() ?: return 0 }
        return launcher.workspace.getPageIndexForScreenId(screenId)
    }

    private fun updateScrollRange(rowIndex: Int) {
        val ids       = rowScreenIds.getOrNull(rowIndex)
        val workspace = launcher.workspace
        if (ids.isNullOrEmpty()) {
            if (rowIndex == 0) workspace.setAllowedPageRange(0, 0)
            return
        }
        val first = workspace.getPageIndexForScreenId(ids.first())
        val last  = workspace.getPageIndexForScreenId(ids.last())
        if (first >= 0 && last >= 0) workspace.setAllowedPageRange(first, last)
    }

    // ── Screen adoption ─────────────────────────────────────────────────────────────────────────

    /**
     * Scans the workspace for screens not yet assigned to any row and adds them to the active row.
     * Called after a drag ends (Launcher3 may have committed a new empty screen) and when
     * [onWorkspacePageSettled] encounters an unrecognized screen ID.
     */
    private fun adoptNewScreens() {
        val workspace   = launcher.workspace
        val screenOrder = workspace.screenOrder
        val allKnown    = rowScreenIds.flatten().toSet()
        var adopted     = false

        for (i in 0 until screenOrder.size()) {
            val id = screenOrder.get(i)
            if (id < 0 || id in allKnown) continue
            rowScreenIds[activeRowIndex].add(id)
            adopted = true
            Log.d(TAG, "adoptNewScreens: screen $id → row $activeRowIndex")
        }

        if (!adopted) return

        // Sort every row by current workspace page order (preserves relative page ordering
        // within each row before we reorder the workspace below).
        for (r in rowScreenIds.indices) {
            rowScreenIds[r].sortBy { workspace.getPageIndexForScreenId(it) }
        }

        // Reorder workspace so all pages of row 0 come first, then row 1, etc.
        // Launcher3 always inserts new pages at the END, which can interleave rows and cause
        // the [first..last] range for one row to span pages of another row.
        workspace.reorderPages(rowScreenIds.flatten())

        // Persist upper rows (row 0 is implicit, no explicit storage needed).
        if (activeRowIndex > 0) {
            prefs.setRowScreenIds(activeRowIndex, rowScreenIds[activeRowIndex].map { it.toString() }.toSet())
        }
        updateScrollRange(activeRowIndex)
    }

    // ── Initialization ──────────────────────────────────────────────────────────────────────────

    private fun initialize() {
        val workspace = launcher.workspace
        val screenOrder = workspace.screenOrder
        rowCount = prefs.rowCount

        rowScreenIds.clear()
        rowPageIndex.clear()
        repeat(rowCount) {
            rowScreenIds.add(mutableListOf())
            rowPageIndex.add(0)
        }

        // Load saved screen IDs for each upper row (1..N-1).
        val savedUpperIds: List<Set<Int>> = (1 until rowCount).map { r ->
            prefs.getRowScreenIds(r).mapNotNull { it.toIntOrNull() }.toSet()
        }
        val allUpperIds = savedUpperIds.flatten().toSet()

        Log.d(TAG, "initialize: screenOrder.size=${screenOrder.size()} rowCount=$rowCount childCount=${workspace.childCount}")

        // Partition loaded workspace screens into rows. Skip negative IDs (EXTRA_EMPTY_SCREEN etc).
        // FIRST_SCREEN_ID=0 is valid and belongs to row 0 unless saved to a higher row.
        for (i in 0 until screenOrder.size()) {
            val id = screenOrder.get(i)
            if (id < 0) continue
            var assigned = false
            for (r in 1 until rowCount) {
                if (id in savedUpperIds[r - 1]) {
                    rowScreenIds[r].add(id)
                    assigned = true
                    break
                }
            }
            if (!assigned) rowScreenIds[0].add(id)
        }

        // Re-add any upper-row screens not loaded by the model (empty screens have no DB items
        // so the model skips them; restore them manually each launch).
        for (r in 1 until rowCount) {
            for (id in savedUpperIds[r - 1]) {
                if (id !in rowScreenIds[r]) {
                    workspace.insertNewWorkspaceScreen(id)
                    rowScreenIds[r].add(id)
                }
            }
        }

        // First run or new row added: allocate a fresh screen for any empty upper row.
        for (r in 1 until rowCount) {
            if (rowScreenIds[r].isEmpty()) {
                val newId = allocateScreenId()
                workspace.insertNewWorkspaceScreen(newId)
                rowScreenIds[r].add(newId)
                prefs.setRowScreenIds(r, rowScreenIds[r].map { it.toString() }.toSet())
            }
        }

        // Protect upper row screens from stripEmptyScreens() — they are intentionally empty.
        for (r in 1 until rowCount) {
            for (id in rowScreenIds[r]) workspace.protectScreenFromStripping(id)
        }

        // Sort all rows by workspace page order, then enforce contiguity.
        // insertNewWorkspaceScreen always appends to the end, which can interleave upper-row pages
        // with row-0 pages if row-0 screens appear later in screenOrder. Reordering now ensures
        // the [first..last] range for each row is clean before the user can interact.
        for (r in rowScreenIds.indices) {
            rowScreenIds[r].sortBy { workspace.getPageIndexForScreenId(it) }
        }
        workspace.reorderPages(rowScreenIds.flatten())

        Log.d(TAG, "initialize done: rows=${rowScreenIds.mapIndexed { i, ids -> "[$i]$ids" }}")

        updateScrollRange(0)
        // Mark initialized before calling setCurrentPage so onWorkspacePageSettled can process
        // the resulting page event normally (updating rowPageIndex) rather than re-entering here.
        initialized = true
        // Cancel any Launcher3-initiated snap animation (e.g. restoring a saved page that belongs
        // to a non-row-0 row) and park the workspace on the correct row-0 starting page.
        // abortScrollerAnimation inside setCurrentPage stops the in-flight scroller so no
        // onPageEndTransition fires afterward for the stale target page.
        workspace.setCurrentPage(targetWorkspacePage(0))
    }

    private fun allocateScreenId(): Int {
        val screenOrder = launcher.workspace.screenOrder
        val allocated   = rowScreenIds.flatten().toSet()
        var maxId = 0
        for (i in 0 until screenOrder.size()) {
            val id = screenOrder.get(i)
            if (id > 0) maxId = maxOf(maxId, id)
        }
        var candidate = maxId + 100
        while (candidate in allocated) candidate++
        return candidate
    }

    companion object {
        private const val PHASE_MS = 150L
        private const val TAG = "RowNav"
    }
}
