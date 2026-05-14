package com.promenar.nexara.domain.repository

import com.promenar.nexara.domain.model.Document
import kotlinx.coroutines.flow.Flow

interface IDocumentRepository {
    fun observeAll(): Flow<List<Document>>
    fun observeByFolder(folderId: String): Flow<List<Document>>
    suspend fun getById(id: String): Document?
    suspend fun getByFolderId(folderId: String): List<Document>
    suspend fun getCount(): Int
    suspend fun countByFolderId(folderId: String): Int
    suspend fun import(path: String, folderId: String): Document
    suspend fun update(id: String, content: String)
    suspend fun delete(id: String)
    suspend fun markVectorized(id: String)
    suspend fun updateTitle(id: String, title: String)
}
