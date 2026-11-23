package com.example.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small persistent diagnostic log. Kotlin and the llama.cpp JNI logger append
 * to the same private file, which survives process restarts and can be exported.
 */
object AppDiagnosticsLogger {
    private const val TAG = "LocalAI"
    private const val LOG_DIRECTORY = "diagnostics"
    private const val LOG_FILE_NAME = "localai.log"

    private val writeLock = Any()

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var currentLogFile: File? = null

    @Volatile
    private var uncaughtHandlerInstalled = false

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        applicationContext = appContext
        val file = getLogFile(appContext)
        currentLogFile = file
        if (!uncaughtHandlerInstalled) {
            synchronized(this) {
                if (!uncaughtHandlerInstalled) {
                    val previous = Thread.getDefaultUncaughtExceptionHandler()
                    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                        error("CRASH", "Exceção não tratada na thread ${thread.name}", throwable)
                        previous?.uncaughtException(thread, throwable)
                            ?: run {
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                    }
                    uncaughtHandlerInstalled = true
                }
            }
        }
        info("DIAGNOSTICS", "Logger inicializado. arquivo=${file.absolutePath}")
        info(
            "DEVICE",
            "Fabricante=${Build.MANUFACTURER}; modelo=${Build.MODEL}; Android=${Build.VERSION.RELEASE}; SDK=${Build.VERSION.SDK_INT}"
        )
    }

    fun logFilePath(context: Context): String = getLogFile(context.applicationContext).absolutePath

    fun info(tag: String, message: String) = write("INFO", tag, message, null)

    fun warn(tag: String, message: String) = write("WARN", tag, message, null)

    fun error(tag: String, message: String, throwable: Throwable? = null) =
        write("ERROR", tag, message, throwable)

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val file = currentLogFile ?: applicationContext?.let(::getLogFile) ?: return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$time [$level] [${Thread.currentThread().name}] [$tag] $message"
        synchronized(writeLock) {
            runCatching {
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.appendLine(line)
                    throwable?.let {
                        writer.appendLine(Log.getStackTraceString(it))
                    }
                }
            }
        }
        when (level) {
            "ERROR" -> Log.e(TAG, "[$tag] $message", throwable)
            "WARN" -> Log.w(TAG, "[$tag] $message", throwable)
            else -> Log.i(TAG, "[$tag] $message")
        }
    }

    suspend fun exportToDownloads(context: Context): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val source = getLogFile(appContext)
        info("DIAGNOSTICS", "Preparando exportação do arquivo de diagnóstico (${source.length()} bytes).")
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val displayName = "LocalAI-diagnostics-$timestamp.txt"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/LocalAI"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = appContext.contentResolver
            val destination: Uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IllegalStateException("O Android não permitiu criar o arquivo na pasta Downloads.")

            try {
                val output = resolver.openOutputStream(destination, "w")
                    ?: throw IllegalStateException("Não foi possível abrir o arquivo de destino em Downloads.")
                output.use { stream ->
                    source.inputStream().use { input -> input.copyTo(stream) }
                    stream.flush()
                }
                val completed = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(destination, completed, null, null)
            } catch (failure: Throwable) {
                runCatching { resolver.delete(destination, null, null) }
                throw failure
            }
        } else {
            @Suppress("DEPRECATION")
            val downloadsRoot = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Conceda a permissão de armazenamento para exportar logs em Android 9 ou anterior.")
            }
            val folder = File(downloadsRoot, "LocalAI")
            if (!folder.exists() && !folder.mkdirs()) {
                throw IllegalStateException("Não foi possível criar Downloads/LocalAI.")
            }
            val destination = File(folder, displayName)
            FileOutputStream(destination).use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
                output.fd.sync()
            }
        }

        info("DIAGNOSTICS", "Arquivo exportado para Downloads/LocalAI/$displayName.")
        displayName
    }

    private fun getLogFile(context: Context): File {
        currentLogFile?.let { return it }
        val directory = File(context.filesDir, LOG_DIRECTORY)
        if (!directory.exists()) directory.mkdirs()
        return File(directory, LOG_FILE_NAME).also { currentLogFile = it }
    }
}
