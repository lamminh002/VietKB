package com.goviet.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Edge-case contract for glide-final (bán âm cuối i/y/u/o) rimes — the wires
 * Step C must not cut when writes route glides straight into
 * [SyllableState.semiCoda] instead of the core.  Each row pins down an
 * ambiguous keystroke after a glide-final nucleus ("au"/"âu"):
 * a rejected coda, fold attempts (w/a), a plain vowel attempt (o), tones
 * on the core (s/j), and the parked literal spelling.
 */
class SemiCodaEdgeTest {

    private lateinit var engine: VietnameseComposer

    @Before
    fun setUp() {
        engine = VietnameseComposer()
        engine.vietnameseModeEnabled = true
    }

    @Test
    fun rejectedCodaAfterGlideStaysLiteral() {
        // A 'c' coda is not legal after the glide, so it falls out as a
        // literal suffix; the parked "au" keeps its literal spelling.
        assertEquals("lauc", engine.process("lauc"))
    }

    @Test
    fun foldAttemptAfterGlideDoesNotReopenTheRime() {
        // 'w' cannot fold the parked "au"; the key falls out literal.
        assertEquals("lauw", engine.process("lauw"))
        // 'a' folds the parked "au" into "âu", consuming the key.
        assertEquals("lâu", engine.process("laua"))
    }

    @Test
    fun vowelAfterGlideFallsOutLiteral() {
        assertEquals("lauo", engine.process("lauo"))
    }

    @Test
    fun sắcToneLandsOnCoreA() {
        // "au"+s → acute on the 'a' (no â resolve on the tone-only path).
        assertEquals("láu", engine.process("laus"))
    }

    @Test
    fun dotToneLandsOnCoreA() {
        assertEquals("lạu", engine.process("lauj"))
    }

    @Test
    fun sắcOnPlainGlideAi() {
        assertEquals("lái", engine.process("lais"))
    }

    @Test
    fun đFinalFoldViaAa() {
        // "aa"→â, +u closes the glide, +s tones the core â.
        assertEquals("đấu", engine.process("ddaaus"))
    }

    @Test
    fun glideIsWrittenIntoSemiCodaNotCore() {
        // "lưu": the 'u' is a closing semivowel and must live in its own
        // buffer, not the nucleus — the structural reason for the refactor.
        val state = VietnameseComposer.SyllableState()
        engine.replayRawToState("luwu", state)
        assertEquals("ư", state.nucleus.toStringVal())
        assertEquals("u", state.semiCoda.toStringVal())
        assertEquals("lưu", state.toDisplayString(engine.options.oldTonePlacement))
    }

    @Test
    fun aiSplitKeepsPlacementInCompany() {
        val state = VietnameseComposer.SyllableState()
        engine.replayRawToState("lai", state)
        assertEquals("a", state.nucleus.toStringVal())
        assertEquals("i", state.semiCoda.toStringVal())
        engine.replayRawToState("lais", state)
        assertEquals("lái", state.toDisplayString(engine.options.oldTonePlacement))
    }

    @Test
    fun parkedGlideParsesPlainWhenTypeStopsEarly() {
        // A standalone glide rime keeps its literal spelling while parked.
        assertEquals("lau", engine.process("lau"))
        assertEquals("lai", engine.process("lai"))
    }
}