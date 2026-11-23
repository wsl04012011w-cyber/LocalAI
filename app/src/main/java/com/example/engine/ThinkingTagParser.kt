package com.example.engine

/**
 * Fallback for templates where the native chat parser leaves visible thinking tags.
 * Handles tags split across individual llama.cpp token pieces.
 */
internal data class ParsedOutputSegment(val isThinking: Boolean, val text: String)

internal class ThinkingTagParser {
    private val pending = StringBuilder()
    private var insideThinking = false

    fun push(chunk: String): List<ParsedOutputSegment> {
        if (chunk.isNotEmpty()) pending.append(chunk)
        return drain(final = false)
    }

    fun finish(): List<ParsedOutputSegment> = drain(final = true)

    private fun drain(final: Boolean): List<ParsedOutputSegment> {
        val result = mutableListOf<ParsedOutputSegment>()
        while (pending.isNotEmpty()) {
            val marker = if (insideThinking) "</think>" else "<think>"
            val markerIndex = pending.indexOf(marker)
            if (markerIndex >= 0) {
                if (markerIndex > 0) result += ParsedOutputSegment(insideThinking, pending.substring(0, markerIndex))
                pending.delete(0, markerIndex + marker.length)
                insideThinking = !insideThinking
                continue
            }
            if (final) {
                result += ParsedOutputSegment(insideThinking, pending.toString())
                pending.clear()
                break
            }
            val safeLength = pending.length - longestMarkerPrefixSuffix(pending, marker)
            if (safeLength > 0) {
                result += ParsedOutputSegment(insideThinking, pending.substring(0, safeLength))
                pending.delete(0, safeLength)
            }
            break
        }
        return result.filter { it.text.isNotEmpty() }
    }

    private fun longestMarkerPrefixSuffix(buffer: StringBuilder, marker: String): Int {
        val max = minOf(buffer.length, marker.length - 1)
        for (length in max downTo 1) {
            val start = buffer.length - length
            var matches = true
            for (offset in 0 until length) {
                if (buffer[start + offset] != marker[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return length
        }
        return 0
    }
}
