package io.emx.term

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TerminalEmulatorHistoryTest {
    @Test fun historyAndPendingQueueBothRemainBounded() {
        val term = TerminalEmulator(cols = 1, rows = 1, scrollbackLimit = 2)
        repeat(10) { term.write("\n") }
        assertEquals(2, term.scrollbackRowCount())
        assertEquals(2, term.takePendingScrollback().size)
    }

    @Test fun scrollingAdvancesTotalHistoryCounter() {
        val term = TerminalEmulator(cols = 1, rows = 1)
        repeat(3) { term.write("x\n") }
        assertEquals(3L, term.scrolledLines)
        assertEquals(3, term.scrollbackRowCount())
    }

    @Test fun terminalKeysEncodesTextAsUtf8() {
        assertContentEquals("héllo".toByteArray(Charsets.UTF_8), TerminalKeys.text("héllo"))
    }
}
