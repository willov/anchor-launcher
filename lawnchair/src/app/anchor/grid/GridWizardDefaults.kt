/*
 * Copyright (C) 2026 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.anchor.grid

import android.content.Context
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.LauncherAppState
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking

/**
 * Shared "apply a sensible grid" action used when the user declines the setup wizard. Applies the
 * device-tuned BALANCED recommendation at the current icon size and label state, so even a decline
 * leaves a grid that fits this screen rather than the generic default.
 */
object GridWizardDefaults {

    fun applyBalanced(context: Context) {
        val prefs = PreferenceManager.getInstance(context)
        val prefs2 = PreferenceManager2.getInstance(context)
        val spacing = prefs2.workspaceSpacingDp.firstBlocking().toFloat()
        val iconFactor = prefs2.homeIconSizeFactor.firstBlocking()
        val showLabels = prefs2.showIconLabelsOnHomeScreen.firstBlocking()
        val rec = GridSizeCaps.recommend(
            context, spacing, iconFactor, showLabels, GridSizeCaps.Density.BALANCED,
        )
        prefs.workspaceColumns.set(rec.columns)
        prefs.workspaceRows.set(rec.rows)
        prefs2.workspaceSpacingDp.setBlocking(rec.gapDp) // even-spacing gap from the recommendation
        LauncherAppState.getIDP(context).onPreferencesChanged(context)
    }
}
