package com.goviet.keyboard.ui

/**
 * Shared BACKSPACE swipe-to-delete-words math, used by both [KeyTouchHandler]
 * (QWERTY) and [TraditionalTpadView] (T-pad).
 *
 * Behaviour contract (must stay identical for both callers):
 * - Moving more than 10dp horizontally stops key repeat.
 * - Every further 30dp dragged left deletes one more word via DELETE_WORD.
 *
 * The tracker only computes; emitting keys and starting/stopping repeat
 * stays with the caller, because DOWN semantics differ (T-pad fires
 * BACKSPACE immediately on DOWN, QWERTY waits for UP/repeat).
 */
class BackspaceSwipeTracker {

    private var startX = 0f
    var selectCount: Int = 0
        private set

    fun reset(startX: Float) {
        this.startX = startX
        selectCount = 0
    }

    /** Whether the finger moved far enough to stop key repeat. */
    fun shouldStopRepeat(currentX: Float, density: Float): Boolean =
        Math.abs(currentX - startX) > 10f * density

    /**
     * Number of *new* DELETE_WORD events to emit for the current finger X
     * (0 when the finger has not crossed another 30dp word boundary).
     */
    fun advanceWords(currentX: Float, density: Float): Int {
        val deltaX = currentX - startX
        if (deltaX >= -30f * density) return 0
        val wordsToDelete = (-deltaX / (30f * density)).toInt()
        if (wordsToDelete <= selectCount) return 0
        val diff = wordsToDelete - selectCount
        selectCount = wordsToDelete
        return diff
    }
}
