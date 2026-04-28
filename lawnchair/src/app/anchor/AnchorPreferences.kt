package app.anchor

import android.content.Context

/**
 * Lightweight SharedPreferences wrapper for Anchor Launcher settings.
 * Decoupled from Lawnchair's preference system so it can be read from any Context.
 */
class AnchorPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True (default) = show the full A–Z letter list on the right edge of the app drawer. */
    var drawerLetterScroller: Boolean
        get() = prefs.getBoolean(KEY_DRAWER_LETTER_SCROLLER, true)
        set(value) { prefs.edit().putBoolean(KEY_DRAWER_LETTER_SCROLLER, value).apply() }

    /** Which animation to use when the device rotates. One of the TRANSITION_* constants. */
    var rotationTransition: String
        get() = prefs.getString(KEY_ROTATION_TRANSITION, TRANSITION_CROSSFADE)!!
        set(value) { prefs.edit().putString(KEY_ROTATION_TRANSITION, value).apply() }

    /**
     * What a downward swipe from the top edge does.
     *
     * [SWIPE_NOTIFICATIONS] — default Android behaviour: expand the notification shade.
     * [SWIPE_NEXT_ROW]      — navigate to the next row of screens (wraps between rows).
     *                         Disables notification shade expansion while active.
     */
    var statusBarSwipeAction: String
        get() = prefs.getString(KEY_STATUS_BAR_SWIPE, SWIPE_NOTIFICATIONS)!!
        set(value) { prefs.edit().putString(KEY_STATUS_BAR_SWIPE, value).apply() }

    /**
     * Total number of workspace rows in the 2D navigation matrix. Row 0 is the bottom icon row;
     * rows 1..N-1 are above it. Default 2.
     */
    var rowCount: Int
        get() = prefs.getInt(KEY_ROW_COUNT, 2).coerceIn(1, MAX_ROWS)
        set(value) { prefs.edit().putInt(KEY_ROW_COUNT, value.coerceIn(1, MAX_ROWS)).apply() }

    /**
     * Screen IDs (as strings) belonging to workspace row [rowIndex]. Row 0 is implicit — it owns
     * every screen not assigned to a higher row. Only rows 1..N-1 need explicit storage.
     *
     * Stored as Set<String> because SharedPreferences has no integer-set type.
     */
    fun getRowScreenIds(rowIndex: Int): Set<String> =
        prefs.getStringSet(rowScreenKey(rowIndex), emptySet()) ?: emptySet()

    fun setRowScreenIds(rowIndex: Int, ids: Set<String>) {
        prefs.edit().putStringSet(rowScreenKey(rowIndex), ids).apply()
    }

    private fun rowScreenKey(rowIndex: Int) = "$KEY_ROW_SCREENS_PREFIX$rowIndex"

    companion object {
        private const val PREFS_NAME             = "anchor_prefs"
        private const val KEY_DRAWER_LETTER_SCROLLER = "drawer_letter_scroller"
        private const val KEY_ROTATION_TRANSITION = "rotation_transition"
        private const val KEY_STATUS_BAR_SWIPE    = "status_bar_swipe_action"
        private const val KEY_ROW_COUNT           = "row_count"
        private const val KEY_ROW_SCREENS_PREFIX  = "row_screens_"

        const val SWIPE_NOTIFICATIONS = "notifications"
        const val SWIPE_NEXT_ROW      = "next_row"

        const val TRANSITION_TRADITIONAL = "traditional"
        const val TRANSITION_INSTANT     = "instant"
        const val TRANSITION_CROSSFADE   = "crossfade"

        const val MAX_ROWS = 5
    }
}
