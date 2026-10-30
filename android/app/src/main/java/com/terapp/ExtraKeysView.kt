package com.terapp

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton

class ExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(12, 6, 12, 6)
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
        addView(container)
        setupKeys()
    }

    private fun setupKeys() {
        val keys = listOf(
            "ESC", "TAB", "CTRL", "ALT", "|", "/", "-", "_", "~", "↑", "↓", "←", "→"
        )

        for (key in keys) {
            val btn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = key
                textSize = 12f
                isAllCaps = false
                insetTop = 0
                insetBottom = 0
                cornerRadius = 16
                minWidth = 0
                minimumWidth = 0
                setPadding(28, 16, 28, 16)
                setTextColor(context.getColorStateList(R.color.bg_extra_key_text))
                setBackgroundResource(R.drawable.bg_extra_key)

                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(6, 4, 6, 4)
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
                // Auto-reset single modifiers after use unless double-locked
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

