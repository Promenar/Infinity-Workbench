package com.promenar.nexara

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import java.io.File
import com.promenar.nexara.data.local.inference.LocalInferenceEngine
import com.promenar.nexara.data.local.inference.LocalInferenceRuntimeGate
import com.promenar.nexara.data.local.inference.SlotType
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.MIGRATION_1_2
import com.promenar.nexara.data.local.db.MIGRATION_2_3
import com.promenar.nexara.data.local.db.MIGRATION_3_4
import com.promenar.nexara.data.local.db.MIGRATION_4_5
import com.promenar.nexara.data.local.db.MIGRATION_5_18
import com.promenar.nexara.data.local.db.MIGRATION_18_19
import com.promenar.nexara.data.local.db.MIGRATION_19_20
import com.promenar.nexara.data.local.db.MIGRATION_20_21
import com.promenar.nexara.data.local.db.MIGRATION_21_22
import com.promenar.nexara.data.local.db.MIGRATION_17_18
import com.promenar.nexara.data.local.db.LegacyDatabasePromoter
import com.promenar.nexara.data.backup.BackupRuntime
import com.promenar.nexara.data.backup.BackupStartupState
import com.promenar.nexara.data.backup.RestoreRelayActivity
import com.promenar.nexara.data.backup.AndroidRestoreJournalAuthenticator
import com.promenar.nexara.data.local.db.recovery.AndroidDualDatabaseRecoveryExecutor
import com.promenar.nexara.data.local.db.recovery.AndroidDualDatabaseSourceProbe
import com.promenar.nexara.data.local.db.recovery.DualDatabaseBootstrapResult
import com.promenar.nexara.data.local.db.recovery.DualDatabaseRecoveryBootstrap
import com.promenar.nexara.data.local.db.recovery.FileDualDatabaseRecoveryJournal
import com.promenar.nexara.data.local.db.recovery.RecoverySnapshotStore
import com.promenar.nexara.utils.NexaraLogger
import com.promenar.nexara.data.rag.EmbeddingClient
import com.promenar.nexara.data.rag.GraphStore
import com.promenar.nexara.data.rag.ImageService
import com.promenar.nexara.data.rag.KeywordSearcher
import com.promenar.nexara.data.rag.MemoryManager
import com.promenar.nexara.data.rag.MicroGraphExtractor
import com.promenar.nexara.data.rag.MicroGraphKgAdapter
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.rag.PendingDocumentIndexCoordinator
import com.promenar.nexara.data.rag.QueryRewriter
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.data.rag.RerankClient
import com.promenar.nexara.domain.usecase.RagConfigPersistence
import com.promenar.nexara.data.rag.RecursiveCharacterTextSplitter
import com.promenar.nexara.data.rag.VectorStore
import com.promenar.nexara.data.rag.VectorizationQueue
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.security.AndroidKeystoreSecretStore
import com.promenar.nexara.data.security.SecretCatalog
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import com.promenar.nexara.data.model.ProviderConfig
import com.promenar.nexara.data.model.toCredentialUpdate
import com.promenar.nexara.data.model.catalog.ModelCatalogRuntime
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.DefaultProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderRequestRouter
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.remote.provider.VertexCredentialException
import com.promenar.nexara.data.remote.provider.VertexCredentialParser
import com.promenar.nexara.data.remote.protocol.VERTEX_DEFAULT_LOCATION
import com.promenar.nexara.data.repository.FileOperationRepository
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.repository.MessageRepository
import com.promenar.nexara.data.repository.SessionRepository
import com.promenar.nexara.data.repository.VectorRepository
import com.promenar.nexara.data.repository.WorkspaceRepository
import com.promenar.nexara.ui.chat.manager.WebSearchContextProvider
import com.promenar.nexara.ui.chat.manager.WebSearchProvider
import com.promenar.nexara.ui.chat.manager.registry.DefaultSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.ModularSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.UserSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.McpSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry
import com.promenar.nexara.ui.chat.manager.skills.CalculatorSkill
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.ChatRouteDependencies
import com.promenar.nexara.ui.chat.manager.KgProvider
import com.promenar.nexara.ui.chat.manager.skills.WebSearchSkill
import com.promenar.nexara.ui.chat.manager.skills.WebFetchSkill
import com.promenar.nexara.ui.chat.manager.skills.ImageGenerationSkill
import com.promenar.nexara.ui.chat.manager.skills.FileReadSkill
import com.promenar.nexara.ui.chat.manager.skills.FileWriteSkill
import com.promenar.nexara.ui.chat.manager.skills.FileDiffSkill
import com.promenar.nexara.ui.chat.manager.skills.FilePatchSkill
import com.promenar.nexara.ui.chat.manager.skills.FileListSkill
import com.promenar.nexara.ui.chat.manager.skills.FileSearchSkill
import com.promenar.nexara.ui.chat.manager.skills.CreateFileSkill
import com.promenar.nexara.ui.chat.manager.skills.CreateDirectorySkill
import com.promenar.nexara.ui.chat.manager.skills.MoveFileSkill
import com.promenar.nexara.ui.chat.manager.skills.DeleteFileSkill
import com.promenar.nexara.ui.chat.manager.skills.ExecJsSkill
import com.promenar.nexara.ui.chat.manager.skills.InitializePlanSkill
import com.promenar.nexara.ui.chat.manager.skills.UpdatePlanSkill
import com.promenar.nexara.ui.chat.manager.skills.GetPlanSkill
import com.promenar.nexara.ui.chat.manager.skills.DropPlanSkill
import com.promenar.nexara.data.repository.TaskRepository
import com.promenar.nexara.data.repository.ToolExecutionLedgerRepository
import com.promenar.nexara.data.repository.SkillRepository
import com.promenar.nexara.data.repository.ISkillRepository
import com.promenar.nexara.data.repository.TokenStatsRepository
import com.promenar.nexara.domain.repository.ITokenStatsRepository
import com.promenar.nexara.util.LocaleHelper
import com.promenar.nexara.ui.theme.ThemePreferenceStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.promenar.nexara.startup.StartupBackgroundHealthMonitor
import com.promenar.nexara.startup.StartupBackgroundTask
import com.promenar.nexara.startup.StartupBackgroundTaskHealth
import com.promenar.nexara.startup.IdempotentStartupMigrationStage
import com.promenar.nexara.startup.StartupMigration
import com.promenar.nexara.startup.StartupWriterRegistration
import com.promenar.nexara.startup.StartupWriterSession
import com.promenar.nexara.startup.StartupWriterTransaction
import com.promenar.nexara.data.repository.LegacyWorkspaceRootAdoptionCoordinator
import com.promenar.nexara.ui.rag.RagWorkspaceProvisioner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import coil3.video.VideoFrameDecoder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

open class NexaraApplication : Application(), SingletonImageLoader.Factory,
    com.promenar.nexara.background.exec.ExecRunRegistryOwner {
    companion object {
        lateinit var instance: NexaraApplication
            private set
    }

    open val secretStore: SecretStore by lazy { AndroidKeystoreSecretStore(this) }
    val termuxRuntime: com.promenar.nexara.data.termux.TermuxRuntime by lazy {
        com.promenar.nexara.data.termux.TermuxRuntime(this)
    }
    open val mcpConnectionSettings: com.promenar.nexara.data.remote.mcp.McpConnectionSettings by lazy {
        com.promenar.nexara.data.remote.mcp.McpConnectionSettings(getSharedPreferences("nexara_settings", MODE_PRIVATE), secretStore)
    }

    val sshHostStore: com.promenar.nexara.data.ssh.SshHostStore by lazy {
        com.promenar.nexara.data.ssh.RoomSshHostStore(database.sshHostDao(), secretStore)
    }
    /** 工具执行中需要用户亲自输入密码或核对主机指纹时的应用内弹窗队列。 */
    val sshUserPrompts = com.promenar.nexara.data.ssh.SshUserPromptBroker()
    val sshRuntime: com.promenar.nexara.data.ssh.SshRuntime by lazy {
        com.promenar.nexara.data.ssh.SshRuntime(sshHostStore, onPersistentActivity = { active ->
            com.promenar.nexara.background.ssh.SshConnectionService.update(this, active)
        })
    }
    /** 内置 Tailscale（tsnet 子进程，不占用系统 VPN）；创建时安装为全局 tailnet 路由。未打包可执行文件时报告不可用。 */
    val tailnetManager: com.promenar.nexara.data.tailnet.TailnetManager by lazy {
        val executable = File(applicationInfo.nativeLibraryDir, com.promenar.nexara.data.tailnet.TailnetManager.EXECUTABLE_NAME)
        com.promenar.nexara.data.tailnet.TailnetManager(
            settingsStore = com.promenar.nexara.data.tailnet.TailnetSettingsStore(
                getSharedPreferences(com.promenar.nexara.data.tailnet.TailnetSettingsStore.PREFERENCES_NAME, MODE_PRIVATE),
                secretStore,
            ),
            launcher = if (executable.isFile) {
                com.promenar.nexara.data.tailnet.AndroidTailnetProcessLauncher(
                    executable = executable,
                    homeDirectory = File(noBackupFilesDir, "tailnet-home"),
                    tempDirectory = File(cacheDir, "tailnet-tmp"),
                )
            } else null,
            stateDirectory = File(noBackupFilesDir, "tailnet"),
            hostname = com.promenar.nexara.data.tailnet.TailnetManager.hostnameFor(android.os.Build.MODEL),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ).also { com.promenar.nexara.data.tailnet.InstalledTailnetRouting.routing = it }
    }
    val themePreferenceStore: ThemePreferenceStore by lazy { ThemePreferenceStore(this) }

    /** 生产默认依赖保持不变；instrumentation 可通过 Application 子类注入可验证端口。 */
    open fun createChatRouteDependencies(): ChatRouteDependencies =
        ChatRouteDependencies.production(this)

    private lateinit var backupRuntime: BackupRuntime
    private val dualDatabaseRecoveryBootstrap: DualDatabaseRecoveryBootstrap by lazy {
        createDualDatabaseRecoveryBootstrap()
    }
    private var acceptedDualDatabaseOfferToken: String? = null
    var restoreRelayEarlyExit: Boolean = false
        private set
    private val _startupState = MutableStateFlow<BackupStartupState>(BackupStartupState.Recovering)
    val startupState: StateFlow<BackupStartupState> = _startupState.asStateFlow()

    /** 启动时完成的备份恢复中需要用户处理的技能事项；界面展示后清除。 */
    private val _skillRestoreNotice = MutableStateFlow<com.promenar.nexara.data.backup.SkillBackupSummary?>(null)
    val skillRestoreNotice: StateFlow<com.promenar.nexara.data.backup.SkillBackupSummary?> = _skillRestoreNotice.asStateFlow()

    fun consumeSkillRestoreNotice() {
        _skillRestoreNotice.value = null
    }

    /** 启动时待恢复事务确定性失败且已完整回滚；界面告知数据保持原状后清除。 */
    private val _restoreNotAppliedNotice = MutableStateFlow(false)
    val restoreNotAppliedNotice: StateFlow<Boolean> = _restoreNotAppliedNotice.asStateFlow()

    fun consumeRestoreNotAppliedNotice() {
        _restoreNotAppliedNotice.value = false
    }

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val modelCatalogUpdater by lazy {
        com.promenar.nexara.data.model.catalog.ModelCatalogUpdater.create(this) { catalog ->
            ModelCatalogRuntime.installPublished(catalog)
            ProviderManager.getInstance().refreshCatalogMetadata()
        }
    }
    private var catalogObserverRegistered = false

    private fun startModelCatalogUpdates() {
        if (!catalogObserverRegistered) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    if (_startupState.value == BackupStartupState.Ready) {
                        startupScope.launch { modelCatalogUpdater.refresh() }
                    }
                }
            })
            catalogObserverRegistered = true
        }
        startupScope.launch { modelCatalogUpdater.refresh() }
    }
    private var startupRecoveryJob: Job? = null
    private var writersInitialized = false
    private var startupWriterSession: StartupWriterSession<PreparedStartupWriters>? = null
    private val startupMigrationStage = IdempotentStartupMigrationStage()
    private val startupBackgroundHealthMonitor = StartupBackgroundHealthMonitor { task, failure ->
        NexaraLogger.logError("StartupBackground.${task.name}", failure)
    }
    val startupBackgroundHealth: StateFlow<Map<StartupBackgroundTask, StartupBackgroundTaskHealth>> =
        startupBackgroundHealthMonitor.state

    val database: NexaraDatabase by lazy {
        LegacyDatabasePromoter.promote(this)
        Room.databaseBuilder(this, NexaraDatabase::class.java, "nexara_v2.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_18, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
            .setQueryCallback(
                androidx.room.RoomDatabase.QueryCallback { sqlQuery, bindArgs ->
                    if (com.promenar.nexara.BuildConfig.DEBUG) {
                        try {
                            if (sqlQuery.contains("Message", ignoreCase = true) || 
                                sqlQuery.contains("TaskNodeEntity", ignoreCase = true) ||
                                sqlQuery.contains("Session", ignoreCase = true)
                            ) {
                                val json = org.json.JSONObject().apply {
                                    put("operation", sqlQuery.trimStart().substringBefore(' ').uppercase())
                                    put("argumentCount", bindArgs.size)
                                }
                                NexaraLogger.metro("DB_QUERY", json.toString())
                            }
                        } catch (e: Exception) {
                            // Ignored
                        }
                    }
                },
                java.util.concurrent.Executors.newSingleThreadExecutor()
            )
            .build()
    }

    val toolExecutionLedger by lazy {
        ToolExecutionLedgerRepository(database)
    }

    val localInferenceRuntimeGate: LocalInferenceRuntimeGate by lazy {
        LocalInferenceRuntimeGate.fromBuildConfig()
    }

    val localInferenceEngine: LocalInferenceEngine by lazy {
        localInferenceRuntimeGate.requireAvailable()
        LocalInferenceEngine(this)
    }

    val agentRepository: com.promenar.nexara.data.repository.AgentRepository by lazy {
        com.promenar.nexara.data.repository.AgentRepository(database.agentDao())
    }

    val configResolver: com.promenar.nexara.domain.usecase.AgentConfigResolver by lazy {
        com.promenar.nexara.domain.usecase.AgentConfigResolver(
            getSharedPreferences("nexara_settings", MODE_PRIVATE)
        )
    }

    val createAgentUseCase: com.promenar.nexara.domain.usecase.CreateAgentUseCase by lazy {
        com.promenar.nexara.domain.usecase.CreateAgentUseCase(agentRepository)
    }

    val sessionRepository: ISessionRepository by lazy {
        SessionRepository(database.sessionDao(), database.messageDao())
    }

    val toolAllowlistStore by lazy {
        com.promenar.nexara.data.repository.ToolAllowlistStore(java.io.File(noBackupFilesDir, "tool-allowlist"))
    }
    val toolOutputStore by lazy {
        com.promenar.nexara.data.repository.ToolOutputStore(java.io.File(noBackupFilesDir, "tool-outputs"))
    }
    val contextFoldStore by lazy {
        com.promenar.nexara.data.repository.ContextFoldStore(java.io.File(noBackupFilesDir, "context-folds"))
    }
    private val contextCompactionPipeline by lazy {
        com.promenar.nexara.data.generation.ContextCompactionPipeline(contextFoldStore)
    }
    val contextCompactionService: com.promenar.nexara.data.generation.ContextCompactionService by lazy {
        com.promenar.nexara.data.generation.ContextCompactionService(
            isGenerating = { sessionId ->
                generationCoordinator.active.value?.sessionId == sessionId
            },
            manualCompaction = { sessionId, progress ->
                val taskId = "compaction-${java.util.UUID.randomUUID()}"
                generationPresentationStore.begin(sessionId, taskId)
                try {
                    generationRunnerFactory.createRuntime(sessionId, taskId).compactNow(sessionId, progress)
                } finally {
                    generationScope.launch {
                        kotlinx.coroutines.delay(3000)
                        generationPresentationStore.finish(sessionId, taskId)
                    }
                }
            },
            admission = { sessionId, block -> sessionExecutionGate.withNewSessionAdmission(sessionId, block) },
            applicationScope = generationScope,
        )
    }
    suspend fun estimateContextUsage(sessionId: String): com.promenar.nexara.data.generation.ContextUsageEstimate =
        sessionExecutionGate.withNewSessionAdmission(sessionId) {
            generationRunnerFactory.createRuntime(sessionId, "context-estimate").estimateContext(sessionId)
        }
    val scriptSettings by lazy {
        com.promenar.nexara.data.script.ScriptSettings(getSharedPreferences("nexara_settings", MODE_PRIVATE))
    }
    val scriptFileApprovals by lazy { com.promenar.nexara.data.script.ScriptFileApprovalBroker() }
    private val scriptRuntime by lazy {
        com.promenar.nexara.domain.script.SupervisedScriptRuntime { session ->
            com.promenar.nexara.data.script.AndroidScriptProcess.bind(this, session)
        }
    }
    private val scriptToolRunner by lazy {
        com.promenar.nexara.data.script.AndroidScriptToolRunner(scriptRuntime, workspaceRepository, externalFolderManager,
            fileOperationRepository, pendingDocumentIndexCoordinator, scriptFileApprovals, toolAllowlistStore, scriptSettings,
            sessionWorkspaceResolver::resolve)
    }
    val generationInteractions by lazy { com.promenar.nexara.data.generation.GenerationInteractionStore() }

    suspend fun resolveToolAllowlistScope(sessionId: String, target: String) =
        com.promenar.nexara.data.repository.ToolAllowlistScopes.resolveForSession(
            sessionId, target, sessionWorkspaceResolver::resolve, externalFolderManager,
        )

    suspend fun verifyTargetGrant(
        session: com.promenar.nexara.data.model.Session,
        skill: com.promenar.nexara.ui.chat.manager.registry.SkillDefinition,
        args: kotlinx.serialization.json.JsonObject,
        target: String,
    ): Boolean {
        val tool = com.promenar.nexara.data.remote.protocol.ProtocolTool(
            function = com.promenar.nexara.data.remote.protocol.ProtocolToolFunction(skill.name, skill.description, skill.parametersSchema),
            risk = skill.risk, runtimeToolId = skill.runtimeToolId, sourceId = skill.sourceId, mcpServerId = skill.mcpServerId,
        )
        val scope = resolveToolAllowlistScope(session.id, target) ?: return false
        return com.promenar.nexara.data.generation.resolveSessionToolTarget(session, tool, args.toString(), workspaceRepository, externalFolderManager, sshHostStore, sshRuntime::sessionHost) == target &&
            toolAllowlistStore.contains(skill.name, target, scope.scopeKey)
    }

    val messageRepository: IMessageRepository by lazy {
        MessageRepository(database.messageDao())
    }

    val chatStore: ChatStore by lazy { ChatStore() }
    val sessionExecutionGate by lazy { com.promenar.nexara.data.session.SessionExecutionGate() }
    private val sessionWorkspaceParent: java.nio.file.Path
        get() = File(filesDir, "session_workspaces").toPath()
    private val workspaceMutationRecoveryCoordinator by lazy {
        com.promenar.nexara.data.repository.WorkspaceMutationRecoveryCoordinator(
            workspaceParent = sessionWorkspaceParent,
            loadUnfinished = { database.workspaceMutationDao().getUnfinished() },
            sessionExists = { sessionId -> database.sessionDao().getById(sessionId) != null },
            deletePrepared = { operationId ->
                check(database.workspaceMutationDao().deletePrepared(operationId) == 1) {
                    "PREPARED journal 恢复清理失败"
                }
            },
            deleteCommitted = { operationId ->
                check(database.workspaceMutationDao().deleteCommitted(operationId) == 1) {
                    "DB_COMMITTED journal 恢复清理失败"
                }
            },
            verifyRootIdentity = { path, identity ->
                com.promenar.nexara.data.repository.SecureWorkspaceFileOps().ensureRoot(
                    path,
                    initializeIdentity = false,
                    expectedIdentity = identity,
                )
                Unit
            },
        )
    }
    private val workspaceFileMutationJournal by lazy {
        com.promenar.nexara.data.repository.RoomWorkspaceFileMutationJournal(database)
    }
    private val workspaceFileMutationRecoveryCoordinator by lazy {
        com.promenar.nexara.data.repository.WorkspaceFileMutationRecoveryCoordinator(database)
    }
    private val sessionDeletionCoordinator by lazy {
        val targetResolver = com.promenar.nexara.data.session.RoomSessionDeletionTargetResolver(
            database,
            sessionWorkspaceParent,
        )
        val journal = com.promenar.nexara.data.session.RoomSessionWorkspaceMutationJournal(
            database,
            sessionWorkspaceParent,
        )
        val transaction = com.promenar.nexara.data.session.RoomSessionDeletionTransaction(database)
        com.promenar.nexara.data.session.SessionDeletionCoordinator(
            gate = sessionExecutionGate,
            recoverSessionDeletion = workspaceMutationRecoveryCoordinator::recoverSessionOrThrow,
            resolveTarget = targetResolver::resolve,
            isDatabaseOnlySession = { sessionId ->
                val session = database.sessionDao().getById(sessionId)
                if (session == null) {
                    true
                } else {
                    val rootUuid = session.workspaceRootUuid
                    val pathStr = session.workspacePath
                    if (rootUuid.isNullOrBlank() || pathStr.isNullOrBlank()) {
                        true
                    } else {
                        val path = java.io.File(pathStr)
                        val root = database.fileEntryDao().getAnyStateByUuidForLifecycle(rootUuid, rootUuid)
                        !path.exists() || !path.isDirectory || root == null
                    }
                }
            },
            deleteDatabaseOnly = transaction::deleteWithoutWorkspace,
            cancelAndJoinGeneration = { sessionId ->
                generationCoordinator.cancelAndJoinSession(sessionId)
                generationCoordinator.release(sessionId, discardTerminal = true)
                sshRuntime.closeOwner("agent:$sessionId")
            },
            recoverFileMutations = { target ->
                workspaceFileMutationRecoveryCoordinator.recoverRootOrThrow(target.workspaceRootUuid)
            },
            closePendingExecution = { sessionId ->
                database.toolExecutionLedgerDao().cancelOpenForSession(
                    sessionId,
                    "会话已进入删除事务",
                    System.currentTimeMillis(),
                )
            },
            acquireVectorBarrier = { target ->
                val lease = vectorizationQueue.acquireSessionDeleteBarrier(
                    target.sessionId,
                    target.workspaceRootUuid,
                    target.fileUuids,
                )
                object : com.promenar.nexara.data.session.SessionDeletionBarrier {
                    override suspend fun awaitReady() = lease.awaitReady()
                    override suspend fun commit() = lease.commit()
                    override suspend fun abort() = lease.abort()
                }
            },
            journal = journal,
            deleteDatabase = transaction::delete,
            afterDatabaseCommit = { target ->
                pendingDocumentIndexCoordinator.clearCommitted(
                    target.workspaceRootUuid,
                    target.fileUuids,
                )
            },
            cleanupSessionData = { sessionId ->
                com.promenar.nexara.data.session.cleanupSessionRuntimeData(
                    // 会话私有工作区只属于该会话，删除会话时一并撤销绑定到它的白名单条目。
                    { toolAllowlistStore.removeScope("session:$sessionId") },
                    { toolOutputStore.deleteSession(sessionId) },
                    deleteMounts = { externalFolderManager.deleteSessionMounts(sessionId) },
                    deleteFolds = { contextFoldStore.deleteSession(sessionId) },
                )
            },
        )
    }

    suspend fun deleteSessionRecoverably(sessionId: String):
        com.promenar.nexara.data.session.SessionDeletionResult =
        sessionDeletionCoordinator.delete(sessionId)

    private val generationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val generationPresentationStore by lazy {
        com.promenar.nexara.data.generation.GenerationPresentationStore()
    }
    val generationForegroundController:
        com.promenar.nexara.background.generation.GenerationForegroundController by lazy {
        com.promenar.nexara.background.generation.AndroidGenerationForegroundController(this)
    }
    val appIntentRouter by lazy {
        com.promenar.nexara.navigation.AppIntentRouter { taskId, sessionId ->
            generationCoordinator.observe(sessionId).value?.taskId == taskId
        }
    }
    private val generationSessionManager by lazy {
        com.promenar.nexara.ui.chat.manager.SessionManager(chatStore, sessionRepository)
    }
    private val generationMessageManager by lazy {
        com.promenar.nexara.ui.chat.manager.MessageManager(
            chatStore,
            messageRepository,
            sessionRepository,
            generationScope,
        )
    }
    private val generationContextBuilder by lazy {
        com.promenar.nexara.ui.chat.manager.ContextBuilder(
            webSearchProvider = webSearchContextProvider,
            ragProvider = com.promenar.nexara.data.rag.MemoryManagerRagAdapter(memoryManager),
            kgProvider = kgProvider,
            taskRepository = taskRepository,
            onEmbeddingNotConfigured = embeddingSetupHint::onEmbeddingNotConfigured,
        )
    }
    val embeddingSetupHint by lazy {
        com.promenar.nexara.data.rag.EmbeddingSetupHint(getSharedPreferences("nexara_settings", MODE_PRIVATE))
    }
    private val generationToolExecutor by lazy {
        com.promenar.nexara.ui.chat.manager.ToolExecutor(
            chatStore,
            generationMessageManager,
            skillRegistry,
            sessionToolResolver,
            taskRepository,
            toolExecutionLedger,
            sessionExecutionGate,
            grantVerifier = this::verifyTargetGrant,
        )
    }
    private val generationPostProcessor by lazy {
        val settings = getSharedPreferences("nexara_settings", MODE_PRIVATE)
        val provider = llmProvider
        com.promenar.nexara.ui.chat.manager.PostProcessor(
            chatStore,
            generationSessionManager,
            generationMessageManager,
            embeddingClient,
            vectorStore,
            textSplitter,
        ).apply {
            setTitleGenerator { params ->
                val quickModelId = settings.getString("preset_summary_model", "")
                com.promenar.nexara.ui.chat.manager.PostProcessor.generateTitleWithFallback(
                    params = params,
                    quickModelId = quickModelId,
                    provider = provider,
                )
            }
        }
    }
    private val generationRunnerFactory: com.promenar.nexara.data.generation.DefaultChatGenerationRunnerFactory by lazy {
        val settings = getSharedPreferences("nexara_settings", MODE_PRIVATE)
        val provider = llmProvider
        com.promenar.nexara.data.generation.DefaultChatGenerationRunnerFactory(
            settings = settings,
            applicationScope = generationScope,
            store = chatStore,
            agentRepository = agentRepository,
            configResolver = configResolver,
            routeGate = com.promenar.nexara.data.generation.ChatProviderRouteGate(providerRequestRouter),
            contextBuilder = generationContextBuilder,
            messageManager = generationMessageManager,
            localProviderFactory = { modelId -> LlmProvider.local(localInferenceEngine, modelId) },
            provider = provider,
            toolLedger = toolExecutionLedger,
            toolExecutor = generationToolExecutor,
            postProcessor = generationPostProcessor,
            memoryManager = memoryManager,
            summaryManager = com.promenar.nexara.data.generation.RoutedToolResultSummarizer(
                com.promenar.nexara.data.generation.ChatProviderRouteGate(providerRequestRouter),
            ) { modelId -> LlmProvider.local(localInferenceEngine, modelId) }.historySummaryManager(provider),
            sessionManager = generationSessionManager,
            skillRegistry = skillRegistry,
            toolResolver = sessionToolResolver,
            presentationStore = generationPresentationStore,
            skillCatalog = skillCatalog,
            toolAllowlistStore = toolAllowlistStore,
            workspaceRepository = workspaceRepository,
            outputStore = toolOutputStore,
            externalFolders = externalFolderManager,
            interactionStore = generationInteractions,
            sshRuntime = sshRuntime,
            compactionPipeline = contextCompactionPipeline,
            compactionService = contextCompactionService,
            sessionWorkspace = sessionWorkspaceResolver::resolve,
        )
    }
    val generationCoordinator: com.promenar.nexara.domain.generation.GenerationCoordinator by lazy {
        com.promenar.nexara.data.generation.DefaultGenerationCoordinator(
            applicationScope = generationScope,
            runnerFactory = generationRunnerFactory,
            presentationStore = generationPresentationStore,
            executionGate = sessionExecutionGate,
            compactionService = contextCompactionService,
        )
    }

    private var _vectorRepository: com.promenar.nexara.domain.repository.IVectorRepository? = null
    val vectorRepository: com.promenar.nexara.domain.repository.IVectorRepository
        get() = _vectorRepository ?: VectorRepository(database.vectorDao(), embeddingClient).also { _vectorRepository = it }

    val skillRepository: ISkillRepository by lazy {
        com.promenar.nexara.data.repository.SkillRepository(database.skillDao())
    }

    val kgUsageStore: com.promenar.nexara.data.rag.KgUsageStore by lazy {
        com.promenar.nexara.data.rag.PreferencesKgUsageStore(getSharedPreferences("kg_usage", MODE_PRIVATE))
    }

    val tokenStatsRepository: ITokenStatsRepository by lazy {
        TokenStatsRepository(
            database.messageDao(), kgUsageStore,
            com.promenar.nexara.data.repository.RolledBackSummaryUsageStore(getSharedPreferences("nexara_settings", MODE_PRIVATE)),
        )
    }

    val workspaceRepository: com.promenar.nexara.domain.repository.IWorkspaceRepository by lazy {
        val lifecycle = com.promenar.nexara.data.repository.WorkspaceLifecycleTransaction(database)
        WorkspaceRepository(
            database.fileEntryDao(),
            database.workspaceSeqDao(),
            File(filesDir, "session_workspaces"),
            deleteCommitter = com.promenar.nexara.data.repository.WorkspaceDeletionTransaction(database)::delete,
            beforeDeleteCommitted = { workspaceRootUuid, ids ->
                vectorizationQueue.acquireDeleteBarrier(workspaceRootUuid, ids)
            },
            afterDeleteCommitted = { workspaceRootUuid, ids ->
                pendingDocumentIndexCoordinator.clearCommitted(workspaceRootUuid, ids)
            },
            recycleCommitter = lifecycle::recycle,
            restoreCommitter = lifecycle::restore,
            beforeRecycleCommitted = { workspaceRootUuid, ids ->
                vectorizationQueue.acquireDeleteBarrier(workspaceRootUuid, ids)
            },
            afterRecycleCommitted = { workspaceRootUuid, ids ->
                pendingDocumentIndexCoordinator.clearForRecycle(workspaceRootUuid, ids)
            },
            afterRestoreCommitted = { workspaceRootUuid, targets ->
                pendingDocumentIndexCoordinator.resumeAfterRestore(
                    workspaceRootUuid,
                    targets.map { it.fileUuid },
                )
                vectorizationQueue.resumeInterruptedTasks().getOrThrow()
            },
            executionGate = sessionExecutionGate,
            mutationJournal = workspaceFileMutationJournal,
            // 延迟到发布时解析，避免与索引协调器的初始化互相依赖。
            indexEventSink = com.promenar.nexara.data.rag.FileIndexEventSink { event ->
                pendingDocumentIndexCoordinator.publish(event)
            },
        )
    }

    val builtinExecService: com.promenar.nexara.data.exec.BuiltinExecService by lazy {
        com.promenar.nexara.data.exec.BuiltinExecService(
            workspace = workspaceRepository as com.promenar.nexara.data.repository.WorkspaceCommandHost,
            runner = com.promenar.nexara.data.exec.BuiltinCommandRunner(
                groupLauncher = listOf("/system/bin/setsid"),
                shell = "/system/bin/sh",
                signaller = com.promenar.nexara.data.exec.AndroidProcessGroupSignaller,
                scratchDirectory = { File(cacheDir, "exec-run") },
                clockNanos = android.os.SystemClock::elapsedRealtimeNanos,
            ),
            environment = com.promenar.nexara.data.exec.BuiltinExecEnvironment.create(this, pipEnabled = BuildConfig.DISTRIBUTION == "sideload", skillsDirectory = skillCatalog.userRoot),
            unavailablePythonAbi = { android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown" },
            keepAlive = com.promenar.nexara.background.exec.AndroidExecKeepAlive(
                this,
                execRunRegistry,
                // 命令前台服务（specialUse）只在侧载渠道声明；Play 渠道待政策评审，只持有唤醒锁。
                foregroundServiceAvailable = BuildConfig.DISTRIBUTION == "sideload",
            ),
            sessionWorkspace = sessionWorkspaceResolver::resolve,
            gitRemote = gitRemoteBroker,
        )
    }

    /** 会话工作区解析：自选目录（项目文件夹位于 `filesDir/projects`，共享存储仅侧载渠道）优先，否则为私有工作区。 */
    val sessionWorkspaceResolver: com.promenar.nexara.data.workspace.SessionWorkspaceResolver by lazy {
        com.promenar.nexara.data.workspace.SessionWorkspaceResolver(
            sessionDao = database.sessionDao(),
            workspaceDao = database.workspaceDao(),
            projectsRoot = File(filesDir, "projects"),
            sharedAccess = com.promenar.nexara.data.workspace.AndroidSharedWorkspaceAccess(this),
        )
    }

    /** 自选工作区的目录浏览与登记：项目文件夹与 [sessionWorkspaceResolver] 共用同一项目根。 */
    val workspaceDirectoryBrowser: com.promenar.nexara.data.workspace.WorkspaceDirectoryBrowser by lazy {
        com.promenar.nexara.data.workspace.WorkspaceDirectoryBrowser(
            projectsRoot = sessionWorkspaceResolver.projectsRoot,
            shared = com.promenar.nexara.data.workspace.AndroidSharedStorageVolumes(this),
            workspaceDao = database.workspaceDao(),
        )
    }

    /** 知识库“工作区”分组：只读浏览已登记目录，单个文件复制进知识库后经索引协调器入队。 */
    val workspaceLibrary: com.promenar.nexara.data.workspace.WorkspaceLibrary by lazy {
        com.promenar.nexara.data.workspace.WorkspaceLibrary(
            workspaceDao = database.workspaceDao(),
            resolveWorkspace = sessionWorkspaceResolver::resolveWorkspace,
            backendFor = sessionWorkspaceResolver::backend,
            knowledge = workspaceRepository,
            indexEvents = pendingDocumentIndexCoordinator,
        )
    }

    /** Git 变更面板的只读数据：打包 Python 直接运行 `nexara_git --nexara-inspect`，不经过 shell_exec 与执行审批。 */
    val gitChangesService: com.promenar.nexara.data.git.GitChangesService by lazy {
        val locator = com.promenar.nexara.data.git.SessionGitWorkspaceLocator(
            resolveWorkspace = sessionWorkspaceResolver::resolve,
            privateRoot = { sessionId -> workspaceRepository.ensureSessionRoot(sessionId).physicalRootPath },
        )
        com.promenar.nexara.data.git.GitChangesService(
            locate = locator::locate,
            inspector = com.promenar.nexara.data.git.GitInspector(
                // 只读检查不需要 pip 与用户站点目录，保持 PYTHONNOUSERSITE，确保加载打包的 dulwich。
                runtime = com.promenar.nexara.data.git.BuiltinGitPythonRuntime(
                    com.promenar.nexara.data.exec.BuiltinExecEnvironment.create(this, pipEnabled = false, skillsDirectory = skillCatalog.userRoot),
                ),
                runner = com.promenar.nexara.data.git.BuiltinGitInspectCommandRunner(
                    com.promenar.nexara.data.exec.BuiltinCommandRunner(
                        groupLauncher = listOf("/system/bin/setsid"),
                        shell = "/system/bin/sh",
                        signaller = com.promenar.nexara.data.exec.AndroidProcessGroupSignaller,
                        scratchDirectory = { File(cacheDir, "git-inspect-run") },
                        clockNanos = android.os.SystemClock::elapsedRealtimeNanos,
                    ),
                ),
                outputDirectory = { File(cacheDir, "git-inspect") },
            ),
        )
    }

    override val execRunRegistry: com.promenar.nexara.data.exec.ExecRunRegistry by lazy {
        com.promenar.nexara.data.exec.ExecRunRegistry()
    }

    internal val globalKnowledgeWorkspaceProvisioner: RagWorkspaceProvisioner by lazy {
        RagWorkspaceProvisioner(
            filesDir = filesDir,
            database = database,
            workspaceRepository = workspaceRepository,
        )
    }

    val externalFolderManager: com.promenar.nexara.domain.storage.ExternalWorkspaceAccess by lazy {
        com.promenar.nexara.data.storage.ExternalFolderManager(
            this, database.externalMountDao(), workspaceRepository, pendingDocumentIndexCoordinator,
            workspaces = sessionWorkspaceResolver,
            userSkillsDirectory = skillCatalog.userRoot,
        )
    }

    val pendingDocumentIndexCoordinator: PendingDocumentIndexCoordinator by lazy {
        PendingDocumentIndexCoordinator(
            downstream = FileIndexEventSink { event -> vectorizationQueue.publish(event) },
            resolveCurrentTarget = { target ->
                val entry = database.fileEntryDao().getActiveByUuid(
                    target.workspaceRootUuid,
                    target.fileUuid,
                )
                entry?.let {
                    target.copy(
                        contentHash = entry.hash,
                        targetEpoch = entry.updatedAt,
                    )
                }
            },
        )
    }

    val fileOperationRepository: com.promenar.nexara.domain.repository.IFileOperationRepository by lazy {
        FileOperationRepository(
            database.fileEntryDao(),
            database.fileVersionDao(),
            indexEventSink = pendingDocumentIndexCoordinator,
            executionGate = sessionExecutionGate,
        )
    }

    val taskRepository: com.promenar.nexara.domain.repository.ITaskRepository by lazy {
        TaskRepository(database.taskNodeDao(), database)
    }

    val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addInterceptor(com.promenar.nexara.utils.MetroLogInterceptor())
                // tailnet 目标经内置 Tailscale 本机代理；其它目标仍按系统默认选择器（无系统代理即直连）。
                config {
                    proxySelector(tailnetManager.proxySelector)
                    proxyAuthenticator(tailnetManager.proxyAuthenticator)
                }
            }
        }
    }

    val presetSkillRegistry: DefaultSkillRegistry by lazy {
        DefaultSkillRegistry().apply {
            fun mounted(skill: com.promenar.nexara.ui.chat.manager.registry.SkillDefinition) =
                com.promenar.nexara.ui.chat.manager.skills.MountedWorkspaceSkill(skill, externalFolderManager, workspaceRepository, fileOperationRepository, pendingDocumentIndexCoordinator)
            register(CalculatorSkill())
            register(com.promenar.nexara.ui.chat.manager.skills.TermuxExecSkill(termuxRuntime::executeRequest) {
                com.promenar.nexara.data.termux.TermuxPreferences.isEnabled(getSharedPreferences("nexara_settings", MODE_PRIVATE))
            })
            com.promenar.nexara.ui.chat.manager.skills.sshTools(sshRuntime).forEach(::register)
            com.promenar.nexara.ui.chat.manager.skills.sshHostManagementTools(
                store = sshHostStore,
                runtime = object : com.promenar.nexara.ui.chat.manager.skills.SshHostManagementRuntime {
                    override suspend fun probe(alias: String) = sshRuntime.probe(alias)
                    override suspend fun run(owner: String, alias: String, command: String): Int? =
                        sshRuntime.exec(owner, alias, command, timeoutSeconds = 30, maxOutputChars = 4_000).let { if (it.timedOut) null else it.exitCode }
                    override suspend fun stopForward(alias: String) = sshRuntime.stopForward(alias)
                    override fun generateKey(): Pair<ByteArray, String> =
                        com.promenar.nexara.data.ssh.SshKeyGenerator.generate().use { it.privateKey.copyOf() to it.publicKey }
                },
                prompts = sshUserPrompts,
            ) {
                com.promenar.nexara.data.ssh.SshAgentPreferences.isHostManagementEnabled(getSharedPreferences("nexara_settings", MODE_PRIVATE))
            }.forEach(::register)
            register(WebSearchSkill(this@NexaraApplication, httpClient, secretStore))
            register(WebFetchSkill(httpClient))
            register(ImageGenerationSkill(this@NexaraApplication, ProviderManager.getInstance(), workspaceMediaStore, workspaceMediaDownloader))
            register(com.promenar.nexara.ui.chat.manager.skills.MediaFetchSkill(workspaceMediaStore, workspaceMediaDownloader,
                mounts = externalFolderManager, sessionWorkspace = sessionWorkspaceResolver::resolve))
            register(mounted(FileReadSkill(fileOperationRepository, workspaceRepository)))
            register(mounted(FileWriteSkill(fileOperationRepository, workspaceRepository)))
            register(mounted(FileDiffSkill(fileOperationRepository, workspaceRepository)))
            register(mounted(FilePatchSkill(fileOperationRepository, workspaceRepository)))
            register(mounted(FileListSkill(workspaceRepository)))
            register(mounted(FileSearchSkill(workspaceRepository, fileOperationRepository)))
            register(mounted(CreateFileSkill(workspaceRepository, fileOperationRepository)))
            register(mounted(CreateDirectorySkill(workspaceRepository)))
            register(mounted(MoveFileSkill(workspaceRepository, pendingDocumentIndexCoordinator)))
            register(mounted(DeleteFileSkill(workspaceRepository)))
            register(ExecJsSkill(scriptToolRunner))
            register(com.promenar.nexara.ui.chat.manager.skills.RunScriptSkill(scriptToolRunner))
            register(com.promenar.nexara.ui.chat.manager.skills.PythonSandboxSkill(scriptToolRunner))
            register(com.promenar.nexara.ui.chat.manager.skills.ShellExecSkill(builtinExecService))
            register(com.promenar.nexara.ui.chat.manager.skills.PythonExecSkill(builtinExecService))
            register(InitializePlanSkill(taskRepository))
            register(UpdatePlanSkill(taskRepository))
            register(GetPlanSkill(taskRepository))
            register(com.promenar.nexara.ui.chat.manager.skills.ReadToolOutputSkill(toolOutputStore))
            register(DropPlanSkill(taskRepository))
            register(com.promenar.nexara.ui.chat.manager.skills.TailnetStatusSkill(tailnetManager))
        }
    }

    val userSkillRegistry: UserSkillRegistry by lazy {
        UserSkillRegistry(skillRepository as SkillRepository)
    }

    val mcpSkillRegistry: McpSkillRegistry by lazy {
        McpSkillRegistry(skillRepository as SkillRepository, httpClient, generationScope, connectionSettings = mcpConnectionSettings, mediaStore = workspaceMediaStore)
    }

    /** Agent Skills 指令包存储（ADR-022）。 */
    val agentSkillStore: com.promenar.nexara.data.skills.AgentSkillStore by lazy {
        com.promenar.nexara.data.skills.AgentSkillStore(
            File(filesDir, "agent_skills"),
            getSharedPreferences("nexara_settings", MODE_PRIVATE),
        )
    }

    /** 用户级与工作区项目级技能的文件目录入口。 */
    val skillCatalog: com.promenar.nexara.data.skills.SkillCatalog by lazy {
        com.promenar.nexara.data.skills.FileSkillCatalog(
            File(filesDir, "agent_skills"),
            getSharedPreferences("nexara_settings", MODE_PRIVATE),
            File(noBackupFilesDir, "skill_state"),
        )
    }

    val skillRegistry: SkillRegistry by lazy { createSkillRegistry() }

    /** 会话工具表绑定自己的工作区，后台生成不依赖界面当前选中的会话。 */
    private fun createSkillRegistry(sessionId: String? = null): SkillRegistry =
        ModularSkillRegistry(
            listOf(
                presetSkillRegistry,
                com.promenar.nexara.ui.chat.manager.skills.AgentSkillToolRegistry(skillCatalog, sessionWorkspaceResolver::resolve, sessionId),
                userSkillRegistry,
                mcpSkillRegistry,
            )
        )

    val sessionToolResolver: com.promenar.nexara.data.generation.SessionToolResolver by lazy {
        com.promenar.nexara.data.generation.SessionToolResolver { session ->
            com.promenar.nexara.data.generation.DefaultSessionToolResolver(
                getSharedPreferences("nexara_settings", MODE_PRIVATE),
                createSkillRegistry(session.id),
            ).resolve(session)
        }
    }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("nexara_provider", MODE_PRIVATE)
    }

    private val _providerConfigurationVersion = MutableStateFlow(0L)
    val providerConfigurationVersion: StateFlow<Long> = _providerConfigurationVersion
    private var localProviderOverride: String? = null
    val llmProvider: LlmProvider get() = buildCredentialResolvingProvider()

    private var _unifiedLlmClient: com.promenar.nexara.data.remote.UnifiedLlmClient? = null
    val unifiedLlmClient: com.promenar.nexara.data.remote.UnifiedLlmClient?
        get() = _unifiedLlmClient ?: buildUnifiedLlmClient().also { _unifiedLlmClient = it }

    /** 聊天主路径按每次请求的稳定模型归属解析，不复用 default Provider 客户端。 */
    val providerRequestRouter: ProviderRequestRouter by lazy {
        val middlewares = if (com.promenar.nexara.BuildConfig.DEBUG) {
            listOf(com.promenar.nexara.data.remote.middleware.MetroLoggingMiddleware())
        } else {
            emptyList()
        }
        DefaultProviderRequestRouter(
            ProviderManager.getInstance(),
            middlewares,
            allowLoopbackHttp = com.promenar.nexara.BuildConfig.DEBUG,
        )
    }

    var hapticEnabled: Boolean = true
        internal set

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
                add(SvgDecoder.Factory())
            }
            .build()
    }

    override fun attachBaseContext(base: Context) {
        if (android.os.Process.isIsolated()) {
            super.attachBaseContext(base)
            return
        }
        val lang = LocaleHelper.getSavedLanguage(base)
        super.attachBaseContext(LocaleHelper.applyLanguage(base, lang))
    }

    override fun onCreate() {
        super.onCreate()
        if (android.os.Process.isIsolated()) return
        instance = this
        com.promenar.nexara.utils.NexaraLogger.init(this)

        // 独立 relay 进程不得打开 Room、pending payload 或注册任何业务 writer。
        if (currentProcessName().endsWith(RestoreRelayActivity.PROCESS_SUFFIX)) {
            restoreRelayEarlyExit = true
            NexaraLogger.log("[NexaraRestoreRelay] application_early_exit_before_runtime_and_writers")
            return
        }

        ModelCatalogRuntime.initialize(this)

        PDFBoxResourceLoader.init(applicationContext)
        startupScope.launch(Dispatchers.IO) {
            runCatching {
                val legacy = File(noBackupFilesDir, "tool-grants")
                check(!legacy.exists() || legacy.deleteRecursively()) { "旧会话授权目录清理失败" }
            }.onFailure { NexaraLogger.log("[ToolAllowlist] 旧会话授权清理失败：${it::class.java.simpleName}") }
        }
        startBackupRecovery()
        // 内置 Tailscale 启用时随 App 启动拉起子进程；App 进程结束时子进程随 stdin 关闭退出。
        startupScope.launch(Dispatchers.IO) { tailnetManager.start() }
    }

    private fun currentProcessName(): String = try {
        Application.getProcessName()
    } catch (_: NoSuchMethodError) {
        // 旧 Robolectric shadow 不提供该静态方法；真实 minSdk 31 设备始终走上支。
        packageName
    }

    fun retryStartupRecovery() {
        startBackupRecovery()
    }

    fun startDualDatabaseRecovery() {
        val required = _startupState.value as? BackupStartupState.DualDatabaseRecoveryRequired ?: return
        acceptedDualDatabaseOfferToken = required.offerToken
        startBackupRecovery()
    }

    private fun startBackupRecovery() {
        if (startupRecoveryJob?.isActive == true || _startupState.value == BackupStartupState.Ready) return
        _startupState.value = BackupStartupState.Recovering
        startupRecoveryJob = startupScope.launch {
            try {
                val dualResult = withContext(Dispatchers.IO) {
                    acceptedDualDatabaseOfferToken?.let { token ->
                        acceptedDualDatabaseOfferToken = null
                        dualDatabaseRecoveryBootstrap.recover(token)
                    } ?: dualDatabaseRecoveryBootstrap.inspectOrResume()
                }
                when (dualResult) {
                    DualDatabaseBootstrapResult.Normal,
                    is DualDatabaseBootstrapResult.Completed -> Unit
                    is DualDatabaseBootstrapResult.RecoveryRequired -> {
                        _startupState.value = BackupStartupState.DualDatabaseRecoveryRequired(
                            dualResult.offer.summary,
                            dualResult.offer.offerToken,
                        )
                        return@launch
                    }
                    is DualDatabaseBootstrapResult.Blocked -> {
                        _startupState.value = BackupStartupState.DualDatabaseRecoveryBlocked(dualResult.detail)
                        return@launch
                    }
                }
                val runtime = withContext(Dispatchers.IO) {
                    if (!::backupRuntime.isInitialized) backupRuntime = createBackupRuntime()
                    backupRuntime
                }
                if (runtime.recoverBeforeWriters() == BackupStartupState.Ready) {
                    com.promenar.nexara.startup.PostBackupStartupRecoverySequence(
                        recoverWorkspaceJournal = {
                            withContext(Dispatchers.IO) {
                                java.nio.file.Files.createDirectories(sessionWorkspaceParent)
                                LegacyWorkspaceRootAdoptionCoordinator(
                                    filesDir = filesDir,
                                    defaultWorkspaceParent = File(filesDir, "session_workspaces"),
                                    sessionDao = database.sessionDao(),
                                    fileEntryDao = database.fileEntryDao(),
                                    workspaceRepository = workspaceRepository,
                                ).adoptAll()
                                workspaceFileMutationRecoveryCoordinator.recoverOrThrow()
                                workspaceMutationRecoveryCoordinator.recoverOrThrow()
                            }
                        },
                        recoverToolLedger = {
                            withContext(Dispatchers.IO) {
                                // 外部副作用是否已发生不可判定，禁止自动重放 RUNNING 调用。
                                toolExecutionLedger.recoverInterruptedRunning(
                                    "进程在工具执行期间中断，请确认结果后重新发起",
                                )
                                toolExecutionLedger.recoverApprovalState()
                            }
                        },
                        recoverWriterTombstones = {
                            withContext(Dispatchers.IO) {
                                val attention = com.promenar.nexara.data.worker.RecycleBinCleanupWorker
                                    .recoverPendingDeletions(database)
                                check(attention.failedRoots.isEmpty() && attention.failedTombstones.isEmpty()) {
                                    "工作区 tombstone 恢复存在冲突，拒绝进入可写态"
                                }
                            }
                        },
                        initializeWriters = { initializeAfterRecoveryOnce() },
                        resumeVectorQueue = {
                            withContext(Dispatchers.IO) {
                                vectorizationQueue.resumeInterruptedTasks().getOrThrow()
                            }
                        },
                    ).runOrThrow()
                    runtime.restoreResult.value?.skills
                        ?.takeIf { it.skippedGitRepositories.isNotEmpty() || it.skippedSymbolicLinks > 0 }
                        ?.let { _skillRestoreNotice.value = it }
                    if (runtime.restoreNotApplied.value) _restoreNotAppliedNotice.value = true
                    _startupState.value = BackupStartupState.Ready
                    startModelCatalogUpdates()
                } else {
                    _startupState.value = BackupStartupState.Blocked
                }
            } catch (error: Exception) {
                NexaraLogger.logError("BackupStartupRecovery", error)
                _startupState.value = BackupStartupState.Blocked
            } catch (error: Error) {
                _startupState.value = BackupStartupState.Blocked
                throw error
            }
        }
    }

    private fun initializeAfterRecoveryOnce() {
        if (writersInitialized) return
        startupWriterSession = createStartupWriterTransaction().commit()
        writersInitialized = true
    }

    private fun createStartupWriterTransaction() = StartupWriterTransaction(
        preflight = ::prepareAndMigrateStartupWriters,
        registrations = ::startupWriterRegistrations,
    )

    private fun prepareAndMigrateStartupWriters(): PreparedStartupWriters {
        backupRuntime.requireWriterGate()
        // 第一阶段仅执行读取和不注册外部资源的对象构造。
        val settingsPrefs = getSharedPreferences("nexara_settings", MODE_PRIVATE)
        val nextHapticEnabled = settingsPrefs.getBoolean("haptic_enabled", true)
        val lastModelToLoad = localInferenceRuntimeGate.startupModelPath(
            localModelsEnabled = settingsPrefs.getBoolean("local_models_enabled", false),
            autoLoadEnabled = settingsPrefs.getBoolean("local_auto_load", false),
            persistedModelPath = prefs.getString("last_local_model", null),
        )
        val inferenceEngine = if (localInferenceRuntimeGate.isAvailable) localInferenceEngine else null
        val processObserver = inferenceEngine?.let { engine ->
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    engine.mainSlot.value.modelPath?.let { mainPath ->
                        prefs.edit().putString("last_local_model", mainPath).apply()
                    }
                }
            }
        }

        // 第二阶段是进程内幂等 migration；后续注册失败重试时不会重复执行。
        startupMigrationStage.run(StartupMigration.WORKSPACE_DIRECTORY) {
            val workSpaceDir = java.io.File(filesDir, "WorkSpace")
            check((workSpaceDir.isDirectory || workSpaceDir.mkdirs()) && workSpaceDir.isDirectory) {
                "workspace_directory_unavailable"
            }
            Unit
        }
        val providerManager = startupMigrationStage.run(StartupMigration.PROVIDER_MIGRATION_AND_INIT) {
            ProviderManager.init(this, secretStore)
        }

        // 依赖 ProviderManager 的对象只能在 migration 完成后构造。
        val preparedVectorizationQueue = vectorizationQueue
        return PreparedStartupWriters(
            providerManager = providerManager,
            settingsPrefs = settingsPrefs,
            hapticEnabled = nextHapticEnabled,
            inferenceEngine = inferenceEngine,
            vectorizationQueue = preparedVectorizationQueue,
            lastModelToLoad = lastModelToLoad,
            processObserver = processObserver,
        )
    }

    private fun startupWriterRegistrations(
        prepared: PreparedStartupWriters,
    ): List<StartupWriterRegistration> {
        return listOfNotNull(
            prepared.processObserver?.let { observer ->
                reversibleRegistration(
                    register = { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) },
                    rollback = { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) },
                )
            },
            reversibleRegistration(
                register = { prefs.registerOnSharedPreferenceChangeListener(providerListener) },
                rollback = { prefs.unregisterOnSharedPreferenceChangeListener(providerListener) },
            ),
            reversibleRegistration(
                register = {
                    prepared.settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener)
                },
                rollback = {
                    prepared.settingsPrefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
                },
            ),
            jobRegistration(prepared) {
                startupBackgroundHealthMonitor.launch(
                    scope = startupScope,
                    task = StartupBackgroundTask.PROVIDER_CONFIGURATION,
                    start = CoroutineStart.LAZY,
                ) {
                    startupBackgroundHealthMonitor.collectResilient(
                        task = StartupBackgroundTask.PROVIDER_CONFIGURATION,
                        events = prepared.providerManager.configurationChanges,
                    ) {
                        _providerConfigurationVersion.value += 1
                        rebuildEmbeddingClient()
                        rebuildRerankClient()
                        resetVectorizationQueue()
                        _unifiedLlmClient = null
                    }
                }
            },
            prepared.lastModelToLoad?.let { modelPath ->
                jobRegistration(prepared) {
                    startupBackgroundHealthMonitor.launch(
                        scope = startupScope,
                        task = StartupBackgroundTask.LOCAL_MODEL_AUTO_LOAD,
                        start = CoroutineStart.LAZY,
                    ) {
                        checkNotNull(prepared.inferenceEngine) { "本地推理启动计划与运行时能力不一致" }
                            .loadModel(SlotType.MAIN, modelPath).getOrThrow()
                    }
                }
            },
            reversibleRegistration(
                register = { hapticEnabled = prepared.hapticEnabled },
                rollback = { hapticEnabled = true },
            ),
        )
    }

    private fun reversibleRegistration(
        register: () -> Unit,
        rollback: () -> Unit,
    ): StartupWriterRegistration {
        var attempted = false
        return StartupWriterRegistration(
            register = {
                attempted = true
                register()
            },
            rollback = {
                if (attempted) {
                    attempted = false
                    rollback()
                }
            },
        )
    }

    private fun jobRegistration(
        prepared: PreparedStartupWriters,
        create: () -> Job,
    ): StartupWriterRegistration {
        var job: Job? = null
        return reversibleRegistration(
            register = {
                create().also { created ->
                    job = created
                    prepared.jobs += created
                    check(created.start()) { "startup_job_not_started" }
                }
            },
            rollback = {
                job?.cancel()
                prepared.jobs.remove(job)
                job = null
            },
        )
    }

    private data class PreparedStartupWriters(
        val providerManager: ProviderManager,
        val settingsPrefs: SharedPreferences,
        val hapticEnabled: Boolean,
        val inferenceEngine: LocalInferenceEngine?,
        val vectorizationQueue: VectorizationQueue,
        val lastModelToLoad: String?,
        val processObserver: DefaultLifecycleObserver?,
        val jobs: MutableList<Job> = mutableListOf(),
    )

    /** 仅供测试应用替换 AndroidKeyStore/真实文件系统依赖；生产始终使用安全 runtime。 */
    protected open fun createBackupRuntime(): BackupRuntime =
        BackupRuntime.createAndroid(this, database, secretStore)

    internal open fun createDualDatabaseRecoveryBootstrap(): DualDatabaseRecoveryBootstrap {
        val databaseDirectory = requireNotNull(getDatabasePath("nexara_v2.db").parentFile).toPath()
        val noBackupDirectory = requireNotNull(androidx.core.content.ContextCompat.getNoBackupFilesDir(this)).toPath()
        val journal = FileDualDatabaseRecoveryJournal(
            noBackupDirectory,
            AndroidRestoreJournalAuthenticator("nexara.dual.database.recovery.hmac.v1"),
        )
        return DualDatabaseRecoveryBootstrap(
            lockDirectory = noBackupDirectory,
            sourceProbe = AndroidDualDatabaseSourceProbe(this),
            journal = journal,
            executorProvider = {
                val snapshotStore = RecoverySnapshotStore(
                    allowedSourceRoots = setOf(databaseDirectory, filesDir.toPath()),
                    maxFiles = 200_000,
                    maxTotalBytes = 16L * 1024L * 1024L * 1024L,
                    trustedAppDataRoot = dataDir.toPath(),
                )
                AndroidDualDatabaseRecoveryExecutor(this, journal, snapshotStore)
            },
        )
    }

    private var _embeddingClient: EmbeddingClient? = null
    val embeddingClient: EmbeddingClient
        get() = _embeddingClient ?: buildEmbeddingClient().also { _embeddingClient = it }

    private fun buildEmbeddingClient(): EmbeddingClient {
        val settingsPrefs = getSharedPreferences("nexara_settings", MODE_PRIVATE)
        // 1. 优先读取显式手动配置的 embedding_base_url (nexara_provider)
        var baseUrl = prefs.getString("embedding_base_url", "") ?: ""
        var apiKey = readSecret(SecretCatalog.embeddingApiKey)
        var resolvedBy = if (baseUrl.isNotBlank()) "manual" else ""
        
        // 2. 如果手动配置为空，则根据预设模型自动查找所属提供商配置
        val presetModel = settingsPrefs.getString("preset_embedding_model", "") ?: ""
        if (baseUrl.isBlank() && presetModel.isNotBlank()) {
            val config = ProviderManager.getInstance().getProviderConfigByModelId(presetModel)
            if (config != null) {
                baseUrl = config.baseUrl
                apiKey = config.apiKey
                resolvedBy = "preset-model"
            }
        }
        
        val presetRemoteModel = ProviderManager.getInstance().getRemoteModelId(presetModel).orEmpty()
        val model = prefs.getString("embedding_model", "")
            ?.ifBlank { presetRemoteModel }
            ?: presetRemoteModel
        NexaraLogger.log("[EmbeddingClient] 构建: model=$model resolvedBy=$resolvedBy baseUrlSet=${baseUrl.isNotBlank()} apiKeySet=${apiKey.isNotBlank()}")
        return EmbeddingClient(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            localEngine = localInferenceRuntimeGate.createIfAvailable { localInferenceEngine },
        )
    }

    fun rebuildEmbeddingClient() {
        _embeddingClient = buildEmbeddingClient()
        rebuildMemoryManager()
        rebuildGraphExtractor()
        _vectorRepository = null
        _imageService = null
        _microGraphExtractor = null
        _kgProvider = null
    }

    private var _rerankClient: RerankClient? = null
    val rerankClient: RerankClient
        get() = _rerankClient ?: buildRerankClient().also { _rerankClient = it }

    private fun buildRerankClient(): RerankClient {
        val settingsPrefs = getSharedPreferences("nexara_settings", MODE_PRIVATE)
        var baseUrl = prefs.getString("embedding_base_url", "") ?: ""
        var apiKey = readSecret(SecretCatalog.embeddingApiKey)
        
        val presetModel = settingsPrefs.getString("preset_rerank_model", "") ?: ""
        if (baseUrl.isBlank() && presetModel.isNotBlank()) {
            val config = ProviderManager.getInstance().getProviderConfigByModelId(presetModel)
            if (config != null) {
                baseUrl = config.baseUrl
                apiKey = config.apiKey
            }
        }
        
        if (baseUrl.isBlank()) {
            baseUrl = prefs.getString("base_url", "") ?: ""
            apiKey = getSavedProviderConfig()?.apiKey.orEmpty()
        }
        
        val savedConfig = getSavedProviderConfig()
        val remotePresetModel = ProviderManager.getInstance().getRemoteModelId(presetModel).orEmpty()
        val maxPerCall = ragConfigPersistence.loadFullConfig().rerankMaxPerCall
        NexaraLogger.log("[RerankClient] 构建: model=$presetModel hasBaseUrl=${baseUrl.isNotBlank()} maxPerCall=$maxPerCall")
        return RerankClient(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelId = remotePresetModel,
            llmProtocol = savedConfig?.let {
                try { llmProvider.protocol } catch (_: Exception) { null }
            },
            llmModelId = savedConfig?.model,
            maxPerCall = maxPerCall
        )
    }

    fun rebuildRerankClient() {
        _rerankClient = buildRerankClient()
        rebuildMemoryManager()
    }

    private val providerListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "base_url" || key == "embedding_base_url") {
            rebuildEmbeddingClient()
            rebuildRerankClient()
            resetVectorizationQueue()
        }
        if (key == "model") {
            rebuildEmbeddingClient()
            resetVectorizationQueue()
        }
    }

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "preset_embedding_model" || key == "all_models" || key == "enabled_models") {
            rebuildEmbeddingClient()
            resetVectorizationQueue()
        }
        if (key == "preset_rerank_model" || key == "all_models") {
            rebuildRerankClient()
        }
        // 非敏感额外提供商地址变化仍由 prefs 监听；凭证变化走 ProviderManager.configurationChanges。
        if (key?.startsWith("extra_provider_") == true && key.endsWith("_base_url")) {
            rebuildEmbeddingClient()
            rebuildRerankClient()
            resetVectorizationQueue()
        }
    }

    private var _imageService: ImageService? = null
    val imageService: ImageService
        get() = _imageService ?: ImageService(
            context = this,
            embeddingClient = embeddingClient,
            vectorStore = vectorStore,
            textSplitter = textSplitter
        ).also { _imageService = it }

    val vectorStore: VectorStore by lazy {
        VectorStore(
            vectorDao = database.vectorDao(),
            kgNodeDao = database.kgNodeDao(),
            kgEdgeDao = database.kgEdgeDao(),
            kgEvidenceDao = database.kgEvidenceDao(),
        )
    }

    val graphStore: GraphStore by lazy {
        GraphStore(database)
    }

    val keywordSearcher: KeywordSearcher by lazy {
        KeywordSearcher(vectorDao = database.vectorDao())
    }

    /** RAG 配置持久化读取器，与 RagViewModel 共用同一个 SharedPreferences */
    private val ragConfigPersistence: RagConfigPersistence by lazy {
        RagConfigPersistence(getSharedPreferences("rag_settings", MODE_PRIVATE))
    }

    private var _memoryManager: MemoryManager? = null
    val memoryManager: MemoryManager
        get() = _memoryManager ?: MemoryManager(
            vectorStore = vectorStore,
            keywordSearcher = keywordSearcher,
            graphStore = graphStore,
            embeddingClient = embeddingClient,
            rerankClient = rerankClient,
            queryRewriter = QueryRewriter(llmProvider.protocol, modelId = getSavedProviderConfig()?.model),
            ragConfig = ragConfigPersistence.loadFullConfig()  // P0: 从用户保存的配置读取，不再硬编码默认值
        ).also { _memoryManager = it }

    fun rebuildMemoryManager() {
        _memoryManager = null
    }

    val textSplitter: RecursiveCharacterTextSplitter by lazy {
        RecursiveCharacterTextSplitter(chunkSize = 800, chunkOverlap = 100)
    }

    private val kgExtractorLock = Any()

    private fun kgExtractionSettings(
        config: com.promenar.nexara.data.rag.RagConfiguration = ragConfigPersistence.loadFullConfig(),
    ) = com.promenar.nexara.data.rag.KgExtractionSettings.resolve(
        config = config,
        summaryModel = getSharedPreferences("nexara_settings", MODE_PRIVATE).getString("preset_summary_model", null),
        mainModel = getSavedProviderConfig()?.model?.takeIf { it.isNotBlank() }
            ?.let { com.promenar.nexara.data.remote.stableModelId("default", it) },
    )

    private fun createKgRequestClient() = com.promenar.nexara.data.rag.KgRequestClient(
        router = providerRequestRouter,
        usageStore = kgUsageStore,
        defaultMaxOutputTokens = ragConfigPersistence.loadFullConfig().kgRequestMaxOutputTokens.coerceIn(128, 16384),
        protocolFactory = { resolved ->
            val config = resolved.config
            com.promenar.nexara.data.remote.protocol.ProtocolFactory.create(
                type = config.protocolType,
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = resolved.remoteModelId,
                serviceAccountJson = config.serviceAccountJson,
                projectId = config.projectId,
                location = config.location,
                localEngine = if (config.protocolType is com.promenar.nexara.data.remote.protocol.ProtocolType.Local) localInferenceEngine else null,
            )
        },
    )

    private var _microGraphExtractor: MicroGraphExtractor? = null
    val microGraphExtractor: MicroGraphExtractor
        get() = synchronized(kgExtractorLock) {
            _microGraphExtractor ?: run {
                val settings = kgExtractionSettings()
                MicroGraphExtractor(
                    requestClient = createKgRequestClient(),
                    graphStore = graphStore,
                    jitCacheDao = database.kgJitCacheDao(),
                    modelId = settings.modelId,
                    systemPrompt = settings.systemPrompt,
                    executionGate = sessionExecutionGate,
                ).also { _microGraphExtractor = it }
            }
        }

    private var _kgProvider: KgProvider? = null
    val kgProvider: KgProvider
        get() = synchronized(kgExtractorLock) {
            _kgProvider ?: com.promenar.nexara.data.rag.PersistentGraphKgProvider(
                graphStore, MicroGraphKgAdapter { microGraphExtractor },
            ) { ragConfigPersistence.loadFullConfig() }.also { _kgProvider = it }
        }

    val webSearchContextProvider: WebSearchProvider by lazy {
        WebSearchContextProvider(this, httpClient, secretStore)
    }

    private var _graphExtractor: com.promenar.nexara.data.rag.GraphExtractor? = null
    val graphExtractor: com.promenar.nexara.data.rag.GraphExtractor
        get() = synchronized(kgExtractorLock) {
            _graphExtractor ?: run {
                val config = ragConfigPersistence.loadFullConfig()
                val settings = kgExtractionSettings(config)
                com.promenar.nexara.data.rag.GraphExtractor(
                    requestClient = createKgRequestClient(),
                    graphStore = graphStore,
                    modelId = settings.modelId,
                    systemPrompt = settings.systemPrompt,
                    chunkSize = config.docChunkSize.coerceAtLeast(400),
                    chunkOverlap = config.chunkOverlap.coerceAtMost(200),
                    timeoutMs = config.kgExtractionTimeoutSeconds.coerceIn(5, 300) * 1000L,
                    budget = com.promenar.nexara.data.rag.KgExtractionBudget.from(config),
                ).also { _graphExtractor = it }
            }
        }

    fun rebuildGraphExtractor() {
        synchronized(kgExtractorLock) {
            _graphExtractor = null
            _microGraphExtractor?.close()
            _microGraphExtractor = null
            _kgProvider = null
        }
        resetVectorizationQueue()
    }

    private val vectorizationQueueResource = com.promenar.nexara.utils.SynchronizedResettableResource<com.promenar.nexara.data.rag.VectorizationQueue>(
        dispose = com.promenar.nexara.data.rag.VectorizationQueue::shutdown,
    )

    private fun resetVectorizationQueue() {
        val handle = vectorizationQueueResource.beginReset() ?: return
        startupScope.launch(Dispatchers.IO) {
            try {
                handle.value.shutdownForReplacement()
            } catch (failure: Exception) {
                NexaraLogger.logError("VectorizationQueue.handoff", failure)
            } finally {
                vectorizationQueueResource.completeReset(handle)
            }
            vectorizationQueue.resumeInterruptedTasks().exceptionOrNull()?.let { failure ->
                NexaraLogger.logError("VectorizationQueue.resumeAfterHandoff", failure)
            }
        }
    }
    val documentIndexService: com.promenar.nexara.data.rag.DocumentIndexService
        get() = createDocumentIndexService(ragConfigPersistence.loadFullConfig())

    private fun createDocumentIndexService(
        config: com.promenar.nexara.data.rag.RagConfiguration,
    ): com.promenar.nexara.data.rag.DocumentIndexService =
        com.promenar.nexara.data.rag.RoomDocumentIndexService(
            database = database,
            executionGate = sessionExecutionGate,
            onDeleted = graphExtractor::clearCheckpoints,
            candidateBuilder = com.promenar.nexara.data.rag.WorkspaceDocumentIndexCandidateBuilder(
                fileEntryDao = database.fileEntryDao(),
                embeddingClient = embeddingClient,
                ragConfig = config,
                graphCandidateBuilder = com.promenar.nexara.data.rag.GraphExtractorKnowledgeGraphCandidateBuilder(
                    graphExtractor,
                ),
            ),
        )

    val vectorizationQueue: com.promenar.nexara.data.rag.VectorizationQueue
        get() = vectorizationQueueResource.getOrCreate {
            val config = ragConfigPersistence.loadFullConfig()
            com.promenar.nexara.data.rag.VectorizationQueue(
                vectorStore = vectorStore,
                embeddingClient = embeddingClient,
                graphExtractor = graphExtractor,
                vectorDao = database.vectorDao(),
                vectorizationTaskDao = database.vectorizationTaskDao(),
                ragConfig = config,
                fileEntryDao = database.fileEntryDao(),
                documentIndexService = createDocumentIndexService(config),
                executionGate = sessionExecutionGate,
            )
        }

    fun observeVectorizationQueueInstances(
        observer: (com.promenar.nexara.data.rag.VectorizationQueue) -> Unit,
    ): AutoCloseable = vectorizationQueueResource.observeInstances(observer)

    val defaultAgents: List<com.promenar.nexara.domain.model.Agent> by lazy {
        // DB 只保存与 Locale 无关的稳定 fallback；显示与编辑按 Activity 当前 Locale 叠加。
        val preset = com.promenar.nexara.data.agent.PresetAgents
        listOf(
            com.promenar.nexara.domain.model.Agent(
                id = "default",
                name = preset.fallbackName("default"),
                description = preset.fallbackDescription("default"),
                icon = "✨", color = "#C0C1FF", isPinned = true
            ),
            com.promenar.nexara.domain.model.Agent(
                id = "coder",
                name = preset.fallbackName("coder"),
                description = preset.fallbackDescription("coder"),
                icon = "💻", color = "#6366F1"
            ),
            com.promenar.nexara.domain.model.Agent(
                id = "writer",
                name = preset.fallbackName("writer"),
                description = preset.fallbackDescription("writer"),
                icon = "📝", color = "#10B981"
            )
        )
    }

    fun updateProvider(
        protocolType: ProtocolType,
        baseUrl: String,
        apiKey: String,
        model: String,
        name: String? = null
    ) {
        // 委托 ProviderManager 持久化
        ProviderManager.getInstance().updateMainProvider(
            protocolType,
            baseUrl,
            apiKey.toCredentialUpdate(),
            model,
            name,
        )
        localProviderOverride = null
        _providerConfigurationVersion.value += 1
        // 同步重建 Embedding / Rerank 客户端（使用最新的 baseUrl/apiKey）
        rebuildEmbeddingClient()
        rebuildRerankClient()
        resetVectorizationQueue() // 让 VectorizationQueue 下次访问时重新捕获新的 embeddingClient
        _unifiedLlmClient = null
    }

    fun updateProvider(
        protocolType: ProtocolType,
        baseUrl: String,
        credentialUpdate: com.promenar.nexara.data.model.CredentialUpdate,
        model: String,
        name: String? = null,
    ) {
        ProviderManager.getInstance().updateMainProvider(
            protocolType, baseUrl, credentialUpdate, model, name,
        )
        localProviderOverride = null
        _providerConfigurationVersion.value += 1
        rebuildEmbeddingClient()
        rebuildRerankClient()
        resetVectorizationQueue()
        _unifiedLlmClient = null
    }

    fun switchToLocalProvider(modelName: String = "") {
        localInferenceRuntimeGate.requireAvailable()
        localProviderOverride = modelName
        _providerConfigurationVersion.value += 1
    }

    fun getSavedProviderConfig(): ProviderConfig? {
        return ProviderManager.getInstance().getMainProviderConfig()
    }

    private fun buildUnifiedLlmClient(): com.promenar.nexara.data.remote.UnifiedLlmClient? {
        val summary = ProviderManager.getInstance().getProviderSummary("default") ?: return null
        if (!summary.hasApiKey && !summary.hasVertexCredentials && summary.protocolType !is ProtocolType.Local) return null
        val middlewares = if (com.promenar.nexara.BuildConfig.DEBUG) {
            listOf(com.promenar.nexara.data.remote.middleware.MetroLoggingMiddleware())
        } else {
            emptyList()
        }
        return com.promenar.nexara.data.remote.UnifiedLlmClient(
            providerConfigResolver = { buildUnifiedProviderConfig() },
            middlewares = middlewares,
        )
    }

    private fun buildUnifiedProviderConfig(): com.promenar.nexara.data.remote.UnifiedProviderConfig? {
        val config = getSavedProviderConfig() ?: return null
        if (config.protocolType is ProtocolType.Local && !localInferenceRuntimeGate.isAvailable) return null
        if (config.apiKey.isBlank() && config.vertexServiceAccountJson.isBlank() && config.protocolType !is ProtocolType.Local) return null
        val vertexProjectId = if (config.protocolType is ProtocolType.Google_VertexAI) {
            try {
                VertexCredentialParser.parse(config.vertexServiceAccountJson).projectId
            } catch (_: VertexCredentialException) {
                return null
            }
        } else {
            ""
        }
        return com.promenar.nexara.data.remote.UnifiedProviderConfig(
            protocolType = config.protocolType,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            defaultModel = config.model,
            serviceAccountJson = config.vertexServiceAccountJson,
            projectId = vertexProjectId,
            location = VERTEX_DEFAULT_LOCATION,
        )
    }

    private fun buildCredentialResolvingProvider(): LlmProvider {
        localProviderOverride?.let {
            return if (localInferenceRuntimeGate.isAvailable) {
                LlmProvider.local(localInferenceEngine, it)
            } else {
                buildUnavailableLocalPlaceholderProvider()
            }
        }
        val summary = ProviderManager.getInstance().getProviderSummary("default")
        if (summary?.protocolType is ProtocolType.Local) {
            return if (localInferenceRuntimeGate.isAvailable) {
                LlmProvider.local(localInferenceEngine, summary.model)
            } else {
                buildUnavailableLocalPlaceholderProvider()
            }
        }
        val protocolType = summary?.protocolType ?: ProtocolType.OpenAI_ChatCompletions
        return LlmProvider.resolving(protocolType) { buildProviderFromPrefs().protocol }
    }

    private fun buildProviderFromPrefs(): LlmProvider {
        val config = getSavedProviderConfig()
        return if (config != null && (
                config.apiKey.isNotBlank() ||
                    config.vertexServiceAccountJson.isNotBlank() ||
                    config.protocolType is ProtocolType.Local
                )) {
            if (config.protocolType is ProtocolType.Local) {
                if (localInferenceRuntimeGate.isAvailable) {
                    LlmProvider.local(localInferenceEngine, config.model)
                } else {
                    buildUnavailableLocalPlaceholderProvider()
                }
            } else if (config.protocolType is ProtocolType.Google_VertexAI) {
                val credential = VertexCredentialParser.parse(config.vertexServiceAccountJson)
                LlmProvider.builder()
                    .protocolType(config.protocolType)
                    .baseUrl(config.baseUrl)
                    .serviceAccountJson(config.vertexServiceAccountJson)
                    .projectId(credential.projectId)
                    .location(VERTEX_DEFAULT_LOCATION)
                    .model(config.model)
                    .build()
            } else {
                LlmProvider.builder()
                    .protocolType(config.protocolType)
                    .baseUrl(config.baseUrl)
                    .apiKey(config.apiKey)
                    .model(config.model)
                    .build()
            }
        } else {
            buildUnavailableLocalPlaceholderProvider()
        }
    }

    /** 仅供旧版依赖持有可取消的 Provider；实际生成会先由 ProviderRequestRouter 返回明确错误。 */
    private fun buildUnavailableLocalPlaceholderProvider(): LlmProvider =
        LlmProvider.builder()
            .protocolType(ProtocolType.OpenAI_ChatCompletions)
            .baseUrl("")
            .apiKey("")
            .model("")
            .build()

    private fun readSecret(id: SecretId): String =
        secretStore.get(id)?.toString(Charsets.UTF_8)?.trim().orEmpty()

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // 能力不可用的发行变体不能在此触发引擎工厂：lazy requireAvailable 会抛出导致进程崩溃。
                localInferenceRuntimeGate.createIfAvailable { localInferenceEngine }?.let { engine ->
                    CoroutineScope(Dispatchers.IO).launch {
                        engine.unloadModel(SlotType.RERANK)
                        engine.unloadModel(SlotType.EMBEDDING)
                    }
                }
            }
        }
    }

    // ProviderConfig 已迁移至 data/model/ProviderModels.kt

    /** 内置 git 的 HTTPS 凭据：主机与用户名、令牌都在 SecretStore，不进应用备份。 */
    val gitCredentialStore: com.promenar.nexara.data.git.remote.GitCredentialStore by lazy {
        com.promenar.nexara.data.git.remote.SecretGitCredentialStore(secretStore)
    }

    /** 按会话把工作区虚拟路径解析为图片、视频、音频等媒体，供会话渲染、播放与模型查看。 */
    val workspaceMediaResolver: com.promenar.nexara.domain.media.WorkspaceMediaResolver by lazy {
        com.promenar.nexara.data.media.DefaultWorkspaceMedia(
            this, sessionRepository, workspaceRepository, externalFolderManager,
            sessionWorkspaceResolver::resolve, pendingDocumentIndexCoordinator,
            userSkillsDirectory = skillCatalog.userRoot,
        )
    }

    /** 把生图、MCP、下载等来源的媒体写入会话工作区。 */
    val workspaceMediaStore: com.promenar.nexara.domain.media.WorkspaceMediaStore by lazy {
        workspaceMediaResolver as com.promenar.nexara.domain.media.WorkspaceMediaStore
    }

    /** 媒体下载与内置 git 一样使用 tailnet 分流；逐跳策略和流量上限由下载器控制。 */
    val workspaceMediaDownloader: com.promenar.nexara.data.media.download.WorkspaceMediaDownloader by lazy {
        com.promenar.nexara.data.media.download.WorkspaceMediaDownloader(
            client = gitRemoteHttpClient,
            policy = com.promenar.nexara.domain.media.MediaFetchUrlPolicy(tailnetManager::isTailnetHost),
            tailnetConnected = { tailnetManager.state.value.phase == com.promenar.nexara.data.tailnet.TailnetPhase.RUNNING },
            cleartextPermitted = { host -> android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host) },
        )
    }

    /** 凭据注入代理向远端转发所用的 OkHttp 客户端：tailnet 上的 git 主机经内置 Tailscale 本机代理，其它主机按系统默认选择器。 */
    val gitRemoteHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .proxySelector(tailnetManager.proxySelector)
            .proxyAuthenticator(tailnetManager.proxyAuthenticator)
            .build()
    }

    /**
     * 内置 git 的远端认证代理（HTTPS 凭据注入 + SSH 桥），由内置执行后端按命令签发 nonce。
     * 装配可能发生在主线程：凭据存储与 SshRuntime 都延迟到首次使用（在 IO 线程）再创建。
     */
    val gitRemoteBroker: com.promenar.nexara.data.git.remote.GitRemoteBroker by lazy {
        val nonces = com.promenar.nexara.data.git.remote.GitNonceRegistry()
        val credentials = com.promenar.nexara.data.git.remote.LazyGitCredentialStore { gitCredentialStore }
        com.promenar.nexara.data.git.remote.GitRemoteBroker(
            credentials = credentials,
            nonces = nonces,
            proxy = com.promenar.nexara.data.git.remote.GitCredentialProxy(credentials, nonces, gitRemoteHttpClient),
            sshBridge = com.promenar.nexara.data.git.remote.GitSshBridge(
                nonces = nonces,
                hosts = { com.promenar.nexara.data.git.remote.currentSshHosts(sshHostStore) },
                executor = { host, command, environment, stdin, stdout, stderr, started ->
                    com.promenar.nexara.data.git.remote.sshRuntimeGitExecutor(sshRuntime)
                        .execute(host, command, environment, stdin, stdout, stderr, started)
                },
            ),
        )
    }
}
