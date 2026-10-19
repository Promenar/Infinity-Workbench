package com.promenar.nexara.data.script

import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.repository.SessionToolGrantStore
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.script.*
import com.promenar.nexara.domain.storage.ExternalFolderException
import com.promenar.nexara.domain.storage.ExternalWorkspaceAccess
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.skills.ScriptWorkspaceGateway
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

fun interface ScriptToolRunner {
    suspend fun execute(request: ScriptRequest, context: SkillExecutionContext): ScriptResult
}

class AndroidScriptToolRunner(
    private val runtime: ScriptRuntime,
    private val workspace: IWorkspaceRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val files: IFileOperationRepository,
    private val indexEvents: FileIndexEventSink,
    private val approvals: ScriptFileApprovalBroker,
    private val grants: SessionToolGrantStore,
    private val settings: ScriptSettings,
) : ScriptToolRunner {
    override suspend fun execute(request: ScriptRequest, context: SkillExecutionContext): ScriptResult = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(ScriptLimits.hostTimeoutMillis(request)) {
                val (gateway, loaded) = ScriptCapabilityDispatcher.Shared.call {
                    val access = ScriptWorkspaceGateway.open(context, workspace, mounts, files, indexEvents, approvals, grants, { settings.approveFileWrites }, request.entryPath)
                    val source = if (request.entryPath == null) request else {
                        val text = access.invoke("read", buildJsonObject { put("path", request.entryPath) }).jsonObject["content"]!!.jsonPrimitive.content
                        request.copy(code = text)
                    }
                    access to source
                }
                val requests = AtomicInteger()
                val networkBudget = ScriptNetworkBudget()
                val http = ScriptHttpClient({ settings.lanHosts })
                runtime.execute(context.sessionId, loaded, ScriptCapabilities { operation, args ->
                    if (requests.incrementAndGet() > ScriptLimits.CAPABILITY_CALLS) throw ScriptFailure("CAPABILITY_LIMIT", "能力调用次数超过限制")
                    if (operation == "fetch") {
                        if (!loaded.allowNetwork) throw ScriptFailure("NETWORK_DENIED", "此脚本未启用 allow_network")
                        http.fetch(args, networkBudget)
                    } else gateway.invoke(operation, args)
                })
            } ?: ScriptResult(errorCode = "TIMEOUT", error = "脚本准备、执行或审批等待超时；执行中的隔离进程已终止")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ScriptFailure) { ScriptResult(errorCode = failure.code, error = failure.message) }
        catch (failure: ExternalFolderException) { ScriptResult(errorCode = failure.code, error = failure.message) }
        catch (_: Exception) { ScriptResult(errorCode = "CAPABILITY_DENIED", error = "工作区或挂载授权不可用") }
    }
}
