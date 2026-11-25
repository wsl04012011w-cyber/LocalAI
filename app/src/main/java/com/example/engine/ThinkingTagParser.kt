package com.example.engine

data class ParsedThinkingChunk(
    val thinkingDelta: String = "",
    val contentDelta: String = "",
    val enteredThinking: Boolean = false,
    val exitedThinking: Boolean = false
)

class ThinkingTagParser {
    private enum class Mode {
        BEFORE_OR_CONTENT,
        IN_THINK_TAG
    }

    private var mode = Mode.BEFORE_OR_CONTENT
    private val pendingBuffer = StringBuilder()

    fun reset() {
        mode = Mode.BEFORE_OR_CONTENT
        pendingBuffer.clear()
    }

    fun consume(chunk: String): ParsedThinkingChunk {
        if (chunk.isEmpty()) return ParsedThinkingChunk()
        pendingBuffer.append(chunk)

        val thinkingOut = StringBuilder()
        val contentOut = StringBuilder()
        var entered = false
        var exited = false

        while (pendingBuffer.isNotEmpty()) {
            val current = pendingBuffer.toString()
            when (mode) {
                Mode.BEFORE_OR_CONTENT -> {
                    // Try both tag formats: <think> and think
                    val (startIdx, openTagLen) = findOpenTag(current)
                    if (startIdx >= 0) {
                        if (startIdx > 0) {
                            contentOut.append(current.substring(0, startIdx))
                        }
                        pendingBuffer.delete(0, startIdx + openTagLen)
                        mode = Mode.IN_THINK_TAG
                        entered = true
                    } else {
                        val partialMatch = maxTrailingPrefix(current, OPEN_TAGS)
                        val safeLen = current.length - partialMatch
                        if (safeLen > 0) {
                            contentOut.append(current.substring(0, safeLen))
                            pendingBuffer.delete(0, safeLen)
                        }
                        break
                    }
                }

                Mode.IN_THINK_TAG -> {
                    // Try both tag formats: </think> and /think
                    val (endIdx, closeTagLen) = findCloseTag(current)
                    if (endIdx >= 0) {
                        if (endIdx > 0) {
                            thinkingOut.append(current.substring(0, endIdx))
                        }
                        pendingBuffer.delete(0, endIdx + closeTagLen)
                        mode = Mode.BEFORE_OR_CONTENT
                        exited = true
                    } else {
                        val partialMatch = maxTrailingPrefix(current, CLOSE_TAGS)
                        val safeLen = current.length - partialMatch
                        if (safeLen > 0) {
                            thinkingOut.append(current.substring(0, safeLen))
                            pendingBuffer.delete(0, safeLen)
                        }
                        break
                    }
                }
            }
        }

        return ParsedThinkingChunk(
            thinkingDelta = thinkingOut.toString(),
            contentDelta = contentOut.toString(),
            enteredThinking = entered,
            exitedThinking = exited
        )
    }

    fun flush(): ParsedThinkingChunk {
        if (pendingBuffer.isEmpty()) return ParsedThinkingChunk()
        val leftover = pendingBuffer.toString()
        pendingBuffer.clear()
        return if (mode == Mode.IN_THINK_TAG) {
            mode = Mode.BEFORE_OR_CONTENT
            ParsedThinkingChunk(thinkingDelta = leftover, exitedThinking = true)
        } else {
            ParsedThinkingChunk(contentDelta = leftover)
        }
    }

    private fun findOpenTag(text: String): Pair<Int, Int> {
        // Check for <think> first (more specific), then think
        val idx1 = text.indexOf("<think>")
        if (idx1 >= 0) return idx1 to 7
        val idx2 = text.indexOf("think")
        if (idx2 >= 0) return idx2 to 5
        return -1 to 0
    }

    private fun findCloseTag(text: String): Pair<Int, Int> {
        // Check for </think> first, then /think
        val idx1 = text.indexOf("</think>")
        if (idx1 >= 0) return idx1 to 8
        val idx2 = text.indexOf("/think")
        if (idx2 >= 0) return idx2 to 6
        return -1 to 0
    }

    private fun maxTrailingPrefix(text: String, tags: List<String>): Int {
        var maxLen = 0
        for (tag in tags) {
            val maxCheck = minOf(text.length, tag.length - 1)
            for (len in maxCheck downTo 1) {
                if (text.endsWith(tag.substring(0, len))) {
                    maxLen = maxOf(maxLen, len)
                }
            }
        }
        return maxLen
    }

    companion object {
        private val OPEN_TAGS = listOf("<think>", "think")
        private val CLOSE_TAGS = listOf("</think>", "/think")
    }
}
