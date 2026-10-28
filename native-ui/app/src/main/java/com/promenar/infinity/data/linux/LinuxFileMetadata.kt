package com.promenar.infinity.data.linux

import android.os.Process
import android.system.Os
import java.io.File

internal data class LinuxFileIdentity(val mode: Int, val uid: Long, val gid: Long)

internal interface LinuxFileMetadata {
    val secureIo: Boolean get() = false
    val access: LinuxFileIo get() = LinuxFileIo(secureIo)
    val appUid: Long
    val appGid: Long
    fun read(file: File): LinuxFileIdentity
    fun chmod(file: File, mode: Int)
}

internal object LinuxFileAndroidMetadata : LinuxFileMetadata {
    override val secureIo = true
    override val appUid get() = Process.myUid().toLong()
    override val appGid get() = Process.myUid().toLong()
    override fun read(file: File): LinuxFileIdentity = Os.lstat(file.path).let {
        LinuxFileIdentity(it.st_mode and 0xfff, it.st_uid.toLong(), it.st_gid.toLong())
    }
    override fun chmod(file: File, mode: Int) = Os.chmod(file.path, mode)
}
