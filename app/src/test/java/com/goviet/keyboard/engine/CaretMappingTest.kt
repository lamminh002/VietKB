package com.goviet.keyboard.engine

import com.goviet.keyboard.VietnameseInputMethodService
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Contract tests for the display↔raw caret mapping in
 * [ImeInputConnectionController] — the piece display-level backspace and
 * mid-word adoption depend on when canonical raw no longer maps 1:1 to
 * display characters (e.g. "â" is one grapheme but two raw keys "aa").
 *
 * Needs Robolectric only to instantiate the controller (its constructor takes
 * the IME service); no InputConnection or editor is touched.
 */
@RunWith(RobolectricTestRunner::class)
class CaretMappingTest {

    private lateinit var engine: VietnameseComposer
    private lateinit var controller: ImeInputConnectionController

    @Before
    fun setUp() {
        engine = VietnameseComposer()
        engine.vietnameseModeEnabled = true
        val service = Robolectric.buildService(VietnameseInputMethodService::class.java).get()
        controller = ImeInputConnectionController(service, engine)
    }

    @Test
    fun rawIndex_twoRawKeysOneGrapheme() {
        // "aa" -> "â": display offset 1 (end) maps back to raw length 2.
        assertEquals(0, controller.rawIndexOfDisplay("aa", "â", 0, true))
        assertEquals(2, controller.rawIndexOfDisplay("aa", "â", 1, true))
    }

    @Test
    fun rawIndex_toneKey() {
        assertEquals(2, controller.rawIndexOfDisplay("as", "á", 1, true))
    }

    @Test
    fun rawIndex_midWordMapsPrefix() {
        // "chuyeen" -> "chuyên" (ê needs the double-e): each display offset
        // maps to the raw prefix that compiles to the same display prefix.
        // Offset 5 is the interesting 2-to-1 case: raw "chuye" is still two
        // display chars short of "chuyê", so it maps to raw 6 ("chuyee").
        assertEquals(3, controller.rawIndexOfDisplay("chuyeen", "chuyên", 3, true))
        assertEquals(4, controller.rawIndexOfDisplay("chuyeen", "chuyên", 4, true))
        assertEquals(6, controller.rawIndexOfDisplay("chuyeen", "chuyên", 5, true))
        // Past-the-end display offset clamps to the raw length.
        assertEquals(7, controller.rawIndexOfDisplay("chuyeen", "chuyên", 6, true))
    }

    @Test
    fun rawIndex_literalPassthrough() {
        assertEquals(0, controller.rawIndexOfDisplay("abc", "abc", 0, true))
        assertEquals(2, controller.rawIndexOfDisplay("abc", "abc", 2, false))
    }

    @Test
    fun displayCursorIndex_compilesRawPrefix() {
        engine.setComposingRaw("chuyen")
        controller.composingCursorIndex = 3
        assertEquals(3, controller.displayCursorIndex())
        controller.composingCursorIndex = 0
        assertEquals(0, controller.displayCursorIndex())
    }

    @Test
    fun compileRawDisplay_singleDerivationPath() {
        engine.setComposingRaw("chuyeen")
        assertEquals("chuyên", controller.compileRawDisplay())
        engine.reset()
        assertEquals("", controller.compileRawDisplay())
    }
}
