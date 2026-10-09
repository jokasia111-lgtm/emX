package io.emx.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalEmulatorTest {

    private fun newTerm(cols: Int = 20, rows: Int = 5): TerminalEmulator =
        TerminalEmulator(cols = cols, rows = rows)

    @Test
    fun plainTextLandsAtCursor() {
        val t = newTerm()
        t.write("hi")
        assertEquals(2, t.cursorCol)
        assertEquals("hi", t.rowText(0).trimEnd())
    }

    @Test
    fun backspaceMovesCursorBack() {
        val t = newTerm()
        t.write("abc\b")
        assertEquals(2, t.cursorCol)
        // The cell at the cursor is not cleared by BS in this simple emulator
        // (matches VT100 behavior where BS only moves the cursor).
        assertEquals('c', t.cell(0, 2).ch)
    }

    @Test
    fun carriageReturnAndNewlineMoveCursor() {
        val t = newTerm()
        t.write("abc\r\nxy")
        assertEquals("xy", t.rowText(1).trimEnd())
        assertEquals(1, t.cursorRow) // cursor stays on the row where "xy" was written
        assertEquals(2, t.cursorCol)
    }

    @Test
    fun wrapContinuesOnNextLine() {
        val t = newTerm(cols = 4)
        t.write("abcdef")
        assertEquals("abcd", t.rowText(0))
        assertEquals("ef", t.rowText(1).trimEnd())
        assertEquals(1, t.cursorRow) // "ef" was written on row 1; cursor stays there
        assertEquals(2, t.cursorCol)
    }

    @Test
    fun lineFeedScrollsAndPushesScrollback() {
        val t = newTerm(rows = 2)
        t.write("one\r\ntwo\r\nthree")
        // "one" scrolled off into scrollback.
        assertEquals(1, t.scrollbackRowCount())
        assertEquals("three", t.rowText(1).trimEnd())
    }

    @Test
    fun cursorUpMovesWithinScreen() {
        val t = newTerm()
        t.write("abc\r\ndef")
        t.write("\u001B[1A") // CUU
        assertEquals(0, t.cursorRow)
        assertEquals(3, t.cursorCol)
    }

    @Test
    fun cursorPositionAbsolute() {
        val t = newTerm()
        t.write("\u001B[3;4H") // CUP row=3 col=4
        assertEquals(2, t.cursorRow)
        assertEquals(3, t.cursorCol)
    }

    @Test
    fun eraseLineAndScreen() {
        val t = newTerm()
        t.write("hello world\r\u001B[K") // CR + EL 0
        // All cells on row 0 must be blank after CR + EL 0.
        for (c in 0 until t.width()) assertEquals(' ', t.cell(0, c).ch)
        // Cursor should be back at column 0.
        assertEquals(0, t.cursorCol)
    }

    @Test
    fun sgrColorsApplyToCells() {
        val t = newTerm()
        t.write("\u001B[31mR") // red fg
        assertEquals(1, t.cell(0, 0).fg)
        t.write("\u001B[0mN") // reset
        assertEquals(TerminalEmulator.DEFAULT_FG, t.cell(0, 1).fg)
    }

    @Test
    fun boldAttributeToggles() {
        val t = newTerm()
        t.write("\u001B[1mB\u001B[0mN")
        assertTrue(t.cell(0, 0).bold)
        assertEquals(false, t.cell(0, 1).bold)
    }

    @Test
    fun saveRestoreCursor() {
        val t = newTerm()
        t.write("abc")
        t.write("\u001B7")      // DECSC
        t.write("\r\nxyz")
        t.write("\u001B8")      // DECRC
        assertEquals(0, t.cursorRow)
        assertEquals(3, t.cursorCol)
    }

    @Test
    fun oscSequencesAreConsumed() {
        val t = newTerm()
        t.write("\u001B]0;window title\u0007after")
        assertEquals("after", t.rowText(0).trimEnd())
    }

    @Test
    fun tabAdvancesToNextMultipleOfEight() {
        val t = newTerm(cols = 32)
        t.write("\t")
        assertEquals(8, t.cursorCol)
        t.write("\t")
        assertEquals(16, t.cursorCol)
    }

    @Test
    fun resizeKeepsLatestLines() {
        val t = newTerm(cols = 10, rows = 4)
        t.write("a\r\nb\r\nc\r\nd\r\ne") // 'a' scrolled into scrollback
        t.resize(10, 2)
        assertEquals(2, t.height())
        // Bottom two rows should be "d" and "e".
        assertEquals("d", t.rowText(0).trimEnd())
        assertEquals("e", t.rowText(1).trimEnd())
    }

    @Test
    fun pendingScrollbackIsDeliveredOnce() {
        val t = newTerm(rows = 2)
        t.write("one\r\ntwo\r\nthree")
        val first = t.takePendingScrollback()
        assertEquals(1, first.size)
        assertEquals("one", first[0].joinToString("") { it.ch.toString() }.trimEnd())
        // Second poll must be empty (consumed).
        assertTrue(t.takePendingScrollback().isEmpty())
    }

    @Test
    fun belAndControlCharsAreIgnored() {
        val t = newTerm()
        t.write("a\u0007b")
        assertEquals("ab", t.rowText(0).trimEnd())
    }

    @Test
    fun reverseVideoSwapsFgBg() {
        val t = newTerm()
        t.write("\u001B[7mX")
        // After reverse, default fg(7) and bg(0) are swapped: fg=0 bg=7.
        assertEquals(0, t.cell(0, 0).fg)
        assertEquals(7, t.cell(0, 0).bg)
    }
}
