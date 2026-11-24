package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.ModelDownloadProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the app's private \`data\` storage directory (\`context.filesDir/models/\`)
 * for downloaded and imported GGUF models, streaming downloads with real-time progress,
 * speed, remaining bytes, and ETA.
 */
class LocalModelStorageManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _downloads = MutableStateFlow<Map<String, ModelDownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, ModelDownloadProgress>> = _downloads.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    fun isModelStored(fileName: String): Boolean {
        val file = File(modelsDir, fileName)
        return file.exists() && file.length() > 0L
    }

    fun getLocalFilePath(fileName: String): String? {
        val file = File(modelsDir, fileName)
        return if (file.exists() && file.length() > 0L) file.absolutePath else null
    }

    fun listCustomStoredFiles(knownFileNames: Set<String>): List<File> {
        return modelsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) && !knownFileNames.contains(it.name) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun deleteStoredModel(fileName: String): Boolean {
        val part = File(modelsDir, "$fileName.part")
        if (part.exists()) part.delete()
        val file = File(modelsDir, fileName)
        return if (file.exists()) file.delete() else true
    }

    fun startDownload(
        model: GgufModelSpec,
        onCompleted: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val url = model.downloadUrl
        if (url.isNullOrBlank()) {
            onError("Link de download indisponível.")
            return
        }
        if (activeJobs.containsKey(model.id)) return

        val job = scope.launch(Dispatchers.IO) {
            val partFile = File(modelsDir, "\${model.fileName}.part")
            val finalFile = File(modelsDir, model.fileName)
            var connection: HttpURLConnection? = null
            try {
                val estimatedTotal = model.fileSizeMb.toLong() * 1024L * 1024L
                updateProgress(
                    ModelDownloadProgress(
                        modelId = model.id,
                        downloadedBytes = 0L,
                        totalBytes = estimatedTotal,
                        speedBytesPerSec = 0L,
                        etaSeconds = 0L
                    )
                )

                connection = openConnectionWithRedirects(url)
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw IllegalStateException("Servidor retornou HTTP $responseCode")
                }

                val contentLength = connection.contentLengthLong
                val totalBytes = if (contentLength > 0L) contentLength else estimatedTotal

                if (partFile.exists()) partFile.delete()

                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                val startMs = System.currentTimeMillis()
                var lastEmitMs = 0L

                connection.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= 160L) {
                                lastEmitMs = now
                                val elapsedSec = ((now - startMs).coerceAtLeast(1L)) / 1000.0
                                val speed = (downloaded / elapsedSec).toLong().coerceAtLeast(1L)
                                val remaining = (totalBytes - downloaded).coerceAtLeast(0L)
                                val eta = if (totalBytes > downloaded) remaining / speed else 0L

                                updateProgress(
                                    ModelDownloadProgress(
                                        modelId = model.id,
                                        downloadedBytes = downloaded,
                                        totalBytes = totalBytes,
                                        speedBytesPerSec = speed,
                                        etaSeconds = eta
                                    )
                                )
                            }
                        }
                        output.flush()
                    }
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }

                clearProgress(model.id)
                withContext(Dispatchers.Main) {
                    onCompleted(finalFile)
                }
            } catch (ce: CancellationException) {
                if (partFile.exists()) partFile.delete()
                clearProgress(model.id)
                throw ce
            } catch (t: Throwable) {
                if (partFile.exists()) partFile.delete()
                clearProgress(model.id)
                withContext(Dispatchers.Main) {
                    onError(t.message ?: "Falha ao baixar o modelo.")
                }
            } finally {
                connection?.disconnect()
                activeJobs.remove(model.id)
            }
        }
        activeJobs[model.id] = job
    }

    fun cancelDownload(modelId: String) {
        activeJobs.remove(modelId)?.cancel()
        clearProgress(modelId)
    }

    suspend fun importFromSafUri(uri: Uri): File? = withContext(Dispatchers.IO) {
        var displayName = "modelo_importado_\${System.currentTimeMillis()}.gguf"
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) {
                    displayName = cursor.getString(idx) ?: displayName
                }
            }
        }
        if (!displayName.endsWith(".gguf", ignoreCase = true)) {
            displayName = "$displayName.gguf"
        }

        val sanitizedName = displayName.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
        val destFile = File(modelsDir, sanitizedName)
        val partFile = File(modelsDir, "$sanitizedName.part")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: return@withContext null

            if (destFile.exists()) destFile.delete()
            if (!partFile.renameTo(destFile)) {
                partFile.copyTo(destFile, overwrite = true)
                partFile.delete()
            }
            destFile
        } catch (_: Throwable) {
            if (partFile.exists()) partFile.delete()
            null
        }
    }

    private fun openConnectionWithRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        repeat(8) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "AyrOff-Android/1.0")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("Redirecionamento inválido ($code)")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
            } else {
                return conn
            }
        }
        throw IllegalStateException("Muitos redirecionamentos ao acessar o arquivo.")
    }

    private fun updateProgress(progress: ModelDownloadProgress) {
        _downloads.update { it + (progress.modelId to progress) }
    }

    private fun clearProgress(modelId: String) {
        _downloads.update { it - modelId }
    }
}
