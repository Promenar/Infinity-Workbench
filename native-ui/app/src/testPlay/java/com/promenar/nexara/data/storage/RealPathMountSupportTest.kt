package com.promenar.nexara.data.storage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.promenar.nexara.domain.storage.ExternalFolderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class RealPathMountSupportTest {
    @Test
    fun playBuildRejectsRealPathsWithoutOfferingPermissionEntry() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(com.promenar.nexara.BuildConfig.ALL_FILES_ACCESS_AVAILABLE)
        assertEquals("play", com.promenar.nexara.BuildConfig.DISTRIBUTION)
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.promenar.nexara.data.storage.RealPathExternalFolderBackend")
        }
        assertFalse(RealPathMountSupport.hasPermission(context))
        assertNull(RealPathMountSupport.permissionIntent(context))
        assertEquals("UNSUPPORTED", assertThrows(ExternalFolderException::class.java) {
            RealPathMountSupport.normalizeSource(context, "/storage/emulated/0/Download")
        }.code)
        assertEquals("UNSUPPORTED", assertThrows(ExternalFolderException::class.java) {
            RealPathMountSupport.createBackend(context, "/storage/emulated/0/Download")
        }.code)
    }
}
