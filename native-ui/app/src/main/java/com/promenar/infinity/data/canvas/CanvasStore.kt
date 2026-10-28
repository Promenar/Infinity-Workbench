package com.promenar.infinity.data.canvas

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CanvasMetadata(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val currentVersion: Int,
    val createdByMessageId: String,
    val versions: List<Int>,
    val workspacePath: String? = null,
    val workspaceSync: CanvasWorkspaceSync = CanvasWorkspaceSync.NOT_WRITTEN,
    val versionSources: Map<Int, CanvasVersionSource> = emptyMap(),
    val workspaceDestination: com.promenar.infinity.data.workspace.WorkspaceFileDestinationBinding? = null,
    /** 页面是单个 HTML；原型由多屏组成，屏间以 [CanvasScreenLinks] 链接跳转。旧元数据缺字段时按页面处理。 */
    val kind: CanvasKind = CanvasKind.PAGE,
    /** 原型当前版本的屏幕顺序与标题；页面为空。 */
    val screens: List<CanvasScreen> = emptyList(),
)

@Serializable
enum class CanvasKind {
    @SerialName("page") PAGE,
    @SerialName("prototype") PROTOTYPE,
}

@Serializable
data class CanvasScreen(val id: String, val title: String)

/** 原型一屏的源码。 */
data class CanvasScreenSource(val id: String, val title: String, val html: String)

/**
 * 原型屏间链接：屏幕 HTML 中 `<a href="infinity-screen:<屏幕 ID>">` 由查看器拦截并切到该屏，不发起导航。
 * 屏幕 ID 为 1–32 位小写字母、数字或连字符，以字母或数字开头，在同一原型内唯一。
 */
object CanvasScreenLinks {
    const val SCHEME = "infinity-screen"
    val ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,31}")

    /** 解析链接里的屏幕 ID；不是屏间链接或 ID 无效时为空。 */
    fun screenId(url: String): String? = url.takeIf { it.startsWith("$SCHEME:") }
        ?.removePrefix("$SCHEME:")?.removePrefix("//")?.trimEnd('/')
        ?.takeIf(ID_PATTERN::matches)
}

/** 原型的一次修改；每次成功修改生成一个新版本。 */
sealed interface PrototypeUpdate {
    /** 整体替换全部屏幕（可增删、改顺序）。 */
    data class ReplaceAll(val screens: List<CanvasScreenSource>) : PrototypeUpdate

    /** 修改其中一屏：[html] 与 [patches] 二选一，[title] 可同时改。 */
    data class EditScreen(
        val screenId: String,
        val html: String? = null,
        val patches: List<CanvasPatch>? = null,
        val title: String? = null,
    ) : PrototypeUpdate
}

@Serializable
enum class CanvasWorkspaceSync { SYNCED, FAILED, MISSING, NOT_WRITTEN }

@Serializable
enum class CanvasVersionSource { CANVAS, WORKSPACE }

/** [html] 对原型为第一屏源码（缩略图与旧调用方使用），完整屏幕在 [screens]；页面的 [screens] 为空。 */
data class CanvasDocument(
    val metadata: CanvasMetadata,
    val html: String,
    val version: Int,
    val screens: List<CanvasScreenSource> = emptyList(),
)

data class CanvasPatch(val find: String, val replace: String)

class CanvasException(val code: String, override val message: String) : Exception(message)

interface CanvasStore {
    suspend fun list(sessionId: String): List<CanvasMetadata>
    fun observe(sessionId: String): Flow<List<CanvasMetadata>>
    suspend fun create(sessionId: String, title: String, html: String, createdByMessageId: String): CanvasDocument
    suspend fun update(
        sessionId: String,
        id: String,
        html: String? = null,
        patches: List<CanvasPatch>? = null,
        expectedVersion: Int? = null,
    ): CanvasDocument
    suspend fun read(sessionId: String, id: String, version: Int? = null): CanvasDocument?
    /** 导入工作区修改；缺失时保留私有版本，未写出或失败时重试同步。 */
    suspend fun syncFromWorkspace(sessionId: String, id: String): CanvasDocument?
    /** 创建原型：1–12 屏，单屏源码不超过 1 MiB、合计不超过 4 MiB；原型不镜像到工作区。 */
    suspend fun createPrototype(
        sessionId: String,
        title: String,
        screens: List<CanvasScreenSource>,
        createdByMessageId: String,
    ): CanvasDocument = throw CanvasException("UNSUPPORTED", "当前存储不支持原型。")

    /** 修改原型；对页面调用时抛出 `NOT_PROTOTYPE`，对原型调用 [update] 时抛出 `IS_PROTOTYPE`。 */
    suspend fun updatePrototype(
        sessionId: String,
        id: String,
        update: PrototypeUpdate,
        expectedVersion: Int? = null,
    ): CanvasDocument = throw CanvasException("UNSUPPORTED", "当前存储不支持原型。")

    suspend fun deleteSession(sessionId: String)
    suspend fun copySession(sourceSessionId: String, targetSessionId: String, messageIdMap: Map<String, String>)
}
