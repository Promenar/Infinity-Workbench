package com.promenar.nexara

import android.app.Application
import android.util.Log
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import java.io.File
import com.promenar.nexara.data.local.inference.LocalInferenceEngine
import com.promenar.nexara.data.local.inference.SlotType
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.backup.BackupRuntime
import com.promenar.nexara.data.backup.BackupStartupState
import com.promenar.nexara.data.backup.RestoreRelayActivity
import com.promenar.nexara.utils.NexaraLogger
import com.promenar.nexara.data.rag.EmbeddingClient
import com.promenar.nexara.data.rag.GraphStore
import com.promenar.nexara.data.rag.ImageService
import com.promenar.nexara.data.rag.KeywordSearcher
import com.promenar.nexara.data.rag.MemoryManager
import com.promenar.nexara.data.rag.MicroGraphExtractor
import com.promenar.nexara.data.rag.MicroGraphKgAdapter
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
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.DefaultProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderRequestRouter
import com.promenar.nexara.data.remote.provider.LlmProvider
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
import com.promenar.nexara.ui.chat.manager.KgProvider
import com.promenar.nexara.ui.chat.manager.skills.WebSearchSkill
import com.promenar.nexara.ui.chat.manager.skills.WebSearchSearXNGSkill
import com.promenar.nexara.ui.chat.manager.skills.WebSearchTavilySkill
import com.promenar.nexara.ui.chat.manager.skills.WebFetchSkill
import com.promenar.nexara.ui.chat.manager.skills.CreateToolSkill
import com.promenar.nexara.ui.chat.manager.skills.ImageGenerationSkill
import com.promenar.nexara.ui.chat.manager.skills.FileReadSkill
import com.promenar.nexara.ui.chat.manager.skills.FileWriteSkill
import com.promenar.nexara.ui.chat.manager.skills.FileDiffSkill
import com.promenar.nexara.ui.chat.manager.skills.FilePatchSkill
import com.promenar.nexara.ui.chat.manager.skills.FileListSkill
import com.promenar.nexara.ui.chat.manager.skills.FileSearchSkill
import com.promenar.nexara.ui.chat.manager.skills.ExecJsSkill
import com.promenar.nexara.ui.chat.manager.skills.InitializePlanSkill
import com.promenar.nexara.ui.chat.manager.skills.UpdatePlanSkill
import com.promenar.nexara.ui.chat.manager.skills.GetPlanSkill
import com.promenar.nexara.ui.chat.manager.skills.DropPlanSkill
import com.promenar.nexara.data.repository.TaskRepository
import com.promenar.nexara.data.repository.SkillRepository
import com.promenar.nexara.data.repository.ISkillRepository
import com.promenar.nexara.data.repository.TokenStatsRepository
import com.promenar.nexara.domain.repository.ITokenStatsRepository
import com.promenar.nexara.util.LocaleHelper
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
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder

open class NexaraApplication : Application(), SingletonImageLoader.Factory {
    companion object {
        lateinit var instance: NexaraApplication
            private set
    }

    open val secretStore: SecretStore by lazy { AndroidKeystoreSecretStore(this) }

    private lateinit var backupRuntime: BackupRuntime
    var restoreRelayEarlyExit: Boolean = false
        private set
    private val _startupState = MutableStateFlow<BackupStartupState>(BackupStartupState.Recovering)
    val startupState: StateFlow<BackupStartupState> = _startupState.asStateFlow()

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
        Room.databaseBuilder(this, NexaraDatabase::class.java, "nexara_v2.db")
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
                                android.util.Log.d("NEXARA_METRO", "EVENT_START|DB_QUERY|${json}|EVENT_END")
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

    val localInferenceEngine: LocalInferenceEngine by lazy {
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

    val messageRepository: IMessageRepository by lazy {
        MessageRepository(database.messageDao())
    }

    val chatStore: ChatStore by lazy { ChatStore() }

    private var _vectorRepository: com.promenar.nexara.domain.repository.IVectorRepository? = null
    val vectorRepository: com.promenar.nexara.domain.repository.IVectorRepository
        get() = _vectorRepository ?: VectorRepository(database.vectorDao(), embeddingClient).also { _vectorRepository = it }

    val skillRepository: ISkillRepository by lazy {
        com.promenar.nexara.data.repository.SkillRepository(database.skillDao())
    }

    val tokenStatsRepository: ITokenStatsRepository by lazy {
        TokenStatsRepository(database.messageDao())
    }

    val workspaceRepository: com.promenar.nexara.domain.repository.IWorkspaceRepository by lazy {
        WorkspaceRepository(
            database.fileEntryDao(),
            database.workspaceSeqDao(),
            File(filesDir, "session_workspaces"),
        )
    }

    val fileOperationRepository: com.promenar.nexara.domain.repository.IFileOperationRepository by lazy {
        FileOperationRepository(database.fileEntryDao(), database.fileVersionDao())
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
            }
        }
    }

    val presetSkillRegistry: DefaultSkillRegistry by lazy {
        DefaultSkillRegistry().apply {
            register(CalculatorSkill())
            register(WebSearchSkill(this@NexaraApplication, httpClient, secretStore))
            register(WebSearchTavilySkill(this@NexaraApplication, httpClient, secretStore))
            register(WebSearchSearXNGSkill(this@NexaraApplication, httpClient))
            register(WebFetchSkill(httpClient))
            register(CreateToolSkill(database.skillDao()))
            register(ImageGenerationSkill(this@NexaraApplication, ProviderManager.getInstance()))
            register(FileReadSkill(fileOperationRepository))
            register(FileWriteSkill(fileOperationRepository))
            register(FileDiffSkill(fileOperationRepository))
            register(FilePatchSkill(fileOperationRepository))
            register(FileListSkill(workspaceRepository))
            register(FileSearchSkill(workspaceRepository))
            register(ExecJsSkill(this@NexaraApplication))
            register(InitializePlanSkill(taskRepository))
            register(UpdatePlanSkill(taskRepository))
            register(GetPlanSkill(taskRepository))
            register(DropPlanSkill(taskRepository))
        }
    }

    val userSkillRegistry: UserSkillRegistry by lazy {
        UserSkillRegistry(skillRepository as SkillRepository)
    }

    val mcpSkillRegistry: McpSkillRegistry by lazy {
        McpSkillRegistry(skillRepository as SkillRepository, httpClient)
    }

    val skillRegistry: SkillRegistry by lazy {
        ModularSkillRegistry(
            listOf(presetSkillRegistry, userSkillRegistry, mcpSkillRegistry)
        )
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
        DefaultProviderRequestRouter(ProviderManager.getInstance(), middlewares)
    }

    var hapticEnabled: Boolean = true
        internal set

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }

    override fun attachBaseContext(base: Context) {
        val lang = LocaleHelper.getSavedLanguage(base)
        super.attachBaseContext(LocaleHelper.applyLanguage(base, lang))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.promenar.nexara.utils.NexaraLogger.init(this)

        // 独立 relay 进程不得打开 Room、pending payload 或注册任何业务 writer。
        if (currentProcessName().endsWith(RestoreRelayActivity.PROCESS_SUFFIX)) {
            restoreRelayEarlyExit = true
            Log.i("NexaraRestoreRelay", "application_early_exit_before_runtime_and_writers")
            return
        }

        startBackupRecovery()
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

    private fun startBackupRecovery() {
        if (startupRecoveryJob?.isActive == true || _startupState.value == BackupStartupState.Ready) return
        _startupState.value = BackupStartupState.Recovering
        startupRecoveryJob = startupScope.launch {
            try {
                val runtime = withContext(Dispatchers.IO) {
                    if (!::backupRuntime.isInitialized) backupRuntime = createBackupRuntime()
                    backupRuntime
                }
                if (runtime.recoverBeforeWriters() == BackupStartupState.Ready) {
                    _startupState.value = runCatching {
                        initializeAfterRecoveryOnce()
                        BackupStartupState.Ready
                    }.getOrElse { BackupStartupState.Blocked }
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
        val lastModelToLoad = if (
            settingsPrefs.getBoolean("local_models_enabled", false) &&
            settingsPrefs.getBoolean("local_auto_load", false)
        ) prefs.getString("last_local_model", null) else null
        val inferenceEngine = localInferenceEngine
        val processObserver = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                inferenceEngine.mainSlot.value.modelPath?.let { mainPath ->
                    prefs.edit().putString("last_local_model", mainPath).apply()
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
            reversibleRegistration(
                register = { ProcessLifecycleOwner.get().lifecycle.addObserver(prepared.processObserver) },
                rollback = { ProcessLifecycleOwner.get().lifecycle.removeObserver(prepared.processObserver) },
            ),
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
                        _vectorizationQueue = null
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
                        prepared.inferenceEngine.loadModel(SlotType.MAIN, modelPath).getOrThrow()
                    }
                }
            },
            jobRegistration(prepared) {
                startupBackgroundHealthMonitor.launch(
                    scope = startupScope,
                    task = StartupBackgroundTask.VECTOR_RESUME,
                    start = CoroutineStart.LAZY,
                ) {
                    prepared.vectorizationQueue.resumeInterruptedTasks().getOrThrow()
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
        val inferenceEngine: LocalInferenceEngine,
        val vectorizationQueue: VectorizationQueue,
        val lastModelToLoad: String?,
        val processObserver: DefaultLifecycleObserver,
        val jobs: MutableList<Job> = mutableListOf(),
    )

    /** 仅供测试应用替换 AndroidKeyStore/真实文件系统依赖；生产始终使用安全 runtime。 */
    protected open fun createBackupRuntime(): BackupRuntime =
        BackupRuntime.createAndroid(this, database, secretStore)

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
        
        val model = prefs.getString("embedding_model", "")?.ifBlank { presetModel } ?: presetModel
        NexaraLogger.log("[EmbeddingClient] 构建: model=$model resolvedBy=$resolvedBy baseUrlSet=${baseUrl.isNotBlank()} apiKeySet=${apiKey.isNotBlank()}")
        return EmbeddingClient(baseUrl = baseUrl, apiKey = apiKey, model = model, localEngine = localInferenceEngine)
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
        val maxPerCall = ragConfigPersistence.loadFullConfig().rerankMaxPerCall
        NexaraLogger.log("[RerankClient] 构建: model=$presetModel hasBaseUrl=${baseUrl.isNotBlank()} maxPerCall=$maxPerCall")
        return RerankClient(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelId = presetModel,
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
            _vectorizationQueue = null
        }
        if (key == "model") {
            rebuildEmbeddingClient()
            _vectorizationQueue = null
        }
    }

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "preset_embedding_model" || key == "all_models" || key == "enabled_models") {
            rebuildEmbeddingClient()
            _vectorizationQueue = null
        }
        if (key == "preset_rerank_model" || key == "all_models") {
            rebuildRerankClient()
        }
        // 非敏感额外提供商地址变化仍由 prefs 监听；凭证变化走 ProviderManager.configurationChanges。
        if (key?.startsWith("extra_provider_") == true && key.endsWith("_base_url")) {
            rebuildEmbeddingClient()
            rebuildRerankClient()
            _vectorizationQueue = null
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
            kgEdgeDao = database.kgEdgeDao()
        )
    }

    val graphStore: GraphStore by lazy {
        GraphStore(
            kgNodeDao = database.kgNodeDao(),
            kgEdgeDao = database.kgEdgeDao()
        )
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

    private var _microGraphExtractor: MicroGraphExtractor? = null
    val microGraphExtractor: MicroGraphExtractor
        get() = _microGraphExtractor ?: MicroGraphExtractor(
            protocol = llmProvider.protocol,
            graphStore = graphStore,
            jitCacheDao = database.kgJitCacheDao(),
            modelId = getSavedProviderConfig()?.model
        ).also { _microGraphExtractor = it }

    private var _kgProvider: KgProvider? = null
    val kgProvider: KgProvider
        get() = _kgProvider ?: MicroGraphKgAdapter(microGraphExtractor).also { _kgProvider = it }

    val webSearchContextProvider: WebSearchProvider by lazy {
        WebSearchContextProvider(this, httpClient, secretStore)
    }

    private var _graphExtractor: com.promenar.nexara.data.rag.GraphExtractor? = null
    val graphExtractor: com.promenar.nexara.data.rag.GraphExtractor
        get() = _graphExtractor ?: run {
            val config = ragConfigPersistence.loadFullConfig()
            // P1: kgExtractionModel 优先 → 降级 summary model → 降级主 LLM 模型
            val effectiveModel = config.kgExtractionModel?.takeIf { it.isNotBlank() }
                ?: getSharedPreferences("nexara_settings", MODE_PRIVATE)
                    .getString("preset_summary_model", "")?.takeIf { it.isNotBlank() }
                ?: getSavedProviderConfig()?.model
            // P1: kgExtractionPrompt 优先 → 降级 DEFAULT_KG_PROMPT
            val effectivePrompt = config.kgExtractionPrompt?.takeIf { it.isNotBlank() }
                ?: com.promenar.nexara.data.rag.GraphExtractor.DEFAULT_KG_PROMPT

            com.promenar.nexara.data.rag.GraphExtractor(
                protocol = llmProvider.protocol,
                graphStore = graphStore,
                modelId = effectiveModel,
                systemPrompt = effectivePrompt,
                chunkSize = config.docChunkSize.coerceAtLeast(400),
                chunkOverlap = config.chunkOverlap.coerceAtMost(200),
                timeoutMs = (config.kgExtractionTimeoutSeconds.coerceIn(5, 300) * 1000L)
            ).also { _graphExtractor = it }
        }

    fun rebuildGraphExtractor() {
        _graphExtractor = null
    }

    private var _vectorizationQueue: com.promenar.nexara.data.rag.VectorizationQueue? = null
    val vectorizationQueue: com.promenar.nexara.data.rag.VectorizationQueue
        get() = _vectorizationQueue ?: com.promenar.nexara.data.rag.VectorizationQueue(
            vectorStore = vectorStore,
            embeddingClient = embeddingClient,
            graphExtractor = graphExtractor,
            vectorDao = database.vectorDao(),
            vectorizationTaskDao = database.vectorizationTaskDao(),
            fileEntryDao = database.fileEntryDao()
        ).also { _vectorizationQueue = it }

    val defaultAgents: List<com.promenar.nexara.domain.model.Agent> by lazy {
        listOf(
            com.promenar.nexara.domain.model.Agent(id = "default", name = "Nexara 助手", description = "通用 AI 助手，支持流式对话与知识检索", icon = "✨", color = "#C0C1FF", isPinned = true),
            com.promenar.nexara.domain.model.Agent(id = "coder", name = "编程专家", description = "精通全栈开发与架构设计", icon = "💻", color = "#6366F1"),
            com.promenar.nexara.domain.model.Agent(id = "writer", name = "创意写作", description = "文学创作、翻译与润色", icon = "📝", color = "#10B981")
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
        _vectorizationQueue = null  // 让 VectorizationQueue 下次访问时重新捕获新的 embeddingClient
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
        _vectorizationQueue = null
        _unifiedLlmClient = null
    }

    fun switchToLocalProvider(modelName: String = "") {
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
        if (config.apiKey.isBlank() && config.vertexServiceAccountJson.isBlank() && config.protocolType !is ProtocolType.Local) return null
        return com.promenar.nexara.data.remote.UnifiedProviderConfig(
            protocolType = config.protocolType,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            defaultModel = config.model,
            serviceAccountJson = config.vertexServiceAccountJson,
            projectId = extractVertexProjectId(config.vertexServiceAccountJson),
        )
    }

    private fun buildCredentialResolvingProvider(): LlmProvider {
        localProviderOverride?.let { return LlmProvider.local(localInferenceEngine, it) }
        val summary = ProviderManager.getInstance().getProviderSummary("default")
        if (summary?.protocolType is ProtocolType.Local) {
            return LlmProvider.local(localInferenceEngine, summary.model)
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
                LlmProvider.local(localInferenceEngine, config.model)
            } else if (config.protocolType is ProtocolType.Google_VertexAI) {
                LlmProvider.builder()
                    .protocolType(config.protocolType)
                    .serviceAccountJson(config.vertexServiceAccountJson)
                    .projectId(extractVertexProjectId(config.vertexServiceAccountJson))
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
            LlmProvider.builder()
                .protocolType(ProtocolType.OpenAI_ChatCompletions)
                .baseUrl("")
                .apiKey("")
                .model("")
                .build()
        }
    }

    private fun readSecret(id: SecretId): String =
        secretStore.get(id)?.toString(Charsets.UTF_8).orEmpty()

    private fun extractVertexProjectId(serviceAccountJson: String): String =
        Regex("\\\"project_id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(serviceAccountJson)?.groupValues?.get(1).orEmpty()

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                CoroutineScope(Dispatchers.IO).launch {
                    localInferenceEngine.unloadModel(SlotType.RERANK)
                    localInferenceEngine.unloadModel(SlotType.EMBEDDING)
                }
            }
        }
    }

    // ProviderConfig 已迁移至 data/model/ProviderModels.kt
}
