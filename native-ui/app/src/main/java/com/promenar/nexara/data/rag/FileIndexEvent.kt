package com.promenar.nexara.data.rag

sealed interface FileIndexEvent {
    val workspaceRootUuid: String
    val fileUuid: String
    val contentHash: String?

    data class Changed(
        override val workspaceRootUuid: String,
        override val fileUuid: String,
        override val contentHash: String,
        val skipVectorization: Boolean = false,
        val kgStrategy: String? = null,
        val useConfiguredKgStrategy: Boolean = true,
    ) : FileIndexEvent

    data class Deleted(
        override val workspaceRootUuid: String,
        override val fileUuid: String,
    ) : FileIndexEvent {
        override val contentHash: String? = null
    }
}

fun interface FileIndexEventSink {
    suspend fun publish(event: FileIndexEvent)

    companion object {
        val None = FileIndexEventSink { }
    }
}
