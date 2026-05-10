package com.promenar.nexara.data.local.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class LlamaContext private constructor(
    private val nativePtr: Long,
    val modelPath: String,
    val contextSize: Int,
    val gpuAccelerated: Boolean
) {
    companion object {
        init {
            System.loadLibrary("nexara_llama")
        }

        suspend fun load(
            modelPath: String,
            contextSize: Int = 2048,
            nThreads: Int = GpuDetector.recommendedThreadCount(),
            useGpu: Boolean = false
        ): LlamaContext? = withContext(Dispatchers.IO) {
            val ptr = nativeLoadModel(modelPath, contextSize, nThreads, useGpu)
            if (ptr == 0L) null
            else LlamaContext(ptr, modelPath, contextSize, useGpu)
        }

        private external fun nativeLoadModel(
            modelPath: String, nCtx: Int, nThreads: Int, useGpu: Boolean
        ): Long
    }

    suspend fun tokenize(text: String, addBos: Boolean = true): IntArray =
        withContext(Dispatchers.IO) {
            nativeTokenize(nativePtr, text, addBos)
        }

    suspend fun detokenize(tokens: IntArray): String =
        withContext(Dispatchers.IO) {
            nativeDetokenize(nativePtr, tokens)
        }

    suspend fun ingestPrompt(tokens: IntArray) {
        withContext(Dispatchers.IO) {
            nativeIngestPrompt(nativePtr, tokens)
        }
    }

    suspend fun sample(): Int = withContext(Dispatchers.IO) {
        nativeSample(nativePtr)
    }

    suspend fun decode(token: Int): Int = withContext(Dispatchers.IO) {
        nativeDecode(nativePtr, token)
    }

    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        nativeEmbed(nativePtr, text)
    }

    suspend fun getEosToken(): Int = withContext(Dispatchers.IO) {
        nativeGetEosToken(nativePtr)
    }

    fun clear() {
        if (nativePtr != 0L) {
            nativeClear(nativePtr)
        }
    }

    fun release() {
        if (nativePtr != 0L) {
            nativeFree(nativePtr)
        }
    }

    protected fun finalize() {
        release()
    }

    fun generate(prompt: String, maxTokens: Int = 256): Flow<String> = flow {
        clear()

        val promptTokens = tokenize(prompt, addBos = true)
        if (promptTokens.isEmpty()) return@flow

        ingestPrompt(promptTokens)

        val eosToken = getEosToken()
        var currentToken = sample()
        if (currentToken < 0) return@flow

        for (i in 0 until maxTokens) {
            if (currentToken == eosToken) break

            val text = detokenize(intArrayOf(currentToken))
            if (text.isNotEmpty()) emit(text)

            currentToken = decode(currentToken)
            if (currentToken < 0) break
        }
    }

    private external fun nativeTokenize(ptr: Long, text: String, addBos: Boolean): IntArray
    private external fun nativeDetokenize(ptr: Long, tokens: IntArray): String
    private external fun nativeIngestPrompt(ptr: Long, tokens: IntArray)
    private external fun nativeSample(ptr: Long): Int
    private external fun nativeDecode(ptr: Long, token: Int): Int
    private external fun nativeEmbed(ptr: Long, text: String): FloatArray
    private external fun nativeGetEosToken(ptr: Long): Int
    private external fun nativeClear(ptr: Long)
    private external fun nativeFree(ptr: Long)
}
