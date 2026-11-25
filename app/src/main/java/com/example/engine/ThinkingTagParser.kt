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
                    val startIdx = current.indexOf(OPEN_TAG)
                    if (startIdx >= 0) {
                        if (startIdx > 0) {
                            contentOut.append(current.substring(0, startIdx))
                        }
                        pendingBuffer.delete(0, startIdx + OPEN_TAG.length)
                        mode = Mode.IN_THINK_TAG
                        entered = true
                    } else {
                        val partialMatch = trailingPrefixLength(current, OPEN_TAG)
                        val safeLen = current.length - partialMatch
                        if (safeLen > 0) {
                            contentOut.append(current.substring(0, safeLen))
                            pendingBuffer.delete(0, safeLen)
                        }
                        break
                    }
                }

                Mode.IN_THINK_TAG -> {
                    val endIdx = current.indexOf(CLOSE_TAG)
                    if (endIdx >= 0) {
                        if (endIdx > 0) {
                            thinkingOut.append(current.substring(0, endIdx))
                        }
                        pendingBuffer.delete(0, endIdx + CLOSE_TAG.length)
                        mode = Mode.BEFORE_OR_CONTENT
                        exited = true
                    } else {
                        val partialMatch = trailingPrefixLength(current, CLOSE_TAG)
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

    private fun trailingPrefixLength(text: String, tag: String): Int {
        val maxCheck = minOf(text.length, tag.length - 1)
        for (len in maxCheck downTo 1) {
            if (text.endsWith(tag.substring(0, len))) {
                return len
            }
        }
        return 0
    }

    companion object {
        private const val OPEN_TAG = "<think>"
        private const val CLOSE_TAG = "</think>"
    }
}
