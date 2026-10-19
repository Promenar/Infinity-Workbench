package com.promenar.nexara.data.workspace

import android.content.Context
import com.promenar.nexara.data.storage.RealPathMountSupport
import com.promenar.nexara.distribution.DistributionCapabilities
import java.io.File

/** 共享卷列表、授权与路径规范化均复用真实路径挂载；Play 渠道的实现不提供任何卷。 */
class AndroidSharedStorageVolumes(context: Context) : SharedStorageVolumes {
    private val application = context.applicationContext

    override val supported: Boolean get() = DistributionCapabilities.allFilesAccessAvailable
    override fun hasPermission(): Boolean = RealPathMountSupport.hasPermission(application)
    override fun roots(): List<File> = RealPathMountSupport.sharedVolumeRoots(application)
    override fun normalize(path: String): String = RealPathMountSupport.normalizeSource(application, path)
}
