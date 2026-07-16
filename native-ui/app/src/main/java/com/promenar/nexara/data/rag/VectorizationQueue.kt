package com.promenar.nexara.data.rag

import android.util.Log
import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.dao.VectorizationTaskDao
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.UUID

class VectorizationQueue(
    private val vectorStore: VectorStore,
    private val embeddingClient: EmbeddingClient,
    private val graphExtractor: GraphExtractor?,
    private val vectorDao: VectorDao,
    private val vectorizationTaskDao: VectorizationTaskDao,
    private val ragConfig: RagConfiguration = RagConfiguration(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val fileEntryDao: com.promenar.nexara.data.local.db.dao.FileEntryDao? = null,
    private val documentIndexService: DocumentIndexService? = null,
) : FileIndexEventSink {
    private val queue = mutableListOf<VectorizationTask>()
    private val retainedAttention = mutableListOf<VectorizationTask>()
    @Volatile private var isProcessing = false
    private val queueLock = Any()
    private val retryCountMap = mutableMapOf<String, Int>()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val enqueueMutex = Mutex()
    private val _state = MutableStateFlow(QueueState(emptyList(), null, false, restored = false))
    val state: StateFlow<QueueState> = _state.asStateFlow()
    @Volatile private var restored = false

    private var onStateChange: ((List<VectorizationTask>, VectorizationTask?) -> Unit)? = null

    fun setOnStateChange(callback: (List<VectorizationTask>, VectorizationTask?) -> Unit) {
        onStateChange = callback
        val current = snapshotState()
        callback(current.queue, current.currentTask)
    }

    override suspend fun publish(event: FileIndexEvent) {
        when (event) {
            is FileIndexEvent.Changed -> {
                val dao = requireNotNull(fileEntryDao) { "文件索引 DAO 未配置" }
                val entry = dao.getByUuid(event.workspaceRootUuid, event.fileUuid)
                    ?: throw java.io.FileNotFoundException("索引文件不存在")
                enqueueDocumentReference(
                    workspaceRootUuid = event.workspaceRootUuid,
                    docId = event.fileUuid,
                    docTitle = entry.name,
                    sourceMimeType = entry.mimeType ?: "application/octet-stream",
                    kgStrategy = event.kgStrategy
                        ?: "full".takeIf { event.useConfiguredKgStrategy && ragConfig.enableKnowledgeGraph },
                    skipVectorization = event.skipVectorization,
                )
            }
            is FileIndexEvent.Deleted -> {
                cancel(event.fileUuid)
                val service = requireNotNull(documentIndexService) { "文档索引服务未配置" }
                when (val result = service.delete(event.workspaceRootUuid, event.fileUuid)) {
                    is DocumentIndexResult.Deleted -> Unit
                    is DocumentIndexResult.Failed -> throw result.failure
                    else -> error("删除索引返回了无效状态: $result")
                }
            }
        }
    }

    suspend fun enqueueDocument(
        workspaceRootUuid: String,
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
            workspaceRootUuid = workspaceRootUuid,
            status = "pending",
            kgStrategy = kgStrategy,
            skipVectorization = skipVectorization,
            userContent = content
        )

        saveTaskToDb(task)
        synchronized(queueLock) { queue.add(task) }
        notifyStateChange()

        startProcessorIfNeeded()
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

        saveTaskToDb(task)
        synchronized(queueLock) { queue.add(task) }
        notifyStateChange()

        startProcessorIfNeeded()
    }

    /** 先持久化文件引用任务，再进入内存队列；返回即表示进程死亡后可恢复。 */
    suspend fun enqueueDocumentReference(
        workspaceRootUuid: String,
        docId: String,
        docTitle: String,
        sourceMimeType: String,
        kgStrategy: String? = null,
        skipVectorization: Boolean = false,
    ): String = enqueueMutex.withLock {
        require(sourceMimeType in DocumentReferenceExtractor.SUPPORTED_MIME_TYPES) { "不支持索引 MIME" }
        val id = deterministicReferenceTaskId(workspaceRootUuid, docId)
        val existing = vectorizationTaskDao.getByWorkspaceFile(workspaceRootUuid, docId, TYPE_DOCUMENT_REFERENCE)
        if (existing != null) {
            if (existing.status !in setOf("failed", "partial", "interrupted")) return@withLock existing.id
            val retried = existing.copy(
                status = "pending",
                progress = 0.0,
                error = null,
                subStatus = "内容已变化，等待重新索引",
                kgStrategy = kgStrategy,
                skipVectorization = skipVectorization,
                sourceMimeType = sourceMimeType,
                contentTruncated = false,
                updatedAt = System.currentTimeMillis(),
            )
            check(vectorizationTaskDao.update(retried) == 1) { "旧索引任务已不存在，无法重新排队" }
            val task = retried.toTask()
            synchronized(queueLock) {
                retainedAttention.removeAll { it.id == task.id }
                queue.removeAll { it.id == task.id }
                queue.add(task)
            }
            notifyStateChange()
            startProcessorIfNeeded()
            return@withLock existing.id
        }
        val task = VectorizationTask(
            id = id,
            type = TYPE_DOCUMENT_REFERENCE,
            docId = docId,
            docTitle = docTitle,
            workspaceRootUuid = workspaceRootUuid,
            status = "pending",
            kgStrategy = kgStrategy,
            skipVectorization = skipVectorization,
            sourceMimeType = sourceMimeType,
            userContent = null,
        )
        val inserted = vectorizationTaskDao.insertIgnore(task.toEntity())
        if (inserted == -1L) {
            return@withLock checkNotNull(
                vectorizationTaskDao.getByWorkspaceFile(workspaceRootUuid, docId, TYPE_DOCUMENT_REFERENCE)
            ).id
        }
        synchronized(queueLock) { queue.add(task) }
        notifyStateChange()
        startProcessorIfNeeded()
        id
    }

    suspend fun retryDocumentReference(workspaceRootUuid: String, docId: String): Boolean =
        enqueueMutex.withLock {
            val existing = vectorizationTaskDao.getByWorkspaceFile(
                workspaceRootUuid,
                docId,
                TYPE_DOCUMENT_REFERENCE,
            ) ?: return@withLock false
            if (existing.status !in setOf("failed", "partial", "interrupted")) return@withLock false
            val retried = existing.copy(
                status = "pending",
                progress = 0.0,
                error = null,
                subStatus = "等待重试",
                contentTruncated = false,
                updatedAt = System.currentTimeMillis(),
            )
            check(vectorizationTaskDao.update(retried) == 1) { "旧索引任务已不存在，无法重试" }
            val task = retried.toTask()
            synchronized(queueLock) {
                retainedAttention.removeAll { it.id == task.id }
                if (queue.none { it.id == task.id }) queue.add(task)
            }
            notifyStateChange()
            startProcessorIfNeeded()
            true
        }

    private suspend fun processNext() {
        val task = synchronized(queueLock) { queue.firstOrNull() }
        if (task == null) {
            isProcessing = false
            notifyStateChange()
            return
        }

        isProcessing = true

        var delegatedRetry = false
        var processingCancelled = false
        try {
            // 不在此处预设状态，交由具体处理函数逐步推进——
            // 避免 "vectorizing(0%)→chunking(15%)→vectorizing(30%)" 的进度回跳
            task.updatedAt = System.currentTimeMillis()
            NexaraLogger.log("[VectorQueue] 开始处理任务 type=${task.type} docTitle=${task.docTitle ?: "N/A"}")

            when (task.type) {
                "memory" -> processMemoryTask(task)
                "document" -> processDocumentTask(task)
                TYPE_DOCUMENT_REFERENCE -> processDocumentReferenceTask(task)
            }

            task.status = if (task.contentTruncated) "partial" else "completed"
            retryCountMap.remove(task.id)
            task.progress = 100.0
            task.subStatus = if (task.contentTruncated) "已完成安全前缀索引（内容截断）" else "处理完成"
            saveTaskToDb(task)
            NexaraLogger.log("[VectorQueue] 任务完成 type=${task.type} docTitle=${task.docTitle ?: "N/A"}")
            notifyStateChange()
            if (!task.contentTruncated) removeTaskFromDb(task.id)
        } catch (cancelled: CancellationException) {
            // 取消不是业务失败：不标 failed、不内部重试。当前持久阶段保留，由启动恢复标记 interrupted 后接管。
            retryCountMap.remove(task.id)
            processingCancelled = true
            throw cancelled
        } catch (error: Exception) {
            NexaraLogger.logError("VectorQueue.processNext", error)

            val msg = error.message?.lowercase() ?: ""

            val isRetryable = msg.contains("network") ||
                msg.contains("timeout") ||
                msg.contains("fetch failed") ||
                msg.contains("5")

            val currentRetries = retryCountMap[task.id] ?: 0

            // 保留失败的阶段信息，避免进度掉回 0
            val failedStep = task.status
            val failedProgress = task.progress

            if (isRetryable && currentRetries < MAX_RETRIES) {
                retryCountMap[task.id] = currentRetries + 1
                task.subStatus = "正在重试 (${currentRetries + 1}/$MAX_RETRIES)..."
                task.error = getFriendlyErrorMessage(error)
                NexaraLogger.log("[VectorQueue] 可重试错误 (${currentRetries + 1}/$MAX_RETRIES): ${error.message?.take(100)}")
                saveTaskToDb(task)
                notifyStateChange()

                val delayMs = minOf(3000L * (1L shl currentRetries), 15000L)
                delay(delayMs)
                delegatedRetry = true
                processNext()
                return
            } else {
                retryCountMap.remove(task.id)
                task.status = "failed"
                task.error = getFriendlyErrorMessage(error)
                // 保留失败时的进度，不下掉到 0
                task.progress = failedProgress
                task.subStatus = "失败于阶段: $failedStep — ${getFriendlyErrorMessage(error)}"
                NexaraLogger.log("[VectorQueue] 任务彻底失败 type=${task.type} step=$failedStep error=${task.error}")
                saveTaskToDb(task)

                notifyStateChange()
                // 失败后暂停 2 秒再移除，让用户看清错误信息
                delay(2000)
            }
        } finally {
            if (!delegatedRetry) {
                synchronized(queueLock) {
                    if (task.status in setOf("failed", "partial") && retainedAttention.none { it.id == task.id }) {
                        retainedAttention.add(task)
                    }
                    if (queue.firstOrNull() === task) queue.removeAt(0)
                }
                notifyStateChange()

                if (processingCancelled) {
                    isProcessing = false
                    notifyStateChange()
                } else if (synchronized(queueLock) { queue.isNotEmpty() }) {
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
    }

    private suspend fun processMemoryTask(task: VectorizationTask) {
        val sessionId = task.sessionId ?: throw IllegalStateException("Memory task missing sessionId")
        val userContent = task.userContent ?: throw IllegalStateException("Memory task missing userContent")
        val aiContent = task.aiContent ?: throw IllegalStateException("Memory task missing aiContent")

        val turnText = "User: $userContent\nAssistant: $aiContent"

        task.status = "chunking"
        task.progress = 20.0
        task.subStatus = "正在对对话记忆进行语义切块..."
        notifyStateChange()

        val splitter = TrigramTextSplitter(
            chunkSize = ragConfig.memoryChunkSize,
            chunkOverlap = ragConfig.chunkOverlap
        )
        val chunks = splitter.splitText(turnText)
        task.totalChunks = chunks.size
        NexaraLogger.log("[VectorQueue] Memory chunking: ${chunks.size} chunks from session=$sessionId")

        task.status = "vectorizing"
        task.progress = 40.0
        task.subStatus = "正在发送 ${chunks.size} 个记忆切块至模型处理..."
        notifyStateChange()

        NexaraLogger.log("[VectorQueue] Memory embedding: ${chunks.size} chunks")
        val embeddingResult = embeddingClient.embedDocuments(chunks)

        task.status = "saving"
        task.progress = 80.0
        task.subStatus = "正在持久化记忆向量数据..."
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
        NexaraLogger.log("[VectorQueue] Memory saved: ${vectors.size} vectors for session=$sessionId")
    }

    private suspend fun processDocumentTask(task: VectorizationTask) {
        val content = task.userContent
            ?: throw IllegalStateException("Document task missing content")
        task.status = "chunking"
        task.progress = 15.0
        task.subStatus = "正在对文档进行语义切块..."
        notifyStateChange()
        val splitter = TrigramTextSplitter(
            chunkSize = ragConfig.docChunkSize,
            chunkOverlap = ragConfig.chunkOverlap
        )
        val chunks = splitter.splitText(content)
        processDocumentChunks(task, chunks, content)
    }

    private suspend fun processDocumentReferenceTask(task: VectorizationTask) {
        val workspaceRootUuid = task.workspaceRootUuid
            ?: throw IllegalStateException("Document reference missing workspaceRootUuid")
        val docId = task.docId ?: throw IllegalStateException("Document reference missing docId")
        val entry = fileEntryDao?.getByUuid(workspaceRootUuid, docId)
            ?: throw IllegalStateException("Document reference file missing")
        if (entry.mimeType != task.sourceMimeType) throw SecurityException("索引任务 MIME 与文件不一致")
        documentIndexService?.let { service ->
            when (val result = service.rebuild(FileIndexEvent.Changed(
                workspaceRootUuid = workspaceRootUuid,
                fileUuid = docId,
                contentHash = entry.hash,
                skipVectorization = task.skipVectorization,
                kgStrategy = task.kgStrategy,
                useConfiguredKgStrategy = false,
            ))) {
                is DocumentIndexResult.Rebuilt -> return
                is DocumentIndexResult.HashChanged -> throw java.util.ConcurrentModificationException(
                    "索引期间文件内容已变化: ${result.currentHash ?: "deleted"}",
                )
                is DocumentIndexResult.Failed -> throw result.failure
                is DocumentIndexResult.Deleted -> throw IllegalStateException("索引文件已删除")
            }
        }
        task.status = "extracting_source"
        task.progress = 8.0
        task.subStatus = "正在安全读取工作区文件..."
        saveTaskToDb(task)
        notifyStateChange()
        val extraction = DocumentReferenceExtractor(
            chunkSize = ragConfig.docChunkSize,
            chunkOverlap = ragConfig.chunkOverlap,
        ).extract(entry)
        task.contentTruncated = extraction.truncated
        if (extraction.truncated) task.subStatus = "内容超过 16 MiB，已索引安全前缀"
        processDocumentChunks(task, extraction.chunks, extraction.graphText)
    }

    private suspend fun processDocumentChunks(
        task: VectorizationTask,
        chunks: List<String>,
        graphText: String,
    ) {
        val docId = task.docId ?: throw IllegalStateException("Document task missing docId")
        val docTitle = task.docTitle ?: "Untitled"
        task.totalChunks = chunks.size
        task.status = "chunking"
        task.progress = 15.0
        task.subStatus = if (task.contentTruncated) "已截断至 16 MiB 安全前缀，共 ${chunks.size} 个切块"
        else "已生成 ${chunks.size} 个文档切块"
        saveTaskToDb(task)
        NexaraLogger.log("[VectorQueue] Document chunking: ${chunks.size} chunks for doc=$docTitle")

        if (chunks.isEmpty()) {
            NexaraLogger.log("[VectorQueue] Document has no chunkable content, skipping: $docTitle")
            task.status = "completed"
            task.progress = 100.0
            task.subStatus = if (task.contentTruncated) "部分内容可索引" else "文档无可索引内容"
            saveTaskToDb(task)
            notifyStateChange()
            updateFileEntryVectorizedAt(
                task.workspaceRootUuid ?: throw SecurityException("Document task missing workspaceRootUuid"),
                docId,
                System.currentTimeMillis(),
            )
            return
        }

        if (!task.skipVectorization) {
            task.status = "vectorizing"
            task.progress = 30.0
            task.subStatus = "正在发送 ${chunks.size} 个切块至模型处理..."
            saveTaskToDb(task)
            notifyStateChange()

            NexaraLogger.log("[VectorQueue] Document embedding: ${chunks.size} chunks for doc=$docTitle")
            val embeddingResult = embeddingClient.embedDocuments(chunks)

            task.status = "saving"
            task.progress = 70.0
            task.subStatus = "正在持久化向量数据..."
            saveTaskToDb(task)
            notifyStateChange()

            val records = chunks.mapIndexed { i, chunk ->
                VectorStore.NewVectorRecord(
                    docId = docId,
                    sessionId = null,
                    content = chunk,
                    embedding = embeddingResult.embeddings[i],
                    metadata = documentVectorMetadata(docId, i, docTitle),
                    startMessageId = docId,
                    endMessageId = docId
                )
            }
            vectorStore.addVectorRecords(records)
            NexaraLogger.log("[VectorQueue] Document vectors saved: ${records.size} vectors for doc=$docTitle")
        } else {
            task.subStatus = "已按配置跳过向量化，继续处理知识图谱"
        }

        task.progress = 95.0
        task.subStatus = "正在更新文件索引状态..."
        notifyStateChange()

        updateFileEntryVectorizedAt(
            task.workspaceRootUuid ?: throw SecurityException("Document task missing workspaceRootUuid"),
            docId,
            System.currentTimeMillis(),
        )

        // 可选：知识图谱提取
        if (graphExtractor != null && task.kgStrategy != null) {
            task.status = "extracting"
            task.progress = 98.0
            task.subStatus = "正在构建知识图谱节点..."
            notifyStateChange()
            NexaraLogger.log("[VectorQueue] KG extraction starting for doc=$docTitle")
            try {
                graphExtractor.extractAndSave(graphText, docId)
                NexaraLogger.log("[VectorQueue] KG extraction completed for doc=$docTitle")
            } catch (e: Exception) {
                NexaraLogger.logError("VectorQueue.KGExtraction", e)
                task.subStatus = "知识图谱提取跳过（非致命）"
                saveTaskToDb(task)
            }
        }
    }

    /** 更新 FileEntry 的 vectorizedAt 时间戳 */
    private suspend fun updateFileEntryVectorizedAt(workspaceRootUuid: String, docId: String, timestamp: Long) {
        val dao = fileEntryDao ?: throw IllegalStateException("文件索引 DAO 未配置")
        val entry = dao.getByUuid(workspaceRootUuid, docId)
            ?: throw IllegalStateException("索引文件不存在")
        dao.update(entry.copy(vectorizedAt = timestamp, updatedAt = timestamp))
    }

    fun getQueueLength(): Int = synchronized(queueLock) { queue.size }

    fun snapshotState(): QueueState {
        val (snapshot, current) = synchronized(queueLock) {
            (queue + retainedAttention) to (queue.firstOrNull() ?: retainedAttention.firstOrNull())
        }
        return QueueState(
            queue = snapshot,
            currentTask = current,
            isProcessing = isProcessing,
            restored = restored,
        )
    }

    fun cancel(docId: String) {
        synchronized(queueLock) {
            queue.removeAll { it.docId == docId }
            retainedAttention.removeAll { it.docId == docId }
        }
        notifyStateChange()
    }

    fun clear() {
        synchronized(queueLock) {
            queue.clear()
            retainedAttention.clear()
        }
        isProcessing = false
        notifyStateChange()
    }

    fun shutdown() {
        scope.cancel()
        clear()
    }

    suspend fun shutdownForReplacement() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        vectorizationTaskDao.markProcessingAsInterrupted(System.currentTimeMillis())
        clear()
    }

    private suspend fun saveTaskToDb(task: VectorizationTask) {
        task.updatedAt = System.currentTimeMillis()
        vectorizationTaskDao.insert(task.toEntity())
    }

    private fun VectorizationTask.toEntity() = VectorizationTaskEntity(
        id = id,
        type = type,
        status = status,
        docId = docId,
        docTitle = docTitle,
        workspaceRootUuid = workspaceRootUuid,
        sessionId = if (type == "memory") sessionId else null,
        userContent = userContent,
        aiContent = aiContent,
        userMessageId = userMessageId,
        assistantMessageId = assistantMessageId,
        lastChunkIndex = lastChunkIndex,
        totalChunks = totalChunks,
        progress = progress,
        error = error,
        kgStrategy = kgStrategy,
        skipVectorization = skipVectorization,
        subStatus = subStatus,
        sourceMimeType = sourceMimeType,
        contentTruncated = contentTruncated,
        targetContentHash = targetContentHash,
        targetEpoch = targetEpoch,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private suspend fun removeTaskFromDb(taskId: String) {
        val task = vectorizationTaskDao.getById(taskId) ?: return
        vectorizationTaskDao.delete(task)
    }

    suspend fun cleanupCompletedTasks() {
        vectorizationTaskDao.deleteCompletedTasks()
    }

    suspend fun resumeInterruptedTasks(): Result<Unit> = try {
            vectorizationTaskDao.markStaleAsInterrupted(System.currentTimeMillis() - 30_000)
            val interruptedTasks = vectorizationTaskDao.getRecoverableTasks()

            val tasks = interruptedTasks.map { it.toTask() }
            val attention = vectorizationTaskDao.getAttentionTasks().map { it.toTask() }

            synchronized(queueLock) {
                retainedAttention.clear()
                retainedAttention.addAll(attention)
                tasks.asReversed().forEach { task ->
                    if (queue.none { it.id == task.id }) queue.add(0, task)
                }
            }
            restored = true
            notifyStateChange()

            if (tasks.isNotEmpty()) startProcessorIfNeeded()
            enqueueMissingDocumentReferences()
            Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private suspend fun enqueueMissingDocumentReferences() {
        val dao = fileEntryDao ?: return
        dao.getUnvectorizedSupportedFiles(DocumentReferenceExtractor.SUPPORTED_MIME_TYPES.toList())
            .forEach { entry ->
                if (vectorizationTaskDao.countActiveForFile(entry.workspaceRootUuid, entry.uuid) == 0) {
                    enqueueDocumentReference(
                        workspaceRootUuid = entry.workspaceRootUuid,
                        docId = entry.uuid,
                        docTitle = entry.name,
                        sourceMimeType = entry.mimeType ?: return@forEach,
                    )
                }
            }
    }

    private fun VectorizationTaskEntity.toTask() = VectorizationTask(
        id = id,
        type = type,
        docId = docId,
        docTitle = docTitle,
        workspaceRootUuid = workspaceRootUuid,
        sessionId = sessionId,
        userContent = userContent,
        aiContent = aiContent,
        userMessageId = userMessageId,
        assistantMessageId = assistantMessageId,
        status = status,
        progress = progress,
        totalChunks = totalChunks,
        lastChunkIndex = lastChunkIndex,
        error = error,
        kgStrategy = kgStrategy,
        skipVectorization = skipVectorization,
        subStatus = subStatus,
        sourceMimeType = sourceMimeType,
        contentTruncated = contentTruncated,
        targetContentHash = targetContentHash,
        targetEpoch = targetEpoch,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun notifyStateChange() {
        val (snapshot, current) = synchronized(queueLock) {
            (queue + retainedAttention) to (queue.firstOrNull() ?: retainedAttention.firstOrNull())
        }
        _state.value = QueueState(snapshot, current, isProcessing, restored)
        onStateChange?.invoke(snapshot, current)
    }

    private fun startProcessorIfNeeded() {
        val shouldStart = synchronized(queueLock) {
            if (isProcessing || queue.isEmpty()) false
            else {
                isProcessing = true
                true
            }
        }
        if (shouldStart) scope.launch { processNext() }
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
        val isProcessing: Boolean,
        val restored: Boolean = false,
    )

    companion object {
        private const val MAX_RETRIES = 3
        const val TYPE_DOCUMENT_REFERENCE = "document_reference"

        private fun deterministicReferenceTaskId(workspaceRootUuid: String, docId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$workspaceRootUuid:$docId".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "doc-ref-${digest.take(40)}"
        }
    }
}
