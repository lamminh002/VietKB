package com.goviet.keyboard.engine

/**
 * Vietnamese tones.  [key] is the Telex key that produces the tone
 * ('z' clears: [NONE] has no key).
 */
enum class Tone(val index: Int, val key: Char?) {
    NONE(0, null),
    ACUTE(1, 's'),
    GRAVE(2, 'f'),
    HOOK(3, 'r'),
    TILDE(4, 'x'),
    DOT(5, 'j');

    companion object {
        fun fromKey(c: Char): Tone? = when (c.lowercaseChar()) {
            's' -> ACUTE
            'f' -> GRAVE
            'r' -> HOOK
            'x' -> TILDE
            'j' -> DOT
            'z' -> NONE
            else -> null
        }

        /** [Tone] by [Tone.index]; [Tone.NONE] for an unknown index. */
        fun fromInt(index: Int): Tone = when (index) {
            1 -> ACUTE
            2 -> GRAVE
            3 -> HOOK
            4 -> TILDE
            5 -> DOT
            else -> NONE
        }
    }
}

/**
 * Engine configuration options.
 */
data class EngineOptions(
    var macroEnabled: Boolean = false,
    var alwaysMacro: Boolean = false,
    var directW: Boolean = false,
    var oldTonePlacement: Boolean = false
)
