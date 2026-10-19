package com.promenar.nexara.data.storage

import android.content.Context
import android.content.Intent
import com.promenar.nexara.domain.storage.ExternalFolderBackend
import com.promenar.nexara.domain.storage.ExternalFolderException
import java.io.File

object RealPathMountSupport {
    fun hasPermission(context: Context): Boolean = false
    fun permissionIntent(context: Context): Intent? = null
    fun normalizeSource(context: Context, source: String): String = unsupported()
    fun createBackend(context: Context, source: String): ExternalFolderBackend = unsupported()
    /** Play 渠道不提供共享存储工作区。 */
    fun sharedVolumeRoots(context: Context): List<File> = emptyList()
    private fun unsupported(): Nothing = throw ExternalFolderException("UNSUPPORTED", "此发行渠道不支持真实路径挂载")
}
