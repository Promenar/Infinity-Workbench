package com.promenar.nexara.data.storage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.promenar.nexara.data.workspace.AndroidSharedStorageVolumes
import com.promenar.nexara.domain.storage.ExternalFolderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class RealPathMountSupportTest {
    /**
     * `RealPathExternalFolderBackend` 是两个渠道共用的目录后端（项目文件夹、技能目录、画布文件），
     * Play 版的约束落在入口上：不提供所有文件访问授权、共享存储挂载与共享存储工作区。
     */
    @Test
    fun playBuildRejectsRealPathsWithoutOfferingPermissionEntry() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(com.promenar.nexara.BuildConfig.ALL_FILES_ACCESS_AVAILABLE)
        assertEquals("play", com.promenar.nexara.BuildConfig.DISTRIBUTION)
        assertFalse(RealPathMountSupport.hasPermission(context))
        assertTrue(RealPathMountSupport.sharedVolumeRoots(context).isEmpty())
        assertNull(RealPathMountSupport.permissionIntent(context))
        assertEquals("UNSUPPORTED", assertThrows(ExternalFolderException::class.java) {
            RealPathMountSupport.normalizeSource(context, "/storage/emulated/0/Download")
        }.code)
        assertEquals("UNSUPPORTED", assertThrows(ExternalFolderException::class.java) {
            RealPathMountSupport.createBackend(context, "/storage/emulated/0/Download")
        }.code)
    }

    @Test
    fun playBuildOffersNoSharedStorageWorkspace() {
        val volumes = AndroidSharedStorageVolumes(ApplicationProvider.getApplicationContext<Application>())
        assertFalse(volumes.supported)
        assertFalse(volumes.hasPermission())
        assertTrue(volumes.roots().isEmpty())
        assertEquals("UNSUPPORTED", assertThrows(ExternalFolderException::class.java) {
            volumes.normalize("/storage/emulated/0/Documents/repo")
        }.code)
    }
}
