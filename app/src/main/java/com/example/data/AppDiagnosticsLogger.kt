package com.example.data

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppDiagnosticsLogger {
    private const val MAX_ENTRIES = 200
    private val entries = CopyOnWriteArrayList<String>()
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        info("DIAG", "Logger inicializado (${context.packageName})")
    }

    fun info(tag: String, message: String) {
        append("I", tag, message)
        runCatching { Log.i(tag, message) }
    }

    fun warn(tag: String, message: String) {
        append("W", tag, message)
        runCatching { Log.w(tag, message) }
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) "$message: ${throwable.message}" else message
        append("E", tag, full)
        runCatching { Log.e(tag, message, throwable) }
    }

    fun recentLogs(): List<String> = entries.toList()

    private fun append(level: String, tag: String, message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        entries.add("$stamp [$level/$tag] $message")
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }
}
