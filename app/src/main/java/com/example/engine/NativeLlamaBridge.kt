package com.example.engine

internal object NativeLlamaBridge {
    init { System.loadLibrary("ayroff_llama") }

    fun interface TokenCallback {
        fun onToken(piece: String)
    }

    external fun nativeLoadModel(path: String, contextLength: Int, batchSize: Int, threads: Int,
        gpuLayers: Int, useMmap: Boolean, useMlock: Boolean): Long
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float,
        topP: Float, topK: Int, repeatPenalty: Float, callback: TokenCallback): Int
    external fun nativeStop(handle: Long)
    external fun nativeUnloadModel(handle: Long)
}
