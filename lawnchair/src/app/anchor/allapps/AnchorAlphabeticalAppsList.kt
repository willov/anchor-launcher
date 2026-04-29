/*
 * Copyright (C) 2025 Anchor Launcher Contributors
 *
 * Licensed under the Apache License, Version 2.0.
 * See LICENSE for details.
 */

package app.anchor.allapps

import android.content.Context
import com.android.launcher3.allapps.AlphabeticalAppsList
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
import com.android.launcher3.allapps.BaseAllAppsAdapter.VIEW_TYPE_SECTION_HEADER
import com.android.launcher3.allapps.PrivateProfileManager
import com.android.launcher3.allapps.WorkProfileManager
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.views.ActivityContext
import app.lawnchair.allapps.LawnchairAlphabeticalAppsList

/**
 * Extends [LawnchairAlphabeticalAppsList] to insert inline alphabetical section header items
 * (A, B, C…) before the first app in each letter group in the main all-apps list.
 *
 * The headers are full-width [VIEW_TYPE_SECTION_HEADER] adapter items. Because that type is
 * included in [VIEW_TYPE_MASK_DIVIDER], the row-index counter in
 * [AlphabeticalAppsList.updateAdapterItems] resets at each header, ensuring the first app after a
 * header always starts a new row.
 *
 * [AlphabeticalAppsList.FastScrollSectionInfo] positions are corrected after header insertion so
 * the side fast-scroller jumps to the header row, not the (now-shifted) first app row.
 *
 * Work / private-space app lists are passed through to the super implementation unchanged.
 */
class AnchorAlphabeticalAppsList<T>(
    context: T,
    appsStore: AllAppsStore<T>,
    workProfileManager: WorkProfileManager?,
    privateProfileManager: PrivateProfileManager?,
) : LawnchairAlphabeticalAppsList<T>(context, appsStore, workProfileManager, privateProfileManager)
    where T : Context, T : ActivityContext {

    override fun addAppsWithSections(appList: List<AppInfo?>?, startPosition: Int): Int {
        if (appList.isNullOrEmpty()) return startPosition

        // If section headers are disabled, delegate entirely to Lawnchair.
        val ctx = mActivityContext as Context
        if (!app.anchor.AnchorPreferences(ctx).drawerSectionHeaders) {
            return super.addAppsWithSections(appList, startPosition)
        }

        // Snapshot how many fast-scroller sections exist before Lawnchair's call.
        val sectionsBefore = getFastScrollerSections().size

        // Lawnchair handles folders, categories, hidden apps, work/private-space delegation.
        val finalPosition = super.addAppsWithSections(appList, startPosition)

        // In folder/category mode Lawnchair doesn't add fast-scroller sections, so the list
        // size won't grow and newSections will be empty — skip header injection.
        val sections = getFastScrollerSections()
        if (sections.size == sectionsBefore) return finalPosition

        val newSections = sections.drop(sectionsBefore).toList()

        // Insert header items BACKWARDS so earlier positions aren't shifted by later inserts.
        for (sectionInfo in newSections.reversed()) {
            val headerItem = AdapterItem(VIEW_TYPE_SECTION_HEADER)
            headerItem.sectionName = sectionInfo.sectionName.toString()
            mAdapterItems.add(sectionInfo.position, headerItem)
        }

        // Rebuild fast-scroller positions: section k has k header items before it.
        val retained = sections.take(sectionsBefore).toList()
        (sections as MutableList).clear()
        sections.addAll(retained)
        for ((k, sectionInfo) in newSections.withIndex()) {
            sections.add(
                AlphabeticalAppsList.FastScrollSectionInfo(
                    sectionInfo.sectionName,
                    sectionInfo.position + k,
                )
            )
        }

        return finalPosition + newSections.size
    }
}
