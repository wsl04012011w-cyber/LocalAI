package com.example.data

import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatTemplateFormat
import com.example.domain.model.ConversationSession
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.GgufQuantization
import com.example.domain.model.InferenceConfig
import com.example.domain.model.ModelOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class GgufRepository(defaultThreads: Int) {

    private val _models = MutableStateFlow(defaultProfileSlots())
    val models: StateFlow<List<GgufModelSpec>> = _models.asStateFlow()

    private val initialSession = ConversationSession(
        id = UUID.randomUUID().toString(),
        title = "Nova Sessão Local"
    )

    private val _sessions = MutableStateFlow(listOf(initialSession))
    val sessions: StateFlow<List<ConversationSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow(initialSession.id)
    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    private val _config = MutableStateFlow(
        InferenceConfig(
            threads = defaultThreads.coerceIn(2, 6)
        )
    )
    val config: StateFlow<InferenceConfig> = _config.asStateFlow()

    fun registerPickedModel(spec: GgufModelSpec) {
        _models.update { current ->
            val filtered = current.filterNot { it.fileName == spec.fileName && it.origin == ModelOrigin.DEVICE_STORAGE }
            listOf(spec) + filtered
        }
    }

    fun removePickedModel(modelId: String) {
        _models.update { current -> current.filterNot { it.id == modelId && it.origin == ModelOrigin.DEVICE_STORAGE } }
    }

    fun updateConfig(transform: (InferenceConfig) -> InferenceConfig) {
        _config.update(transform)
    }

    fun createNewSession(modelId: String?): ConversationSession {
        val session = ConversationSession(
            id = UUID.randomUUID().toString(),
            title = "Nova Sessão Local",
            modelId = modelId
        )
        _sessions.update { listOf(session) + it }
        _activeSessionId.value = session.id
        return session
    }

    fun selectSession(sessionId: String) {
        if (_sessions.value.any { it.id == sessionId }) {
            _activeSessionId.value = sessionId
        }
    }

    fun deleteSession(sessionId: String) {
        _sessions.update { current ->
            val remaining = current.filterNot { it.id == sessionId }
            if (remaining.isEmpty()) {
                val fallback = ConversationSession(title = "Nova Sessão Local")
                _activeSessionId.value = fallback.id
                listOf(fallback)
            } else {
                if (_activeSessionId.value == sessionId) {
                    _activeSessionId.value = remaining.first().id
                }
                remaining
            }
        }
    }

    fun updateSessionMessages(
        sessionId: String,
        autoTitleFromFirstPrompt: String? = null,
        transform: (List<ChatMessage>) -> List<ChatMessage>
    ) {
        _sessions.update { list ->
            list.map { session ->
                if (session.id != sessionId) return@map session
                val updatedMessages = transform(session.messages)
                val updatedTitle = if (
                    autoTitleFromFirstPrompt != null &&
                    session.title == "Nova Sessão Local"
                ) {
                    autoTitleFromFirstPrompt.trim().take(34).ifBlank { session.title }
                } else {
                    session.title
                }
                session.copy(
                    title = updatedTitle,
                    updatedAtMs = System.currentTimeMillis(),
                    messages = updatedMessages
                )
            }
        }
    }

    private fun defaultProfileSlots(): List<GgufModelSpec> = listOf(
        GgufModelSpec(
            id = "slot_qwen25_1_5b",
            name = "Qwen 2.5 Instruct",
            architecture = "Qwen2.5",
            parameterLabel = "1.5B",
            quantization = GgufQuantization.Q4_K_M,
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            fileSizeMb = 986,
            estimatedRamMb = 1280,
            maxContextLength = 8192,
            defaultTemplate = ChatTemplateFormat.CHATML,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true",
            repositoryUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            description = "Compacto e rápido para smartphones de 4–6 GB RAM."
        ),
        GgufModelSpec(
            id = "slot_llama32_3b",
            name = "Llama 3.2 Instruct",
            architecture = "Llama",
            parameterLabel = "3B",
            quantization = GgufQuantization.Q4_K_M,
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            fileSizeMb = 2019,
            estimatedRamMb = 2480,
            maxContextLength = 8192,
            defaultTemplate = ChatTemplateFormat.LLAMA3,
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf?download=true",
            repositoryUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF",
            description = "Equilíbrio ideal entre qualidade e uso de RAM em aparelhos de 6–8 GB."
        ),
        GgufModelSpec(
            id = "slot_gemma2_2b",
            name = "Gemma 2 IT",
            architecture = "Gemma2",
            parameterLabel = "2B",
            quantization = GgufQuantization.IQ4_XS,
            fileName = "gemma-2-2b-it-IQ4_XS.gguf",
            fileSizeMb = 1420,
            estimatedRamMb = 1790,
            maxContextLength = 8192,
            defaultTemplate = ChatTemplateFormat.GEMMA,
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-IQ4_XS.gguf?download=true",
            repositoryUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF",
            description = "Quantização IQ4_XS otimizada para largura de banda móvel."
        ),
        GgufModelSpec(
            id = "slot_phi35_mini",
            name = "Phi-3.5 Mini Instruct",
            architecture = "Phi-3.5",
            parameterLabel = "3.8B",
            quantization = GgufQuantization.Q4_K_M,
            fileName = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
            fileSizeMb = 2390,
            estimatedRamMb = 2900,
            maxContextLength = 4096,
            defaultTemplate = ChatTemplateFormat.PHI3,
            downloadUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf?download=true",
            repositoryUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF",
            description = "Modelo denso focado em lógica e instruções estruturadas."
        )
    )
}
