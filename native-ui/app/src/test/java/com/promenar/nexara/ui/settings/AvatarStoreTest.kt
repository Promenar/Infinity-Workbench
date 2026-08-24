package com.promenar.nexara.ui.settings

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AvatarStoreTest {
    private val uri = Uri.parse("content://avatar/fixture")

    @Test
    fun `valid image is fully staged before atomically replacing old avatar`() {
        val directory = Files.createTempDirectory("avatar-store").toFile()
        val target = directory.resolve("user-avatar.img").apply { writeBytes(byteArrayOf(9)) }
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            1, 2, 3,
        )
        val store = AvatarStore(directory, { "image/png" }) { ByteArrayInputStream(png) }

        val saved = store.save(uri, "user-avatar")

        assertThat(saved).isEqualTo(target.absolutePath)
        assertThat(target.readBytes()).isEqualTo(png)
        assertThat(directory.listFiles()?.map { it.name }).containsExactly("user-avatar.img")
    }

    @Test
    fun `empty video and copy failure all preserve old avatar`() {
        val directory = Files.createTempDirectory("avatar-store-failure").toFile()
        val target = directory.resolve("user-avatar.img").apply { writeBytes(byteArrayOf(7, 8)) }

        assertThat(
            AvatarStore(directory, { "image/png" }) { ByteArrayInputStream(byteArrayOf()) }
                .save(uri, "user-avatar"),
        ).isNull()
        assertThat(target.readBytes()).isEqualTo(byteArrayOf(7, 8))

        assertThat(
            AvatarStore(directory, { "video/mp4" }) { ByteArrayInputStream(byteArrayOf(1)) }
                .save(uri, "user-avatar"),
        ).isNull()
        assertThat(target.readBytes()).isEqualTo(byteArrayOf(7, 8))

        assertThat(
            AvatarStore(directory, { "image/png" }) { throw IOException("copy") }
                .save(uri, "user-avatar"),
        ).isNull()
        assertThat(target.readBytes()).isEqualTo(byteArrayOf(7, 8))

        assertThat(
            AvatarStore(directory, { throw IOException("mime") }) { ByteArrayInputStream(byteArrayOf(1)) }
                .save(uri, "user-avatar"),
        ).isNull()
        assertThat(target.readBytes()).isEqualTo(byteArrayOf(7, 8))
        assertThat(directory.listFiles()?.map { it.name }).containsExactly("user-avatar.img")
    }
}
