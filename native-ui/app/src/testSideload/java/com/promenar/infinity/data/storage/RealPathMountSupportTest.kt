package com.promenar.infinity.data.storage

import android.app.Application
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.promenar.infinity.domain.storage.ExternalFolderException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE, shadows = [ExternalFolderEnvironmentShadow::class])
class RealPathMountSupportTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var sharedRoot: File

    @Before
    fun sharedStorage() {
        sharedRoot = temporaryFolder.newFolder("shared").canonicalFile
        ShadowEnvironment.setExternalStorageDirectory(sharedRoot.toPath())
        shadowOf(context.getSystemService(StorageManager::class.java)).addStorageVolume(
            StorageVolumeBuilder("primary", sharedRoot, "测试共享存储", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
                .setIsPrimary(true).setIsEmulated(true).build(),
        )
        ExternalFolderEnvironmentShadow.allFilesAllowed = false
    }

    @Test
    fun normalizesOnlySharedDirectoriesAndRejectsPrivateOrLinkedSources() {
        val notes = File(sharedRoot, "Notes").apply { mkdirs() }
        assertEquals(notes.path, RealPathMountSupport.normalizeSource(context, notes.path))
        val forbidden = listOf(context.filesDir, File("/system"), File(sharedRoot, "Android/data/other"), File(sharedRoot, "Android/obb/other"), File(sharedRoot, "Android/media/other"), File(sharedRoot, ".infinity-cache"), File(sharedRoot, ".recycle_bin"))
        forbidden.forEach { source ->
            assertEquals("INVALID_PATH", assertThrows(ExternalFolderException::class.java) {
                RealPathMountSupport.normalizeSource(context, source.path)
            }.code)
        }
        val linked = File(sharedRoot, "linked")
        Files.createSymbolicLink(linked.toPath(), notes.toPath())
        assertEquals("INVALID_PATH", assertThrows(ExternalFolderException::class.java) {
            RealPathMountSupport.normalizeSource(context, linked.path)
        }.code)
    }

    @Test
    fun permissionEntryTargetsCurrentAppAndFactoryRequiresAllFilesAccess() = runBlocking {
        val intent = RealPathMountSupport.permissionIntent(context)!!
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
        val exception = try {
            RealPathMountSupport.createBackend(context, sharedRoot.path)
            null
        } catch (exception: ExternalFolderException) {
            exception
        }
        assertEquals("UNAVAILABLE", exception?.code)
    }

    @Test
    fun grantedBackendRechecksAccessRevocationBeforeOpeningFiles() = runBlocking {
        ExternalFolderEnvironmentShadow.allFilesAllowed = true
        File(sharedRoot, "Android/data/other").mkdirs()
        val backend = RealPathMountSupport.createBackend(context, sharedRoot.path)
        ExternalFolderEnvironmentShadow.allFilesAllowed = false
        val rejected = try {
            backend.stat("/Android/data")
            null
        } catch (exception: ExternalFolderException) {
            exception
        }
        assertEquals("UNAVAILABLE", rejected?.code)
    }

}

/** 只控制授权结果；路径、卷枚举和真实文件操作仍经过生产实现。 */
@Implements(Environment::class)
class ExternalFolderEnvironmentShadow : ShadowEnvironment() {
    companion object {
        var allFilesAllowed = false

        @Implementation
        @JvmStatic
        fun isExternalStorageManager(): Boolean = allFilesAllowed
    }
}
