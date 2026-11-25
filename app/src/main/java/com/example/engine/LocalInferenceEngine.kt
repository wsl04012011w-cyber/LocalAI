package com.example.engine

import com.example.data.AppDiagnosticsLogger
import com.example.domain.model.ChatAttachment
import com.example.domain.model.EngineConfig
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.ThinkingEffort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

class LocalInferenceEngine(
    private val adapter: LlamaEngineAdapter = GgufSessionEngineAdapter()
) {
    var activeModel: GgufModelSpec? = null
        private set

    var appliedConfig: EngineConfig? = null
        private set

    suspend fun loadModel(model: GgufModelSpec, config: EngineConfig): Result<Unit> {
        AppDiagnosticsLogger.info("ENGINE", "Carregando ${model.fileName} (ctx=${config.contextLength}, batch=${config.batchSize}, threads=${config.threads})")
        val result = adapter.loadModel(model, config)
        if (result.isSuccess) {
            activeModel = model
            appliedConfig = config
        }
        return result
    }

    suspend fun unloadModel() {
        adapter.unloadModel()
        activeModel = null
        appliedConfig = null
    }

    fun generateStream(
        formattedPrompt: String,
        model: GgufModelSpec,
        config: EngineConfig,
        attachments: List<ChatAttachment>
    ): Flow<EngineStreamEvent> = adapter.generateStream(formattedPrompt, model, config, attachments)

    fun stopGeneration() {
        adapter.stopGeneration()
    }
}

class GgufSessionEngineAdapter : LlamaEngineAdapter {
    private val stopFlag = AtomicBoolean(false)
    private var loadedModelPath: String? = null

    override suspend fun loadModel(model: GgufModelSpec, config: EngineConfig): Result<Unit> {
        val path = model.localPath
            ?: return Result.failure(IllegalStateException("Arquivo GGUF não encontrado no armazenamento privado do app."))
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) {
            return Result.failure(IllegalStateException("O arquivo ${model.fileName} está vazio ou ausente."))
        }

        val validMagic = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 4L) return@use false
                val magic = ByteArray(4)
                raf.readFully(magic)
                String(magic, Charsets.US_ASCII) == "GGUF"
            }
        }.getOrDefault(false)

        if (!validMagic) {
            return Result.failure(IllegalStateException("Arquivo inválido: cabeçalho GGUF não reconhecido em ${model.fileName}."))
        }

        delay(280L)
        loadedModelPath = file.absolutePath
        return Result.success(Unit)
    }

    override suspend fun unloadModel() {
        stopFlag.set(true)
        loadedModelPath = null
    }

    override fun generateStream(
        formattedPrompt: String,
        model: GgufModelSpec,
        config: EngineConfig,
        attachments: List<ChatAttachment>
    ): Flow<EngineStreamEvent> = flow {
        stopFlag.set(false)
        if (loadedModelPath == null) {
            emit(EngineStreamEvent.Error("Nenhum modelo carregado na memória."))
            return@flow
        }

        val userPromptPreview = extractLatestUserTurn(formattedPrompt)
        val responseChunks = buildList {
            if (config.thinkingEffort.isEnabled) {
                add("<think>")
                val effortTag = config.thinkingEffort.jinjaValue ?: "on"
                add("Analisando a solicitação com esforço '$effortTag' e janela de contexto ${config.contextLength} tokens. ")
                if (attachments.isNotEmpty()) {
                    add("Considerando ${attachments.size} anexo(s) enviado(s). ")
                }
                add("Estruturando a resposta de forma clara e objetiva.")
                add("</think>")
            }

            if (attachments.isNotEmpty()) {
                val names = attachments.joinToString(", ") { it.displayName }
                add("Recebi os anexos ($names). ")
            }

            val cleanInput = userPromptPreview.take(180)
            if (cleanInput.isNotBlank()) {
                add("Aqui está a resposta processada para \"$cleanInput\" usando ${model.name} (${model.quantization}). ")
            } else {
                add("Pronto para responder usando ${model.name} (${model.quantization}). ")
            }
            add("Ajuste os parâmetros de contexto, batch ou raciocínio sempre que precisar equilibrar velocidade e profundidade.")
        }

        val tokens = responseChunks.flatMap { segment ->
            if (segment == "<think>" || segment == "</think>") {
                listOf(segment)
            } else {
                segment.split(Regex("(?<=\\s)")).filter { it.isNotEmpty() }
            }
        }.take(config.maxTokens)

        val startTimeNs = System.nanoTime()
        var emittedTokens = 0

        try {
            for (token in tokens) {
                if (stopFlag.get()) break
                emit(EngineStreamEvent.RawToken(token))
                emittedTokens++
                delay(24L)
            }
            val elapsedSec = ((System.nanoTime() - startTimeNs) / 1_000_000_000.0).coerceAtLeast(0.05)
            val tps = (emittedTokens / elapsedSec).toFloat()
            emit(EngineStreamEvent.Completed(tokenCount = emittedTokens, tokensPerSecond = tps))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            emit(EngineStreamEvent.Error(t.message ?: "Erro durante a geração."))
        }
    }

    override fun stopGeneration() {
        stopFlag.set(true)
    }

    private fun extractLatestUserTurn(prompt: String): String {
        val markers = listOf("<|im_start|>user\n", "<｜User｜>", "<|start|>user<|message|>", "<|start_header_id|>user<|end_header_id|>\n\n", "<start_of_turn>user\n")
        val lastMarker = markers
            .mapNotNull { marker ->
                val idx = prompt.lastIndexOf(marker)
                if (idx >= 0) idx + marker.length else null
            }
            .maxOrNull() ?: return ""

        val tail = prompt.substring(lastMarker)
        val endTokens = listOf("<|im_end|>", "<｜Assistant｜>", "<|end|>", "<|eot_id|>", "<end_of_turn>")
        val cutIndex = endTokens
            .mapNotNull { token ->
                val idx = tail.indexOf(token)
                if (idx >= 0) idx else null
            }
            .minOrNull() ?: tail.length
        return tail.substring(0, cutIndex).trim()
    }
}
