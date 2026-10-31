package com.terapp

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton

class ExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padH = (6 * density).toInt()
        val padV = (4 * density).toInt()
        setPadding(padH, padV, padH, padV)
    }

    var isCtrlActive: Boolean = false
        private set
    var isAltActive: Boolean = false
        private set

    private var ctrlButton: MaterialButton? = null
    private var altButton: MaterialButton? = null

    var onKeyAction: ((actionKey: String) -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(container)
        setupKeys()
    }

    private fun setupKeys() {
        val keys = listOf(
            "ESC", "TAB", "CTRL", "ALT", "|", "/", "-", "_", "~", ":", "↑", "↓", "←", "→"
        )

        val btnHeight = (38 * density).toInt()
        val padH = (12 * density).toInt()
        val marginH = (3 * density).toInt()
        val marginV = (4 * density).toInt()
        val cornerRadiusPx = (8 * density).toInt()

        for (key in keys) {
            val btn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = key
                textSize = 12f
                typeface = Typeface.MONOSPACE
                isAllCaps = false
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                cornerRadius = cornerRadiusPx
                setPadding(padH, 0, padH, 0)
                setTextColor(context.getColorStateList(R.color.bg_extra_key_text))
                setBackgroundResource(R.drawable.bg_extra_key)

                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    btnHeight
                ).apply {
                    setMargins(marginH, marginV, marginH, marginV)
                }
                layoutParams = params

                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    handleKeyPress(key, this)
                }
            }

            if (key == "CTRL") ctrlButton = btn
            if (key == "ALT") altButton = btn

            container.addView(btn)
        }
    }

    private fun handleKeyPress(key: String, button: MaterialButton) {
        when (key) {
            "CTRL" -> {
                isCtrlActive = !isCtrlActive
                button.isActivated = isCtrlActive
            }
            "ALT" -> {
                isAltActive = !isAltActive
                button.isActivated = isAltActive
            }
            else -> {
                onKeyAction?.invoke(key)
                // Auto-reset single modifiers after use unless explicitly preserved
                if (isCtrlActive) {
                    isCtrlActive = false
                    ctrlButton?.isActivated = false
                }
                if (isAltActive) {
                    isAltActive = false
                    altButton?.isActivated = false
                }
            }
        }
    }

    fun consumeModifiers(): Pair<Boolean, Boolean> {
        val ctrl = isCtrlActive
        val alt = isAltActive
        if (ctrl) {
            isCtrlActive = false
            ctrlButton?.isActivated = false
        }
        if (alt) {
            isAltActive = false
            altButton?.isActivated = false
        }
        return Pair(ctrl, alt)
    }
}
