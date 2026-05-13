package com.promenar.nexara.domain.model

data class Document(
    val id: String,
    val folderId: String,
    val title: String,
    val content: String,
    val summary: String? = null,
    val hash: String = "",
    val source: String? = null,
    val fileSize: Long? = null,
    val vectorized: Int = 0,
    val chunkSize: Int = 500,
    val chunkOverlap: Int = 50,
    val vectorizedAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long? = null
)
