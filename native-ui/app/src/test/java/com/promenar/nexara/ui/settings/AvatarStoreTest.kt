package com.promenar.nexara.ui.settings

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
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
    fun `valid image is fully staged into a new cache identity and removes the legacy slot`() {
        val directory = Files.createTempDirectory("avatar-store").toFile()
        val legacyTarget = directory.resolve("user-avatar.img").apply { writeBytes(byteArrayOf(9)) }
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        val store = AvatarStore(directory, { "image/png" }) { ByteArrayInputStream(png) }

        val saved = store.save(uri, "user-avatar")

        val target = File(requireNotNull(saved))
        assertThat(target.name).startsWith("user-avatar-")
        assertThat(target.name).endsWith(".img")
        assertThat(target.readBytes()).isEqualTo(png)
        assertThat(legacyTarget.exists()).isFalse()
        assertThat(directory.listFiles()?.map { it.name }).containsExactly(target.name)
    }

    @Test
    fun `a different second upload gets a different path and removes the superseded image`() {
        val directory = Files.createTempDirectory("avatar-store-cache-identity").toFile()
        var payload = png(Color.RED)
        val store = AvatarStore(directory, { "image/png" }) { ByteArrayInputStream(payload) }

        val first = requireNotNull(store.save(uri, "agent-a1"))
        payload = png(Color.BLUE)
        val second = requireNotNull(store.save(uri, "agent-a1"))

        assertThat(second).isNotEqualTo(first)
        assertThat(File(first).exists()).isFalse()
        assertThat(File(second).readBytes()).isEqualTo(payload)
        assertThat(directory.listFiles()?.map { it.absolutePath }).containsExactly(second)
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

    private fun png(color: Int): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        } finally {
            bitmap.recycle()
        }
        output.toByteArray()
    }
}
