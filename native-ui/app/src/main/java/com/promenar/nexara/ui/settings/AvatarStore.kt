package com.promenar.nexara.ui.settings

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
                    source.copyTo(output)
                    output.fd.sync()
                }
            }
            if (staged.length() == 0L || !hasSupportedImageSignature(staged)) return null
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

    private fun ByteArray.startsWith(prefix: ByteArray, available: Int): Boolean =
        available >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

    private companion object {
        val SAFE_SLOT = Regex("[A-Za-z0-9_-]{1,96}")
    }
}
