package com.promenar.nexara.data.rag

import com.promenar.nexara.data.local.db.dao.DocumentDao
import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.dao.VectorizationTaskDao
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity
import kotlinx.coroutines.*
import java.util.UUID

class VectorizationQueue(
    private val vectorStore: VectorStore,
    private val embeddingClient: EmbeddingClient,
    private val graphExtractor: GraphExtractor?,
    private val documentDao: DocumentDao,
    private val vectorDao: VectorDao,
    private val vectorizationTaskDao: VectorizationTaskDao,
    private val ragConfig: RagConfiguration = RagConfiguration()
) {
    private val queue = mutableListOf<VectorizationTask>()
    private var isProcessing = false
    private val retryCountMap = mutableMapOf<String, Int>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var onStateChange: ((List<VectorizationTask>, VectorizationTask?) -> Unit)? = null

    fun setOnStateChange(callback: (List<VectorizationTask>, VectorizationTask?) -> Unit) {
        onStateChange = callback
    }

    suspend fun enqueueDocument(
        docId: String,
        docTitle: String,
        content: String,
        kgStrategy: String? = null,
        skipVectorization: Boolean = false
    ) {
        val task = VectorizationTask(
            id = UUID.randomUUID().toString(),
            type = "document",
            docId = docId,
            docTitle = docTitle,
            status = "pending",
            kgStrategy = kgStrategy,
            skipVectorization = skipVectorization
        )

        queue.add(task)
        saveTaskToDb(task)
        notifyStateChange()

        if (!skipVectorization) {
            documentDao.updateVectorized(docId, 1)
        }

        if (!isProcessing) {
            scope.launch { processNext() }
        }
    }

    suspend fun enqueueMemory(
        sessionId: String,
        userContent: String,
        aiContent: String,
        userMessageId: String,
        assistantMessageId: String
    ) {
        val sanitize: (String) -> String = { text ->
            text.replace(Regex("!\\[.*?\\]\\(data:image/.*?;base64,.*?\\)"), "[Image]")
        }

        val task = VectorizationTask(
            id = UUID.randomUUID().toString(),
            type = "memory",
            sessionId = sessionId,
            userContent = sanitize(userContent),
            aiContent = sanitize(aiContent),
            userMessageId = userMessageId,
            assistantMessageId = assistantMessageId,
            status = "pending"
        )

        queue.add(task)
        saveTaskToDb(task)
        notifyStateChange()

        if (!isProcessing) {
            scope.launch { processNext() }
        }
    }

    private suspend fun processNext() {
        if (queue.isEmpty()) {
            isProcessing = false
            notifyStateChange()
            return
        }

        isProcessing = true
        val task = queue[0]

        try {
            task.status = "vectorizing"
            task.updatedAt = System.currentTimeMillis()
            saveTaskToDb(task)
            notifyStateChange()

            when (task.type) {
                "document" -> processDocumentTask(task)
                "memory" -> processMemoryTask(task)
            }

            if (task.status != "warning") {
                task.status = "completed"
            }
            retryCountMap.remove(task.id)
            task.progress = 100.0
            removeTaskFromDb(task.id)
        } catch (error: Exception) {
            val msg = error.message?.lowercase() ?: ""

            val isRetryable = msg.contains("network") ||
                msg.contains("timeout") ||
                msg.contains("fetch failed") ||
                msg.contains("5")

            val currentRetries = retryCountMap[task.id] ?: 0

            if (isRetryable && currentRetries < MAX_RETRIES) {
                retryCountMap[task.id] = currentRetries + 1
                task.subStatus = "Retrying (${currentRetries + 1}/$MAX_RETRIES)..."
                saveTaskToDb(task)
                notifyStateChange()

                val delay = minOf(3000L * (1L shl currentRetries), 15000L)
                delay(delay)
                processNext()
                return
            } else {
                retryCountMap.remove(task.id)
                task.status = "failed"
                task.error = getFriendlyErrorMessage(error)
                saveTaskToDb(task)

                if (task.type == "document" && task.docId != null && !task.skipVectorization) {
                    documentDao.updateVectorized(task.docId, -1)
                }
            }
        } finally {
            if (queue.isNotEmpty() && queue[0] === task) {
                queue.removeAt(0)
            }
            notifyStateChange()

            if (queue.isNotEmpty()) {
                scope.launch {
                    delay(500)
                    processNext()
                }
            } else {
                isProcessing = false
                cleanupCompletedTasks()
            }
        }
    }

    private suspend fun processDocumentTask(task: VectorizationTask) {
        val docId = task.docId ?: throw IllegalStateException("Document task missing docId")
        val doc = documentDao.getById(docId) ?: throw IllegalStateException("Document not found")
        val content = doc.content ?: throw IllegalStateException("Document has no content")

        val contentHash = simpleHash(content)
        if (!task.skipVectorization && ragConfig.enableIncrementalHash) {
            if (doc.contentHash == contentHash && doc.vectorized == 2) {
                task.status = "completed"
                task.progress = 100.0
                return
            }
        }

        var processedContent = content
        if (ragConfig.enableLocalPreprocess) {
            processedContent = preprocessText(content)
        }

        task.status = "chunking"
        task.progress = 10.0
        notifyStateChange()

        val splitter = TrigramTextSplitter(
            chunkSize = ragConfig.docChunkSize,
            chunkOverlap = ragConfig.chunkOverlap
        )
        val chunks = splitter.splitText(processedContent)
        task.totalChunks = chunks.size

        if (!task.skipVectorization) {
            task.status = "vectorizing"
            task.progress = 20.0
            notifyStateChange()

            val batchSize = 10
            val allEmbeddings = mutableListOf<FloatArray>()
            val startIndex = task.lastChunkIndex

            for (i in startIndex until chunks.size step batchSize) {
                val batch = chunks.subList(i, minOf(i + batchSize, chunks.size))
                val result = embeddingClient.embedDocuments(batch)
                allEmbeddings.addAll(result.embeddings)

                val completedChunks = minOf(i + batchSize, chunks.size)
                task.progress = 20.0 + (completedChunks.toDouble() / chunks.size) * 60.0
                task.lastChunkIndex = completedChunks
                task.subStatus = "Vectorizing $completedChunks/${chunks.size} chunks"
                task.updatedAt = System.currentTimeMillis()
                notifyStateChange()
            }

            task.status = "saving"
            task.progress = 85.0
            notifyStateChange()

            val vectors = chunks.mapIndexed { index, chunk ->
                VectorStore.NewVectorRecord(
                    docId = docId,
                    content = chunk,
                    embedding = allEmbeddings[index],
                    metadata = """{"source":"import","type":"doc","chunkIndex":$index}"""
                )
            }
            vectorStore.addVectorRecords(vectors)
            documentDao.updateVectorizationStatusWithHash(docId, 2, vectors.size, contentHash)
        } else {
            task.progress = 85.0
        }

        // Knowledge Graph extraction
        if (task.skipVectorization || ragConfig.enableKnowledgeGraph) {
            if (graphExtractor != null) {
                task.status = "extracting"
                task.progress = 85.0
                notifyStateChange()

                val strategy = task.kgStrategy ?: ragConfig.costStrategy
                var kgHasError = false

                when (strategy) {
                    "full" -> {
                        for ((k, chunk) in chunks.withIndex()) {
                            val kgResult = graphExtractor.extractAndSave(chunk, docId)
                            if (kgResult.error != null) kgHasError = true
                            task.progress = 85.0 + ((k + 1).toDouble() / chunks.size) * 15.0
                            task.subStatus = "KG extraction ${k + 1}/${chunks.size} chunks"
                            task.updatedAt = System.currentTimeMillis()
                            notifyStateChange()
                        }
                    }
                    "summary-first" -> {
                        val sample = listOfNotNull(
                            chunks.getOrNull(0),
                            chunks.getOrNull(chunks.size / 2),
                            chunks.lastOrNull()
                        )
                        for ((s, sampleText) in sample.withIndex()) {
                            val kgResult = graphExtractor.extractAndSave(sampleText, docId)
                            if (kgResult.error != null) kgHasError = true
                            task.progress = 85.0 + ((s + 1).toDouble() / sample.size) * 15.0
                            task.subStatus = "KG extraction ${s + 1}/${sample.size} chunks (summary)"
                            task.updatedAt = System.currentTimeMillis()
                            notifyStateChange()
                        }
                    }
                }

                if (kgHasError) {
                    task.status = "warning"
                    task.subStatus = "Vectorization succeeded, KG extraction partially failed"
                    notifyStateChange()
                }
            }
        }
    }

    private suspend fun processMemoryTask(task: VectorizationTask) {
        val sessionId = task.sessionId ?: throw IllegalStateException("Memory task missing sessionId")
        val userContent = task.userContent ?: throw IllegalStateException("Memory task missing userContent")
        val aiContent = task.aiContent ?: throw IllegalStateException("Memory task missing aiContent")

        val turnText = "User: $userContent\nAssistant: $aiContent"

        task.status = "chunking"
        task.progress = 20.0
        notifyStateChange()

        val splitter = TrigramTextSplitter(
            chunkSize = ragConfig.memoryChunkSize,
            chunkOverlap = ragConfig.chunkOverlap
        )
        val chunks = splitter.splitText(turnText)
        task.totalChunks = chunks.size

        task.status = "vectorizing"
        task.progress = 40.0
        notifyStateChange()

        val embeddingResult = embeddingClient.embedDocuments(chunks)

        task.status = "saving"
        task.progress = 80.0
        notifyStateChange()

        val vectors = chunks.mapIndexed { i, chunk ->
            VectorStore.NewVectorRecord(
                sessionId = sessionId,
                content = chunk,
                embedding = embeddingResult.embeddings[i],
                metadata = """{"type":"memory","chunkIndex":$i}""",
                startMessageId = task.userMessageId,
                endMessageId = task.assistantMessageId
            )
        }
        vectorStore.addVectorRecords(vectors)
    }

    fun getQueueLength(): Int = queue.size

    fun getState(): QueueState {
        return QueueState(
            queue = queue.toList(),
            currentTask = queue.firstOrNull(),
            isProcessing = isProcessing
        )
    }

    fun cancel(docId: String) {
        queue.removeAll { it.docId == docId }
        notifyStateChange()
    }

    fun clear() {
        queue.clear()
        isProcessing = false
        notifyStateChange()
    }

    private suspend fun saveTaskToDb(task: VectorizationTask) {
        try {
            vectorizationTaskDao.insert(
                VectorizationTaskEntity(
                    id = task.id,
                    type = task.type,
                    status = task.status,
                    docId = task.docId,
                    docTitle = task.docTitle,
                    sessionId = task.sessionId,
                    userContent = task.userContent,
                    aiContent = task.aiContent,
                    userMessageId = task.userMessageId,
                    assistantMessageId = task.assistantMessageId,
                    lastChunkIndex = task.lastChunkIndex,
                    totalChunks = task.totalChunks,
                    progress = task.progress,
                    error = task.error,
                    createdAt = task.createdAt,
                    updatedAt = task.updatedAt
                )
            )
        } catch (e: Exception) {
            // Persistence failure is non-critical
        }
    }

    private suspend fun removeTaskFromDb(taskId: String) {
        try {
            val task = vectorizationTaskDao.getById(taskId) ?: return
            vectorizationTaskDao.delete(task)
        } catch (e: Exception) {
            // Non-critical
        }
    }

    suspend fun cleanupCompletedTasks() {
        try {
            vectorizationTaskDao.deleteCompletedTasks()
        } catch (e: Exception) {
            // Non-critical
        }
    }

    suspend fun resumeInterruptedTasks() {
        try {
            vectorizationTaskDao.markStaleAsInterrupted(System.currentTimeMillis() - 30_000)
            val interruptedTasks = vectorizationTaskDao.getRecoverableTasks()

            if (interruptedTasks.isEmpty()) return

            val tasks = interruptedTasks.map { entity ->
                VectorizationTask(
                    id = entity.id,
                    type = entity.type,
                    docId = entity.docId,
                    docTitle = entity.docTitle,
                    sessionId = entity.sessionId,
                    userContent = entity.userContent,
                    aiContent = entity.aiContent,
                    userMessageId = entity.userMessageId,
                    assistantMessageId = entity.assistantMessageId,
                    status = entity.status,
                    progress = entity.progress,
                    totalChunks = entity.totalChunks,
                    lastChunkIndex = entity.lastChunkIndex,
                    error = entity.error,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt
                )
            }

            queue.addAll(0, tasks)
            notifyStateChange()

            if (!isProcessing) {
                scope.launch { processNext() }
            }
        } catch (e: Exception) {
            // Non-critical
        }
    }

    private fun notifyStateChange() {
        onStateChange?.invoke(queue.toList(), queue.firstOrNull())
    }

    private fun simpleHash(str: String): String {
        var hash = 0
        for (c in str) {
            hash = (hash shl 5) - hash + c.code
            hash = hash and hash
        }
        return hash.toString(36) + str.length.toString(36)
    }

    private fun preprocessText(text: String): String {
        return text.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()
    }

    private fun getFriendlyErrorMessage(error: Throwable): String {
        val msg = error.message?.lowercase() ?: ""
        return when {
            msg.contains("api key") || msg.contains("401") -> "API key invalid"
            msg.contains("quota") || msg.contains("429") -> "Quota exceeded"
            msg.contains("network") || msg.contains("timeout") -> "Network error"
            msg.contains("no embedding") -> "No embedding model configured"
            else -> "Failed: ${(error.message ?: "Unknown").take(50)}"
        }
    }

    data class QueueState(
        val queue: List<VectorizationTask>,
        val currentTask: VectorizationTask?,
        val isProcessing: Boolean
    )

    companion object {
        private const val MAX_RETRIES = 3
    }
}
