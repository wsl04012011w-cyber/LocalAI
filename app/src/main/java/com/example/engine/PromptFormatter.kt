package com.example.engine

import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatTemplateFormat
import com.example.domain.model.InferenceConfig
import com.example.domain.model.MessageRole

/**
 * Pure Kotlin prompt serializer matching standard llama.cpp chat templates.
 * Once the native JNI bridge is compiled, this output feeds directly into llama_tokenize().
 */
object PromptFormatter {

    fun formatPrompt(
        messages: List<ChatMessage>,
        config: InferenceConfig
    ): String {
        val cleanMessages = messages.filter { it.content.isNotBlank() }
        return when (config.chatTemplate) {
            ChatTemplateFormat.LLAMA3 -> formatLlama3(cleanMessages, config.systemPrompt)
            ChatTemplateFormat.CHATML -> formatChatMl(cleanMessages, config.systemPrompt)
            ChatTemplateFormat.GEMMA -> formatGemma(cleanMessages, config.systemPrompt)
            ChatTemplateFormat.PHI3 -> formatPhi3(cleanMessages, config.systemPrompt)
        }
    }

    /**
     * Lightweight ~4 chars/token heuristic for UI context meter before native tokenizer binding.
     */
    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        return ((text.length + 3) / 4).coerceAtLeast(1)
    }

    private fun formatLlama3(messages: List<ChatMessage>, systemPrompt: String): String = buildString {
        append("<|begin_of_text|>")
        if (systemPrompt.isNotBlank()) {
            append("<|start_header_id|>system<|end_header_id|>\n\n")
            append(systemPrompt.trim())
            append("<|eot_id|>")
        }
        messages.forEach { msg ->
            val roleTag = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> "system"
            }
            append("<|start_header_id|>$roleTag<|end_header_id|>\n\n")
            append(msg.content.trim())
            append("<|eot_id|>")
        }
        append("<|start_header_id|>assistant<|end_header_id|>\n\n")
    }

    private fun formatChatMl(messages: List<ChatMessage>, systemPrompt: String): String = buildString {
        if (systemPrompt.isNotBlank()) {
            append("<|im_start|>system\n${systemPrompt.trim()}<|im_end|>\n")
        }
        messages.forEach { msg ->
            val roleTag = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> "system"
            }
            append("<|im_start|>$roleTag\n${msg.content.trim()}<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    private fun formatGemma(messages: List<ChatMessage>, systemPrompt: String): String = buildString {
        var systemPrepended = false
        messages.forEachIndexed { index, msg ->
            when (msg.role) {
                MessageRole.USER -> {
                    append("<start_of_turn>user\n")
                    if (!systemPrepended && systemPrompt.isNotBlank() && index == 0) {
                        append(systemPrompt.trim())
                        append("\n\n")
                        systemPrepended = true
                    }
                    append(msg.content.trim())
                    append("<end_of_turn>\n")
                }
                MessageRole.ASSISTANT -> {
                    append("<start_of_turn>model\n${msg.content.trim()}<end_of_turn>\n")
                }
                MessageRole.SYSTEM -> Unit
            }
        }
        append("<start_of_turn>model\n")
    }

    private fun formatPhi3(messages: List<ChatMessage>, systemPrompt: String): String = buildString {
        if (systemPrompt.isNotBlank()) {
            append("<|system|>\n${systemPrompt.trim()}<|end|>\n")
        }
        messages.forEach { msg ->
            val tag = when (msg.role) {
                MessageRole.USER -> "<|user|>"
                MessageRole.ASSISTANT -> "<|assistant|>"
                MessageRole.SYSTEM -> "<|system|>"
            }
            append("$tag\n${msg.content.trim()}<|end|>\n")
        }
        append("<|assistant|>\n")
    }
}
