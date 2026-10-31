package com.terapp

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

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var session: TerminalSession? = null
        set(value) {
            field = value
            field?.onScreenChanged = { postInvalidate() }
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
        val newCols = (width / charWidth).toInt().coerceAtLeast(10)
        val newRows = (height / charHeight).toInt().coerceAtLeast(5)

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

        // 1. Fill background
        bgPaint.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val s = session ?: return

        // 2. Draw text lines with scrollOffset
        for (row in 0 until rows) {
            val lineText = s.getLineText(row, scrollOffset)
            if (lineText.isNotEmpty()) {
                val y = row * charHeight + charAscent
                val spans = TerminalColorizer.colorize(lineText, theme.foreground, s.workingDir)
                for (span in spans) {
                    spanPaint.color = span.color
                    spanPaint.isFakeBoldText = span.isBold
                    spanPaint.isUnderlineText = span.isUnderline
                    canvas.drawText(lineText, span.start, span.end, span.start * charWidth, y, spanPaint)
                }
            }
        }

        // 3. Draw blinking cursor (only visible when viewing active prompt at bottom)
        if (cursorVisible && scrollOffset == 0) {
            val cx = s.getCursorX()
            val cy = s.getCursorY()

            if (cx in 0 until cols && cy in 0 until rows) {
                cursorPaint.color = theme.cursor
                val left = cx * charWidth
                val top = cy * charHeight
                canvas.drawRect(left, top, left + charWidth, top + charHeight, cursorPaint)

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

        // 4. Draw scroll indicator bar when scrolled back
        val maxOffset = s.getScrollbackLength()
        if (maxOffset > 0 && scrollOffset > 0) {
            val barWidth = 4f * resources.displayMetrics.density
            val totalContentLines = rows + maxOffset
            val thumbHeight = (rows.toFloat() / totalContentLines * height).coerceAtLeast(32f * resources.displayMetrics.density)
            val thumbTop = ((maxOffset - scrollOffset).toFloat() / maxOffset) * (height - thumbHeight)
            val right = width.toFloat() - 2f * resources.displayMetrics.density
            val left = right - barWidth
            scrollBarPaint.color = 0xAA00E5FF.toInt() // semi-transparent cyan
            canvas.drawRoundRect(left, thumbTop, right, thumbTop + thumbHeight, barWidth / 2f, barWidth / 2f, scrollBarPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    // --- Virtual Keyboard (IME) Integration ---

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyDown(keyCode, null)
        scrollToBottom()
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                session?.writeBytes(byteArrayOf(0x08))
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                session?.writeBytes(byteArrayOf(0x0A))
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
                    session?.writeBytes(byteArrayOf(0x08)) // Backspace
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event?.action == KeyEvent.ACTION_DOWN) {
                    scrollToBottom()
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> {
                            session?.writeBytes(byteArrayOf(0x08))
                            return true
                        }
                        KeyEvent.KEYCODE_ENTER -> {
                            session?.writeBytes(byteArrayOf(0x0A)) // Enter \n
                            return true
                        }
                        KeyEvent.KEYCODE_TAB -> {
                            session?.writeBytes(byteArrayOf(0x09)) // Tab \t
                            return true
                        }
                        KeyEvent.KEYCODE_ESCAPE -> {
                            session?.writeBytes(byteArrayOf(0x1B)) // Esc
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
                    }

                    val unicode = event.unicodeChar
                    if (unicode != 0) {
                        sendChar(unicode.toChar())
                        return true
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    fun sendChar(c: Char) {
        scrollToBottom()
        val (ctrl, alt) = extraKeysView?.consumeModifiers() ?: Pair(false, false)

        if (ctrl) {
            // Apply Ctrl modifier (e.g. 'c' -> 0x03)
            val upper = c.uppercaseChar()
            if (upper in '@'..'_') {
                val ctrlByte = (upper.code - '@'.code).toByte()
                session?.writeBytes(byteArrayOf(ctrlByte))
                return
            }
        }

        if (alt) {
            // Alt prepends ESC byte \x1b
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
            "↑" -> session?.writeBytes("\u001b[A".toByteArray())
            "↓" -> session?.writeBytes("\u001b[B".toByteArray())
            "←" -> session?.writeBytes("\u001b[D".toByteArray())
            "→" -> session?.writeBytes("\u001b[C".toByteArray())
            else -> session?.writeInput(key)
        }
    }
}

