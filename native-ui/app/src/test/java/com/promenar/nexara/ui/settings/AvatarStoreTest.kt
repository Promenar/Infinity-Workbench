package com.promenar.nexara.ui.settings

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
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
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        val store = AvatarStore(directory, { "image/png" }) { ByteArrayInputStream(png) }

        val saved = store.save(uri, "user-avatar")

        assertThat(saved).isEqualTo(target.absolutePath)
        assertThat(target.readBytes()).isEqualTo(png)
        assertThat(directory.listFiles()?.map { it.name }).containsExactly("user-avatar.img")
    }

    @Test
    fun `signature only payload is rejected because pixels cannot be decoded`() {
        val directory = Files.createTempDirectory("avatar-store-invalid-pixels").toFile()
        val target = directory.resolve("user-avatar.img").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val signatureOnly = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            1, 2, 3,
        )

        val result = AvatarStore(directory, { "image/png" }) {
            ByteArrayInputStream(signatureOnly)
        }.save(uri, "user-avatar")

        assertThat(result).isNull()
        assertThat(target.readBytes()).isEqualTo(byteArrayOf(9, 8, 7))
    }

    @Test
    fun `oversized stream stops at hard byte budget and preserves old avatar`() {
        val directory = Files.createTempDirectory("avatar-store-oversize").toFile()
        val target = directory.resolve("user-avatar.img").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val oversized = ByteArray(10 * 1024 * 1024 + 1).apply {
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            ).copyInto(this)
        }

        val result = AvatarStore(directory, { "image/png" }) {
            ByteArrayInputStream(oversized)
        }.save(uri, "user-avatar")

        assertThat(result).isNull()
        assertThat(target.readBytes()).isEqualTo(byteArrayOf(4, 5, 6))
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
