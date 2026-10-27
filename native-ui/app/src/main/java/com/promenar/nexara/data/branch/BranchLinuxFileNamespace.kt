package com.promenar.nexara.data.branch

import com.promenar.nexara.domain.storage.ExternalFolderBackend
import com.promenar.nexara.domain.storage.ExternalMount
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 文件工具与命令共用的可信基础挂载；相对文件路径使用命令的实际工作目录。 */
data class BranchLinuxFileNamespace(
    val commandWorkspace: BranchLinuxCommandWorkspace,
    val backend: ExternalFolderBackend,
)

/** 只由 Linux 文件适配器在已核验的操作范围内注入；不从工具参数反序列化。 */
internal data class BranchLinuxWorkspaceOperation(
    val sessionId: String,
    val mount: ExternalMount,
    val backend: ExternalFolderBackend,
    val baseRoot: File,
    val workspacePaths: Boolean = false,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BranchLinuxWorkspaceOperation>
}
