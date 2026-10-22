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
import io.emx.term.TerminalKeys
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max

/** PTY renderer and input surface; history is owned by [TerminalEmulator]. */
class TerminalView(context: Context) : View(context) {
    companion object {
        private val PALETTE = intArrayOf(
            Color.parseColor("#000000"), Color.parseColor("#cd3131"),
            Color.parseColor("#0dbc79"), Color.parseColor("#e5e510"),
            Color.parseColor("#2472c8"), Color.parseColor("#bc3fbc"),
            Color.parseColor("#11a8cd"), Color.parseColor("#e5e5e5"),
        )
        private const val CURSOR_BLINK_MS = 530L
    }

    var onInput: ((ByteArray) -> Unit)? = null
    var onResize: ((cols: Int, rows: Int) -> Unit)? = null
    lateinit var emulator: TerminalEmulator

    private val paint = Paint().apply { typeface = Typeface.MONOSPACE; isAntiAlias = false }
    private val backgroundPaint = Paint()
    private val pendingInput = ConcurrentLinkedQueue<ByteArray>()
    private val inputLock = Any()
    private var charWidth = 1f
    private var charHeight = 1f
    private var fontAscent = 0f
    private var scrollOffset = 0
    private var cursorVisible = true
    private var touchDownY = 0f
    private var lastTouchY = 0f
    private var dragging = false
    private val touchSlop = 10f * resources.displayMetrics.density
    private val inputMethodManager =
        context.getSystemService(InputMethodManager::class.java)

    private val blink = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            if (scrollOffset == 0 && hasFocus()) invalidate()
            postDelayed(this, CURSOR_BLINK_MS)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun attach(emulator: TerminalEmulator) {
        this.emulator = emulator
        scrollOffset = 0
        recomputeMetrics()
        invalidate()
    }

    fun resetView() {
        scrollOffset = 0
        invalidate()
    }

    fun feed(data: ByteArray, len: Int) {
        if (len <= 0) return
        synchronized(inputLock) { pendingInput.add(data.copyOf(len)) }
        post { drainInput() }
    }

    private fun drainInput() {
        if (!::emulator.isInitialized) return
        synchronized(inputLock) {
            while (true) {
                val bytes = pendingInput.poll() ?: break
                emulator.write(bytes)
            }
        }
        // The emulator owns rendered history. Draining pending lines prevents
        // an unbounded backlog when this view is detached or paused.
        emulator.takePendingScrollback()
        val maxOffset = emulator.scrollbackRowCount().coerceAtLeast(0)
        scrollOffset = scrollOffset.coerceIn(0, maxOffset)
        invalidate()
    }

    private fun recomputeMetrics() {
        val density = resources.displayMetrics
        val textSize = 14f * density.density * resources.configuration.fontScale
        paint.textSize = textSize
        val metrics = paint.fontMetrics
        charHeight = (metrics.descent - metrics.ascent).coerceAtLeast(textSize)
        fontAscent = -metrics.ascent
        charWidth = max(1f, paint.measureText("M"))
        if (!::emulator.isInitialized || width <= 0 || height <= 0) return
        val cols = max(20, width / charWidth.toInt())
        val rows = max(4, height / charHeight.toInt())
        if (cols != emulator.width() || rows != emulator.height()) {
            emulator.resize(cols, rows)
            onResize?.invoke(cols, rows)
            scrollOffset = scrollOffset.coerceIn(0, emulator.scrollbackRowCount())
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) recomputeMetrics()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!::emulator.isInitialized) return
        backgroundPaint.color = PALETTE[TerminalEmulator.DEFAULT_BG]
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val cols = emulator.width()
        val rows = emulator.height()
        val history = emulator.scrollbackRowCount()
        val first = (history - scrollOffset).coerceAtLeast(0)
        for (screenRow in 0 until rows) {
            val line = first + screenRow
            if (line >= history + rows) break
            val y = screenRow * charHeight
            if (line < history) {
                val source = emulator.scrollbackLine(line)
                for (col in 0 until minOf(cols, source.size)) drawCell(canvas, source[col], col * charWidth, y)
            } else {
                val gridRow = line - history
                for (col in 0 until cols) drawCell(canvas, emulator.cell(gridRow, col), col * charWidth, y)
            }
        }
        if (scrollOffset == 0 && cursorVisible && hasFocus()) {
            paint.color = PALETTE[TerminalEmulator.DEFAULT_FG]
            paint.style = Paint.Style.FILL
            canvas.drawRect(emulator.cursorCol * charWidth, emulator.cursorRow * charHeight,
                (emulator.cursorCol + 1) * charWidth, (emulator.cursorRow + 1) * charHeight, paint)
        }
    }

    private fun drawCell(canvas: Canvas, cell: TerminalEmulator.Cell, x: Float, y: Float) {
        val foreground = PALETTE[cell.fg]
        val background = PALETTE[cell.bg]
        if (background != PALETTE[TerminalEmulator.DEFAULT_BG]) {
            backgroundPaint.color = background
            canvas.drawRect(x, y, x + charWidth, y + charHeight, backgroundPaint)
        }
        if (cell.ch != ' ') {
            paint.color = if (cell.bold && foreground == PALETTE[TerminalEmulator.DEFAULT_FG]) Color.WHITE else foreground
            paint.style = Paint.Style.FILL
            canvas.drawText(cell.ch.toString(), x, y + fontAscent, paint)
        }
    }

    fun toggleKeyboard() {
        val manager = inputMethodManager
        if (manager == null) return
        if (manager.isActive(this)) manager.hideSoftInputFromWindow(windowToken, 0)
        else showKeyboard()
    }

    private fun showKeyboard() {
        requestFocus()
        inputMethodManager?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = 0
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, cursor: Int): Boolean {
                sendToChild(TerminalKeys.text(text.toString())); return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val key = TerminalKeys.key(event)
                if (key != null) sendToChild(key)
                return key != null || super.sendKeyEvent(event)
            }
            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                sendToChild(byteArrayOf(8)); return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val bytes = TerminalKeys.key(event) ?: return super.onKeyDown(keyCode, event)
        sendToChild(bytes)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && (event.isCtrlPressed || event.isAltPressed)) {
            val bytes = TerminalKeys.key(event)
            if (bytes != null) { sendToChild(bytes); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun sendToChild(data: ByteArray) {
        if (data.isEmpty()) return
        onInput?.invoke(data)
        scrollOffset = 0
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!::emulator.isInitialized) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownY = event.y; lastTouchY = event.y; dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = lastTouchY - event.y
                if (abs(event.y - touchDownY) > touchSlop) dragging = true
                if (abs(delta) >= charHeight) {
                    val lines = (delta / charHeight).toInt()
                    if (lines != 0) {
                        val maxOffset = emulator.scrollbackRowCount()
                        scrollOffset = (scrollOffset + lines).coerceIn(0, maxOffset)
                        lastTouchY += lines * charHeight
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                    showKeyboard()
                }
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                val maxOffset = emulator.scrollbackRowCount()
                scrollOffset = (scrollOffset - event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 3)
                    .toInt().coerceIn(0, maxOffset)
                invalidate(); return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(blink)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        super.onDetachedFromWindow()
    }
}
