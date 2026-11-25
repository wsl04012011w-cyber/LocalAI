package com.example.data

import com.example.domain.model.GgufModelSpec
import com.example.domain.model.ThinkingSupportMode
import java.io.File

class GgufRepository(
    private val storageManager: LocalModelStorageManager
) {
    private val curatedCatalog: List<GgufModelSpec> = listOf(
        GgufModelSpec(
            id = "deepseek-r1-distill-qwen-1.5b",
            name = "DeepSeek-R1 Distill Qwen 1.5B",
            family = "DeepSeek-R1",
            parameterSize = "1.5B",
            quantization = "Q4_K_M",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            fileSizeMb = 1120,
            maxContextLength = 131_072,
            defaultContextLength = 8192,
            supportsVision = false,
            thinkingSupport = ThinkingSupportMode.BINARY,
            jinjaTemplate = JINJA_DEEPSEEK_R1,
            downloadUrl = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"
        ),
        GgufModelSpec(
            id = "qwen3-1.7b",
            name = "Qwen3 1.7B",
            family = "Qwen3",
            parameterSize = "1.7B",
            quantization = "Q4_K_M",
            fileName = "Qwen3-1.7B-Q4_K_M.gguf",
            fileSizeMb = 1280,
            maxContextLength = 32_768,
            defaultContextLength = 8192,
            supportsVision = false,
            thinkingSupport = ThinkingSupportMode.BINARY,
            jinjaTemplate = JINJA_QWEN3,
            downloadUrl = "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
        ),
        GgufModelSpec(
            id = "qwen3-0.6b",
            name = "Qwen3 0.6B",
            family = "Qwen3",
            parameterSize = "0.6B",
            quantization = "Q4_K_M",
            fileName = "Qwen3-0.6B-Q4_K_M.gguf",
            fileSizeMb = 484,
            maxContextLength = 32_768,
            defaultContextLength = 4096,
            supportsVision = false,
            thinkingSupport = ThinkingSupportMode.BINARY,
            jinjaTemplate = JINJA_QWEN3,
            downloadUrl = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"
        ),
        GgufModelSpec(
            id = "gpt-oss-20b",
            name = "GPT-OSS 20B",
            family = "GPT-OSS",
            parameterSize = "20B",
            quantization = "Q4_K_M",
            fileName = "openai_gpt-oss-20b-Q4_K_M.gguf",
            fileSizeMb = 12_100,
            maxContextLength = 131_072,
            defaultContextLength = 8192,
            supportsVision = false,
            thinkingSupport = ThinkingSupportMode.EFFORT_LEVELS,
            jinjaTemplate = JINJA_HARMONY_EFFORT,
            downloadUrl = "https://huggingface.co/bartowski/openai_gpt-oss-20b-GGUF/resolve/main/openai_gpt-oss-20b-Q4_K_M.gguf"
        ),
        GgufModelSpec(
            id = "smolvlm2-2.2b-instruct",
            name = "SmolVLM2 2.2B Instruct",
            family = "SmolVLM2",
            parameterSize = "2.2B",
            quantization = "Q4_K_M",
            fileName = "SmolVLM2-2.2B-Instruct-Q4_K_M.gguf",
            fileSizeMb = 1180,
            maxContextLength = 16_384,
            defaultContextLength = 4096,
            supportsVision = true,
            thinkingSupport = ThinkingSupportMode.NONE,
            jinjaTemplate = JINJA_CHATML_VISION,
            downloadUrl = "https://huggingface.co/ggml-org/SmolVLM2-2.2B-Instruct-GGUF/resolve/main/SmolVLM2-2.2B-Instruct-Q4_K_M.gguf"
        ),
        GgufModelSpec(
            id = "llama-3.2-1b-instruct",
            name = "Llama 3.2 1B Instruct",
            family = "Llama 3.2",
            parameterSize = "1B",
            quantization = "Q4_K_M",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            fileSizeMb = 808,
            maxContextLength = 131_072,
            defaultContextLength = 8192,
            supportsVision = false,
            thinkingSupport = ThinkingSupportMode.NONE,
            jinjaTemplate = JINJA_LLAMA3,
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
        ),
        GgufModelSpec(
            id = "gemma-3-1b-it",
            name = "Gemma 3 1B IT",
            family = "Gemma 3",
            parameterSize = "1B",
            quantization = "Q4_K_M",
            fileName = "google_gemma-3-1b-it-Q4_K_M.gguf",
            fileSizeMb = 806,
            maxContextLength = 32_768,
            defaultContextLength = 8192,
            supportsVision = false,
            thinkingSupport = ThinkingSupportMode.NONE,
            jinjaTemplate = JINJA_GEMMA3,
            downloadUrl = "https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF/resolve/main/google_gemma-3-1b-it-Q4_K_M.gguf"
        )
    )

    fun loadModelsSnapshot(): List<GgufModelSpec> {
        val knownFileNames = curatedCatalog.map { it.fileName }.toSet()
        val catalogResolved = curatedCatalog.map { spec ->
            val path = storageManager.getLocalFilePath(spec.fileName)
            spec.copy(
                isDownloaded = path != null,
                localPath = path
            )
        }

        val imported = storageManager.listCustomStoredFiles(knownFileNames).map { file ->
            buildImportedModelSpec(file)
        }

        return imported + catalogResolved
    }

    fun buildImportedModelSpec(file: File): GgufModelSpec {
        val lower = file.name.lowercase()
        val supportsVision = listOf("vl", "vision", "llava", "minicpm-v", "smolvlm").any { lower.contains(it) }
        val effortThinking = listOf("gpt-oss", "reasoning-effort").any { lower.contains(it) }
        val binaryThinking = !effortThinking && listOf("r1", "think", "qwq", "qwen3").any { lower.contains(it) }
        val thinkingSupport = when {
            effortThinking -> ThinkingSupportMode.EFFORT_LEVELS
            binaryThinking -> ThinkingSupportMode.BINARY
            else -> ThinkingSupportMode.NONE
        }
        val sizeMb = (file.length() / (1024L * 1024L)).toInt().coerceAtLeast(1)
        val cleanTitle = file.nameWithoutExtension.replace('_', ' ').replace('-', ' ')
        val quant = Regex("""Q\d_[A-Z0-9_]+""", RegexOption.IGNORE_CASE).find(file.name)?.value?.uppercase() ?: "GGUF"
        val params = Regex("""\d+(?:\.\d+)?[BM]""", RegexOption.IGNORE_CASE).find(file.name)?.value?.uppercase() ?: "Local"

        return GgufModelSpec(
            id = "imported_${file.name}",
            name = cleanTitle,
            family = "GGUF Local",
            parameterSize = params,
            quantization = quant,
            fileName = file.name,
            fileSizeMb = sizeMb,
            maxContextLength = 131_072,
            defaultContextLength = 8192,
            supportsVision = supportsVision,
            thinkingSupport = thinkingSupport,
            jinjaTemplate = when (thinkingSupport) {
                ThinkingSupportMode.EFFORT_LEVELS -> JINJA_HARMONY_EFFORT
                ThinkingSupportMode.BINARY -> JINJA_QWEN3
                ThinkingSupportMode.NONE -> JINJA_CHATML_GENERIC
            },
            downloadUrl = null,
            isDownloaded = true,
            isImported = true,
            localPath = file.absolutePath
        )
    }

    companion object {
        private const val JINJA_DEEPSEEK_R1 =
            "{% for message in messages %}{% if message['role'] == 'system' %}{{ message['content'] }}{% elif message['role'] == 'user' %}<｜User｜>{{ message['content'] }}{% elif message['role'] == 'assistant' %}<｜Assistant｜>{{ message['content'] }}<｜end▁of▁sentence｜>{% endif %}{% endfor %}{% if add_generation_prompt %}<｜Assistant｜>{% if enable_thinking %}<think>\n{% else %}<think>\n</think>\n{% endif %}{% endif %}"

        private const val JINJA_QWEN3 =
            "{% for message in messages %}<|im_start|>{{ message['role'] }}\n{{ message['content'] }}<|im_end|>\n{% endfor %}{% if add_generation_prompt %}<|im_start|>assistant\n{% if enable_thinking %}<think>\n{% else %}<think>\n\n</think>\n\n{% endif %}{% endif %}"

        private const val JINJA_HARMONY_EFFORT =
            "<|start|>system<|message|>Reasoning: {{ reasoning_effort | default('medium') }}\n{{ system_prompt }}<|end|>{% for message in messages %}<|start|>{{ message['role'] }}<|message|>{{ message['content'] }}<|end|>{% endfor %}<|start|>assistant"

        private const val JINJA_CHATML_VISION =
            "{% for message in messages %}<|im_start|>{{ message['role'] }}\n{{ message['content'] }}<|im_end|>\n{% endfor %}<|im_start|>assistant\n"

        private const val JINJA_CHATML_GENERIC =
            "{% for message in messages %}<|im_start|>{{ message['role'] }}\n{{ message['content'] }}<|im_end|>\n{% endfor %}<|im_start|>assistant\n"

        private const val JINJA_LLAMA3 =
            "<|begin_of_text|>{% for message in messages %}<|start_header_id|>{{ message['role'] }}<|end_header_id|>\n\n{{ message['content'] }}<|eot_id|>{% endfor %}<|start_header_id|>assistant<|end_header_id|>\n\n"

        private const val JINJA_GEMMA3 =
            "{% for message in messages %}<start_of_turn>{{ message['role'] }}\n{{ message['content'] }}<end_of_turn>\n{% endfor %}<start_of_turn>model\n"
    }
}
