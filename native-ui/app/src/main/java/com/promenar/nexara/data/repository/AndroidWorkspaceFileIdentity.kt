package com.promenar.nexara.data.repository

/** 仅在已打开的普通文件FD上操作随机持久身份，不通过路径解析或硬链接回退。 */
@androidx.annotation.Keep
internal object AndroidWorkspaceFileIdentity {
    init { System.loadLibrary("nexara_workspace_fs") }

    fun create(fd: Int, token: String) {
        requireCreationToken(token)
        nativeCreate(fd, token)
    }

    fun read(fd: Int): String? = nativeRead(fd)

    private external fun nativeCreate(fd: Int, token: String)
    private external fun nativeRead(fd: Int): String?
}
