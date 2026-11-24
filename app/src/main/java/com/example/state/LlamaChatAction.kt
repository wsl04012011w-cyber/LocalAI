package com.example.state

import android.net.Uri
import com.example.domain.model.FlashAttentionMode
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.KvCacheQuant

sealed interface LlamaChatAction {
    data class SelectDestination(val destination: AppDestination) : LlamaChatAction
    data class UpdateDraft(val text: String) : LlamaChatAction
    data class SendPrompt(val overrideText: String? = null) : LlamaChatAction
    data object StopGeneration : LlamaChatAction
    data object RegenerateLastResponse : LlamaChatAction

    data class LoadModel(val model: GgufModelSpec) : LlamaChatAction
    data object LoadRecommendedModel : LlamaChatAction
    data object UnloadModel : LlamaChatAction
    data class DownloadModel(val model: GgufModelSpec) : LlamaChatAction
    data class OpenModelRepository(val url: String) : LlamaChatAction
    data class ImportGgufUri(val uri: Uri) : LlamaChatAction
    data class RemoveImportedModel(val modelId: String) : LlamaChatAction
    data object RefreshDeviceMemory : LlamaChatAction

    data object CreateNewSession : LlamaChatAction
    data class SelectSession(val sessionId: String) : LlamaChatAction
    data class DeleteSession(val sessionId: String) : LlamaChatAction

    data class SetPromptInspectorOpen(val open: Boolean) : LlamaChatAction
    data object DismissStatusBanner : LlamaChatAction
    data object ExportDiagnostics : LlamaChatAction

    // Bounded & selective Engine / GGUF optimization configuration actions
    data class UpdateContextLength(val nCtx: Int) : LlamaChatAction
    data class UpdateThreads(val threads: Int) : LlamaChatAction
    data class UpdateBatchSize(val nBatch: Int) : LlamaChatAction
    data class UpdateMicroBatchSize(val nUBatch: Int) : LlamaChatAction
    data class UpdateFlashAttentionMode(val mode: FlashAttentionMode) : LlamaChatAction
    data class ToggleThinking(val enabled: Boolean) : LlamaChatAction
    data class UpdateGpuLayers(val layers: Int) : LlamaChatAction
    data class ToggleMmap(val enabled: Boolean) : LlamaChatAction
    data class ToggleMlock(val enabled: Boolean) : LlamaChatAction
    data class UpdateKvCacheQuant(val quant: KvCacheQuant) : LlamaChatAction
    data class UpdateTemperature(val temperature: Float) : LlamaChatAction
    data class UpdateTopP(val topP: Float) : LlamaChatAction
    data class UpdateTopK(val topK: Int) : LlamaChatAction
    data class UpdateRepeatPenalty(val penalty: Float) : LlamaChatAction
    data class UpdateMaxTokens(val maxTokens: Int) : LlamaChatAction
    data class UpdateSystemPrompt(val prompt: String) : LlamaChatAction
    data object ResetDefaultConfig : LlamaChatAction
}
