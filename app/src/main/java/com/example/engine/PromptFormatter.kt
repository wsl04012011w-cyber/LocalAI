package com.example.engine

/** UI-only token estimate; llama.cpp renders prompts with the model GGUF Jinja template. */
object PromptFormatter {
    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        return ((text.length + 3) / 4).coerceAtLeast(1)
    }
}
