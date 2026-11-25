package com.example.engine

import com.example.domain.model.AttachmentType
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatRole
import com.example.domain.model.EngineConfig
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.ThinkingEffort
import com.example.domain.model.ThinkingSupportMode

object PromptFormatter {

    fun formatPrompt(
        model: GgufModelSpec,
        messages: List<ChatMessage>,
        config: EngineConfig
    ): String {
        val template = model.jinjaTemplate
        val normalizedMessages = buildNormalizedMessages(messages, config.systemPrompt)

        return when {
            template.contains("<|start|>system<|message|>") -> {
                formatHarmonyTemplate(normalizedMessages, config.thinkingEffort)
            }
            template.contains("<｜User｜>") -> {
                formatDeepSeekR1Template(normalizedMessages, config.thinkingEffort)
            }
            template.contains("<|start_header_id|>") -> {
                formatLlama3Template(normalizedMessages)
            }
            template.contains("<start_of_turn>") -> {
                formatGemma3Template(normalizedMessages)
            }
            else -> {
                formatChatMlTemplate(
                    messages = normalizedMessages,
                    thinkingSupport = model.thinkingSupport,
                    thinkingEffort = config.thinkingEffort
                )
            }
        }
    }

    private fun buildNormalizedMessages(
        messages: List<ChatMessage>,
        systemPrompt: String
    ): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (systemPrompt.isNotBlank()) {
            out += "system" to systemPrompt.trim()
        }
        messages.forEach { msg ->
            if (msg.role == ChatRole.SYSTEM) return@forEach
            val role = if (msg.role == ChatRole.USER) "user" else "assistant"
            val body = buildString {
                if (msg.content.isNotBlank()) append(msg.content.trim())
                if (msg.attachments.isNotEmpty()) {
                    msg.attachments.forEach { att ->
                        if (isNotEmpty()) append("\n\n")
                        when (att.type) {
                            AttachmentType.IMAGE -> append("[Imagem anexada: ${att.displayName}]")
                            AttachmentType.DOCUMENT -> {
                                append("[Arquivo anexado: ${att.displayName}]\n")
                                append(att.extractedText.orEmpty())
                            }
                        }
                    }
                }
            }
            if (body.isNotBlank()) {
                out += role to body
            }
        }
        return out
    }

    private fun formatDeepSeekR1Template(
        messages: List<Pair<String, String>>,
        thinkingEffort: ThinkingEffort
    ): String = buildString {
        messages.forEach { (role, content) ->
            when (role) {
                "system" -> append(content).append("\n")
                "user" -> append("<｜User｜>").append(content)
                "assistant" -> append("<｜Assistant｜>").append(content).append("<｜end▁of▁sentence｜>")
            }
        }
        append("<｜Assistant｜>")
        if (thinkingEffort.isEnabled) {
            append("<think>\n")
        } else {
            append("<think>\n</think>\n")
        }
    }

    private fun formatChatMlTemplate(
        messages: List<Pair<String, String>>,
        thinkingSupport: ThinkingSupportMode,
        thinkingEffort: ThinkingEffort
    ): String = buildString {
        messages.forEach { (role, content) ->
            append("<|im_start|>").append(role).append("\n")
            append(content).append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
        if (thinkingSupport != ThinkingSupportMode.NONE) {
            if (thinkingEffort.isEnabled) {
                append("<think>\n")
            } else {
                append("<think>\n\n</think>\n\n")
            }
        }
    }

    private fun formatHarmonyTemplate(
        messages: List<Pair<String, String>>,
        thinkingEffort: ThinkingEffort
    ): String = buildString {
        val effort = thinkingEffort.jinjaValue ?: "none"
        val systemText = messages.firstOrNull { it.first == "system" }?.second.orEmpty()
        append("<|start|>system<|message|>Reasoning: $effort")
        if (systemText.isNotBlank()) {
            append("\n").append(systemText)
        }
        append("<|end|>")
        messages.filter { it.first != "system" }.forEach { (role, content) ->
            append("<|start|>").append(role).append("<|message|>").append(content).append("<|end|>")
        }
        append("<|start|>assistant")
    }

    private fun formatLlama3Template(messages: List<Pair<String, String>>): String = buildString {
        append("<|begin_of_text|>")
        messages.forEach { (role, content) ->
            append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n")
            append(content).append("<|eot_id|>")
        }
        append("<|start_header_id|>assistant<|end_header_id|>\n\n")
    }

    private fun formatGemma3Template(messages: List<Pair<String, String>>): String = buildString {
        messages.forEach { (role, content) ->
            val mappedRole = if (role == "assistant") "model" else role
            append("<start_of_turn>").append(mappedRole).append("\n")
            append(content).append("<end_of_turn>\n")
        }
        append("<start_of_turn>model\n")
    }
}
