package com.sharethis.app.ui.components

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.sharethis.app.R

/**
 * 6-box PIN keypad. Auto-advances on input, steps back on delete,
 * exposes the raw 6-digit string via [pin].
 */
class PinEntryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        const val PIN_LENGTH = 6
    }

    private val boxes = ArrayList<EditText>(PIN_LENGTH)
    private var listener: ((String) -> Unit)? = null
    private var suppressWatcher = false

    /** Current PIN digits (may be shorter than 6 while typing). */
    val pin: String
        get() = boxes.joinToString("") { it.text.toString() }

    val isComplete: Boolean get() = pin.length == PIN_LENGTH

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val density = resources.displayMetrics.density
        val boxSize = (52f * density).toInt()
        val margin = (6f * density).toInt()

        for (index in 0 until PIN_LENGTH) {
            val box = EditText(context).apply {
                layoutParams = LayoutParams(boxSize, boxSize).apply {
                    setMargins(margin, margin, margin, margin)
                }
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(1))
                gravity = Gravity.CENTER
                textSize = 22f
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundResource(R.drawable.bg_pin_box)
                setTextColor(resolveOnSurfaceColor(context))
            }
            val position = index
            box.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (suppressWatcher) return
                    if (!s.isNullOrEmpty()) {
                        if (position < PIN_LENGTH - 1) boxes[position + 1].requestFocus()
                    }
                    listener?.invoke(pin)
                }
            })
            box.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (box.text.isEmpty() && position > 0) {
                        boxes[position - 1].requestFocus()
                        boxes[position - 1].setText("")
                    }
                }
                false
            }
            box.onFocusChangeListener = OnFocusChangeListener { v, hasFocus ->
                if (hasFocus) (v as EditText).setSelection(v.text.length)
            }
            boxes.add(box)
            addView(box)
        }
    }

    fun setOnPinChangedListener(listener: (String) -> Unit) {
        this.listener = listener
    }

    fun clear() {
        suppressWatcher = true
        boxes.forEach { it.setText("") }
        suppressWatcher = false
        boxes.firstOrNull()?.requestFocus()
        listener?.invoke("")
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        boxes.forEach { it.isEnabled = enabled }
        alpha = if (enabled) 1f else 0.5f
    }

    fun focusFirst() {
        boxes.firstOrNull()?.requestFocus()
    }

    private fun resolveOnSurfaceColor(context: Context): Int {
        val typedValue = android.util.TypedValue()
        return if (context.theme.resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, typedValue, true
            )
        ) {
            if (typedValue.resourceId != 0) {
                // ContextCompat — Context.getColor() is API 23+.
                ContextCompat.getColor(context, typedValue.resourceId)
            } else {
                typedValue.data
            }
        } else {
            0xFF111111.toInt()
        }
    }
}
