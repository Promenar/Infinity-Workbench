package com.promenar.infinity.data.workspace

import android.content.Context
import com.promenar.infinity.data.storage.RealPathMountSupport
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderException
import java.io.File

/** 复用真实路径挂载的校验与后端；Play 渠道的 [RealPathMountSupport] 一律返回 UNSUPPORTED。 */
class AndroidSharedWorkspaceAccess(context: Context) : SharedWorkspaceAccess {
    private val application = context.applicationContext

    override fun root(location: String): File {
        val normalized = RealPathMountSupport.normalizeSource(application, location)
        if (!RealPathMountSupport.hasPermission(application)) throw ExternalFolderException("UNAVAILABLE", "请先授予所有文件访问权限")
        return File(normalized)
    }

    override fun backend(root: File): ExternalFolderBackend = RealPathMountSupport.createBackend(application, root.path)
}
