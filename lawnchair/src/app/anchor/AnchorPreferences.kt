package app.anchor

import android.content.Context

/**
 * Lightweight SharedPreferences wrapper for Anchor Launcher settings.
 * Decoupled from Lawnchair's preference system so it can be read from any Context.
 */
class AnchorPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True (default) = app drawer is the rightmost page. False = leftmost page. */
    var drawerOnRight: Boolean
        get() = prefs.getBoolean(KEY_DRAWER_RIGHT, true)
        set(value) { prefs.edit().putBoolean(KEY_DRAWER_RIGHT, value).apply() }

    /** Number of columns in the app drawer grid. 0 = auto (4 on phone, 5 on tablet). */
    var drawerColumns: Int
        get() = prefs.getInt(KEY_DRAWER_COLS, 0)
        set(value) { prefs.edit().putInt(KEY_DRAWER_COLS, value).apply() }

    /** Which animation to use when the device rotates. One of the TRANSITION_* constants. */
    var rotationTransition: String
        get() = prefs.getString(KEY_ROTATION_TRANSITION, TRANSITION_CROSSFADE)!!
        set(value) { prefs.edit().putString(KEY_ROTATION_TRANSITION, value).apply() }

    companion object {
        private const val PREFS_NAME             = "anchor_prefs"
        private const val KEY_DRAWER_RIGHT        = "drawer_on_right"
        private const val KEY_DRAWER_COLS         = "drawer_columns"
        private const val KEY_ROTATION_TRANSITION = "rotation_transition"

        const val TRANSITION_TRADITIONAL = "traditional"
        const val TRANSITION_INSTANT     = "instant"
        const val TRANSITION_CROSSFADE   = "crossfade"
    }
}
