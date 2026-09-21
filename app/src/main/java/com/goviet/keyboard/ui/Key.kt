package com.goviet.keyboard.ui

import android.graphics.RectF

class Key(
    val code: String,
    var label: String,
    var secondaryLabel: String? = null,
    val isFunctional: Boolean = false,
    val isSpecialEnter: Boolean = false,
    val weight: Float = 1.0f,
    var longPressOptions: List<String>? = null,
    val longPressDefaultIndex: Int = 0,
    val isAccent: Boolean = false,
    val isError: Boolean = false,
    val isSelectingStatus: Boolean = false,
    var isCenterPad: Boolean = false,
    val iconId: String? = null
) {
    val rect: RectF = RectF()
    val visualRect: RectF = RectF()
    val shadowRect: RectF = RectF()
    var isPressed: Boolean = false

    /**
     * Standard drop shadow under the key, derived from [visualRect].
     * Call only after visualRect has been laid out (every call site sets
     * visualRect immediately before — Standard/Tpad/Emoji verified).
     */
    fun applyShadow(density: Float) {
        shadowRect.set(
            visualRect.left, visualRect.top + 0.8f * density,
            visualRect.right, visualRect.bottom + 1.2f * density
        )
    }
}

/**
 * Shared linear hit-test: first key whose touch rect contains (x, y),
 * or null. Used by every grid view; symbol/emoji grid-index math stays
 * separate (different constants and scroll handling).
 */
internal fun findKeyAt(keys: List<Key>, x: Float, y: Float): Key? {
    for (key in keys) {
        if (key.rect.contains(x, y)) {
            return key
        }
    }
    return null
}
