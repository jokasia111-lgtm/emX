package io.emx.term

/**
 * Minimal VT100/ANSI terminal emulator.
 *
 * Pure Kotlin, no Android imports, so it is fully unit-testable.
 *
 * Supported: printable ASCII, CR, LF, BS, BEL, TAB, ESC 7/8 (DECSC/DECRC),
 * OSC (consumed and ignored), CSI sequences for cursor movement (CUU/CUD/
 * CUF/CUB/CUP/CHA/VPA), erase (ED/EL), scroll regions are NOT supported,
 * and SGR attributes (bold, reverse, 8/16/256/truecolor mapped down to 8).
 *
 * Screen is a [rows] x [cols] grid of [Cell]. Lines that scroll off the top
 * are pushed into a bounded scrollback buffer. Printing past the right edge
 * wraps to the next line (VT100 wrap). Line wrap is implicit: the grid is
 * the primary display and any long line consumes multiple grid rows.
 */
class TerminalEmulator(
    val cols: Int,
    val rows: Int,
    private val scrollbackLimit: Int = 5000
) {
    /** One character cell with its attributes. */
    data class Cell(val ch: Char = ' ', val fg: Int = DEFAULT_FG, val bg: Int = DEFAULT_BG, val bold: Boolean = false)

    companion object {
        const val DEFAULT_FG = 7
        const val DEFAULT_BG = 0
        // SGR 30-37 and 40-47 map to the internal 0-7 color palette.
        private val COLOR_MAP = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
    }

    init {
        require(cols > 0 && rows > 0) { "Terminal dimensions must be positive" }
        require(scrollbackLimit >= 0) { "Scrollback limit must not be negative" }
    }

    private var curCols = cols
    private var curRows = rows
    private var screen: Array<Array<Cell>> = Array(rows) { Array(cols) { Cell() } }
    private val scrollback = ArrayDeque<List<Cell>>()
    /** Kept bounded even when no renderer polls it. */
    private val pendingScrollback = ArrayDeque<List<Cell>>()
    var scrolledLines: Long = 0
        private set
    var historyGeneration: Long = 0
        private set

    /** History is owned here, not duplicated in the view. Cells/rows are immutable. */
    fun scrollbackLine(index: Int): List<Cell> = scrollback[index]
    fun scrollbackRowCount(): Int = scrollback.size

    private fun rememberLine(line: List<Cell>) {
        if (scrollback.size >= scrollbackLimit) scrollback.removeFirst()
        scrollback.addLast(line)
        if (pendingScrollback.size >= scrollbackLimit) pendingScrollback.removeFirst()
        pendingScrollback.addLast(line)
        scrolledLines++
    }

    var cursorRow: Int
        get() = cursorRowValue
        private set(value) { cursorRowValue = value; wrapPending = false }
    private var cursorRowValue = 0
    var cursorCol: Int = 0
        private set
    /** VT100 autowrap delay: the next graphic char, not this one, starts the next line. */
    private var wrapPending = false
    private var savedRow = 0
    private var savedCol = 0
    private var savedFg = DEFAULT_FG
    private var savedBg = DEFAULT_BG
    private var savedBold = false

    private var fg = DEFAULT_FG
    private var bg = DEFAULT_BG
    private var bold = false

    /** Set true by write() after any mutation; renderer clears it after a redraw. */
    @Volatile
    var dirty: Boolean = false
        private set

    private enum class ParseState { GROUND, ESC, CSI, OSC }
    private var state = ParseState.GROUND
    private val csiParams = StringBuilder()

    // ------------------------------------------------------------------ API

    /** Append process bytes (this small emulator currently renders printable ASCII). */
    fun write(data: ByteArray, len: Int = data.size) {
        require(len in 0..data.size) { "Invalid byte count" }
        for (i in 0 until len) {
            val b = data[i].toInt() and 0xFF
            writeChar(b.toChar())
        }
    }

    /** Append a string (e.g. for test input). */
    fun write(text: String) {
        for (ch in text) writeChar(ch)
    }

    fun cell(row: Int, col: Int): Cell = screen[row][col]

    /** Grid width/height in columns/rows (may change via resize). */
    fun width(): Int = curCols
    fun height(): Int = curRows

    /** Consume pending scrollback lines pushed since the last call. */
    fun takePendingScrollback(): List<List<Cell>> {
        val out = ArrayList<List<Cell>>(pendingScrollback.size)
        while (pendingScrollback.isNotEmpty()) out.add(pendingScrollback.removeFirst())
        return out
    }

    fun clear() {
        for (r in 0 until curRows) for (c in 0 until curCols) screen[r][c] = Cell()
        scrollback.clear()
        pendingScrollback.clear()
        historyGeneration++
        cursorRow = 0
        cursorCol = 0
        dirty = true
    }

    /** Reset all attributes and content. */
    fun reset() {
        fg = DEFAULT_FG; bg = DEFAULT_BG; bold = false
        savedRow = 0; savedCol = 0
        savedFg = DEFAULT_FG; savedBg = DEFAULT_BG; savedBold = false
        clear()
        state = ParseState.GROUND
        csiParams.setLength(0)
    }

    /** No text reflow: shrinking keeps the latest rows, moving discarded rows to history. */
    fun resize(newCols: Int, newRows: Int) {
        require(newCols > 0 && newRows > 0) { "Terminal dimensions must be positive" }
        if (newCols == curCols && newRows == curRows) return
        val droppedRows = (curRows - newRows).coerceAtLeast(0)
        for (r in 0 until droppedRows) rememberLine(screen[r].toList())
        val newScreen = Array(newRows) { Array(newCols) { Cell() } }
        for (r in 0 until minOf(curRows, newRows)) {
            for (c in 0 until minOf(curCols, newCols)) newScreen[r][c] = screen[r + droppedRows][c]
        }
        curCols = newCols
        curRows = newRows
        screen = newScreen
        cursorRow = (cursorRow - droppedRows).coerceIn(0, newRows - 1)
        cursorCol = cursorCol.coerceAtMost(newCols - 1)
        savedRow = (savedRow - droppedRows).coerceIn(0, newRows - 1)
        savedCol = savedCol.coerceAtMost(newCols - 1)
        dirty = true
    }

    /** Row text for testing/debugging. */
    fun rowText(r: Int): String = buildString {
        for (c in 0 until curCols) append(screen[r][c].ch)
    }

    // ------------------------------------------------------------------ parser

    private fun writeChar(ch: Char) {
        when (state) {
            ParseState.GROUND -> when (ch.code) {
                0x07 -> {} // BEL
                0x08 -> { if (cursorCol > 0) cursorCol--; dirty = true } // BS
                0x09 -> { // TAB: next multiple of 8
                    val next = ((cursorCol / 8) + 1) * 8
                    cursorCol = if (next >= curCols) curCols - 1 else next
                    dirty = true
                }
                0x0A -> { if (cursorRow == curRows - 1) scrollUp() else cursorRow++; dirty = true } // LF
                0x0D -> { cursorCol = 0; dirty = true } // CR
                0x1B -> state = ParseState.ESC
                in 0x20..0x7E -> printChar(ch)
                else -> {} // other control bytes ignored
            }
            ParseState.ESC -> when (ch) {
                '[' -> { state = ParseState.CSI; csiParams.setLength(0) }
                '7' -> { // DECSC
                    savedRow = cursorRow; savedCol = cursorCol
                    savedFg = fg; savedBg = bg; savedBold = bold
                    state = ParseState.GROUND
                }
                '8' -> { // DECRC
                    cursorRow = savedRow; cursorCol = savedCol
                    fg = savedFg; bg = savedBg; bold = savedBold
                    state = ParseState.GROUND
                    dirty = true
                }
                ']' -> state = ParseState.OSC
                'M' -> { // RI: scroll down
                    if (cursorRow == 0) scrollDown() else cursorRow--
                    state = ParseState.GROUND
                    dirty = true
                }
                else -> state = ParseState.GROUND
            }
            ParseState.CSI -> when (ch) {
                in '0'..'9', ';', '?' -> if (csiParams.length < 256) csiParams.append(ch)
                else -> {
                    handleCsi(ch)
                    state = ParseState.GROUND
                }
            }
            ParseState.OSC -> when (ch.code) {
                0x07 -> state = ParseState.GROUND
                0x1B -> state = ParseState.ESC
                else -> {} // Titles are ignored, not accumulated without a bound.
            }
        }
    }

    private fun handleCsi(final: Char) {
        val params = csiParams.toString()
        val privateMode = params.startsWith("?")
        val body = params.removePrefix("?")
        val parts = body.split(';')
        fun p(i: Int, default: Int): Int {
            val v = parts.getOrNull(i)?.toIntOrNull() ?: return default
            return if (v == 0) default else v
        }
        when (final) {
            'A' -> { cursorRow = (cursorRow - p(0, 1)).coerceAtLeast(0); dirty = true }
            'B' -> { cursorRow = (cursorRow + p(0, 1)).coerceAtMost(curRows - 1); dirty = true }
            'C' -> { cursorCol = (cursorCol + p(0, 1)).coerceAtMost(curCols - 1); dirty = true }
            'D' -> { cursorCol = (cursorCol - p(0, 1)).coerceAtLeast(0); dirty = true }
            'H', 'f' -> {
                cursorRow = (p(0, 1) - 1).coerceIn(0, curRows - 1)
                cursorCol = (p(1, 1) - 1).coerceIn(0, curCols - 1)
                dirty = true
            }
            'G' -> { cursorCol = (p(0, 1) - 1).coerceIn(0, curCols - 1); dirty = true }
            'd' -> { cursorRow = (p(0, 1) - 1).coerceIn(0, curRows - 1); dirty = true }
            'J' -> when (p(0, 0)) {
                0 -> clearCursorToScreenEnd()
                1 -> clearScreenStartToCursor()
                2 -> clearScreenKeepCursor()
            }
            'K' -> when (p(0, 0)) {
                0 -> clearCursorToLineEnd()
                1 -> clearLineStartToCursor()
                2 -> clearCurrentLine()
            }
            'm' -> if (!privateMode) handleSgr(parts)
        }
    }

    private fun handleSgr(parts: List<String>) {
        if (parts.isEmpty() || (parts.size == 1 && parts[0].isEmpty())) {
            fg = DEFAULT_FG; bg = DEFAULT_BG; bold = false
            dirty = true
            return
        }
        var i = 0
        while (i < parts.size) {
            val v = parts[i].toIntOrNull() ?: 0
            when (v) {
                0 -> { fg = DEFAULT_FG; bg = DEFAULT_BG; bold = false }
                1 -> bold = true
                7 -> { val t = fg; fg = bg; bg = t } // reverse
                22 -> bold = false
                27 -> { val t = fg; fg = bg; bg = t }
                in 30..37 -> fg = COLOR_MAP[v - 30]
                38 -> {
                    val kind = parts.getOrNull(i + 1)?.toIntOrNull()
                    if (kind == 5) { // 38;5;n
                        val n = parts.getOrNull(i + 2)?.toIntOrNull() ?: 0
                        fg = map256(n)
                        i += 2
                    } else if (kind == 2) { // 38;2;r;g;b
                        val r = parts.getOrNull(i + 2)?.toIntOrNull() ?: 0
                        val g = parts.getOrNull(i + 3)?.toIntOrNull() ?: 0
                        val b = parts.getOrNull(i + 4)?.toIntOrNull() ?: 0
                        fg = rgbToAnsi(r, g, b)
                        i += 4
                    }
                }
                39 -> fg = DEFAULT_FG
                in 40..47 -> bg = COLOR_MAP[v - 40]
                48 -> {
                    val kind = parts.getOrNull(i + 1)?.toIntOrNull()
                    if (kind == 5) {
                        val n = parts.getOrNull(i + 2)?.toIntOrNull() ?: 0
                        bg = map256(n)
                        i += 2
                    } else if (kind == 2) {
                        val r = parts.getOrNull(i + 2)?.toIntOrNull() ?: 0
                        val g = parts.getOrNull(i + 3)?.toIntOrNull() ?: 0
                        val b = parts.getOrNull(i + 4)?.toIntOrNull() ?: 0
                        bg = rgbToAnsi(r, g, b)
                        i += 4
                    }
                }
                49 -> bg = DEFAULT_BG
                in 90..97 -> fg = COLOR_MAP[v - 90]
                in 100..107 -> bg = COLOR_MAP[v - 100]
            }
            i++
        }
        dirty = true
    }

    /** Map a 256-color index to our 8-color palette (rough approximation). */
    private fun map256(n: Int): Int = when (n) {
        in 0..15 -> COLOR_MAP[n % 8]
        in 232..255 -> 7
        else -> {
            val c = n - 16
            val r = c / 36
            val g = (c / 6) % 6
            val b = c % 6
            rgbToAnsi(r * 51, g * 51, b * 51)
        }
    }

    /** Map RGB to the nearest of the 8 basic colors (0=black ... 7=white). */
    private fun rgbToAnsi(r: Int, g: Int, b: Int): Int = when {
        r > 128 && g > 128 && b > 128 -> 7
        r > 128 && g > 128 -> 3
        g > 128 && b > 128 -> 6
        r > 128 && b > 128 -> 5
        r > 128 -> 1
        g > 128 -> 2
        b > 128 -> 4
        else -> 0
    }

    // ------------------------------------------------------------------ writes

    private fun printChar(ch: Char) {
        if (wrapPending) {
            cursorCol = 0
            if (cursorRow == curRows - 1) scrollUp() else cursorRow++
        }
        screen[cursorRow][cursorCol] = Cell(ch, fg, bg, bold)
        cursorCol++
        if (cursorCol >= curCols) {
            cursorCol = curCols - 1
            wrapPending = true
        }
        dirty = true
    }

    private fun scrollUp() {
        wrapPending = false
        val row = ArrayList<Cell>(curCols)
        for (c in 0 until curCols) row.add(screen[0][c])
        rememberLine(row)
        for (r in 1 until curRows) {
            for (c in 0 until curCols) screen[r - 1][c] = screen[r][c]
        }
        for (c in 0 until curCols) screen[curRows - 1][c] = Cell()
    }

    private fun scrollDown() {
        wrapPending = false
        for (r in curRows - 1 downTo 1) {
            for (c in 0 until curCols) screen[r][c] = screen[r - 1][c]
        }
        for (c in 0 until curCols) screen[0][c] = Cell()
    }

    private fun clearCursorToScreenEnd() {
        for (c in cursorCol until curCols) screen[cursorRow][c] = Cell()
        for (r in cursorRow + 1 until curRows) for (c in 0 until curCols) screen[r][c] = Cell()
        dirty = true
    }

    private fun clearScreenStartToCursor() {
        for (r in 0 until cursorRow) for (c in 0 until curCols) screen[r][c] = Cell()
        for (c in 0..cursorCol.coerceAtMost(curCols - 1)) screen[cursorRow][c] = Cell()
        dirty = true
    }

    private fun clearScreenKeepCursor() {
        for (r in 0 until curRows) for (c in 0 until curCols) screen[r][c] = Cell()
        dirty = true
    }

    private fun clearCursorToLineEnd() {
        for (c in cursorCol until curCols) screen[cursorRow][c] = Cell()
        dirty = true
    }

    private fun clearLineStartToCursor() {
        for (c in 0..cursorCol.coerceAtMost(curCols - 1)) screen[cursorRow][c] = Cell()
        dirty = true
    }

    private fun clearCurrentLine() {
        for (c in 0 until curCols) screen[cursorRow][c] = Cell()
        dirty = true
    }
}
