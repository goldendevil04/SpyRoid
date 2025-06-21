package com.myrat.app.utils

import android.util.Log

object Logger {
    private const val TAG = "MyRatApp"
    private const val MAX_LOG_LENGTH = 4000

    fun log(message: String) {
        try {
            if (message.length > MAX_LOG_LENGTH) {
                val chunks = message.chunked(MAX_LOG_LENGTH)
                chunks.forEachIndexed { index, chunk ->
                    Log.d(TAG, "[$index/${chunks.size}] $chunk")
                }
            } else {
                Log.d(TAG, message)
            }
        } catch (e: Exception) {
            try {
                Log.e(TAG, "Logger error: ${e.message}")
            } catch (ignored: Exception) {
                // Silent fail to prevent crash loops
            }
        }
    }

    fun error(message: String, throwable: Throwable? = null) {
        try {
            if (message.length > MAX_LOG_LENGTH) {
                val chunks = message.chunked(MAX_LOG_LENGTH)
                chunks.forEachIndexed { index, chunk ->
                    if (index == 0 && throwable != null) {
                        Log.e(TAG, "[$index/${chunks.size}] $chunk", throwable)
                    } else {
                        Log.e(TAG, "[$index/${chunks.size}] $chunk")
                    }
                }
            } else {
                Log.e(TAG, message, throwable)
            }
        } catch (e: Exception) {
            try {
                Log.e(TAG, "Logger error in error(): ${e.message}")
            } catch (ignored: Exception) {
                // Silent fail to prevent crash loops
            }
        }
    }

    fun warn(message: String, throwable: Throwable? = null) {
        try {
            if (message.length > MAX_LOG_LENGTH) {
                val chunks = message.chunked(MAX_LOG_LENGTH)
                chunks.forEachIndexed { index, chunk ->
                    if (index == 0 && throwable != null) {
                        Log.w(TAG, "[$index/${chunks.size}] $chunk", throwable)
                    } else {
                        Log.w(TAG, "[$index/${chunks.size}] $chunk")
                    }
                }
            } else {
                Log.w(TAG, message, throwable)
            }
        } catch (e: Exception) {
            try {
                Log.e(TAG, "Logger error in warn(): ${e.message}")
            } catch (ignored: Exception) {
                // Silent fail to prevent crash loops
            }
        }
    }

    fun info(message: String) {
        try {
            if (message.length > MAX_LOG_LENGTH) {
                val chunks = message.chunked(MAX_LOG_LENGTH)
                chunks.forEachIndexed { index, chunk ->
                    Log.i(TAG, "[$index/${chunks.size}] $chunk")
                }
            } else {
                Log.i(TAG, message)
            }
        } catch (e: Exception) {
            try {
                Log.e(TAG, "Logger error in info(): ${e.message}")
            } catch (ignored: Exception) {
                // Silent fail to prevent crash loops
            }
        }
    }
}