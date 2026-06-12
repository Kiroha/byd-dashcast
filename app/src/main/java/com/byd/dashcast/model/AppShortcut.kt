package com.byd.dashcast.model

import android.graphics.drawable.Drawable

// @JvmField keeps direct field access (shortcut.id) working from the
// remaining Java call sites during the Kotlin migration.
class AppShortcut(
    @JvmField val id: String,
    @JvmField val label: String,
    @JvmField val icon: Drawable?
)
