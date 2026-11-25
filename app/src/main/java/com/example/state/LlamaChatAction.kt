package com.example.state

import android.net.Uri
import com.example.domain.model.GgufModelSpec

sealed interface LlamaChatAction {
    data class SelectDestination(val destination: AppDestination) : LlamaChatAction
    data object CreateNewSession : LlamaChatAction
    data class SelectSession(val sessionId: String) : LlamaChatAction
    data class DeleteSession(val sessionId: String) : LlamaChatAction

    data class UpdateComposerText(val text: String) : LlamaChatAction
    data object SendMessage : LlamaChatAction
    data object StopGeneration : LlamaChatAction
    data object RegenerateLastResponse : LlamaChatAction
    data class ToggleThinkingBox(val messageId: String) : LlamaChatAction
    data object CycleThinkingEffort : LlamaChatAction

    data class SetAttachmentSheetOpen(val open: Boolean) : LlamaChatAction
    data class SetContextSheetOpen(val open: Boolean) : LlamaChatAction
    data class AddImageAttachmentUri(val uri: Uri) : LlamaChatAction
    data class AddDocumentAttachmentUri(val uri: Uri) : LlamaChatAction
    data class RemoveAttachment(val attachmentId: String) : LlamaChatAction

    data class DownloadModel(val model: GgufModelSpec) : LlamaChatAction
    data class CancelDownload(val modelId: String) : LlamaChatAction
    data class ImportModelUri(val uri: Uri) : LlamaChatAction
    data class LoadModel(val model: GgufModelSpec) : LlamaChatAction
    data object UnloadModel : LlamaChatAction
    data class DeleteModel(val model: GgufModelSpec) : LlamaChatAction
    data object ReloadActiveModel : LlamaChatAction

    data class UpdateContextLength(val contextLength: Int) : LlamaChatAction
    data class UpdateMaxTokens(val maxTokens: Int) : LlamaChatAction
    data class UpdateBatchSize(val batchSize: Int) : LlamaChatAction
    data class UpdateThreads(val threads: Int) : LlamaChatAction
    data class UpdateGpuLayers(val gpuLayers: Int) : LlamaChatAction
    data class UpdateTemperature(val temperature: Float) : LlamaChatAction
    data class UpdateTopP(val topP: Float) : LlamaChatAction
    data class UpdateTopK(val topK: Int) : LlamaChatAction
    data class UpdateRepeatPenalty(val repeatPenalty: Float) : LlamaChatAction
    data class UpdateUseMmap(val useMmap: Boolean) : LlamaChatAction
    data class UpdateUseMlock(val useMlock: Boolean) : LlamaChatAction
    data class UpdateSystemPrompt(val systemPrompt: String) : LlamaChatAction
    data object ResetEngineDefaults : LlamaChatAction

    data class ShowBanner(val message: String) : LlamaChatAction
    data object DismissStatusBanner : LlamaChatAction
}
