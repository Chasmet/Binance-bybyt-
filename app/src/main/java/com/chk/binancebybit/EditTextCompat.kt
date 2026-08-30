package com.chk.binancebybit

import android.widget.EditText

/** Compatibility property used by the programmatic Bot CHK UI. */
var EditText.singleLine: Boolean
    get() = isSingleLine
    set(value) {
        setSingleLine(value)
    }
