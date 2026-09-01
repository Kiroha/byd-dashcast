package com.byd.dashcast.ui.main

/** Pure layout decision for the regular and compact application panels. */
object AppsPanelLayoutPolicy {
    data class Config(
        val fixedWidthDp: Int?,
        val weight: Float,
        val gridSpanCount: Int,
        val showCategoryFilters: Boolean,
    )

    @JvmStatic
    fun resolve(compact: Boolean, filtersEnabled: Boolean): Config =
        if (compact) {
            Config(fixedWidthDp = 160, weight = 0f, gridSpanCount = 2,
                showCategoryFilters = false)
        } else {
            Config(fixedWidthDp = null, weight = 1.4f, gridSpanCount = 5,
                showCategoryFilters = filtersEnabled)
        }
}