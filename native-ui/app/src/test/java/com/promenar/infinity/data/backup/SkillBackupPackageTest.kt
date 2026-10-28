package com.promenar.infinity.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SkillBackupPackageTest {
    @Test
    fun `旧技能包内部普通回收目录仍可导入`() {
        val files = mapOf("alpha/SKILL.md" to "技能".toByteArray(), "alpha/.recycle_bin/data" to "资料".toByteArray())
        val metadata = SkillBackupMetadata(listOf("alpha", "alpha/.recycle_bin"), SkillBackupSummary(1))
        val bytes = DefaultBackupPackageCodec().encode(
            BackupSnapshot(databaseSchemaVersion = 30, appVersion = "0.2.2-beta", skills = SkillBackupPartition(files, metadata)),
            BackupOptions(emptySet()),
        )
        DefaultBackupPackageCodec().decode(bytes, null).use {
            assertThat(it.skills!!.files["alpha/.recycle_bin/data"]).isEqualTo("资料".toByteArray())
        }
    }

    @Test
    fun `技能文件空目录与仓库恢复提示经加密包往返`() {
        val files = mapOf("alpha/SKILL.md" to "甲".toByteArray(), "beta/SKILL.md" to "乙".toByteArray(),
            "alpha/scripts/tool.py" to "print(1)".toByteArray())
        val metadata = SkillBackupMetadata(
            listOf("alpha", "alpha/scripts", "beta", "beta/empty"),
            SkillBackupSummary(2, 3, listOf(SkippedSkillGitRepository(".",
                listOf(SkillGitRemote("origin", "https://example.invalid/repo.git")), "main", "a".repeat(40)))),
        )
        val codec = DefaultBackupPackageCodec()
        val bytes = codec.encode(
            BackupSnapshot(databaseSchemaVersion = 5, appVersion = "test", skills = SkillBackupPartition(files, metadata)),
            BackupOptions(emptySet(), password = "synthetic".toCharArray(), encryptPackage = true),
        )
        codec.decode(bytes, "synthetic".toCharArray()).use { restored ->
            assertThat(restored.skills?.metadata).isEqualTo(metadata)
            assertThat(restored.skills?.files?.keys).containsExactlyElementsIn(files.keys)
            files.forEach { (path, content) -> assertThat(restored.skills?.files?.get(path)).isEqualTo(content) }
        }
    }

    @Test
    fun `技能分区拒绝越界和保留路径`() {
        listOf("skills/../escape", "skills//absolute", "skills/C:/absolute", "skills/alpha/../../escape",
            "skills/.infinity-owner", "skills/alpha/.infinity-cache", "/skills/alpha", "skills/alpha\\escape").forEach { path ->
            assertThrows<BackupValidationException> { DefaultBackupPackageCodec().decode(archive(listOf(path)), null) }
        }
    }

    @Test
    fun `技能分区拒绝符号链接 ZIP 条目`() {
        val bytes = archive(listOf("skills/alpha/SKILL.md"))
        val central = centralEntries(bytes).last()
        bytes[central + 5] = 3
        val mode = 0xa1ff shl 16
        repeat(4) { index -> bytes[central + 38 + index] = (mode ushr (8 * index)).toByte() }
        assertThat(assertThrows<BackupValidationException> { DefaultBackupPackageCodec().decode(bytes, null) })
            .hasMessageThat().contains("符号链接")
    }

    @Test
    fun `技能分区拒绝重复 ZIP 路径`() {
        val bytes = archive(listOf("skills/alpha/a", "skills/alpha/b"))
        val old = "skills/alpha/b".toByteArray()
        for (index in 0..bytes.size - old.size) {
            if (bytes.copyOfRange(index, index + old.size).contentEquals(old)) bytes[index + old.size - 1] = 'a'.code.toByte()
        }
        assertThrows<BackupValidationException> { DefaultBackupPackageCodec().decode(bytes, null) }
    }

    @Test
    fun `ZIP 本地头独有的符号链接扩展属性也被拒绝`() {
        val bytes = archive(listOf("skills/alpha/SKILL.md"), skillExtra = byteArrayOf(
            0x6e, 0x75, 6, 0, 0, 0, 0, 0, 0xff.toByte(), 0xa1.toByte(),
        ))
        val central = centralEntries(bytes).last()
        val nameSize = (bytes[central + 28].toInt() and 255) or ((bytes[central + 29].toInt() and 255) shl 8)
        val extra = central + 46 + nameSize
        bytes[extra] = 0x00
        bytes[extra + 1] = 0x00
        assertThrows<BackupValidationException> { DefaultBackupPackageCodec().decode(bytes, null) }
    }

    @Test
    fun `中央目录指向文件内容中的伪造头也不能隐藏实际符号链接属性`() {
        val name = "skills/alpha/SKILL.md"
        val nameBytes = name.toByteArray()
        val fakeHeader = ByteBuffer.allocate(30 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x04034b50)
            position(26)
            putShort(nameBytes.size.toShort())
            putShort(0)
            put(nameBytes)
        }.array()
        val values = listOf(name to byteArrayOf(1), "files/decoy" to fakeHeader)
        val manifest = BackupManifest(2, 5, "test", 1, values.map { (path, value) ->
            BackupManifestEntry(path, value.size.toLong(), MessageDigest.getInstance("SHA-256").digest(value)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) })
        }, false, false, skills = SkillBackupMetadata(listOf("alpha"), SkillBackupSummary(1)))
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                (listOf("manifest.json" to Json.encodeToString(manifest).toByteArray()) + values).forEach { (path, value) ->
                    zip.putNextEntry(ZipEntry(path).apply {
                        method = ZipEntry.STORED
                        size = value.size.toLong()
                        crc = CRC32().apply { update(value) }.value
                        if (path == name) extra = byteArrayOf(0x6e, 0x75, 6, 0, 0, 0, 0, 0, 0xff.toByte(), 0xa1.toByte())
                    })
                    zip.write(value)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val central = centralEntries(bytes)
        val target = central[1]
        val decoyLocal = buffer.getInt(central[2] + 42)
        val fakeOffset = decoyLocal + 30 + (buffer.getShort(decoyLocal + 26).toInt() and 65535) +
            (buffer.getShort(decoyLocal + 28).toInt() and 65535)
        buffer.putInt(target + 42, fakeOffset)
        val targetExtra = target + 46 + (buffer.getShort(target + 28).toInt() and 65535)
        buffer.putShort(targetExtra, 0)
        assertThrows<BackupValidationException> { DefaultBackupPackageCodec().decode(bytes, null) }
    }

    @Test
    fun `目录与内容冲突或缺少分区元数据的包被拒绝`() {
        val conflicting = archive(listOf("skills/alpha/SKILL.md", "skills/alpha"))
        assertThrows<BackupValidationException> { DefaultBackupPackageCodec().decode(conflicting, null) }
        val missing = archive(listOf("skills/alpha/SKILL.md"), metadata = null)
        assertThrows<BackupValidationException> { DefaultBackupPackageCodec().decode(missing, null) }
    }

    @Test
    fun `v1 无技能分区包仍可导入而 v1 伪造技能分区被拒绝`() {
        DefaultBackupPackageCodec().decode(archive(emptyList(), version = 1, metadata = null), null).use {
            assertThat(it.skills).isNull()
        }
        assertThrows<BackupValidationException> {
            DefaultBackupPackageCodec().decode(archive(listOf("skills/alpha/SKILL.md"), version = 1), null)
        }
    }

    @Test
    fun `技能目录和可选密钥条目共同计入整包条目上限`() {
        val partition = SkillBackupPartition(emptyMap(), SkillBackupMetadata((0 until 9998).map { "dir$it" }))
        val snapshot = BackupSnapshot(databaseSchemaVersion = 5, appVersion = "test", skills = partition)
        assertThrows<BackupValidationException> {
            DefaultBackupPackageCodec().encode(snapshot,
                BackupOptions(BackupContent.entries.toSet(), includeSecrets = true, password = "synthetic".toCharArray()))
        }
    }

    @Test
    fun `带技能分区的备份可校验并保留嵌套路径`() {
        val content = "技能说明".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val manifest = """
            {"formatVersion":2,"databaseSchemaVersion":5,"appVersion":"test","createdAt":1,
             "entries":[{"path":"skills/alpha/SKILL.md","sizeBytes":${content.size},"sha256":"$hash"}],
             "encrypted":false,"containsSecrets":false,
             "skills":{"directories":["alpha"],"summary":{"skillCount":1,"skippedSymbolicLinks":0,"skippedGitRepositories":[]}}}
        """.trimIndent().toByteArray()
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                listOf("manifest.json" to manifest, "skills/alpha/SKILL.md" to content).forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

        DefaultBackupPackageCodec().decode(bytes, null).use { decoded ->
            assertThat(decoded.manifest.entries.map { it.path }).containsExactly("skills/alpha/SKILL.md")
        }
    }

    private fun archive(
        paths: List<String>,
        version: Int = 2,
        metadata: SkillBackupMetadata? = SkillBackupMetadata(listOf("alpha"),
            SkillBackupSummary(skillCount = paths.count { it == "skills/alpha/SKILL.md" })),
        skillExtra: ByteArray? = null,
    ): ByteArray {
        val content = byteArrayOf(1)
        val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val manifest = BackupManifest(version, 5, "test", 1, paths.map { BackupManifestEntry(it, 1, hash) }, false, false,
            skills = metadata)
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                (listOf("manifest.json" to Json.encodeToString(manifest).toByteArray()) + paths.map { it to content }).forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name).apply { if (name.startsWith("skills/")) extra = skillExtra })
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun centralEntries(bytes: ByteArray): List<Int> = (0..bytes.size - 4).filter { index ->
        bytes[index] == 0x50.toByte() && bytes[index + 1] == 0x4b.toByte() &&
            bytes[index + 2] == 0x01.toByte() && bytes[index + 3] == 0x02.toByte()
    }
}
