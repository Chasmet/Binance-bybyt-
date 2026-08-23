package com.chk.binancebybit

import android.graphics.Color
import android.widget.TextView

/**
 * Compatibility overload used by the programmatic v0.4 UI.
 * Inside TextView.apply blocks, the receiver's `text` property can shadow
 * the activity's foreground color field. This overload preserves the intended
 * foreground color while still passing through integer colors unchanged.
 */
fun TextView.setTextColor(value: Any?) {
    val resolved = if (value is Int) value else Color.rgb(246, 247, 249)
    this.setTextColor(resolved)
}
