package com.promenar.nexara.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path

/** 同一进程内按规范化物理根串行化全部文件与元数据变更。 */
object WorkspaceMutationCoordinator {
    private data class RootLock(val mutex: Mutex = Mutex(), var references: Int = 0)

    private val monitor = Any()
    private val roots = mutableMapOf<String, RootLock>()
    private val identities = LinkedHashMap<String, String>(32, 0.75f, true)

    suspend fun <T> withRoot(root: Path, block: suspend () -> T): T {
        val key = root.toFile().canonicalPath
        val lock = acquire(key, identity = null)
        return use(key, lock, block)
    }

    suspend fun <T> withBoundRoot(root: Path, identity: String, block: suspend () -> T): T {
        if (identity.isBlank()) throw SecurityException("工作区根缺少数据库身份绑定")
        val key = root.toFile().canonicalPath
        val lock = acquire(key, identity)
        return use(key, lock, block)
    }

    private fun acquire(key: String, identity: String?): RootLock = synchronized(monitor) {
        if (identity != null) identities[key] = identity
        roots.getOrPut(key) { RootLock() }.also { it.references += 1 }
    }

    private suspend fun <T> use(key: String, lock: RootLock, block: suspend () -> T): T {
        try {
            return lock.mutex.withLock { block() }
        } finally {
            synchronized(monitor) {
                lock.references -= 1
                if (lock.references == 0 && roots[key] === lock) roots.remove(key)
                trimIdentities()
            }
        }
    }

    internal fun bindIdentityForTesting(root: Path, identity: String) {
        if (identity.isBlank()) throw SecurityException("工作区根缺少数据库身份绑定")
        synchronized(monitor) {
            identities[root.toFile().canonicalPath] = identity
            trimIdentities()
        }
    }

    internal fun bindIdentityWhileHeld(root: Path, identity: String) {
        if (identity.isBlank()) throw SecurityException("工作区根缺少数据库身份绑定")
        val key = root.toFile().canonicalPath
        synchronized(monitor) {
            if ((roots[key]?.references ?: 0) <= 0) throw IllegalStateException("root identity 必须在持锁期间绑定")
            identities[key] = identity
        }
    }

    fun expectedIdentity(root: Path): String? = synchronized(monitor) {
        identities[root.toFile().canonicalPath]
    }

    private fun trimIdentities() {
        while (identities.size > 4096) {
            val removable = identities.keys.firstOrNull { key -> (roots[key]?.references ?: 0) == 0 }
                ?: return
            identities.remove(removable)
        }
    }
}
