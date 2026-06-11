/*
 * Copyright (C) 2025 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.anchor.applist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.util.Themes

/**
 * Alphabetical section header used in the all-apps drawer (the bold "A", "B", "C…" rows).
 *
 * Draws a thin hairline separator across the top so each letter group is visually divided from
 * the one above it. The first header in the list paints no line (there is nothing above it).
 *
 * Replaces the inline plain TextView previously created in [com.android.launcher3.allapps.BaseAllAppsAdapter].
 */
class SectionHeaderView(context: Context) : TextView(context) {

    /** Set false for the very first header so it does not draw a dangling top line. */
    var drawTopDivider: Boolean = true

    private val density = resources.displayMetrics.density

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Group rule: ~30% of the secondary text colour at 1.5dp reads as a clear divider
        // without being a heavy black line.
        color = (Themes.getAttrColor(context, android.R.attr.textColorSecondary) and 0x00FFFFFF) or 0x4D000000
        strokeWidth = (1.5f * density).coerceAtLeast(1f)
    }

    init {
        layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT,
        )
        val hPad = (16 * density).toInt()
        setPadding(hPad, (14 * density).toInt(), hPad, (4 * density).toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        setTextColor(Themes.getAttrColor(context, android.R.attr.textColorSecondary))
    }

    override fun onDraw(canvas: Canvas) {
        if (drawTopDivider) {
            val inset = paddingLeft.toFloat()
            val y = dividerPaint.strokeWidth / 2f
            canvas.drawLine(inset, y, width - inset, y, dividerPaint)
        }
        super.onDraw(canvas)
    }
}
