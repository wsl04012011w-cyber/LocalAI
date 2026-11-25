package com.example

import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatRole
import com.example.domain.model.EngineConfig
import com.example.domain.model.EngineLimits
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.ThinkingEffort
import com.example.domain.model.ThinkingSupportMode
import com.example.engine.PromptFormatter
import com.example.engine.ThinkingTagParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun thinkingTagParser_splitsStreamingChunksAcrossBoundaries() {
        val parser = ThinkingTagParser()
        val c1 = parser.consume("<thi")
        assertEquals("", c1.thinkingDelta)
        assertEquals("", c1.contentDelta)

        val c2 = parser.consume("nk>Passo 1")
        assertTrue(c2.enteredThinking)
        assertEquals("Passo 1", c2.thinkingDelta)
        assertEquals("", c2.contentDelta)

        val c3 = parser.consume("</th")
        assertEquals("", c3.thinkingDelta)

        val c4 = parser.consume("ink>Resposta final")
        assertTrue(c4.exitedThinking)
        assertEquals("", c4.thinkingDelta)
        assertEquals("Resposta final", c4.contentDelta)

        val tail = parser.flush()
        assertEquals("", tail.thinkingDelta)
        assertEquals("", tail.contentDelta)
    }

    @Test
    fun promptFormatter_appliesModelJinjaAndReasoningEffort() {
        val model = GgufModelSpec(
            id = "gpt-oss-20b",
            name = "GPT-OSS 20B",
            family = "GPT-OSS",
            parameterSize = "20B",
            quantization = "Q4_K_M",
            fileName = "openai_gpt-oss-20b-Q4_K_M.gguf",
            fileSizeMb = 12100,
            maxContextLength = 131_072,
            defaultContextLength = 8192,
            supportsVision = false,
            thinkingSupport = ThinkingSupportMode.EFFORT_LEVELS,
            jinjaTemplate = "<|start|>system<|message|>Reasoning: {{ reasoning_effort | default('medium') }}\n{{ system_prompt }}<|end|>"
        )
        val config = EngineConfig(
            contextLength = 32768,
            thinkingEffort = ThinkingEffort.XHIGH,
            systemPrompt = "Seja direto."
        )
        val messages = listOf(
            ChatMessage(id = "1", role = ChatRole.USER, content = "Olá")
        )

        val formatted = PromptFormatter.formatPrompt(model, messages, config)
        assertTrue(formatted.contains("Reasoning: xhigh"))
        assertTrue(formatted.contains("<|start|>user<|message|>Olá<|end|>"))
        assertTrue(formatted.endsWith("<|start|>assistant"))
    }

    @Test
    fun engineConfig_detectsReloadRequirement() {
        val base = EngineConfig(contextLength = 4096, batchSize = 512)
        val changed = base.copy(contextLength = 16384)
        val thinkingOnly = base.copy(thinkingEffort = ThinkingEffort.HIGH)

        assertTrue(changed.requiresReloadComparedTo(base))
        assertFalse(thinkingOnly.requiresReloadComparedTo(base))
        assertTrue(EngineLimits.MAX_CONTEXT >= 131_072)
    }
}
