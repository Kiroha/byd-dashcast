package com.byd.dashcast.data.prefs

/** Traffic side controls roundabout circulation, independently of steering-wheel position. */
enum class RoundaboutTrafficSide(val storedValue: String) {
    SOURCE("source"), RIGHT("right"), LEFT("left");

    companion object {
        fun fromStored(value: String?): RoundaboutTrafficSide =
            entries.firstOrNull { it.storedValue == value } ?: SOURCE
    }
}
