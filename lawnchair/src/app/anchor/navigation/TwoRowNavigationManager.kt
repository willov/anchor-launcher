package app.anchor.navigation

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
    private var isDragging = false
    private var currentDragOverlay: TwoRowDragOverlay? = null

    private val handler = Handler(Looper.getMainLooper())
    private val clearTransitionFlag = Runnable { isTransitioning = false }

    fun setup() { /* deferred to onWorkspacePageSettled */ }

    // ── Convenience accessors used by SwipeDownStatusBarController ───────────────────────────────

    /** Navigate one row up (toward higher index). No-op if already at the top or transitioning. */
    fun navigateUp() {
        if (isTransitioning || !initialized) return
        cleanupStaleScreenIds()
        if (activeRowIndex >= rowCount - 1) return
        val from = activeRowIndex
        activeRowIndex++
        animateRowTransition(from, activeRowIndex)
    }

    /** Navigate one row down (toward lower index). No-op if already at row 0 or transitioning. */
    fun navigateDown() {
        if (isTransitioning || !initialized) return
        cleanupStaleScreenIds()
        if (activeRowIndex <= 0) return
        val from = activeRowIndex
        activeRowIndex--
        animateRowTransition(from, activeRowIndex)
    }

    /**
     * Plays a brief downward nudge to signal there is no row above.
     * Called by both [app.anchor.navigation.TwoRowSwipeTouchController] and
     * [app.anchor.navigation.SwipeDownStatusBarController] so both entry points share the same
     * feedback.
     */
    fun bounceTopEdge() {
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

    // ── Drag handling ───────────────────────────────────────────────────────────────────────────

    /** Called when a drag begins. Adds the row-switch overlay and releases scroll clamping. */
    fun onDragStarted() {
        if (!initialized) return
        isDragging = true
        // Reposition EXTRA_EMPTY_SCREEN (if Launcher3 inserted one) to sit immediately after
        // the active row's last page so the user can drag right to create a new page.
        // Do NOT call updateScrollRange here: Launcher3 just inserted EXTRA via addView which
        // makes isPageScrollsInitialized() false, so getScrollForPage() returns 0, and calling
        // setAllowedPageRange → updateMinAndMaxScrollX() would set mMaxScroll = 0, causing our
        // scrollTo override to snap the workspace to position 0. mAllowedPageEnd is already
        // correct from the last onWorkspacePageSettled call.
        launcher.workspace.repositionExtraEmptyScreenForDrag()
        installDragOverlay()
    }

    /**
     * Creates (or re-creates) the row-switch edge-strip overlay for the current [activeRowIndex].
     * Called at drag-start and again after every row switch so the user can bounce back.
     */
    private fun installDragOverlay() {
        val overlay = TwoRowDragOverlay(
            context = launcher,
            activeRowIndex = activeRowIndex,
            rowCount = rowCount,
            onRowSwitch = { targetRowIndex ->
                currentDragOverlay?.let { if (it.parent != null) launcher.dragLayer.removeView(it) }
                currentDragOverlay = null
                if (targetRowIndex > activeRowIndex) navigateUp() else navigateDown()
                // Install a fresh overlay for the new active row so the user can switch back.
                if (isDragging) installDragOverlay()
            },
        )
        launcher.dragLayer.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        currentDragOverlay = overlay
    }

    /** Called on every drag-move with the icon's Y coordinate in DragLayer space. */
    fun onDragMoved(dragY: Float) {
        currentDragOverlay?.onDragMoved(dragY)
    }

    /** Called when the drag ends (drop or cancel). Adopts any new screens then re-clamps. */
    fun onDragEnded() {
        isDragging = false
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
        // Refresh scroll bounds on every page settle — handles cases where page indices shifted
        // (e.g. EXTRA removed after drag, page deleted) without a full navigation cycle.
        // Skip during drag: Launcher3 inserts EXTRA_EMPTY_SCREEN via addView, making
        // isPageScrollsInitialized() false. Calling setAllowedPageRange then sets mMaxScroll = 0
        // and excludes EXTRA from mAllowedPageEnd, blocking new-page creation on the right edge.
        if (!isDragging) updateScrollRange(activeRowIndex)
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

        // During a drag, move to the target row's exact bounds immediately so the user cannot
        // scroll back into the previous row's pages while the animation is playing.
        if (isDragging) updateScrollRange(toRow)

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
        // Skip stale IDs (screens removed by stripEmptyScreens before rowScreenIds is updated).
        // Using first/last that are still live avoids an early return that would leave
        // mAllowedPageEnd pointing at a deleted page, which silently disables the clamp.
        val liveIndices = ids.mapNotNull { id ->
            workspace.getPageIndexForScreenId(id).takeIf { it >= 0 }
        }
        if (liveIndices.isNotEmpty()) workspace.setAllowedPageRange(liveIndices.first(), liveIndices.last())
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

        // Protect any upper-row screens from being stripped (adoption may have added unprotected ones).
        for (r in 1 until rowCount) {
            for (id in rowScreenIds[r]) workspace.protectScreenFromStripping(id)
        }

        // Persist upper rows (row 0 is implicit, no explicit storage needed).
        if (activeRowIndex > 0) {
            prefs.setRowScreenIds(activeRowIndex, rowScreenIds[activeRowIndex].map { it.toString() }.toSet())
        }
        updateScrollRange(activeRowIndex)
    }

    // ── Stale screen cleanup ────────────────────────────────────────────────────────────────────

    /**
     * Removes screen IDs from [rowScreenIds] that no longer exist in the workspace (deleted by
     * stripEmptyScreens or explicitly removed). If an upper row becomes empty after cleanup,
     * allocates a fresh screen so the row remains navigable. Called before every row navigation.
     */
    private fun cleanupStaleScreenIds() {
        val workspace = launcher.workspace
        var changed = false

        for (r in rowScreenIds.indices) {
            val before = rowScreenIds[r].size
            rowScreenIds[r].removeAll { workspace.getPageIndexForScreenId(it) < 0 }
            if (rowScreenIds[r].size != before) {
                changed = true
                rowPageIndex[r] = rowPageIndex[r].coerceAtMost((rowScreenIds[r].size - 1).coerceAtLeast(0))
                Log.d(TAG, "cleanupStaleScreenIds: removed stale IDs from row $r, remaining=${rowScreenIds[r]}")
            }
            // Upper rows must always have at least one screen.
            if (r > 0 && rowScreenIds[r].isEmpty()) {
                val newId = allocateScreenId()
                workspace.insertNewWorkspaceScreen(newId)
                workspace.protectScreenFromStripping(newId)
                rowScreenIds[r].add(newId)
                prefs.setRowScreenIds(r, rowScreenIds[r].map { it.toString() }.toSet())
                changed = true
                Log.d(TAG, "cleanupStaleScreenIds: row $r empty, allocated replacement $newId")
            }
        }

        if (changed) {
            for (r in rowScreenIds.indices) {
                rowScreenIds[r].sortBy { workspace.getPageIndexForScreenId(it) }
            }
            workspace.reorderPages(rowScreenIds.flatten())
            for (r in 1 until rowCount) {
                prefs.setRowScreenIds(r, rowScreenIds[r].map { it.toString() }.toSet())
            }
            updateScrollRange(activeRowIndex)
        }
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

        // Register callback so TwoRowNavigationManager is notified (deferred, after layout) when
        // stripEmptyScreens removes pages and physical indices shift.
        launcher.workspace.setOnWorkspaceScreensChanged(Runnable {
            if (initialized) updateScrollRange(activeRowIndex)
        })

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
        private const val BOUNCE_NUDGE_DP = 24f
    }
}
