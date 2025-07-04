package com.myrat.app.utils

import android.util.Log

object Logger {
    private const val TAG = "SmsApp"

    fun log(message: String) {
        Log.d(TAG, message)
    }

    fun error(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }
}