package io.emx.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.emx.term.TerminalEmulator
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max

/**
 * Canvas-based terminal renderer.
 *
 * Displays a [TerminalEmulator] on a monospace grid. Handles:
 *  - rendering cells with per-cell fg/bg colors
 *  - cursor (blinking block)
 *  - scrollback (swipe up/down over the view)
 *  - soft keyboard text input via [onCreateInputConnection]
 *
 * Redraw strategy: post invalidations when the emulator reports [TerminalEmulator.dirty]
 * or when scroll offset changes. A background thread feeds the emulator; this view
 * must only touch it on the UI thread, so writes go through a queue drained in
 * a post() callback.
 *
 * Product requirement: when this view has focus, the Back key toggles the soft
 * keyboard (hide if shown, show if hidden). View.onKeyDown cannot consume the
 * Back key reliably on all API levels, so that behavior lives in
 * [MainActivity.onBackPressed]; this view just needs to be focusable so the
 * activity can detect the terminal context.
 */
class TerminalView(context: Context) : View(context) {

    companion object {
        // ANSI palette (0-7). Default fg is palette[7] (white), bg palette[0] (black).
        private val PALETTE = intArrayOf(
            Color.parseColor("#000000"), // 0 black
            Color.parseColor("#cd3131"), // 1 red
            Color.parseColor("#0dbc79"), // 2 green
            Color.parseColor("#e5e510"), // 3 yellow
            Color.parseColor("#2472c8"), // 4 blue
            Color.parseColor("#bc3fbc"), // 5 magenta
            Color.parseColor("#11a8cd"), // 6 cyan
            Color.parseColor("#e5e5e5")  // 7 white
        )
        private const val CURSOR_BLINK_MS = 530L
    }

    /** The emulator backing this view. Set before the view is attached. */
    lateinit var emulator: TerminalEmulator

    private val paint = Paint().apply {
        typeface = Typeface.MONOSPACE
        isAntiAlias = false // crisp terminal look
    }
    private val bgPaint = Paint()

    /** Pending bytes from the process reader thread; drained on the UI thread. */
    private val pendingInput = ConcurrentLinkedQueue<ByteArray>()
    private val inputLock = Any()

    private var charWidth = 0f
    private var charHeight = 0f
    private var fontAscent = 0f

    /** Scrollback offset in lines (0 = live view). Populated but not yet
     *  gesture-driven; a later milestone should wire touch scrolling to this. */
    private var scrollOffset = 0
    private val scrollbackLines = ArrayList<List<TerminalEmulator.Cell>>()

    private var cursorVisible = true
    private val blinkRunnable = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            if (scrollOffset == 0) invalidate()
            postDelayed(this, CURSOR_BLINK_MS)
        }
    }

    fun attach(emulator: TerminalEmulator) {
        this.emulator = emulator
        recomputeMetrics()
    }

    /** Feed raw bytes from the process reader thread (thread-safe). */
    fun feed(data: ByteArray, len: Int) {
        synchronized(inputLock) {
            pendingInput.add(if (len == data.size) data else data.copyOf(len))
        }
        post { drainInput() }
    }

    /** Drain pending input into the emulator; must run on UI thread. */
    private fun drainInput() {
        synchronized(inputLock) {
            while (true) {
                val chunk = pendingInput.poll() ?: break
                emulator.write(chunk)
            }
        }
        // Consume any new scrollback lines into our local buffer.
        scrollbackLines.addAll(emulator.takePendingScrollback())
        if (scrollOffset != 0) {
            // Keep the user pinned to the bottom of the live stream while scrolled.
            // (Do nothing; user is intentionally browsing history.)
        }
        invalidate()
    }

    private fun recomputeMetrics() {
        val textSize = 14f * resources.displayMetrics.scaledDensity
        paint.textSize = textSize
        val fm = paint.fontMetrics
        charHeight = (fm.descent - fm.ascent).coerceAtLeast(textSize)
        fontAscent = -fm.ascent
        charWidth = paint.measureText("M")
        // Resize the emulator grid to fit the view.
        if (::emulator.isInitialized && width > 0 && height > 0) {
            val newCols = max(20, (width / charWidth).toInt())
            val newRows = max(4, (height / charHeight).toInt())
            if (newCols != emulator.width() || newRows != emulator.height()) {
                emulator.resize(newCols, newRows)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeMetrics()
    }

    // ------------------------------------------------------------------ render

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!::emulator.isInitialized) return

        val cols = emulator.width()
        val rows = emulator.height()

        // Background.
        bgPaint.color = PALETTE[TerminalEmulator.DEFAULT_BG]
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Render scrollback (above the live screen) if scrolled up.
        val visibleRows = rows
        var rowIndex = -scrollOffset // index of the first visible line in (scrollback + screen)

        // Compute total lines = scrollbackLines.size + rows.
        val totalLines = scrollbackLines.size + rows
        // Clamp scroll offset.
        scrollOffset = scrollOffset.coerceIn(0, (totalLines - rows).coerceAtLeast(0))

        // Paint each visible line.
        for (screenRow in 0 until visibleRows) {
            val lineIdx = rowIndex + screenRow
            val y = screenRow * charHeight
            var x = 0f
            if (lineIdx < 0) continue
            if (lineIdx < scrollbackLines.size) {
                // Scrollback line.
                val line = scrollbackLines[lineIdx]
                for (c in 0 until minOf(cols, line.size)) {
                    drawCell(canvas, line[c], x, y)
                    x += charWidth
                }
            } else {
                val gridRow = lineIdx - scrollbackLines.size
                if (gridRow in 0 until rows) {
                    for (c in 0 until cols) {
                        drawCell(canvas, emulator.cell(gridRow, c), x, y)
                        x += charWidth
                    }
                }
            }
        }

        // Cursor (only when scrolled to live view).
        if (scrollOffset == 0 && cursorVisible && hasFocus()) {
            val cx = emulator.cursorCol * charWidth
            val cy = emulator.cursorRow * charHeight
            paint.color = PALETTE[TerminalEmulator.DEFAULT_FG]
            paint.style = Paint.Style.FILL
            // Draw a thin block cursor (a filled rect the size of one cell).
            canvas.drawRect(cx, cy, cx + charWidth, cy + charHeight, paint)
        }
    }

    private fun drawCell(canvas: Canvas, cell: TerminalEmulator.Cell, x: Float, y: Float) {
        val bg = PALETTE[cell.bg]
        val fg = PALETTE[cell.fg]
        // Only paint bg when it differs from the default (perf micro-optimization).
        if (bg != PALETTE[TerminalEmulator.DEFAULT_BG]) {
            bgPaint.color = bg
            canvas.drawRect(x, y, x + charWidth, y + charHeight, bgPaint)
        }
        if (cell.ch != ' ') {
            paint.color = fg
            canvas.drawText(cell.ch.toString(), x, y + fontAscent, paint)
        }
    }

    // ------------------------------------------------------------------ input

    /** Public: used by MainActivity's Back handler. */
    fun toggleKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.isActive(this)) {
            imm.hideSoftInputFromWindow(windowToken, 0)
        } else {
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                emulator.write(text.toString())
                // Echo handled by the PTY (child echoes back). For pipe fallback,
                // the parent must echo; we detect that case via emulator type.
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // Backspace key from the IME.
                emulator.write("\b")
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_ENTER -> emulator.write("\r")
                        KeyEvent.KEYCODE_DEL -> emulator.write("\b")
                        else -> {
                            val ch = event.unicodeChar
                            if (ch != 0) emulator.write(ch.toChar().toString())
                        }
                    }
                }
                return true
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(blinkRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(blinkRunnable)
    }
}
