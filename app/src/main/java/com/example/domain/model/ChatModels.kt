package com.example.domain.model

import java.util.UUID

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

enum class MessageStatus {
    QUEUED,
    STREAMING,
    COMPLETED,
    STOPPED,
    ERROR
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.COMPLETED,
    val tokenCount: Int = 0,
    val tokensPerSecond: Float = 0f,
    val generationDurationMs: Long = 0L,
    val modelName: String? = null
)

data class ConversationSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    val modelId: String? = null
)

enum class GgufQuantization(
    val label: String,
    val bitsPerWeight: Float,
    val summary: String
) {
    IQ4_XS("IQ4_XS", 4.25f, "Ultra-compacta (menor RAM)"),
    Q4_0("Q4_0", 4.5f, "Leve para CPUs ARM antigas"),
    Q4_K_M("Q4_K_M", 4.85f, "Equilíbrio ideal qualidade/RAM"),
    Q5_K_M("Q5_K_M", 5.65f, "Alta fidelidade, uso médio"),
    Q8_0("Q8_0", 8.5f, "Quase sem perda, exige mais RAM"),
    F16("F16", 16.0f, "Precisão total (referência)")
}

enum class KvCacheQuant(
    val label: String,
    val bytesPerElement: Float
) {
    F16("F16 (Padrão)", 2.0f),
    Q8_0("Q8_0 (-50% KV)", 1.0f),
    Q4_0("Q4_0 (-75% KV)", 0.5f)
}

enum class ModelOrigin {
    PROFILE_SLOT,
    DEVICE_STORAGE
}

data class GgufModelSpec(
    val id: String,
    val name: String,
    val architecture: String,
    val parameterLabel: String,
    val quantization: GgufQuantization,
    val fileName: String,
    val fileSizeMb: Int,
    val estimatedRamMb: Int,
    val maxContextLength: Int,
    val downloadUrl: String? = null,
    val repositoryUrl: String? = null,
    val uriString: String? = null,
    val origin: ModelOrigin = ModelOrigin.PROFILE_SLOT,
    val description: String
)

object EngineLimits {
    val CONTEXT_OPTIONS = listOf(512, 1024, 2048, 4096, 8192)
    val BATCH_OPTIONS = listOf(64, 128, 256, 512, 1024)
    val MAX_TOKENS_OPTIONS = listOf(128, 256, 512, 1024, 2048)
    val GPU_LAYER_PRESETS = listOf(0, 8, 16, 24, 32, 99)
    val TEMPERATURE_OPTIONS = listOf(0.0f, 0.2f, 0.5f, 0.7f, 0.9f, 1.2f)
    val TOP_P_OPTIONS = listOf(0.50f, 0.80f, 0.90f, 0.95f, 1.00f)
    val TOP_K_OPTIONS = listOf(1, 20, 40, 60, 80, 100)
    val REPEAT_PENALTY_OPTIONS = listOf(1.00f, 1.05f, 1.10f, 1.15f, 1.25f)

    const val MIN_THREADS = 1
    const val MAX_GPU_LAYERS = 99
    const val MIN_TEMP = 0.0f
    const val MAX_TEMP = 2.0f
}

data class InferenceConfig(
    val contextLength: Int = 4096,
    val threads: Int = 4,
    val batchSize: Int = 512,
    val gpuLayers: Int = 0,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val kvCacheQuant: KvCacheQuant = KvCacheQuant.Q8_0,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 512,
    val systemPrompt: String = "Você é um assistente de IA local executado diretamente no dispositivo via llama.cpp. Responda de forma clara, objetiva e técnica."
) {
    /**
     * Rough KV-cache footprint estimation across typical 24-32 layer mobile GGUF models
     * (2 tensors K+V * layers * head_dim * n_ctx * bytesPerElement).
     */
    fun estimateKvCacheMb(model: GgufModelSpec?): Int {
        val layerFactor = when {
            model == null -> 28f
            model.parameterLabel.contains("1.") || model.parameterLabel.contains("2B") -> 24f
            model.parameterLabel.contains("3B") -> 28f
            model.parameterLabel.contains("7B") || model.parameterLabel.contains("8B") -> 32f
            else -> 28f
        }
        val bytes = 2.0 * layerFactor * 1024.0 * contextLength * kvCacheQuant.bytesPerElement
        return (bytes / (1024.0 * 1024.0)).toInt().coerceAtLeast(16)
    }
}

data class DeviceMemoryInfo(
    val totalRamMb: Int,
    val availableRamMb: Int,
    val cpuCores: Int,
    val primaryAbi: String,
    val isArm64: Boolean
)

data class EngineTelemetry(
    val tokensPerSecond: Float = 0f,
    val promptTokensUsed: Int = 0,
    val generatedTokens: Int = 0,
    val promptEvalMs: Long = 0L,
    val estimatedKvCacheMb: Int = 0,
    val estimatedTotalFootprintMb: Int = 0
)
