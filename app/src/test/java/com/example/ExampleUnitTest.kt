package com.example

import com.example.data.GgufRepository
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatTemplateFormat
import com.example.domain.model.EngineLimits
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.GgufQuantization
import com.example.domain.model.InferenceConfig
import com.example.domain.model.KvCacheQuant
import com.example.domain.model.MessageRole
import com.example.engine.EngineLifecycleState
import com.example.engine.FinishReason
import com.example.engine.InferenceStreamEvent
import com.example.engine.PromptFormatter
import com.example.engine.StubLlamaEngineAdapter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun promptFormatter_formatsChatMlAndLlama3Correctly() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Olá GGUF")
        )
        val llama3Config = InferenceConfig(
            chatTemplate = ChatTemplateFormat.LLAMA3,
            systemPrompt = "Sys"
        )
        val llama3Prompt = PromptFormatter.formatPrompt(messages, llama3Config)
        assertTrue(llama3Prompt.contains("<|begin_of_text|>"))
        assertTrue(llama3Prompt.contains("<|start_header_id|>user<|end_header_id|>"))
        assertTrue(llama3Prompt.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))

        val chatMlPrompt = PromptFormatter.formatPrompt(
            messages,
            llama3Config.copy(chatTemplate = ChatTemplateFormat.CHATML)
        )
        assertTrue(chatMlPrompt.contains("<|im_start|>system"))
        assertTrue(chatMlPrompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun kvCacheEstimation_scalesWithQuantization() {
        val f16 = InferenceConfig(contextLength = 4096, kvCacheQuant = KvCacheQuant.F16)
        val q8 = InferenceConfig(contextLength = 4096, kvCacheQuant = KvCacheQuant.Q8_0)
        val q4 = InferenceConfig(contextLength = 4096, kvCacheQuant = KvCacheQuant.Q4_0)

        assertTrue(f16.estimateKvCacheMb(null) > q8.estimateKvCacheMb(null))
        assertTrue(q8.estimateKvCacheMb(null) > q4.estimateKvCacheMb(null))
    }

    @Test
    fun repositoryModels_haveValidHuggingFaceGgufLinksAndValidEngineLimits() {
        val repo = GgufRepository(defaultThreads = 4)
        val models = repo.models.value
        assertTrue(models.isNotEmpty())
        models.forEach { model ->
            val url = model.downloadUrl.orEmpty()
            assertTrue(url.startsWith("https://huggingface.co/"))
            assertTrue(url.contains(".gguf"))
        }
        assertTrue(EngineLimits.CONTEXT_OPTIONS.all { it in 512..8192 })
        assertTrue(EngineLimits.BATCH_OPTIONS.all { it in 64..1024 })
    }

    @Test
    fun stubEngine_loadsModelStreamsTokensAndResetsState() = runTest {
        val engine = StubLlamaEngineAdapter()
        assertEquals(EngineLifecycleState.UNLOADED, engine.engineState.value)

        val spec = GgufModelSpec(
            id = "test_model",
            name = "Test Qwen",
            architecture = "Qwen2.5",
            parameterLabel = "1.5B",
            quantization = GgufQuantization.Q4_K_M,
            fileName = "test.gguf",
            fileSizeMb = 900,
            estimatedRamMb = 1200,
            maxContextLength = 4096,
            defaultTemplate = ChatTemplateFormat.CHATML,
            description = "Test slot"
        )
        val config = InferenceConfig(maxTokens = 24, threads = 4)

        val loadRes = engine.loadModel(spec, config)
        assertTrue(loadRes.isSuccess)
        assertEquals(EngineLifecycleState.READY, engine.engineState.value)

        val events = engine.streamCompletion(
            messages = listOf(ChatMessage(role = MessageRole.USER, content = "Teste")),
            config = config
        ).toList()

        assertTrue(events.first() is InferenceStreamEvent.Started)
        assertTrue(events.any { it is InferenceStreamEvent.Token })
        val completed = events.last() as InferenceStreamEvent.Completed
        assertEquals(FinishReason.MAX_TOKENS, completed.finishReason)
        assertEquals(EngineLifecycleState.READY, engine.engineState.value)
    }
}
