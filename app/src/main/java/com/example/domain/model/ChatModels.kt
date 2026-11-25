package com.example.domain.model

object EngineLimits {
    const val MIN_CONTEXT = 512
    const val MAX_CONTEXT = 131_072
    const val MIN_MAX_TOKENS = 128
    const val MAX_MAX_TOKENS = 65_536
    const val MIN_BATCH = 32
    const val MAX_BATCH = 4096
    const val MIN_THREADS = 1
    const val MAX_THREADS = 16
    const val MIN_GPU_LAYERS = 0
    const val MAX_GPU_LAYERS = 99
}

enum class ThinkingSupportMode {
    NONE,
    BINARY,
    EFFORT_LEVELS
}

enum class ThinkingEffort(
    val shortLabel: String,
    val jinjaValue: String?
) {
    OFF("Think", null),
    ON("Think", "on"),
    LOW("Low", "low"),
    MEDIUM("Med", "medium"),
    HIGH("High", "high"),
    XHIGH("XHigh", "xhigh");

    val isEnabled: Boolean
        get() = this != OFF
}

enum class ChatRole {
    USER,
    ASSISTANT,
    SYSTEM
}

enum class AttachmentType {
    IMAGE,
    DOCUMENT
}

data class ChatAttachment(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val type: AttachmentType,
    val uriString: String,
    val extractedText: String? = null,
    val sizeBytes: Long = 0L
)

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val thinkingContent: String = "",
    val isThinkingInProgress: Boolean = false,
    val isThinkingExpanded: Boolean = true,
    val thinkingDurationMs: Long = 0L,
    val isStreaming: Boolean = false,
    val attachments: List<ChatAttachment> = emptyList(),
    val tokensPerSecond: Float? = null,
    val generatedTokenCount: Int = 0,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ConversationSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class GgufModelSpec(
    val id: String,
    val name: String,
    val family: String,
    val parameterSize: String,
    val quantization: String,
    val fileName: String,
    val fileSizeMb: Int,
    val maxContextLength: Int,
    val defaultContextLength: Int,
    val supportsVision: Boolean = false,
    val thinkingSupport: ThinkingSupportMode = ThinkingSupportMode.NONE,
    val jinjaTemplate: String,
    val downloadUrl: String? = null,
    val isDownloaded: Boolean = false,
    val isImported: Boolean = false,
    val localPath: String? = null
)

data class ModelDownloadProgress(
    val modelId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Long
) {
    val fraction: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val percent: Int
        get() = (fraction * 100f).toInt().coerceIn(0, 100)

    val remainingBytes: Long
        get() = (totalBytes - downloadedBytes).coerceAtLeast(0L)
}

data class EngineConfig(
    val contextLength: Int = 4096,
    val maxTokens: Int = 2048,
    val batchSize: Int = 512,
    val threads: Int = 4,
    val gpuLayers: Int = 0,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val systemPrompt: String = "Você é um assistente útil, direto e preciso.",
    val thinkingEffort: ThinkingEffort = ThinkingEffort.OFF
) {
    fun requiresReloadComparedTo(other: EngineConfig?): Boolean {
        if (other == null) return false
        return contextLength != other.contextLength ||
            batchSize != other.batchSize ||
            threads != other.threads ||
            gpuLayers != other.gpuLayers ||
            useMmap != other.useMmap ||
            useMlock != other.useMlock
    }
}
