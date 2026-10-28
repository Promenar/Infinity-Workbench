package com.promenar.infinity.data.linux

import java.io.File

/** 只能执行安装器解压到 nativeLibraryDir 的程序，不能从应用数据目录 exec。 */
class PackagedProot(libraryDirectory: File) {
    val executable = File(libraryDirectory, "libnxproot.so")
    val loader = File(libraryDirectory, "libnxproot-loader.so")
    val isAvailable: Boolean
        get() = executable.isFile && executable.canExecute() && loader.isFile && loader.canExecute()
}
