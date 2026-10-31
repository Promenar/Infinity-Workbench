package com.promenar.infinity.data.repository

/** 严格不覆盖移动与显式核对后移动分别暴露，调用方决定卷兼容策略。 */
@androidx.annotation.Keep
internal object AndroidWorkspaceFileMoves {
    init { System.loadLibrary("infinity_workspace_fs") }

    fun isFuse(directory: Int): Boolean = nativeIsFuse(directory)

    fun moveNoReplace(sourceParent: Int, sourceName: String, targetParent: Int, targetName: String) {
        validateNames(sourceName, targetName)
        nativeMoveNoReplace(sourceParent, sourceName.toByteArray(Charsets.UTF_8),
            targetParent, targetName.toByteArray(Charsets.UTF_8))
    }

    fun moveReplacing(sourceParent: Int, sourceName: String, targetParent: Int, targetName: String) {
        validateNames(sourceName, targetName)
        nativeMoveReplacing(sourceParent, sourceName.toByteArray(Charsets.UTF_8),
            targetParent, targetName.toByteArray(Charsets.UTF_8))
    }

    private fun validateNames(sourceName: String, targetName: String) {
        listOf(sourceName, targetName).forEach { name ->
            require(name.isNotEmpty() && name != "." && name != ".." &&
                '/' !in name && '\\' !in name && '\u0000' !in name)
        }
    }

    private external fun nativeMoveNoReplace(sourceParent: Int, sourceName: ByteArray,
        targetParent: Int, targetName: ByteArray)
    private external fun nativeMoveReplacing(sourceParent: Int, sourceName: ByteArray,
        targetParent: Int, targetName: ByteArray)
    private external fun nativeIsFuse(directory: Int): Boolean
}
