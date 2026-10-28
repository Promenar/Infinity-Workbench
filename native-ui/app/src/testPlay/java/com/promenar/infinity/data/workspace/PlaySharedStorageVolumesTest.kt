package com.promenar.infinity.data.workspace

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.promenar.infinity.data.storage.RealPathMountSupport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Play 渠道不提供共享存储工作区：没有卷根、不报告授权，选择器因此不显示共享存储一栏。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class PlaySharedStorageVolumesTest {
    @Test
    fun playHasNoSharedVolumeRoots() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(RealPathMountSupport.sharedVolumeRoots(context).isEmpty())
        val volumes = AndroidSharedStorageVolumes(context)
        assertFalse(volumes.supported)
        assertFalse(volumes.hasPermission())
        assertTrue(volumes.roots().isEmpty())
    }
}
