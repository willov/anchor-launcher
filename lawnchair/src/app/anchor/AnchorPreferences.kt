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

    /** True (default) = insert alphabetical section headers (A, B, C…) between app groups in the drawer. */
    var drawerSectionHeaders: Boolean
        get() = prefs.getBoolean(KEY_DRAWER_SECTION_HEADERS, true)
        set(value) { prefs.edit().putBoolean(KEY_DRAWER_SECTION_HEADERS, value).apply() }

    /**
     * Which image the stabilized background renders. One of the WALLPAPER_SOURCE_* constants.
     *
     * - [WALLPAPER_SOURCE_SYSTEM] (default): no stabilization — the real device wallpaper is shown
     *   by the system (FLAG_SHOW_WALLPAPER) and rotates normally. Never black, no permission.
     * - [WALLPAPER_SOURCE_CUSTOM]: a user-picked image (copied to app storage via the photo picker;
     *   no permission needed) is rendered with the counter-rotation → pixel-perfect rotation.
     * - [WALLPAPER_SOURCE_SYSTEM_STABILIZED]: render the real system wallpaper with the
     *   counter-rotation. Requires reading the wallpaper bitmap, which needs MANAGE_EXTERNAL_STORAGE
     *   — only available in the github/nightly builds; not a Play-safe path (power-user only).
     */
    var wallpaperSource: String
        get() = prefs.getString(KEY_WALLPAPER_SOURCE, WALLPAPER_SOURCE_SYSTEM)!!
        set(value) { prefs.edit().putString(KEY_WALLPAPER_SOURCE, value).apply() }

    /** Absolute path to the user's picked custom wallpaper image in app storage, or null. */
    var customWallpaperPath: String?
        get() = prefs.getString(KEY_CUSTOM_WALLPAPER_PATH, null)
        set(value) { prefs.edit().putString(KEY_CUSTOM_WALLPAPER_PATH, value).apply() }

    /** True once the first-launch "set a rotation-stable wallpaper?" prompt has been shown. */
    var wallpaperOnboardingShown: Boolean
        get() = prefs.getBoolean(KEY_WALLPAPER_ONBOARDING_SHOWN, false)
        set(value) { prefs.edit().putBoolean(KEY_WALLPAPER_ONBOARDING_SHOWN, value).apply() }

    /** True once the first-launch grid/icon setup wizard has been shown (or skipped). */
    var gridOnboardingShown: Boolean
        get() = prefs.getBoolean(KEY_GRID_ONBOARDING_SHOWN, false)
        set(value) { prefs.edit().putBoolean(KEY_GRID_ONBOARDING_SHOWN, value).apply() }

    /**
     * One-shot flag: the picker set the Anchor live wallpaper and the launcher should restart once on
     * its next resume. Restarting reproduces the clean cold-start state under which Samsung reliably
     * shows our live wallpaper — setting it from within the running app otherwise leaves the wallpaper
     * surface in a state that blanks to black on the next Home press. Consumed (cleared) on read.
     */
    var pendingWallpaperRestart: Boolean
        get() = prefs.getBoolean(KEY_PENDING_WALLPAPER_RESTART, false)
        // commit() (synchronous) NOT apply(): the launcher restarts (kills the process) immediately
        // after clearing this flag, so an async apply() would be lost → restart loop. commit() persists
        // before the process dies.
        set(value) { prefs.edit().putBoolean(KEY_PENDING_WALLPAPER_RESTART, value).commit() }

    /**
     * True (default) = columns and rows are linked: moving one grid slider moves the other along the
     * screen's aspect ratio (like linked width/height in a design tool), keeping the sweet-spot
     * proportions the recommendation engine targets. False = the sliders move independently.
     */
    var linkGridDimensions: Boolean
        get() = prefs.getBoolean(KEY_LINK_GRID_DIMENSIONS, true)
        set(value) { prefs.edit().putBoolean(KEY_LINK_GRID_DIMENSIONS, value).apply() }

    /**
     * True when the legacy **window-background** counter-rotation drawable should be active. This is
     * the OLD rendering path; the CUSTOM source no longer uses it — a picked image is now rendered by
     * [app.anchor.wallpaper.AnchorWallpaperService] (our live wallpaper), which is a wallpaper SURFACE
     * and therefore escapes the app→home screenshot-rotate snap. The window-bg path remains only for
     * the power-user SYSTEM_STABILIZED source. CUSTOM runs as passthrough (FLAG_SHOW_WALLPAPER stays
     * set) so the live wallpaper shows through and drives parallax.
     */
    val wallpaperStabilizationActive: Boolean
        get() = wallpaperSource == WALLPAPER_SOURCE_SYSTEM_STABILIZED

    /**
     * Which animation to use when the device rotates. One of the TRANSITION_* constants.
     * The legacy [TRANSITION_CROSSFADE] value is migrated to [TRANSITION_FADE] on read.
     */
    var rotationTransition: String
        get() = when (val v = prefs.getString(KEY_ROTATION_TRANSITION, TRANSITION_FADE)!!) {
            TRANSITION_CROSSFADE -> TRANSITION_FADE
            else -> v
        }
        set(value) { prefs.edit().putString(KEY_ROTATION_TRANSITION, value).apply() }

    /** Duration (ms) of the fade for [TRANSITION_FADE]. */
    var rotationFadeDurationMs: Int
        get() = prefs.getInt(KEY_FADE_DURATION, FADE_DURATION_DEFAULT)
            .coerceIn(FADE_DURATION_MIN, FADE_DURATION_MAX)
        set(value) { prefs.edit().putInt(KEY_FADE_DURATION, value).apply() }

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
     * True (default) = a swipe up starting from the bottom edge of the screen — anywhere outside the
     * system gesture zones (home pill / back) — opens the standard all-apps drawer, from any row.
     * A system-Overview-style gesture. False = only the standard row-0 swipe-up opens all-apps.
     */
    var bottomEdgeSwipeUpAllApps: Boolean
        get() = prefs.getBoolean(KEY_BOTTOM_EDGE_SWIPE_UP, true)
        set(value) { prefs.edit().putBoolean(KEY_BOTTOM_EDGE_SWIPE_UP, value).apply() }

    /**
     * Height of the bottom-edge grab strip for the swipe-up-to-all-apps gesture, as a PERCENT of the
     * screen height. A swipe up must start within this bottom band to open the drawer. Range
     * [BOTTOM_EDGE_ZONE_PERCENT_MIN]..[BOTTOM_EDGE_ZONE_PERCENT_MAX], default
     * [BOTTOM_EDGE_ZONE_PERCENT_DEFAULT].
     */
    var bottomEdgeSwipeUpZonePercent: Int
        get() = prefs.getInt(KEY_BOTTOM_EDGE_ZONE_PERCENT, BOTTOM_EDGE_ZONE_PERCENT_DEFAULT)
            .coerceIn(BOTTOM_EDGE_ZONE_PERCENT_MIN, BOTTOM_EDGE_ZONE_PERCENT_MAX)
        set(value) {
            prefs.edit()
                .putInt(
                    KEY_BOTTOM_EDGE_ZONE_PERCENT,
                    value.coerceIn(BOTTOM_EDGE_ZONE_PERCENT_MIN, BOTTOM_EDGE_ZONE_PERCENT_MAX),
                )
                .apply()
        }

    /**
     * Wallpaper parallax strength as a PERCENT of the screen's short side that the wallpaper drifts
     * per navigation step (one page swipe or one row switch). 0 disables parallax. Capped per axis at
     * the image's available pan room. Default [PARALLAX_PERCENT_DEFAULT].
     */
    var wallpaperParallaxPercent: Int
        get() = prefs.getInt(KEY_PARALLAX_PERCENT, PARALLAX_PERCENT_DEFAULT)
            .coerceIn(PARALLAX_PERCENT_MIN, PARALLAX_PERCENT_MAX)
        set(value) {
            prefs.edit()
                .putInt(KEY_PARALLAX_PERCENT, value.coerceIn(PARALLAX_PERCENT_MIN, PARALLAX_PERCENT_MAX))
                .apply()
        }

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
        private const val KEY_DRAWER_SECTION_HEADERS  = "drawer_section_headers"
        private const val KEY_WALLPAPER_SOURCE = "wallpaper_source"
        private const val KEY_CUSTOM_WALLPAPER_PATH = "custom_wallpaper_path"
        private const val KEY_WALLPAPER_ONBOARDING_SHOWN = "wallpaper_onboarding_shown"
        private const val KEY_GRID_ONBOARDING_SHOWN = "grid_onboarding_shown"
        private const val KEY_PENDING_WALLPAPER_RESTART = "pending_wallpaper_restart"
        private const val KEY_LINK_GRID_DIMENSIONS = "link_grid_dimensions"
        private const val KEY_ROTATION_TRANSITION = "rotation_transition"
        private const val KEY_FADE_DURATION       = "rotation_fade_duration_ms"
        private const val KEY_STATUS_BAR_SWIPE    = "status_bar_swipe_action"
        private const val KEY_BOTTOM_EDGE_SWIPE_UP = "bottom_edge_swipe_up_all_apps"
        private const val KEY_BOTTOM_EDGE_ZONE_PERCENT = "bottom_edge_swipe_up_zone_percent"
        private const val KEY_ROW_COUNT           = "row_count"
        private const val KEY_ROW_SCREENS_PREFIX  = "row_screens_"
        private const val KEY_PARALLAX_PERCENT    = "wallpaper_parallax_percent"

        const val SWIPE_NOTIFICATIONS = "notifications"
        const val SWIPE_NEXT_ROW      = "next_row"

        const val TRANSITION_TRADITIONAL  = "traditional"
        const val TRANSITION_INSTANT      = "instant"
        // Fade the icons out, rebind the grid hidden, fade them back in over the stabilized
        // wallpaper. The legacy "crossfade" value maps here for backward compatibility.
        const val TRANSITION_FADE         = "fade"
        const val TRANSITION_CROSSFADE    = "crossfade"  // legacy value → treated as TRANSITION_FADE

        // Fade duration bounds (ms) for the fade-based transitions.
        const val FADE_DURATION_MIN = 50
        const val FADE_DURATION_MAX = 600
        const val FADE_DURATION_DEFAULT = 150

        // Wallpaper source for the stabilized background.
        const val WALLPAPER_SOURCE_SYSTEM = "system"                       // default: no stabilization
        const val WALLPAPER_SOURCE_CUSTOM = "custom"                       // user-picked image
        const val WALLPAPER_SOURCE_SYSTEM_STABILIZED = "system_stabilized" // needs MANAGE_EXTERNAL_STORAGE

        const val MAX_ROWS = 5

        // Bottom-edge swipe-up grab strip height (percent of screen height).
        const val BOTTOM_EDGE_ZONE_PERCENT_MIN = 0
        const val BOTTOM_EDGE_ZONE_PERCENT_MAX = 20
        const val BOTTOM_EDGE_ZONE_PERCENT_DEFAULT = 10

        // Wallpaper parallax strength (percent of screen short side per navigation step).
        const val PARALLAX_PERCENT_MIN = 0
        const val PARALLAX_PERCENT_MAX = 25
        const val PARALLAX_PERCENT_DEFAULT = 15
    }
}
