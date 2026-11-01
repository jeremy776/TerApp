package com.terapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var session: TerminalSession? = null
        set(value) {
            field = value
            field?.onScreenChanged = { postInvalidate() }
            if (cols > 0 && rows > 0) {
                field?.resize(cols, rows)
            }
            requestLayout()
            postInvalidate()
        }

    var extraKeysView: ExtraKeysView? = null

    // Font and Layout sizing
    var fontSizeSp: Float = 13f
        set(value) {
            field = value.coerceIn(8f, 32f)
            updateFontMetrics()
            calculateGrid()
            postInvalidate()
        }

    private val padH: Float
        get() = 8f * resources.displayMetrics.density
    private val padV: Float
        get() = 4f * resources.displayMetrics.density

    private fun spToPx(sp: Float): Float {
        return android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            sp,
            resources.displayMetrics
        )
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val spanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }

    private val bgPaint = Paint()
    private val cursorPaint = Paint()

    private var charWidth: Float = 0f
    private var charHeight: Float = 0f
    private var charAscent: Float = 0f

    var cols: Int = 80
        private set
    var rows: Int = 24
        private set

    // Scrolling support
    var scrollOffset: Int = 0
        private set
    private var scrollAccumulator = 0f
    private val scroller by lazy { android.widget.Scroller(context) }
    private val scrollBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Cursor blink
    private var cursorVisible = true
    private val blinkHandler = Handler(Looper.getMainLooper())
    private val blinkRunnable = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            invalidate()
            blinkHandler.postDelayed(this, 500)
        }
    }

    // Gestures
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        isFocusable = true
        isFocusableInTouchMode = true

        updateFontMetrics()

        val displayMetrics = resources.displayMetrics
        if (charWidth > 0f && charHeight > 0f) {
            val availW = (displayMetrics.widthPixels - 2f * padH).coerceAtLeast(0f)
            val availH = (displayMetrics.heightPixels * 0.6f - 2f * padV).coerceAtLeast(0f)
            cols = (availW / charWidth).toInt().coerceIn(24, 120)
            rows = (availH / charHeight).toInt().coerceIn(10, 60)
        }

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                fontSizeSp *= detector.scaleFactor
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (scrollOffset > 0) {
                    scrollToBottom()
                }
                requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(this@TerminalView, InputMethodManager.SHOW_IMPLICIT)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                showContextMenuDialog()
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (scaleDetector.isInProgress) return false
                val s = session ?: return false
                val maxOffset = s.getScrollbackLength()
                if (maxOffset > 0 && charHeight > 0f) {
                    scrollAccumulator += distanceY
                    val deltaLines = (-scrollAccumulator / charHeight).toInt()
                    if (deltaLines != 0) {
                        scrollOffset = (scrollOffset + deltaLines).coerceIn(0, maxOffset)
                        scrollAccumulator += deltaLines * charHeight
                        postInvalidate()
                    }
                    return true
                }
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (scaleDetector.isInProgress) return false
                val s = session ?: return false
                val maxOffset = s.getScrollbackLength()
                if (maxOffset > 0 && charHeight > 0f) {
                    val velocityLines = (-velocityY / (charHeight * 6f)).toInt()
                    scroller.fling(0, scrollOffset, 0, velocityLines, 0, 0, 0, maxOffset)
                    postInvalidateOnAnimation()
                    return true
                }
                return false
            }
        })

        blinkHandler.postDelayed(blinkRunnable, 500)
    }

    private fun showContextMenuDialog() {
        val options = arrayOf(
            "Select All & Copy",
            "Copy Visible Text",
            "Paste Clipboard",
            "Send Ctrl+C (Interrupt)",
            "Send Reset",
            "Scroll to Bottom"
        )

        AlertDialog.Builder(context)
            .setTitle("Terminal Options")
            .setItems(options) { _, which ->
                val s = session ?: return@setItems
                val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                when (which) {
                    0 -> { // Select All & Copy
                        val text = s.getVisibleText()
                        if (text.isNotEmpty() && clipMgr != null) {
                            clipMgr.setPrimaryClip(ClipData.newPlainText("TerApp Terminal", text))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> { // Copy Visible
                        val text = s.getVisibleText()
                        if (text.isNotEmpty() && clipMgr != null) {
                            clipMgr.setPrimaryClip(ClipData.newPlainText("TerApp Terminal", text))
                            Toast.makeText(context, "Visible text copied", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> { // Paste
                        val clip = clipMgr?.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val pasteText = clip.getItemAt(0).coerceToText(context).toString()
                            if (pasteText.isNotEmpty()) {
                                s.writeInput(pasteText)
                            }
                        }
                    }
                    3 -> { // Ctrl+C
                        s.writeBytes(byteArrayOf(0x03))
                    }
                    4 -> { // Reset
                        s.writeBytes("\u001bc".toByteArray())
                    }
                    5 -> { // Scroll to bottom
                        scrollToBottom()
                    }
                }
            }
            .show()
    }

    private fun updateFontMetrics() {
        textPaint.textSize = spToPx(fontSizeSp)
        spanPaint.textSize = textPaint.textSize
        val fontMetrics = textPaint.fontMetrics
        charHeight = fontMetrics.bottom - fontMetrics.top
        charAscent = -fontMetrics.top
        charWidth = textPaint.measureText("X")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateGrid()
    }

    private fun calculateGrid() {
        if (charWidth <= 0 || charHeight <= 0 || width <= 0 || height <= 0) return
        val availW = (width - 2f * padH).coerceAtLeast(0f)
        val availH = (height - 2f * padV).coerceAtLeast(0f)
        val newCols = (availW / charWidth).toInt().coerceAtLeast(10)
        val newRows = (availH / charHeight).toInt().coerceAtLeast(5)

        if (newCols != cols || newRows != rows) {
            cols = newCols
            rows = newRows
            session?.resize(cols, rows)
        }
    }

    override fun computeScroll() {
        super.computeScroll()
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currY
            postInvalidateOnAnimation()
        }
    }

    fun scrollToBottom() {
        if (scrollOffset != 0) {
            scrollOffset = 0
            scroller.forceFinished(true)
            postInvalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val theme = ThemeManager.currentTheme
        val pH = padH
        val pV = padV

        // 1. Draw terminal background
        bgPaint.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val s = session ?: return

        // 2. Draw text lines with native VT100 parsed colors & attributes
        for (row in 0 until rows) {
            val lineText = s.getLineText(row, scrollOffset)
            if (lineText.isNotEmpty()) {
                val y = pV + row * charHeight + charAscent
                val displayLine = if (lineText.length > cols) lineText.substring(0, cols) else lineText

                val colors = s.getLineColors(
                    row,
                    scrollOffset,
                    theme.foreground,
                    theme.background,
                    theme.ansiColors
                )

                if (colors != null && colors.size >= displayLine.length * 2) {
                    var col = 0
                    while (col < displayLine.length) {
                        val fg = colors[col * 2]
                        val flags = (colors[col * 2 + 1] ushr 24) and 0xFF
                        val isBold = (flags and 1) != 0
                        val isUnderline = (flags and (1 shl 3)) != 0

                        spanPaint.color = fg
                        spanPaint.isFakeBoldText = isBold
                        spanPaint.isUnderlineText = isUnderline

                        val startCol = col
                        while (col < displayLine.length &&
                            colors[col * 2] == fg &&
                            ((colors[col * 2 + 1] ushr 24) and 0xFF) == flags
                        ) {
                            col++
                        }

                        val startX = pH + startCol * charWidth
                        if (startX < width - pH) {
                            canvas.drawText(displayLine, startCol, col, startX, y, spanPaint)
                        }
                    }
                } else {
                    // Fallback to TerminalColorizer
                    val spans = TerminalColorizer.colorize(displayLine, theme.foreground, s.workingDir)
                    for (span in spans) {
                        spanPaint.color = span.color
                        spanPaint.isFakeBoldText = span.isBold
                        spanPaint.isUnderlineText = span.isUnderline
                        val startX = pH + span.start * charWidth
                        if (startX < width - pH) {
                            canvas.drawText(displayLine, span.start, span.end, startX, y, spanPaint)
                        }
                    }
                }
            }
        }

        // 3. Draw blinking cursor
        if (cursorVisible && s.isCursorVisible() && scrollOffset == 0) {
            val cx = s.getCursorX().coerceIn(0, (cols - 1).coerceAtLeast(0))
            val cy = s.getCursorY().coerceIn(0, (rows - 1).coerceAtLeast(0))

            val left = pH + cx * charWidth
            val top = pV + cy * charHeight
            if (left + charWidth <= width - pH + 2f) {
                cursorPaint.color = theme.cursor
                val cursorRadius = 2f * resources.displayMetrics.density
                canvas.drawRoundRect(left, top, left + charWidth, top + charHeight, cursorRadius, cursorRadius, cursorPaint)

                // Invert char under cursor
                val lineText = s.getLineText(cy, 0)
                if (cx < lineText.length) {
                    val ch = lineText[cx].toString()
                    textPaint.color = theme.background
                    canvas.drawText(ch, left, top + charAscent, textPaint)
                    textPaint.color = theme.foreground
                }
            }
        }

        // 4. Draw modern scroll indicator bar when viewing scrollback history
        val maxOffset = s.getScrollbackLength()
        if (maxOffset > 0 && scrollOffset > 0) {
            val barWidth = 3f * resources.displayMetrics.density
            val totalContentLines = rows + maxOffset
            val availHeight = (height - 2f * pV).coerceAtLeast(1f)
            val thumbHeight = (rows.toFloat() / totalContentLines * availHeight).coerceAtLeast(24f * resources.displayMetrics.density)
            val thumbTop = pV + (((maxOffset - scrollOffset).toFloat() / maxOffset) * (availHeight - thumbHeight))
            val right = width.toFloat() - 3f * resources.displayMetrics.density
            val left = right - barWidth
            scrollBarPaint.color = 0x8838BDF8.toInt()
            canvas.drawRoundRect(left, thumbTop, right, thumbTop + thumbHeight, barWidth / 2f, barWidth / 2f, scrollBarPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    // --- Virtual Keyboard (IME) & Key Handling ---

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyDown(keyCode, null)
        scrollToBottom()

        // Handle Ctrl modifier on physical keyboards
        if (event.isCtrlPressed) {
            val unicode = event.unicodeChar
            if (unicode in 'a'.code..'z'.code || unicode in 'A'.code..'Z'.code) {
                val upper = unicode.toChar().uppercaseChar()
                val ctrlByte = (upper.code - '@'.code).toByte()
                session?.writeBytes(byteArrayOf(ctrlByte))
                return true
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                session?.writeBytes(byteArrayOf(0x7F)) // Standard terminal backspace
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                session?.writeBytes("\u001b[3~".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                session?.writeBytes(byteArrayOf(0x0D)) // CR (\r) for real PTY terminals
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                session?.writeBytes(byteArrayOf(0x09))
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                session?.writeBytes(byteArrayOf(0x1B))
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                session?.writeBytes("\u001b[A".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                session?.writeBytes("\u001b[B".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                session?.writeBytes("\u001b[D".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                session?.writeBytes("\u001b[C".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_MOVE_HOME -> {
                session?.writeBytes("\u001b[H".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                session?.writeBytes("\u001b[F".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                session?.writeBytes("\u001b[5~".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                session?.writeBytes("\u001b[6~".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_INSERT -> {
                session?.writeBytes("\u001b[2~".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_F1 -> { session?.writeBytes("\u001bOP".toByteArray()); return true }
            KeyEvent.KEYCODE_F2 -> { session?.writeBytes("\u001bOQ".toByteArray()); return true }
            KeyEvent.KEYCODE_F3 -> { session?.writeBytes("\u001bOR".toByteArray()); return true }
            KeyEvent.KEYCODE_F4 -> { session?.writeBytes("\u001bOS".toByteArray()); return true }
            KeyEvent.KEYCODE_F5 -> { session?.writeBytes("\u001b[15~".toByteArray()); return true }
            KeyEvent.KEYCODE_F6 -> { session?.writeBytes("\u001b[17~".toByteArray()); return true }
            KeyEvent.KEYCODE_F7 -> { session?.writeBytes("\u001b[18~".toByteArray()); return true }
            KeyEvent.KEYCODE_F8 -> { session?.writeBytes("\u001b[19~".toByteArray()); return true }
            KeyEvent.KEYCODE_F9 -> { session?.writeBytes("\u001b[20~".toByteArray()); return true }
            KeyEvent.KEYCODE_F10 -> { session?.writeBytes("\u001b[21~".toByteArray()); return true }
            KeyEvent.KEYCODE_F11 -> { session?.writeBytes("\u001b[23~".toByteArray()); return true }
            KeyEvent.KEYCODE_F12 -> { session?.writeBytes("\u001b[24~".toByteArray()); return true }
        }

        val unicode = event.unicodeChar
        if (unicode != 0) {
            sendChar(unicode.toChar())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE

        return object : android.view.inputmethod.BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    for (c in text) {
                        sendChar(c)
                    }
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                scrollToBottom()
                if (beforeLength > 0) {
                    session?.writeBytes(byteArrayOf(0x7F))
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event?.action == KeyEvent.ACTION_DOWN) {
                    return this@TerminalView.onKeyDown(event.keyCode, event)
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    fun sendChar(c: Char) {
        scrollToBottom()
        val (ctrl, alt) = extraKeysView?.consumeModifiers() ?: Pair(false, false)

        if (ctrl) {
            val upper = c.uppercaseChar()
            if (upper in '@'..'_') {
                val ctrlByte = (upper.code - '@'.code).toByte()
                session?.writeBytes(byteArrayOf(ctrlByte))
                return
            }
        }

        if (alt) {
            val bytes = byteArrayOf(0x1B, c.code.toByte())
            session?.writeBytes(bytes)
            return
        }

        session?.writeInput(c.toString())
    }

    fun sendSpecialKey(key: String) {
        scrollToBottom()
        when (key) {
            "ESC" -> session?.writeBytes(byteArrayOf(0x1B))
            "TAB" -> session?.writeBytes(byteArrayOf(0x09))
            "HOME" -> session?.writeBytes("\u001b[H".toByteArray())
            "END" -> session?.writeBytes("\u001b[F".toByteArray())
            "PGUP" -> session?.writeBytes("\u001b[5~".toByteArray())
            "PGDN" -> session?.writeBytes("\u001b[6~".toByteArray())
            "INS" -> session?.writeBytes("\u001b[2~".toByteArray())
            "DEL" -> session?.writeBytes("\u001b[3~".toByteArray())
            "↑" -> session?.writeBytes("\u001b[A".toByteArray())
            "↓" -> session?.writeBytes("\u001b[B".toByteArray())
            "←" -> session?.writeBytes("\u001b[D".toByteArray())
            "→" -> session?.writeBytes("\u001b[C".toByteArray())
            else -> session?.writeInput(key)
        }
    }
}
