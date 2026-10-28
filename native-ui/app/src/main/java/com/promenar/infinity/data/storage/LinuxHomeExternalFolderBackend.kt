package com.promenar.infinity.data.storage

import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.data.linux.LinuxFileSystemImpl
import com.promenar.infinity.data.linux.LinuxFileWorkspaceBackend

/** Linux 主目录按目录工作区协议工作，文件读写与硬链接语义由 Linux 文件系统统一实现。 */
class LinuxHomeExternalFolderBackend(files: LinuxFileSystemImpl, directory: String = "/root", available: () -> Boolean) : ExternalFolderBackend by
    LinuxFileWorkspaceBackend(files, directory, available)
