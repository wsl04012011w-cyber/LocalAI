package com.example.engine

import com.example.data.AppDiagnosticsLogger
import com.example.domain.model.AttachmentType
import com.example.domain.model.ChatAttachment
import com.example.domain.model.EngineConfig
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.ThinkingSupportMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LlamaCppEngineAdapter : LlamaEngineAdapter {
    private val lock = Any()
    private var modelHandle = 0L
    @Volatile private var detectedThinking = ThinkingSupportMode.NONE

    override fun detectedThinkingSupport(): ThinkingSupportMode = detectedThinking

    override suspend fun loadModel(model: GgufModelSpec, config: EngineConfig): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = model.localPath
                    ?: throw IllegalStateException("O caminho do arquivo GGUF não está disponível.")
                val file = File(path)
                if (!file.isFile || file.length() <= 0L) {
                    throw IllegalStateException("O arquivo ${model.fileName} está vazio ou ausente.")
                }
                unloadCurrentModel()
                AppDiagnosticsLogger.info("LLAMA_CPP",
                    "Carregando ${file.name}; ctx=${config.contextLength}, batch=${config.batchSize}, threads=${config.threads}, mmap=${config.useMmap}")
                val handle = NativeLlamaBridge.nativeLoadModel(
                    file.absolutePath, config.contextLength.coerceAtLeast(512),
                    config.batchSize.coerceAtLeast(1), config.threads.coerceAtLeast(1),
                    config.gpuLayers.coerceAtLeast(0), config.useMmap, config.useMlock
                )
                if (handle == 0L) throw IllegalStateException("llama.cpp não conseguiu inicializar o modelo.")
                detectedThinking = when (NativeLlamaBridge.nativeDetectThinking(handle)) {
                    2 -> ThinkingSupportMode.EFFORT_LEVELS
                    1 -> ThinkingSupportMode.BINARY
                    else -> ThinkingSupportMode.NONE
                }
                synchronized(lock) { modelHandle = handle }
                AppDiagnosticsLogger.info("LLAMA_CPP",
                    "Modelo carregado: ${file.name}; detector thinking=${detectedThinking}")
            }
        }

    override suspend fun unloadModel() {
        withContext(Dispatchers.IO) { unloadCurrentModel() }
    }

    private fun unloadCurrentModel() {
        val old = synchronized(lock) { modelHandle.also { modelHandle = 0L } }
        if (old != 0L) {
            detectedThinking = ThinkingSupportMode.NONE
            NativeLlamaBridge.nativeStop(old)
            NativeLlamaBridge.nativeUnloadModel(old)
        }
    }

    override fun generateStream(prompt: String, model: GgufModelSpec, config: EngineConfig,
        attachments: List<ChatAttachment>): Flow<EngineStreamEvent> = channelFlow {
        val handle = synchronized(lock) { modelHandle }
        if (handle == 0L) {
            trySend(EngineStreamEvent.Error("Nenhum modelo llama.cpp está carregado."))
            close()
            return@channelFlow
        }
        if (attachments.any { it.type == AttachmentType.IMAGE }) {
            trySend(EngineStreamEvent.Error("Esta build executa llama.cpp para texto; entrada de imagem multimodal ainda não está conectada."))
            close()
            return@channelFlow
        }

        val started = System.nanoTime()
        val worker = launch(Dispatchers.IO) {
            try {
                val count = NativeLlamaBridge.nativeGenerate(
                    handle, prompt, config.maxTokens.coerceAtLeast(1),
                    config.temperature.coerceAtLeast(0f), config.topP.coerceIn(0.01f, 1f),
                    config.topK.coerceAtLeast(1), config.repeatPenalty.coerceAtLeast(1f),
                    NativeLlamaBridge.TokenCallback { token ->
                        if (token.isNotEmpty()) trySend(EngineStreamEvent.RawToken(token))
                    }
                )
                val seconds = ((System.nanoTime() - started) / 1_000_000_000.0).coerceAtLeast(0.001)
                trySend(EngineStreamEvent.Completed(count, (count / seconds).toFloat()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppDiagnosticsLogger.error("LLAMA_CPP", "Falha na geração nativa", error)
                trySend(EngineStreamEvent.Error(error.message ?: "Erro na inferência llama.cpp."))
            } finally {
                close()
            }
        }
        awaitClose {
            NativeLlamaBridge.nativeStop(handle)
            worker.cancel()
        }
    }

    override fun stopGeneration() {
        val handle = synchronized(lock) { modelHandle }
        if (handle != 0L) NativeLlamaBridge.nativeStop(handle)
    }
}
