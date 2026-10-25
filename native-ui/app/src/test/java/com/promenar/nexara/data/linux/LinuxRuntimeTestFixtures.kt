package com.promenar.nexara.data.linux

import com.promenar.nexara.domain.linux.LinuxPaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

internal fun linuxTestArchive(): ByteArray = ByteArrayOutputStream().also { bytes ->
    GZIPOutputStream(bytes).use { gzip ->
        mapOf("bin/sh" to "test-shell", "etc/alpine-release" to "3.24.3\n", "root/base" to "base",
            "etc/apk/world" to "busybox\n", "lib/apk/db/installed" to "P:busybox\nV:1\n\n").forEach { (name, body) ->
            val header = ByteArray(512)
            fun put(offset: Int, value: String) = value.toByteArray().copyInto(header, offset)
            put(0, name); put(100, "0000755"); put(124, body.length.toString(8).padStart(11, '0'))
            header[156] = '0'.code.toByte()
            (148..155).forEach { header[it] = 32 }
            put(148, header.sumOf { it.toInt() and 255 }.toString(8).padStart(6, '0') + "\u0000 ")
            gzip.write(header); gzip.write(body.toByteArray()); gzip.write(ByteArray((512 - body.length % 512) % 512))
        }
        gzip.write(ByteArray(1024))
    }
}.toByteArray()

internal fun linuxTestMetadata(archive: ByteArray = linuxTestArchive()) = LinuxRootfsMetadata(
    "3.24.3", MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) },
    "linux/rootfs.bin", "proot-test", 4096,
)

internal fun linuxTestPaths(directory: File): LinuxPaths = File(directory, "linux/alpine").let { LinuxPaths(it, File(it, "root")) }

internal fun linuxTestProot(directory: File) = PackagedProot(File(directory, "lib").apply { mkdirs() }).also { proot ->
    listOf(proot.executable, proot.loader).forEach { it.writeText("elf"); it.setExecutable(true) }
}
