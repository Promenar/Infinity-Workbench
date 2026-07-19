package com.promenar.nexara.data.rag

import android.util.Log
import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.dao.VectorizationTaskDao
import com.promenar.nexara.data.local.db.dao.VectorizationTaskTargetUpsertOutcome
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity
import com.promenar.nexara.data.repository.WorkspaceDeleteBarrierLease
import com.promenar.nexara.utils.NexaraLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

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
    private val retryCountMap = mutableMapOf<TaskTargetKey, Int>()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val enqueueMutex = Mutex()
    private val deletionBarrierMutex = Mutex()
    private val fencedTargets = mutableSetOf<WorkspaceDocKey>()
    private val _state = MutableStateFlow(QueueState(emptyList(), null, false, restored = false))
    val state: StateFlow<QueueState> = _state.asStateFlow()
    @Volatile private var restored = false
    private var processingTask: VectorizationTask? = null
    private var processingJob: Job? = null

    internal var beforeFinalQueueTransitionForTest: suspend () -> Unit = {}
    internal var beforeRetryDelayForTest: suspend () -> Unit = {}
    internal var beforeFailureDisplayDelayForTest: suspend () -> Unit = {}
    internal var beforePersistentCancelDeleteForTest: suspend () -> Unit = {}

    private val stateListenerLock = Any()
    private var stateListener: StateListener? = null

    fun setOnStateChange(
        callback: (List<VectorizationTask>, VectorizationTask?) -> Unit,
    ): AutoCloseable {
        val listener = StateListener(callback)
        val previous = synchronized(stateListenerLock) {
            stateListener.also { stateListener = listener }
        }
        previous?.closeAndAwait()
        val current = snapshotState()
        listener.invokeIfActive(current.queue, current.currentTask)
        return AutoCloseable {
            synchronized(stateListenerLock) {
                if (stateListener === listener) stateListener = null
            }
            listener.closeAndAwait()
        }
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
                    targetContentHash = event.contentHash,
                    targetEpoch = event.targetEpoch,
                    kgStrategy = event.kgStrategy
                        ?: "full".takeIf { event.useConfiguredKgStrategy && ragConfig.enableKnowledgeGraph },
                    skipVectorization = event.skipVectorization,
                )
            }
            is FileIndexEvent.Deleted -> {
                val barrier = acquireDeleteBarrier(event.workspaceRootUuid, listOf(event.fileUuid))
                try {
                    barrier.awaitReady()
                    val service = requireNotNull(documentIndexService) { "文档索引服务未配置" }
                    when (val result = service.delete(event.workspaceRootUuid, event.fileUuid)) {
                        is DocumentIndexResult.Deleted -> Unit
                        is DocumentIndexResult.Failed -> throw result.failure
                        else -> error("删除索引返回了无效状态: $result")
                    }
                    withContext(NonCancellable) { barrier.commit() }
                } catch (failure: Throwable) {
                    withContext(NonCancellable) { barrier.abort() }
                    throw failure
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
    ) = enqueueMutex.withLock {
        ensureTargetNotFenced(workspaceRootUuid, docId)
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
        targetContentHash: String,
        targetEpoch: Long,
        kgStrategy: String? = null,
        skipVectorization: Boolean = false,
    ): String = enqueueMutex.withLock {
        requireNotNull(documentIndexService) { "document_reference 必须配置事务索引服务" }
        require(sourceMimeType in DocumentReferenceExtractor.SUPPORTED_MIME_TYPES) { "不支持索引 MIME" }
        require(targetContentHash.isNotBlank()) { "document_reference target hash 不能为空" }
        require(targetEpoch > 0) { "document_reference target epoch 必须大于 0" }
        ensureTargetNotFenced(workspaceRootUuid, docId)
        val id = deterministicReferenceTaskId(workspaceRootUuid, docId)
        val incoming = VectorizationTask(
            id = id,
            type = TYPE_DOCUMENT_REFERENCE,
            docId = docId,
            docTitle = docTitle,
            workspaceRootUuid = workspaceRootUuid,
            status = "pending",
            kgStrategy = kgStrategy,
            skipVectorization = skipVectorization,
            sourceMimeType = sourceMimeType,
            targetContentHash = targetContentHash,
            targetEpoch = targetEpoch,
            userContent = null,
        )
        val upsert = vectorizationTaskDao.upsertTarget(incoming.toEntity())
        if (upsert.outcome == VectorizationTaskTargetUpsertOutcome.IGNORED_OLDER) {
            return@withLock upsert.activeTaskId
        }
        var active = checkNotNull(vectorizationTaskDao.getById(upsert.activeTaskId)) {
            "upsert 后 active target 不存在: ${upsert.activeTaskId}"
        }
        if (active.status in setOf("failed", "partial", "interrupted")) {
            val retried = active.copy(
                status = "pending",
                progress = 0.0,
                error = null,
                subStatus = "等待重新索引",
                kgStrategy = kgStrategy,
                skipVectorization = skipVectorization,
                sourceMimeType = sourceMimeType,
                contentTruncated = false,
                updatedAt = System.currentTimeMillis(),
            )
            if (vectorizationTaskDao.updateForTarget(retried) != 1) {
                active = checkNotNull(
                    vectorizationTaskDao.getByWorkspaceFile(
                        workspaceRootUuid,
                        docId,
                        TYPE_DOCUMENT_REFERENCE,
                    ),
                ) { "reset CAS 失败后 active target 不存在" }
            } else {
                active = retried
            }
        }
        val task = active.toTask()
        var added = false
        var supersededJob: Job? = null
        var supersededTarget: TaskTargetKey? = null
        synchronized(queueLock) {
            retainedAttention.removeAll {
                it.type == TYPE_DOCUMENT_REFERENCE && it.workspaceRootUuid == workspaceRootUuid &&
                    it.docId == docId
            }
            if (queue.none { it.targetKey() == task.targetKey() }) {
                queue.removeAll { queued ->
                    queued.type == TYPE_DOCUMENT_REFERENCE &&
                        queued.workspaceRootUuid == workspaceRootUuid && queued.docId == docId &&
                        queued !== processingTask
                }
                queue.add(task)
                added = true
            }
            val current = processingTask
            if (current?.type == TYPE_DOCUMENT_REFERENCE &&
                current.workspaceRootUuid == workspaceRootUuid && current.docId == docId &&
                current.targetKey() != task.targetKey()
            ) {
                supersededJob = processingJob
                supersededTarget = current.targetKey()
            }
        }
        supersededJob?.cancel(TargetSupersededCancellation(checkNotNull(supersededTarget)))
        notifyStateChange()
        if (added) startProcessorIfNeeded()
        active.id
    }

    suspend fun retryDocumentReference(workspaceRootUuid: String, docId: String): Boolean {
        requireNotNull(documentIndexService) { "document_reference 必须配置事务索引服务" }
        val existing = vectorizationTaskDao.getByWorkspaceFile(
            workspaceRootUuid,
            docId,
            TYPE_DOCUMENT_REFERENCE,
        ) ?: return false
        if (existing.status !in setOf("failed", "partial", "interrupted")) return false
        val entry = requireNotNull(fileEntryDao).getByUuid(workspaceRootUuid, docId) ?: return false
        enqueueDocumentReference(
            workspaceRootUuid = workspaceRootUuid,
            docId = docId,
            docTitle = entry.name,
            sourceMimeType = entry.mimeType ?: return false,
            targetContentHash = entry.hash,
            targetEpoch = entry.updatedAt,
            kgStrategy = existing.kgStrategy,
            skipVectorization = existing.skipVectorization,
        )
        return true
    }

    private suspend fun processNext(task: VectorizationTask) {

        var delegatedRetry = false
        var processingCancelled = false
        var superseded = false
        val retryKey = task.targetKey()
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
            retryCountMap.remove(retryKey)
            task.progress = 100.0
            task.subStatus = if (task.contentTruncated) "已完成安全前缀索引（内容截断）" else "处理完成"
            saveTaskToDb(task)
            NexaraLogger.log("[VectorQueue] 任务完成 type=${task.type} docTitle=${task.docTitle ?: "N/A"}")
            notifyStateChange()
            if (!task.contentTruncated) removeTaskFromDb(task)
        } catch (_: TargetSuperseded) {
            superseded = true
            retryCountMap.remove(retryKey)
        } catch (_: TargetSupersededCancellation) {
            superseded = true
            retryCountMap.remove(retryKey)
        } catch (_: TargetDeletedCancellation) {
            superseded = true
            retryCountMap.remove(retryKey)
        } catch (cancelled: CancellationException) {
            // 取消不是业务失败：不标 failed、不内部重试。当前持久阶段保留，由启动恢复标记 interrupted 后接管。
            retryCountMap.remove(retryKey)
            processingCancelled = true
            throw cancelled
        } catch (error: Exception) {
            val msg = error.message?.lowercase() ?: ""

            val isRetryable = msg.contains("network") ||
                msg.contains("timeout") ||
                msg.contains("fetch failed") ||
                msg.contains("5")

            val currentRetries = retryCountMap[retryKey] ?: 0

            // 保留失败的阶段信息，避免进度掉回 0
            val failedStep = task.status
            val failedProgress = task.progress

            if (isRetryable && currentRetries < MAX_RETRIES) {
                retryCountMap[retryKey] = currentRetries + 1
                task.subStatus = "正在重试 (${currentRetries + 1}/$MAX_RETRIES)..."
                task.error = getFriendlyErrorMessage(error)
                try {
                    saveTaskToDb(task)
                    NexaraLogger.logError("VectorQueue.processNext", error)
                    NexaraLogger.log("[VectorQueue] 可重试错误 (${currentRetries + 1}/$MAX_RETRIES): ${error.message?.take(100)}")
                    notifyStateChange()
                    val delayMs = minOf(3000L * (1L shl currentRetries), 15000L)
                    beforeRetryDelayForTest()
                    delay(delayMs)
                    delegatedRetry = true
                    processNext(task)
                    return
                } catch (_: TargetSuperseded) {
                    superseded = true
                    retryCountMap.remove(retryKey)
                } catch (_: TargetSupersededCancellation) {
                    superseded = true
                    retryCountMap.remove(retryKey)
                } catch (_: TargetDeletedCancellation) {
                    superseded = true
                    retryCountMap.remove(retryKey)
                }
            } else {
                retryCountMap.remove(retryKey)
                task.status = "failed"
                task.error = getFriendlyErrorMessage(error)
                // 保留失败时的进度，不下掉到 0
                task.progress = failedProgress
                task.subStatus = "失败于阶段: $failedStep — ${getFriendlyErrorMessage(error)}"
                try {
                    saveTaskToDb(task)
                    NexaraLogger.logError("VectorQueue.processNext", error)
                    NexaraLogger.log("[VectorQueue] 任务彻底失败 type=${task.type} step=$failedStep error=${task.error}")
                    notifyStateChange()
                    // 失败后暂停 2 秒再移除，让用户看清错误信息
                    beforeFailureDisplayDelayForTest()
                    delay(2000)
                } catch (_: TargetSuperseded) {
                    superseded = true
                    retryCountMap.remove(retryKey)
                } catch (_: TargetSupersededCancellation) {
                    superseded = true
                    retryCountMap.remove(retryKey)
                } catch (_: TargetDeletedCancellation) {
                    superseded = true
                    retryCountMap.remove(retryKey)
                }
            }
        } finally {
            if (!delegatedRetry) {
                beforeFinalQueueTransitionForTest()
                val currentJob = coroutineContext[Job]
                var nextJob: Job? = null
                var shouldCleanup = false
                synchronized(queueLock) {
                    if (!superseded && task.status in setOf("failed", "partial") &&
                        retainedAttention.none { it.targetKey() == task.targetKey() }
                    ) {
                        retainedAttention.add(task)
                    }
                    queue.removeAll { it === task }
                    if (processingJob === currentJob) {
                        processingJob = null
                        processingTask = null
                        nextJob = prepareNextProcessorLocked()
                        if (nextJob == null && (queue.isEmpty() || !scope.isActive)) {
                            isProcessing = false
                            shouldCleanup = queue.isEmpty()
                        }
                    }
                }
                notifyStateChange()
                nextJob?.start()
                if (shouldCleanup && !processingCancelled) cleanupCompletedTasks()
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
        val targetContentHash = task.targetContentHash
            ?: throw IllegalStateException("Document reference missing target hash")
        require(task.targetEpoch > 0) { "Document reference missing target epoch" }
        val service = requireNotNull(documentIndexService) {
            "document_reference 必须配置事务索引服务"
        }
        val entry = fileEntryDao?.getByUuid(workspaceRootUuid, docId)
            ?: throw IllegalStateException("Document reference file missing")
        if (entry.mimeType != task.sourceMimeType) throw SecurityException("索引任务 MIME 与文件不一致")
        task.status = "extracting_source"
        task.progress = 8.0
        task.subStatus = "正在构建事务索引候选..."
        saveTaskToDb(task)
        notifyStateChange()
        when (val result = service.rebuild(FileIndexEvent.Changed(
            workspaceRootUuid = workspaceRootUuid,
            fileUuid = docId,
            contentHash = targetContentHash,
            targetEpoch = task.targetEpoch,
            skipVectorization = task.skipVectorization,
            kgStrategy = task.kgStrategy,
            useConfiguredKgStrategy = false,
            activeTaskId = task.id,
        ))) {
            is DocumentIndexResult.Rebuilt -> return
            is DocumentIndexResult.HashChanged -> throw java.util.ConcurrentModificationException(
                "索引期间文件目标已变化: ${result.currentHash ?: "deleted"}",
            )
            is DocumentIndexResult.Failed -> throw result.failure
            is DocumentIndexResult.Deleted -> throw IllegalStateException("索引文件已删除")
        }
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
            } catch (cancelled: CancellationException) {
                throw cancelled
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
        dao.update(entry.copy(vectorizedAt = maxOf(timestamp, entry.updatedAt)))
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

    suspend fun cancelAndJoin(workspaceRootUuid: String, docIds: Collection<String>) {
        if (docIds.isEmpty()) return
        val ids = docIds.distinct()
        val barrier = acquireDeleteBarrier(workspaceRootUuid, ids)
        try {
            barrier.awaitReady()
            beforePersistentCancelDeleteForTest()
            vectorizationTaskDao.deleteByWorkspaceFiles(workspaceRootUuid, ids)
            withContext(NonCancellable) { barrier.commit() }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { barrier.abort() }
            throw failure
        }
    }

    suspend fun acquireDeleteBarrier(
        workspaceRootUuid: String,
        docIds: List<String>,
    ): WorkspaceDeleteBarrierLease = acquireDeleteBarrierInternal(
        targets = docIds.distinct().map { WorkspaceDocKey(workspaceRootUuid, it) }.toSet(),
    )

    private suspend fun acquireDeleteBarrierInternal(
        targets: Set<WorkspaceDocKey>,
    ): WorkspaceDeleteBarrierLease {
        if (targets.isEmpty()) return NoOpDeleteBarrierLease
        deletionBarrierMutex.lock()
        return try {
            enqueueMutex.withLock { createDeleteBarrierLeaseLocked(targets) }
        } catch (failure: Throwable) {
            deletionBarrierMutex.unlock()
            throw failure
        }
    }

    private fun createDeleteBarrierLeaseLocked(
        targets: Set<WorkspaceDocKey>,
    ): WorkspaceDeleteBarrierLease {
        var activeJob: Job? = null
        var activeTarget: TaskTargetKey? = null
        var activeTaskId: String? = null
        var queuedSnapshots: List<IndexedTaskSnapshot> = emptyList()
        var attentionSnapshots: List<IndexedTaskSnapshot> = emptyList()
        synchronized(queueLock) {
            check(fencedTargets.intersect(targets).isEmpty()) { "删除屏障目标已被占用" }
            fencedTargets.addAll(targets)
            val matches: (VectorizationTask) -> Boolean = { task ->
                task.workspaceRootUuid?.let { root ->
                    task.docId?.let { doc -> WorkspaceDocKey(root, doc) in targets }
                } == true
            }
            queuedSnapshots = queue.withIndex()
                .filter { matches(it.value) }
                .map { IndexedTaskSnapshot(it.index, it.value.copy()) }
            attentionSnapshots = retainedAttention.withIndex()
                .filter { matches(it.value) }
                .map { IndexedTaskSnapshot(it.index, it.value.copy()) }
            queue.removeAll(matches)
            retainedAttention.removeAll(matches)
            processingTask?.takeIf(matches)?.let { task ->
                activeJob = processingJob
                activeTarget = task.targetKey()
                activeTaskId = task.id
            }
        }
        activeJob?.cancel(TargetDeletedCancellation(checkNotNull(activeTarget)))
        notifyStateChangeNoThrow("DeleteBarrier.acquire")
        return DeleteBarrierLease(
            targets = targets,
            activeJob = activeJob,
            activeTaskId = activeTaskId,
            queuedSnapshots = queuedSnapshots,
            attentionSnapshots = attentionSnapshots,
        )
    }

    fun clear() {
        val (activeJob, activeTarget) = synchronized(queueLock) {
            val current = processingJob
            val target = processingTask?.targetKey()
            queue.clear()
            retainedAttention.clear()
            processingTask = null
            processingJob = null
            isProcessing = false
            current to target
        }
        activeJob?.cancel(
            TargetDeletedCancellation(
                checkNotNull(activeTarget) { "processingJob 存在时 processingTask 不应为空" },
            ),
        )
        notifyStateChange()
    }

    fun shutdown() {
        clear()
        scope.cancel()
    }

    suspend fun shutdownForReplacement() {
        deletionBarrierMutex.withLock {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            vectorizationTaskDao.markProcessingAsInterrupted(System.currentTimeMillis())
            clear()
        }
    }

    private suspend fun saveTaskToDb(task: VectorizationTask) {
        task.updatedAt = System.currentTimeMillis()
        val entity = task.toEntity()
        if (task.type == TYPE_DOCUMENT_REFERENCE) {
            if (vectorizationTaskDao.updateForTarget(entity) != 1) {
                throw TargetSuperseded(task.targetKey())
            }
        } else {
            vectorizationTaskDao.insert(entity)
        }
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

    private suspend fun removeTaskFromDb(task: VectorizationTask) {
        if (task.type == TYPE_DOCUMENT_REFERENCE) {
            val targetContentHash = task.targetContentHash ?: throw TargetSuperseded(task.targetKey())
            if (vectorizationTaskDao.deleteForTarget(
                    task.id,
                    targetContentHash,
                    task.targetEpoch,
                ) != 1
            ) {
                throw TargetSuperseded(task.targetKey())
            }
        } else {
            vectorizationTaskDao.getById(task.id)?.let { vectorizationTaskDao.delete(it) }
        }
    }

    suspend fun cleanupCompletedTasks() {
        vectorizationTaskDao.getCompletedDocumentReferenceTasks().forEach { task ->
            vectorizationTaskDao.deleteForTarget(
                task.id,
                task.targetContentHash,
                task.targetEpoch,
            )
        }
        vectorizationTaskDao.deleteCompletedNonReferenceTasks()
    }

    suspend fun resumeInterruptedTasks(): Result<Unit> = try {
            cleanupCompletedTasks()
            vectorizationTaskDao.markStaleAsInterrupted(System.currentTimeMillis() - 30_000)
            val interruptedTasks = normalizeRecoverableTasks(
                vectorizationTaskDao.getRecoverableTasks(),
            )

            val tasks = interruptedTasks.map { it.toTask() }
            val attention = normalizeAttentionTasks(
                vectorizationTaskDao.getAttentionTasks(),
            ).map { it.toTask() }

            synchronized(queueLock) {
                retainedAttention.clear()
                retainedAttention.addAll(attention.filterNot(::isFencedLocked))
                tasks.asReversed().forEach { task ->
                    if (!isFencedLocked(task) && queue.none { it.id == task.id }) queue.add(0, task)
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
                if (synchronized(queueLock) {
                        WorkspaceDocKey(entry.workspaceRootUuid, entry.uuid) in fencedTargets
                    }
                ) return@forEach
                if (vectorizationTaskDao.countActiveForFile(entry.workspaceRootUuid, entry.uuid) == 0) {
                    enqueueDocumentReference(
                        workspaceRootUuid = entry.workspaceRootUuid,
                        docId = entry.uuid,
                        docTitle = entry.name,
                        sourceMimeType = entry.mimeType ?: return@forEach,
                        targetContentHash = entry.hash,
                        targetEpoch = entry.updatedAt,
                        kgStrategy = "full".takeIf { ragConfig.enableKnowledgeGraph },
                    )
                }
            }
    }

    private suspend fun normalizeRecoverableTasks(
        tasks: List<VectorizationTaskEntity>,
    ): List<VectorizationTaskEntity> = tasks.mapNotNull { task ->
        if (task.type != "document") return@mapNotNull task
        migrateLegacyEntityToReference(task)?.let { return@mapNotNull it }
        val root = task.workspaceRootUuid
        val docId = task.docId
        if (root != null && docId != null) {
            if (documentIndexService?.prepareLegacyRetry(root, docId, task.id) != true) {
                vectorDao.deleteByDocId(docId)
                graphExtractor?.clearPersistedGraphForDoc(docId)
            }
        }
        val current = vectorizationTaskDao.getById(task.id) ?: return@mapNotNull null
        vectorizationTaskDao.update(
            current.copy(
                status = "failed",
                error = "旧索引任务无法安全迁移",
                subStatus = "需要重新导入或修复文件类型",
                updatedAt = System.currentTimeMillis(),
            ),
        )
        null
    }

    private suspend fun normalizeAttentionTasks(
        tasks: List<VectorizationTaskEntity>,
    ): List<VectorizationTaskEntity> = tasks.mapNotNull { task ->
        if (task.type != LEGACY_DOCUMENT_TASK_TYPE) return@mapNotNull task

        val root = task.workspaceRootUuid
        val docId = task.docId
        val hasIdentity = root != null && docId != null
        val hasCurrentFile = hasIdentity && (
            fileEntryDao == null || fileEntryDao.getByUuid(root, docId) != null
        )
        if (hasCurrentFile) return@mapNotNull task

        if (docId != null) {
            vectorDao.deleteByDocId(docId)
            graphExtractor?.clearPersistedGraphForDoc(docId)
        }
        vectorizationTaskDao.delete(task)
        null
    }

    private suspend fun migrateLegacyEntityToReference(
        legacy: VectorizationTaskEntity,
    ): VectorizationTaskEntity? {
        val root = legacy.workspaceRootUuid ?: return null
        val docId = legacy.docId ?: return null
        val entry = fileEntryDao?.getByUuid(root, docId) ?: return null
        val mimeType = entry.mimeType ?: return null
        if (documentIndexService == null || mimeType !in DocumentReferenceExtractor.SUPPORTED_MIME_TYPES ||
            entry.hash.isBlank() || entry.updatedAt <= 0
        ) return null
        val replacement = VectorizationTask(
            id = deterministicReferenceTaskId(root, docId),
            type = TYPE_DOCUMENT_REFERENCE,
            docId = docId,
            docTitle = entry.name,
            workspaceRootUuid = root,
            status = "pending",
            kgStrategy = legacy.kgStrategy,
            skipVectorization = legacy.skipVectorization,
            sourceMimeType = mimeType,
            targetContentHash = entry.hash,
            targetEpoch = entry.updatedAt,
        )
        val upsert = vectorizationTaskDao.migrateLegacyToTarget(legacy.id, replacement.toEntity())
            ?: return null
        return vectorizationTaskDao.getById(upsert.activeTaskId)
    }

    private fun isFencedLocked(task: VectorizationTask): Boolean {
        val root = task.workspaceRootUuid ?: return false
        val doc = task.docId ?: return false
        return WorkspaceDocKey(root, doc) in fencedTargets
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
        val listener = synchronized(stateListenerLock) { stateListener }
        listener?.invokeIfActive(snapshot, current)
    }

    private class StateListener(
        private val callback: (List<VectorizationTask>, VectorizationTask?) -> Unit,
    ) {
        private val lock = java.util.concurrent.locks.ReentrantLock()
        private val idle = lock.newCondition()
        private var active = true
        private var inFlight = 0
        private val invokingThreads = java.util.IdentityHashMap<Thread, Int>()

        fun invokeIfActive(queue: List<VectorizationTask>, current: VectorizationTask?) {
            val thread = Thread.currentThread()
            lock.lock()
            try {
                if (!active) return
                inFlight += 1
                invokingThreads[thread] = (invokingThreads[thread] ?: 0) + 1
            } finally {
                lock.unlock()
            }
            try {
                runCatching { callback(queue, current) }
            } finally {
                lock.lock()
                try {
                    inFlight -= 1
                    val depth = checkNotNull(invokingThreads[thread]) - 1
                    if (depth == 0) invokingThreads.remove(thread) else invokingThreads[thread] = depth
                    idle.signalAll()
                } finally {
                    lock.unlock()
                }
            }
        }

        fun closeAndAwait() {
            val thread = Thread.currentThread()
            lock.lock()
            try {
                active = false
                val selfDepth = invokingThreads[thread] ?: 0
                while (inFlight > selfDepth) idle.await()
            } finally {
                lock.unlock()
            }
        }
    }

    private fun startProcessorIfNeeded() {
        val nextJob = synchronized(queueLock) { prepareNextProcessorLocked() }
        nextJob?.start()
    }

    private fun prepareNextProcessorLocked(): Job? {
        if (!scope.isActive || processingJob != null || queue.isEmpty()) return null
        val task = queue.first()
        isProcessing = true
        processingTask = task
        val job = scope.launch(start = CoroutineStart.LAZY) { processNext(task) }
        processingJob = job
        job.invokeOnCompletion {
            var nextJob: Job? = null
            var recoveredBeforeBody = false
            synchronized(queueLock) {
                // LAZY job 可能在真正进入 processNext 前就被 newer 取消；此时函数 finally 不会执行。
                if (processingJob === job) {
                    queue.removeAll { it === task }
                    processingJob = null
                    processingTask = null
                    nextJob = prepareNextProcessorLocked()
                    if (nextJob == null && (queue.isEmpty() || !scope.isActive)) {
                        isProcessing = false
                    }
                    recoveredBeforeBody = true
                }
            }
            if (recoveredBeforeBody) {
                notifyStateChange()
                nextJob?.start()
            }
        }
        return job
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

    private fun ensureTargetNotFenced(workspaceRootUuid: String, docId: String) {
        check(
            synchronized(queueLock) {
                WorkspaceDocKey(workspaceRootUuid, docId) !in fencedTargets
            },
        ) { "文件正在永久删除，拒绝新的向量化任务" }
    }

    private inner class DeleteBarrierLease(
        private val targets: Set<WorkspaceDocKey>,
        private val activeJob: Job?,
        private val activeTaskId: String?,
        private val queuedSnapshots: List<IndexedTaskSnapshot>,
        private val attentionSnapshots: List<IndexedTaskSnapshot>,
    ) : WorkspaceDeleteBarrierLease {
        private val finished = AtomicBoolean(false)

        override suspend fun awaitReady() {
            activeJob?.join()
        }

        override suspend fun commit() {
            if (!finished.compareAndSet(false, true)) return
            synchronized(queueLock) { fencedTargets.removeAll(targets) }
            deletionBarrierMutex.unlock()
            notifyStateChangeNoThrow("DeleteBarrier.commit")
        }

        override suspend fun abort() {
            if (!finished.compareAndSet(false, true)) return
            var restoredQueue: List<IndexedTaskSnapshot> = queuedSnapshots
            var restoredAttention: List<IndexedTaskSnapshot> = attentionSnapshots
            try {
                activeJob?.join()
                enqueueMutex.lock()
                try {
                    activeTaskId?.let { id ->
                        queuedSnapshots.firstOrNull { it.task.id == id }?.task
                            ?.takeIf { it.type == "document" }
                            ?.let { legacy ->
                                if (migrateLegacyAbortToReference(legacy) == null) {
                                    prepareLegacyAbortForSafeRetry(legacy)
                                }
                            }
                    }
                    val persisted = targets.groupBy { it.workspaceRootUuid }.flatMap { (root, keys) ->
                        vectorizationTaskDao.getByWorkspaceFiles(root, keys.map { it.docId })
                    }
                    val persistedById = persisted.associateBy { it.id }
                    val originalSnapshots = (queuedSnapshots + attentionSnapshots)
                        .distinctBy { it.task.id }
                    val restoredSnapshotEntities = originalSnapshots.mapNotNull { snapshot ->
                        val current = persistedById[snapshot.task.id] ?: return@mapNotNull null
                        val restored = when {
                            current.status == "completed" -> null
                            current.status in PROCESSING_STATUSES -> markRecoverableAfterDeleteAbort(current)
                            else -> current
                        } ?: return@mapNotNull null
                        snapshot.copy(task = restored.toTask())
                    }
                    restoredQueue = restoredSnapshotEntities
                        .filterNot { it.task.status in setOf("failed", "partial") }
                    restoredAttention = restoredSnapshotEntities
                        .filter { it.task.status in setOf("failed", "partial") }

                    val snapshotIds = originalSnapshots.mapTo(mutableSetOf()) { it.task.id }
                    val missingSnapshots = persisted.filterNot { it.id in snapshotIds }.mapNotNull { current ->
                        when {
                            current.status == "completed" -> null
                            current.status in PROCESSING_STATUSES -> markRecoverableAfterDeleteAbort(current)
                            else -> current
                        }?.toTask()
                    }
                    val missingQueue = missingSnapshots.filterNot { it.status in setOf("failed", "partial") }
                    val missingAttention = missingSnapshots.filter { it.status in setOf("failed", "partial") }
                    restoredQueue += missingQueue.mapIndexed { index, task ->
                        IndexedTaskSnapshot(Int.MAX_VALUE - missingQueue.size + index, task)
                    }
                    restoredAttention += missingAttention.mapIndexed { index, task ->
                        IndexedTaskSnapshot(Int.MAX_VALUE - missingAttention.size + index, task)
                    }
                    synchronized(queueLock) {
                        fencedTargets.removeAll(targets)
                        restoredQueue.sortedBy { it.index }.forEach { snapshot ->
                            if (queue.none { it.id == snapshot.task.id }) {
                                queue.add(snapshot.index.coerceAtMost(queue.size), snapshot.task)
                            }
                        }
                        restoredAttention.sortedBy { it.index }.forEach { snapshot ->
                            if (retainedAttention.none { it.id == snapshot.task.id }) {
                                retainedAttention.add(
                                    snapshot.index.coerceAtMost(retainedAttention.size),
                                    snapshot.task,
                                )
                            }
                        }
                    }
                } finally {
                    enqueueMutex.unlock()
                }
            } finally {
                synchronized(queueLock) { fencedTargets.removeAll(targets) }
                deletionBarrierMutex.unlock()
                notifyStateChangeNoThrow("DeleteBarrier.abort")
                if (restoredQueue.isNotEmpty()) startProcessorIfNeeded()
            }
        }

        private suspend fun markRecoverableAfterDeleteAbort(
            current: VectorizationTaskEntity,
        ): VectorizationTaskEntity? {
            val recoverable = current.copy(
                status = "interrupted",
                error = null,
                subStatus = "删除回滚，等待恢复索引",
                updatedAt = System.currentTimeMillis(),
            )
            val updated = if (current.type == TYPE_DOCUMENT_REFERENCE) {
                vectorizationTaskDao.updateForTarget(recoverable)
            } else {
                vectorizationTaskDao.update(recoverable)
            }
            return recoverable.takeIf { updated == 1 }
        }

        private suspend fun migrateLegacyAbortToReference(
            legacy: VectorizationTask,
        ): VectorizationTaskEntity? {
            val persisted = vectorizationTaskDao.getById(legacy.id) ?: return null
            return migrateLegacyEntityToReference(persisted)
        }

        private suspend fun prepareLegacyAbortForSafeRetry(legacy: VectorizationTask) {
            val root = legacy.workspaceRootUuid ?: return
            val docId = legacy.docId ?: return
            if (documentIndexService?.prepareLegacyRetry(root, docId, legacy.id) == true) return
            vectorDao.deleteByDocId(docId)
            graphExtractor?.clearPersistedGraphForDoc(docId)
        }
    }

    private object NoOpDeleteBarrierLease : WorkspaceDeleteBarrierLease {
        override suspend fun awaitReady() = Unit
        override suspend fun commit() = Unit
        override suspend fun abort() = Unit
    }

    private fun notifyStateChangeNoThrow(operation: String) {
        runCatching { notifyStateChange() }.exceptionOrNull()?.let { failure ->
            NexaraLogger.logError("VectorizationQueue.$operation", failure)
        }
    }

    private fun VectorizationTask.targetKey() = TaskTargetKey(id, targetContentHash, targetEpoch)

    private fun VectorizationTaskEntity.targetKey() = TaskTargetKey(id, targetContentHash, targetEpoch)

    private data class TaskTargetKey(
        val taskId: String,
        val contentHash: String?,
        val epoch: Long,
    )

    private data class WorkspaceDocKey(
        val workspaceRootUuid: String,
        val docId: String,
    )

    private data class IndexedTaskSnapshot(
        val index: Int,
        val task: VectorizationTask,
    )

    private class TargetSuperseded(target: TaskTargetKey) :
        IllegalStateException("target 已被更新目标替代: $target")

    private class TargetSupersededCancellation(target: TaskTargetKey) :
        CancellationException("target 已被更新目标主动取消: $target")

    private class TargetDeletedCancellation(target: TaskTargetKey) :
        CancellationException("target 已因文件删除主动取消: $target")

    data class QueueState(
        val queue: List<VectorizationTask>,
        val currentTask: VectorizationTask?,
        val isProcessing: Boolean,
        val restored: Boolean = false,
    )

    companion object {
        private const val MAX_RETRIES = 3
        const val TYPE_DOCUMENT_REFERENCE = "document_reference"
        private const val LEGACY_DOCUMENT_TASK_TYPE = "document"
        private val PROCESSING_STATUSES = setOf(
            "processing", "extracting_source", "chunking", "vectorizing", "saving", "extracting",
        )

        private fun deterministicReferenceTaskId(workspaceRootUuid: String, docId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$workspaceRootUuid:$docId".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "doc-ref-${digest.take(40)}"
        }
    }
}
