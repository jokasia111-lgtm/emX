package io.emx.term

import android.view.KeyEvent

/** Maps terminal input to the exact UTF-8 bytes expected by a PTY. */
object TerminalKeys {
    fun text(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    fun key(event: KeyEvent): ByteArray? {
        if (event.action != KeyEvent.ACTION_DOWN) return null
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val code = event.keyCode
        fun sequence(body: String): ByteArray = text("\u001b") + (if (alt) byteArrayOf(0x1b) else ByteArray(0)) + body.toByteArray()

        if (ctrl) {
            val letter = event.unicodeChar.toChar().lowercaseChar()
            if (letter in 'a'..'z') return byteArrayOf((letter.code - 'a'.code + 1).toByte())
            return when (code) {
                KeyEvent.KEYCODE_SPACE -> byteArrayOf(0)
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL -> byteArrayOf(8)
                KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1b)
                else -> null
            }
        }
        if (alt && event.unicodeChar != 0) return byteArrayOf(0x1b) + text(event.unicodeChar.toChar().toString())

        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> sequence("[A")
            KeyEvent.KEYCODE_DPAD_DOWN -> sequence("[B")
            KeyEvent.KEYCODE_DPAD_RIGHT -> sequence("[C")
            KeyEvent.KEYCODE_DPAD_LEFT -> sequence("[D")
            KeyEvent.KEYCODE_MOVE_HOME -> sequence("[H")
            KeyEvent.KEYCODE_MOVE_END -> sequence("[F")
            KeyEvent.KEYCODE_PAGE_UP -> sequence("[5~")
            KeyEvent.KEYCODE_PAGE_DOWN -> sequence("[6~")
            KeyEvent.KEYCODE_TAB -> byteArrayOf('\t'.code.toByte())
            KeyEvent.KEYCODE_ENTER -> byteArrayOf('\r'.code.toByte())
            KeyEvent.KEYCODE_DEL -> byteArrayOf(8)
            KeyEvent.KEYCODE_FORWARD_DEL -> sequence("[3~")
            KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1b)
            else -> if (event.unicodeChar != 0) text(event.unicodeChar.toChar().toString()) else null
        }
    }
}
