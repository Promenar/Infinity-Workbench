package com.promenar.nexara.ui.settings

import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class AvatarStore(
    private val directory: File,
    private val mimeTypeOf: (Uri) -> String?,
    private val openInput: (Uri) -> InputStream?,
) {
    fun save(uri: Uri, slot: String): String? {
        if (!SAFE_SLOT.matches(slot)) return null
        var temporary: File? = null
        return try {
            val mimeType = mimeTypeOf(uri)?.substringBefore(';')?.trim()?.lowercase()
            if (mimeType?.startsWith("image/") != true) return null
            if (!directory.exists() && !directory.mkdirs()) return null

            val target = File(directory, "$slot.img")
            val staged = File.createTempFile(".$slot-", ".tmp", directory)
            temporary = staged
            val input = openInput(uri) ?: return null
            input.use { source ->
                FileOutputStream(staged).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_AVATAR_BYTES) return null
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (
                staged.length() == 0L ||
                !hasSupportedImageSignature(staged) ||
                !hasDecodablePixels(staged)
            ) return null
            Files.move(
                staged.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            target.absolutePath
        } catch (_: Exception) {
            null
        } finally {
            temporary?.delete()
        }
    }

    private fun hasSupportedImageSignature(file: File): Boolean {
        val header = ByteArray(12)
        val count = file.inputStream().use { it.read(header) }
        return count >= 3 && (
            header.startsWith(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), count) ||
                header.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), count) ||
                header.startsWith("GIF87a".toByteArray(), count) ||
                header.startsWith("GIF89a".toByteArray(), count) ||
                (count >= 12 && header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
                    header.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()))
            )
    }

    /**
     * 先只读取尺寸阻止解压炸弹，再用采样解码验证载荷确实包含可读像素。
     */
    private fun hasDecodablePixels(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (
            width <= 0 || height <= 0 ||
            width > MAX_AVATAR_DIMENSION || height > MAX_AVATAR_DIMENSION ||
            width.toLong() * height.toLong() > MAX_AVATAR_PIXELS
        ) return false

        var sampleSize = 1
        while (maxOf(width, height) / sampleSize > DECODE_PROBE_DIMENSION) {
            sampleSize *= 2
        }
        val probe = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return false
        return try {
            probe.width > 0 && probe.height > 0
        } finally {
            probe.recycle()
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray, available: Int): Boolean =
        available >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

    private companion object {
        val SAFE_SLOT = Regex("[A-Za-z0-9_-]{1,96}")
        const val MAX_AVATAR_BYTES = 10L * 1024 * 1024
        const val MAX_AVATAR_DIMENSION = 8192
        const val MAX_AVATAR_PIXELS = 16_777_216L
        const val DECODE_PROBE_DIMENSION = 512
    }
}
