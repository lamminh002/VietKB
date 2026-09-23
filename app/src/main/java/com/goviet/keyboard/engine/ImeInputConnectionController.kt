package com.goviet.keyboard.engine

import com.goviet.keyboard.VietnameseInputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * ImeInputConnectionController (IME Input Controller)
 *
 * Architecture Role:
 * - Direct-commit typing without underline ("gõ không gạch chân").
 * - Default always on: no settings toggle required.
 * - Flicker-free in Zalo, Telegram, Chrome, and custom editors:
 *   1. Minimal diff sync: pure appends never delete; tone/vowel changes delete only the differing suffix.
 *   2. Atomic batch edits: all mutations enclosed in beginBatchEdit/endBatchEdit.
 *   3. Zero redundant setSelection calls: caret naturally tracks the edit point without jumping.
 *   4. Zero synchronous Binder IPCs during typing bursts.
 *   5. Robust onUpdateSelection: never resets composing state during active typing or idle pauses.
 */
class ImeInputConnectionController(
    val service: VietnameseInputMethodService,
    val inputEngine: VietnameseComposer
) {

    private val TAG = "ImeInputConnectionController"

    val backspaceHandler = BackspaceHandler(this)

    enum class TypingMode {
        VIETNAMESE,
        LATIN
    }

    var typingMode: TypingMode = TypingMode.VIETNAMESE
        private set

    fun updateTypingMode(editorInfo: EditorInfo?) {
        if (editorInfo == null) {
            typingMode = TypingMode.VIETNAMESE
            return
        }
        val inputType = editorInfo.inputType
        if (inputType == android.text.InputType.TYPE_NULL) {
            typingMode = TypingMode.LATIN
            return
        }
        val classType = inputType and android.text.InputType.TYPE_MASK_CLASS
        if (classType == android.text.InputType.TYPE_CLASS_TEXT) {
            val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
            if (variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == 224) {
                typingMode = TypingMode.LATIN
                return
            }
        }
        typingMode = TypingMode.VIETNAMESE
    }

    var lastSetComposingText: String? = null
    private var lastShiftTime = 0L
    var isSelecting: Boolean = false
    var lastKeyPressTime = 0L
    private val displayBuf = OwnedBuffer()
    var composingStartInEditor = -1
    var composingCursorIndex = 0

    var cachedSelStart: Int = 0
    var cachedSelEnd: Int = 0
    var cachedCandidatesStart: Int = -1
    var cachedCandidatesEnd: Int = -1

    var userMovedCursor: Boolean = false
    var userSelectedText: Boolean = false

    /**
     * Handles selection and cursor updates from the host application.
     */
    fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        if (newSelStart >= 0) cachedSelStart = newSelStart
        if (newSelEnd >= 0) cachedSelEnd = newSelEnd
        cachedCandidatesStart = candidatesStart
        cachedCandidatesEnd = candidatesEnd

        if (!inputEngine.isComposing()) {
            userMovedCursor = (newSelStart != oldSelStart || newSelEnd != oldSelEnd)
            userSelectedText = (newSelStart != newSelEnd)
            return
        }

        // Active composing session:
        // In direct-commit mode without underline, candidatesStart is typically -1.
        // We track composingStartInEditor and the length of the current word.
        val lastDisplay = lastSetComposingText ?: ""
        val wordLen = lastDisplay.length
        val expectedCaret = if (composingStartInEditor >= 0) composingStartInEditor + wordLen else -1

        val isRecentTyping = (System.currentTimeMillis() - lastKeyPressTime < 450L)

        // If the cursor is at the expected caret position or within the recent typing window,
        // this is our own typing reflection; do NOT clear state.
        val isOurOwnTyping = (expectedCaret >= 0 && newSelStart == expectedCaret && newSelEnd == expectedCaret) ||
                (isRecentTyping && composingStartInEditor >= 0 && newSelStart >= composingStartInEditor && newSelStart <= composingStartInEditor + wordLen + 1)

        if (isOurOwnTyping) {
            userMovedCursor = false
            userSelectedText = false
            return
        }

        // User genuinely tapped elsewhere on the screen or selected text
        userMovedCursor = true
        userSelectedText = (newSelStart != newSelEnd)
        clearState()
    }

    data class MacroExpansionRecord(val trigger: String, val expandedText: String, val timestamp: Long)
    var lastExpandedMacro: MacroExpansionRecord? = null

    private fun recordImeCommit(word: String) {
        val trimmed = word.trim()
        if (trimmed.isNotEmpty()) {
            service.lastCommittedWord = VietnameseUnicode.normalizeNfc(trimmed)
        }
    }

    fun clearState() {
        inputEngine.reset()
        lastSetComposingText = null
        lastKeyPressTime = 0L
        composingStartInEditor = -1
        composingCursorIndex = 0
        lastExpandedMacro = null
    }

    fun composeAsVietnameseLetterChar(c: Char): Boolean {
        if (c.isDigit()) return false
        if (c.isLetter()) return true
        val type = Character.getType(c)
        return type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
    }

    fun hasRealSelection(ic: InputConnection): Boolean {
        if (isSelecting) return true
        if (userSelectedText) return true
        val selStart = cachedSelStart
        val selEnd = cachedSelEnd
        if (selStart < 0 || selEnd < 0 || selStart == selEnd) return false
        return true
    }

    fun findWordAroundCursor(ic: InputConnection): WordAtCursor? {
        val beforeText = ic.getTextBeforeCursor(48, 0)?.toString() ?: ""
        val afterText = ic.getTextAfterCursor(48, 0)?.toString() ?: ""

        var i = beforeText.length - 1
        while (i >= 0 && composeAsVietnameseLetterChar(beforeText[i])) {
            i--
        }
        val wordBefore = beforeText.substring(i + 1)

        var j = 0
        while (j < afterText.length && composeAsVietnameseLetterChar(afterText[j])) {
            j++
        }
        val wordAfter = afterText.substring(0, j)

        val fullWord = wordBefore + wordAfter
        if (fullWord.isEmpty()) return null

        val base = if (cachedSelStart >= 0) cachedSelStart else 0

        return WordAtCursor(
            text = fullWord,
            startInEditor = (base - wordBefore.length).coerceAtLeast(0),
            endInEditor = base + wordAfter.length,
            cursorOffset = wordBefore.length
        )
    }

    /**
     * Resolves composition when a keypress occurs while not currently composing.
     * Only inspects the preceding word if the key is a tone or vowel modifier.
     */
    private fun resolveCompositionAtCursor(
        ic: InputConnection,
        key: String
    ) {
        composingStartInEditor = if (cachedSelStart >= 0) cachedSelStart else 0
        composingCursorIndex = 0

        val lowerKey = if (key.isNotEmpty()) key[0].lowercaseChar() else ' '
        val isTone = VietnameseComposer.isToneKey(lowerKey)
        val isVowelMod = VietnameseComposer.isVowelModifierKey(lowerKey)

        // If not a modifier, start a fresh new composition cleanly with zero IPC
        if (!isTone && !isVowelMod) {
            inputEngine.reset()
            inputEngine.composeAsVietnamese = true
            userMovedCursor = false
            return
        }

        // Only query the preceding word when the key could potentially modify it
        val wordAtCursor = findWordAroundCursor(ic)
        if (wordAtCursor == null || wordAtCursor.text.isEmpty()) {
            inputEngine.reset()
            inputEngine.composeAsVietnamese = true
            userMovedCursor = false
            return
        }

        val wordCursorOffset = wordAtCursor.cursorOffset
        val wordText = wordAtCursor.text
        val isAtEnd = wordCursorOffset == wordText.length

        val adoptTarget = if (isAtEnd) wordText else wordText.substring(0, wordCursorOffset)
        val adoptResult = if (adoptTarget.isNotEmpty() && EditedVietnameseRecognizer.canRecompose(adoptTarget)) {
            inputEngine.adoptWord(adoptTarget)
        } else null

        if (adoptResult != null && adoptResult.isValid) {
            val canonicalRaw = inputEngine.canonicalRawIfRoundTrips(adoptResult, adoptTarget)
            if (canonicalRaw != null) {
                composingStartInEditor = wordAtCursor.startInEditor
                inputEngine.composeAsVietnamese = true
                inputEngine.setComposingRaw(canonicalRaw)
                composingCursorIndex = canonicalRaw.length
                lastSetComposingText = adoptTarget
                userMovedCursor = false
                return
            }
        }

        inputEngine.reset()
        inputEngine.composeAsVietnamese = true
        userMovedCursor = false
    }

    fun isImmediateCommitMode(): Boolean {
        val editorInfo = service.currentInputEditorInfo ?: return false
        return editorInfo.inputType == android.text.InputType.TYPE_NULL
    }

    private fun isBypassVietnameseComposing(): Boolean {
        return typingMode == TypingMode.LATIN
    }

    private fun composeAsVietnameseComposingKey(key: String): Boolean {
        if (service._languageMode.value == "ENG") return false
        if (isBypassVietnameseComposing()) return false
        if (key.length != 1) return false
        val char = key[0]
        return char in 'a'..'z' || char in 'A'..'Z' || char.lowercaseChar() != char.uppercaseChar()
    }

    /* =========================================================================
     * COMPOSING BUFFER & DIRECT-COMMIT SYNC (NO UNDERLINE, ZERO FLICKER)
     * ========================================================================= */

    fun resetComposingUI(ic: InputConnection, backspaceCountIfImmediate: Int = 0) {
        val lastStr = lastSetComposingText ?: ""
        lastSetComposingText = null
        inputEngine.reset()
        if (isImmediateCommitMode()) {
            if (backspaceCountIfImmediate > 0) {
                backspaceHandler.sendBackspaceEvents(ic, backspaceCountIfImmediate)
            }
        } else if (lastStr.isNotEmpty()) {
            ic.deleteSurroundingText(lastStr.length, 0)
        }
        composingStartInEditor = -1
        composingCursorIndex = 0
    }

    /**
     * Direct-commit sync without underline ("gõ không gạch chân").
     *
     * Ensures zero flicker:
     * - Pure append: commits only the appended characters (zero deletions).
     * - Pure truncation: deletes only the removed characters.
     * - Diacritic / tone mutation: calculates the longest common prefix and deletes/commits
     *   only the differing suffix. Never calls setSelection or triggers cursor jumps.
     */
    fun syncPreeditDirect(ic: InputConnection, display: String) {
        val lastStr = lastSetComposingText ?: ""
        if (display == lastStr) return

        if (lastStr.isEmpty()) {
            if (display.isNotEmpty()) {
                ic.commitText(display, 1)
            }
            return
        }

        // Pure append (e.g. 't' -> 'ti' -> 'tie'): 0 deletions, instantaneous commit!
        if (display.startsWith(lastStr)) {
            ic.commitText(display.substring(lastStr.length), 1)
            return
        }

        // Pure truncation (e.g. backspace): delete tail
        if (lastStr.startsWith(display)) {
            ic.deleteSurroundingText(lastStr.length - display.length, 0)
            return
        }

        // Diacritic / tone replacement (e.g. 'tie' -> 'tiê' or 'tiê' -> 'tiế'):
        // Keep common prefix untouched; only delete and replace the differing tail!
        var common = 0
        val maxCommon = minOf(lastStr.length, display.length)
        while (common < maxCommon && lastStr[common] == display[common]) {
            common++
        }

        val toDelete = lastStr.length - common
        val toInsert = display.substring(common)

        if (toDelete > 0) {
            ic.deleteSurroundingText(toDelete, 0)
        }
        if (toInsert.isNotEmpty()) {
            ic.commitText(toInsert, 1)
        }
    }

    fun updateComposingUI(ic: InputConnection, lastLenIfImmediate: Int = 0, explicitCompiled: String? = null) {
        val compiled = explicitCompiled ?: compileComposingText()
        if (isImmediateCommitMode()) {
            val lastStr = lastSetComposingText ?: ""
            if (lastStr.isNotEmpty() && compiled.length == lastStr.length - 1 && lastStr.startsWith(compiled)) {
                backspaceHandler.sendBackspaceEvents(ic, 1)
            } else if (lastLenIfImmediate > 0) {
                backspaceHandler.sendBackspaceEvents(ic, lastLenIfImmediate)
            }
            ic.commitText(compiled, 1)
        } else {
            syncPreeditDirect(ic, compiled)
            if (composingStartInEditor >= 0 && composingCursorIndex != inputEngine.composingRawLength()) {
                val target = composingStartInEditor + displayCursorIndex()
                ic.setSelection(target, target)
            }
        }
        lastSetComposingText = compiled
    }

    private fun handleBackspace(ic: InputConnection) {
        backspaceHandler.handleBackspace(ic)
    }

    private fun handleDeleteForward(ic: InputConnection) {
        backspaceHandler.handleDeleteForward(ic)
    }

    private fun handleDeleteWord(ic: InputConnection) {
        backspaceHandler.handleDeleteWord(ic)
    }

    private fun handleSeparator(ic: InputConnection, separator: String) {
        if (inputEngine.isComposing()) {
            commitAndReset(wordBreak = separator)
        } else {
            commitAndReset()
            ic.commitText(separator, 1)
        }
        if (separator != " ") {
            recordImeCommit(separator)
        }
        service.notifySentenceStateAfterKey(separator)
        service.evaluateAutoShift(forceIpc = false)
    }

    fun handleKeyPress(key: String) {
        val now = System.currentTimeMillis()
        lastKeyPressTime = now
        val ic: InputConnection? = service.currentInputConnection
        if (ic == null) {
            return
        }

        if (key != "BACKSPACE") {
            lastExpandedMacro = null
        }

        ic.beginBatchEdit()
        try {
            if (key == "SPACE") {
                handleSeparator(ic, " ")
                return
            } else if (key == "ENTER") {
                commitAndReset()
                val editorInfo = service.currentInputEditorInfo
                val inputType = editorInfo?.inputType ?: 0
                val isMultiLine = (inputType and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_TEXT &&
                        ((inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
                         (inputType and android.text.InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0)
                val imeOptions = editorInfo?.imeOptions ?: 0
                val actionMasked = imeOptions and EditorInfo.IME_MASK_ACTION
                val hasNoEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

                if (!isMultiLine && !hasNoEnterAction && actionMasked != EditorInfo.IME_ACTION_NONE && actionMasked != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(actionMasked)
                } else if (!isMultiLine && !hasNoEnterAction && editorInfo?.actionId != 0 && editorInfo?.actionId != null) {
                    ic.performEditorAction(editorInfo.actionId)
                } else {
                    sendKeyEvent(ic, KeyEvent.KEYCODE_ENTER)
                }
                service.notifySentenceStateAfterKey("ENTER")
                service.evaluateAutoShift(forceIpc = false)
                return
            } else if (BoundaryClassifier.isBoundary(key)) {
                handleSeparator(ic, key)
                return
            }

            when (key) {
                "BACKSPACE" -> handleBackspace(ic)
                "DELETE", "FORWARD_DELETE" -> handleDeleteForward(ic)
                "DELETE_WORD" -> handleDeleteWord(ic)
                "PASTE_OTP" -> {
                    val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val primaryClip = clipboard.primaryClip
                    if (primaryClip != null && primaryClip.itemCount > 0) {
                        val text = primaryClip.getItemAt(0).text?.toString() ?: ""
                        val otpRegex = "\\d{4,8}".toRegex()
                        val match = otpRegex.find(text)
                        val otp = match?.value ?: text.filter { it.isDigit() }.take(6)
                        if (otp.isNotEmpty()) {
                            ic.commitText(otp, 1)
                        }
                    }
                }
                "SHIFT" -> {
                    val shiftNow = System.currentTimeMillis()
                    lastShiftTime = service.shiftController.toggleShiftKey(shiftNow, lastShiftTime)
                }
                "SHIFT_LONG" -> {
                    service.shiftController.forceCapsLock()
                }
                else -> {
                    val actualKey = if (service.shiftController.isShifted && key.length == 1 && key[0].isLetter()) {
                        key.uppercase()
                    } else {
                        key
                    }
                    service.notifySentenceStateAfterKey(actualKey)
                    if (!composeAsVietnameseComposingKey(key)) {
                        commitAndReset()
                        ic.commitText(actualKey, 1)
                        service.lastCommittedWord = actualKey
                        service.shiftController.consumeSingleShift()
                        service.evaluateAutoShift(forceIpc = false)
                    } else {
                        if (userMovedCursor && inputEngine.isComposing()) {
                            commitAndReset()
                        }
                        if (!inputEngine.isComposing()) {
                            resolveCompositionAtCursor(ic, actualKey)
                        }
                        val lastLen = lastSetComposingText?.length ?: 0
                        inputEngine.insertComposingKey(composingCursorIndex, actualKey[0])
                        composingCursorIndex += actualKey.length

                        val casedDisplay = if (inputEngine.composeAsVietnamese) {
                            inputEngine.toDisplayString()
                        } else {
                            compileRawDisplay()
                        }

                        updateComposingUI(ic, lastLen, casedDisplay)

                        if (isImmediateCommitMode()) {
                            recordImeCommit(casedDisplay)
                        }
                        service.shiftController.consumeSingleShift()
                    }
                }
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun sendKeyEvent(ic: InputConnection, keyCode: Int, isShifted: Boolean = false) {
        if (isShifted) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
        }
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        if (isShifted) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
        }
    }

    private fun sendMoveKey(ic: InputConnection, keycode: Int) {
        sendKeyEvent(ic, keycode, isSelecting)
    }

    fun handleEditAction(action: String) {
        val ic = service.currentInputConnection ?: return
        when (action) {
            "LEFT" -> sendMoveKey(ic, KeyEvent.KEYCODE_DPAD_LEFT)
            "RIGHT" -> sendMoveKey(ic, KeyEvent.KEYCODE_DPAD_RIGHT)
            "UP" -> sendMoveKey(ic, KeyEvent.KEYCODE_DPAD_UP)
            "DOWN" -> sendMoveKey(ic, KeyEvent.KEYCODE_DPAD_DOWN)
            "HOME" -> sendMoveKey(ic, KeyEvent.KEYCODE_MOVE_HOME)
            "END" -> sendMoveKey(ic, KeyEvent.KEYCODE_MOVE_END)
            "TOGGLE_SELECT" -> {
                isSelecting = !isSelecting
            }
            "SELECT_ALL" -> ic.performContextMenuAction(android.R.id.selectAll)
            "COPY" -> ic.performContextMenuAction(android.R.id.copy)
            "PASTE" -> {
                ic.performContextMenuAction(android.R.id.paste)
                isSelecting = false
            }
            "CUT" -> {
                ic.performContextMenuAction(android.R.id.cut)
                isSelecting = false
            }
            "DELETE" -> {
                val selected = ic.getSelectedText(0)
                if (selected != null && selected.isNotEmpty()) {
                    ic.commitText("", 1)
                } else {
                    if (isImmediateCommitMode()) {
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL))
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_FORWARD_DEL))
                    } else {
                        backspaceHandler.deleteNextGraphemeOrChar(ic)
                    }
                }
            }
        }
    }

    fun compileComposingText(): String = compileRawDisplay()

    /**
     * Derives current display string with casing from raw composition.
     */
    fun compileRawDisplay(): String {
        if (!inputEngine.isComposing()) return ""
        inputEngine.toDisplayBuffer(displayBuf)
        return VietnameseUnicode.applyCasingFromRaw(displayBuf, inputEngine.composingRaw())
    }

    /** Display caret offset (chars) for the current raw caret. */
    fun displayCursorIndex(): Int {
        val raw = inputEngine.composingRaw()
        val end = composingCursorIndex.coerceIn(0, inputEngine.composingRawLength())
        if (end <= 0) return 0
        inputEngine.compileRawInto(raw, inputEngine.composeAsVietnamese, displayBuf, end)
        return displayBuf.len
    }

    /**
     * Maps a display offset back to the raw buffer offset.
     */
    fun rawIndexOfDisplay(
        raw: CharSequence,
        display: String,
        displayOffset: Int,
        vietnamese: Boolean
    ): Int {
        if (displayOffset <= 0) return 0
        if (displayOffset >= display.length) return raw.length
        if (!vietnamese) return displayOffset.coerceAtMost(raw.length)
        for (i in 0..raw.length) {
            inputEngine.compileRawInto(raw, inputEngine.composeAsVietnamese, displayBuf, i)
            if (displayPrefixMatches(displayBuf, display, displayOffset)) return i
        }
        return displayOffset.coerceAtMost(raw.length)
    }

    private fun displayPrefixMatches(buf: OwnedBuffer, display: String, len: Int): Boolean {
        if (buf.len != len) return false
        for (j in 0 until len) {
            if (buf[j] != display[j]) return false
        }
        return true
    }

    fun compileText(raw: String): String {
        if (raw.isEmpty()) return ""
        inputEngine.compileRawInto(raw, vietnamese = true, displayBuf)
        return VietnameseUnicode.applyCasingFromRaw(displayBuf, raw)
    }

    private fun tryExpandMacro(raw: String, wordBreak: String): String? {
        if (!inputEngine.macroEnabled) return null
        val store = inputEngine.macroStore ?: return null
        if (store.isEmpty()) return null

        store.lookup(raw.lowercase())?.let { expansion ->
            return applyMacroCase(expansion, raw) + wordBreak
        }

        val composed = compileText(raw)
        if (composed != raw) {
            store.lookup(composed.lowercase())?.let { expansion ->
                return applyMacroCase(expansion, composed) + wordBreak
            }
        }
        return null
    }

    private fun applyMacroCase(expansion: String, typed: String): String =
        if (typed.isNotEmpty() && typed.all { it.isUpperCase() }) expansion.uppercase() else expansion

    /**
     * Commits the current composing text and resets engine state.
     * In direct-commit mode without underline, the word text is already in the editor.
     * Only wordBreak (such as a space or punctuation) needs to be appended.
     */
    fun commitAndReset(wordBreak: String = "") {
        if (inputEngine.isComposing()) {
            val ic = service.currentInputConnection
            if (ic != null) {
                ic.beginBatchEdit()
                try {
                    val raw = inputEngine.composingRaw().toString()
                    val macroExpanded = tryExpandMacro(raw, wordBreak)
                    val lastStr = lastSetComposingText ?: ""

                    if (isImmediateCommitMode()) {
                        val lastLen = lastStr.length
                        if (lastLen > 0) {
                            backspaceHandler.sendBackspaceEvents(ic, lastLen)
                        }
                        val outputText = macroExpanded ?: (if (!inputEngine.composeAsVietnamese) raw + wordBreak else compileRawDisplay() + wordBreak)
                        ic.commitText(outputText, 1)
                        recordImeCommit(outputText.trim())
                    } else if (macroExpanded != null) {
                        if (lastStr.isNotEmpty()) {
                            ic.deleteSurroundingText(lastStr.length, 0)
                        }
                        ic.commitText(macroExpanded, 1)
                        recordImeCommit(macroExpanded.trim())
                    } else {
                        // Word is already in the editor! Just append separator.
                        if (wordBreak.isNotEmpty()) {
                            ic.commitText(wordBreak, 1)
                        }
                        recordImeCommit(lastStr.trim())
                    }
                    clearState()
                    if (macroExpanded != null) {
                        lastExpandedMacro = MacroExpansionRecord(raw, macroExpanded, System.currentTimeMillis())
                    }
                } finally {
                    ic.endBatchEdit()
                }
            } else {
                clearState()
            }
            service.evaluateAutoShift()
        }
    }

    fun commitAndFinishing(wordBreak: String = "") = commitAndReset(wordBreak)
}
