package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.domain.model.AttachmentType
import com.example.domain.model.ChatAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class AttachmentReader(
    private val context: Context
) {
    suspend fun readImageAttachment(uri: Uri): ChatAttachment? = withContext(Dispatchers.IO) {
        val (name, size, mime) = queryMetadata(uri, "image/jpeg")
        ChatAttachment(
            id = UUID.randomUUID().toString(),
            displayName = name,
            mimeType = mime,
            type = AttachmentType.IMAGE,
            uriString = uri.toString(),
            extractedText = null,
            sizeBytes = size
        )
    }

    suspend fun readDocumentAttachment(uri: Uri): ChatAttachment? = withContext(Dispatchers.IO) {
        val (name, size, mime) = queryMetadata(uri, "text/plain")
        val extracted = runCatching {
            val isTextReadable = mime.startsWith("text/") ||
                mime.contains("json") ||
                mime.contains("xml") ||
                mime.contains("csv") ||
                name.endsWith(".txt", ignoreCase = true) ||
                name.endsWith(".md", ignoreCase = true) ||
                name.endsWith(".json", ignoreCase = true) ||
                name.endsWith(".csv", ignoreCase = true) ||
                name.endsWith(".kt", ignoreCase = true) ||
                name.endsWith(".py", ignoreCase = true) ||
                name.endsWith(".js", ignoreCase = true)

            context.contentResolver.openInputStream(uri)?.use { stream ->
                if (isTextReadable) {
                    val bytes = stream.readBytes()
                    val text = bytes.toString(Charsets.UTF_8)
                    if (text.length > 6000) text.take(6000) + "\n...[truncado]" else text
                } else if (mime.contains("pdf") || name.endsWith(".pdf", ignoreCase = true)) {
                    val raw = stream.readBytes()
                    extractReadablePdfText(raw).ifBlank {
                        "Arquivo PDF anexado ($name, ${size / 1024} KB)."
                    }
                } else {
                    "Arquivo anexado ($name, $mime, ${size / 1024} KB)."
                }
            }
        }.getOrNull()

        ChatAttachment(
            id = UUID.randomUUID().toString(),
            displayName = name,
            mimeType = mime,
            type = AttachmentType.DOCUMENT,
            uriString = uri.toString(),
            extractedText = extracted,
            sizeBytes = size
        )
    }

    private fun extractReadablePdfText(bytes: ByteArray): String {
        val ascii = bytes.toString(Charsets.ISO_8859_1)
        val matches = Regex("""\(([^()\\]{3,160})\)""").findAll(ascii)
            .map { it.groupValues[1].replace(Regex("""[^\p{Print}\s]"""), "").trim() }
            .filter { it.any(Char::isLetterOrDigit) }
            .take(80)
            .joinToString(" ")
        return matches.take(4000)
    }

    private fun queryMetadata(uri: Uri, fallbackMime: String): Triple<String, Long, String> {
        var name = "arquivo_${System.currentTimeMillis()}"
        var size = 0L
        val mime = context.contentResolver.getType(uri) ?: fallbackMime

        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx != -1) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
            }
        }
        return Triple(name, size, mime)
    }
}
