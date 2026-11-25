package com.example.engine

import com.example.data.AppDiagnosticsLogger
import com.example.domain.model.ChatAttachment
import com.example.domain.model.EngineConfig
import com.example.domain.model.GgufModelSpec
import kotlinx.coroutines.flow.Flow

class LocalInferenceEngine(
    private val adapter: LlamaEngineAdapter = LlamaCppEngineAdapter()
) {
    var activeModel: GgufModelSpec? = null
        private set
    var appliedConfig: EngineConfig? = null
        private set

    suspend fun loadModel(model: GgufModelSpec, config: EngineConfig): Result<Unit> {
        AppDiagnosticsLogger.info("ENGINE",
            "llama.cpp load: ${model.fileName}, ctx=${config.contextLength}, batch=${config.batchSize}, threads=${config.threads}")
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

    fun generateStream(prompt: String, model: GgufModelSpec, config: EngineConfig,
        attachments: List<ChatAttachment>): Flow<EngineStreamEvent> =
        adapter.generateStream(prompt, model, config, attachments)

    fun stopGeneration() = adapter.stopGeneration()
}
