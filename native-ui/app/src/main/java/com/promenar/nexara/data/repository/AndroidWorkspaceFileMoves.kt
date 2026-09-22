package com.promenar.nexara.data.repository

/** Android API 30+ 的renameat2桥接；不存在退回覆盖式rename的分支。 */
@androidx.annotation.Keep
internal object AndroidWorkspaceFileMoves {
    init { System.loadLibrary("nexara_workspace_fs") }

    fun moveNoReplace(sourceParent: Int, sourceName: String, targetParent: Int, targetName: String) {
        listOf(sourceName, targetName).forEach { name ->
            require(name.isNotEmpty() && name != "." && name != ".." &&
                '/' !in name && '\\' !in name && '\u0000' !in name)
        }
        nativeMoveNoReplace(sourceParent, sourceName.toByteArray(Charsets.UTF_8),
            targetParent, targetName.toByteArray(Charsets.UTF_8))
    }

    private external fun nativeMoveNoReplace(sourceParent: Int, sourceName: ByteArray,
        targetParent: Int, targetName: ByteArray)
}
