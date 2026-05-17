package com.promenar.nexara.data.remote.middleware

import com.promenar.nexara.data.remote.protocol.StreamChunk

class LlmMiddlewareChain(
    middlewares: List<LlmMiddleware>
) {
    private val middlewares: List<LlmMiddleware>

    init {
        val order = mapOf(
            MiddlewareEnforce.PRE to 0,
            MiddlewareEnforce.NORMAL to 1,
            MiddlewareEnforce.POST to 2
        )
        this.middlewares = middlewares.sortedBy { order[it.enforce] ?: 1 }
    }

    suspend fun onRequestStart(params: StreamTextParams) {
        for (mw in middlewares) mw.onRequestStart(params)
    }

    suspend fun transformParams(params: StreamTextParams): StreamTextParams {
        var result = params
        for (mw in middlewares) result = mw.transformParams(result)
        return result
    }

    suspend fun transformStreamChunk(
        rawChunk: StreamChunk,
        emitter: suspend (StreamChunk) -> Unit
    ) {
        val chain = middlewares.foldRight(emitter) { mw, next ->
            { chunk: StreamChunk -> mw.transformStreamChunk(chunk, next) }
        }
        chain(rawChunk)
    }

    suspend fun onRequestEnd(params: StreamTextParams) {
        for (mw in middlewares.reversed()) mw.onRequestEnd(params)
    }
}
