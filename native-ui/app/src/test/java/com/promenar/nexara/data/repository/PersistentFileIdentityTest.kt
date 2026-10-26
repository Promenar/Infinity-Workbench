package com.promenar.nexara.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.attribute.UserDefinedFileAttributeView

class PersistentFileIdentityTest {
    private val nonce = "11111111-1111-4111-8111-111111111111"

    @Test fun `编码忽略设备号并保留nonce与inode两个边界`() {
        val identity = PersistentFileIdentity.encode(nonce, "(dev=1,ino=42)")
        assertThat(PersistentFileIdentity.encode(nonce, "(dev=ffff,ino=42)")).isEqualTo(identity)
        assertThat(PersistentFileIdentity.encode(nonce, "ino=43")).isNotEqualTo(identity)
        assertThat(PersistentFileIdentity.encode("22222222-2222-4222-8222-222222222222", "ino=42")).isNotEqualTo(identity)
        assertThat(PersistentFileIdentity.parseMarker(PersistentFileIdentity.marker(nonce, "(dev=1,ino=42)")))
            .isEqualTo(nonce to "ino=42")
        assertThat(identity).doesNotContain("dev=")
    }

    @Test fun `拒绝无法解析的inode与无版本认领且不猜测缺失信息`() {
        listOf("", "(dev=1)", "ino=0", "ino=-1", "ino=18446744073709551616", "path/name").forEach {
            assertThrows(SecurityException::class.java) { PersistentFileIdentity.inode(it) }
        }
        assertThat(PersistentFileIdentity.parseMarker("$nonce\n(dev=1,ino=42)")).isEqualTo(nonce to "ino=42")
        assertThat(PersistentFileIdentity.isValid("$nonce|${"a".repeat(64)}")).isFalse()
    }

    @Test fun `节点nonce跨实例保存且同inode替换nonce后身份不同`() {
        val root = Files.createTempDirectory("persistent-nonce")
        try {
            val first = PersistentFileIdentity.read(root)
            assertThat(PersistentFileIdentity.read(root)).isEqualTo(first)
            val attributes = Files.getFileAttributeView(root, UserDefinedFileAttributeView::class.java)
            attributes.write("nexara_persistent_identity", ByteBuffer.wrap(nonce.toByteArray(Charsets.US_ASCII)))
            assertThat(PersistentFileIdentity.read(root)).isNotEqualTo(first)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun `已可见但未同步的nonce不会在同步失败时发布身份`() {
        var synced = false
        assertThrows(java.io.IOException::class.java) {
            PersistentFileIdentity.claimPersistentNonce({ nonce }, { error("不应重写") }, { throw java.io.IOException("同步失败") })
        }
        assertThat(PersistentFileIdentity.claimPersistentNonce({ nonce }, { error("不应重写") }, { synced = true })).isEqualTo(nonce)
        assertThat(synced).isTrue()
    }

    @Test fun `创建竞争读取对方nonce也必须同步且本次创建同步失败不能吞掉`() {
        var stored: String? = null
        var synced = false
        val candidate = "22222222-2222-4222-8222-222222222222"
        assertThat(PersistentFileIdentity.claimPersistentNonce({ stored }, {
            stored = nonce
            throw java.io.IOException("并发创建已存在")
        }, { synced = true }, { candidate })).isEqualTo(nonce)
        assertThat(synced).isTrue()
        stored = null
        assertThrows(java.io.IOException::class.java) {
            PersistentFileIdentity.claimPersistentNonce({ stored }, {
                stored = it
                throw java.io.IOException("创建后同步失败")
            }, {}, { candidate })
        }
    }

}
