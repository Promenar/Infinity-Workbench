package com.promenar.nexara.data.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path

internal const val CREATE_CLEANUP_SUFFIX = ".cleanup-v2"
private val cleanupJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }
private val operationIdPattern = Regex("[A-Za-z0-9-]{1,64}")

@Serializable
private data class CreationCleanupReceipt(
    val version: Int = 1,
    val operationId: String,
    val manifest: WorkspaceCreateOwnershipManifest,
    val rolledBack: Boolean,
    val ownerIdentity: WorkspaceNodeIdentity,
    val entries: Map<String, WorkspaceNodeIdentity>,
)

internal fun creationCleanupReceiptPath(owner: List<String>): List<String> {
    require(owner.size == 2 && owner.first() == CREATE_OWNERSHIP_DIRECTORY && operationIdPattern.matches(owner.last()))
    return owner.dropLast(1) + (owner.last() + CREATE_CLEANUP_SUFFIX)
}

internal fun creationOwnershipOperationIds(fileOps: WorkspaceFileOps, root: Path): Set<String> =
    fileOps.listChildren(root, listOf(CREATE_OWNERSHIP_DIRECTORY)).mapTo(linkedSetOf()) { name ->
        name.removeSuffix(CREATE_CLEANUP_SUFFIX).also {
            if (!operationIdPattern.matches(it)) throw SecurityException("创建归属目录含未知条目")
        }
    }

/**
 * 收尾证明保存在 owner 同级，直到已验证的直属文件与空目录全部清理完成才回收。
 * 调用方须持有工作区根锁，应用私有的创建操作目录只能由遵守该锁的代码修改。
 * 文件系统没有按 inode 条件执行 unlink 的原语；此合同不覆盖同 UID 代码绕过根锁，
 * 在身份核验与删除之间篡改私有操作目录的情形。
 */
internal fun cleanupCompletedCreateOwnership(
    fileOps: WorkspaceFileOps,
    root: Path,
    owner: List<String>,
    rolledBack: Boolean? = null,
) {
    val receiptPath = creationCleanupReceiptPath(owner)
    val hasReceipt = fileOps.exists(root, receiptPath)
    if (!hasReceipt && !fileOps.exists(root, owner)) return
    val receipt = if (hasReceipt) {
        cleanupJson.decodeFromString<CreationCleanupReceipt>(
            fileOps.readLimited(root, receiptPath, 32768).toString(Charsets.UTF_8),
        )
    } else {
        prepareCleanupReceipt(fileOps, root, owner, rolledBack == true).also { prepared ->
            fileOps.createFile(root, receiptPath, cleanupJson.encodeToString(prepared).toByteArray(Charsets.UTF_8))
        }
    }
    validateReceipt(receipt, owner, rolledBack)
    val manifest = receipt.manifest
    val token = requireNotNull(manifest.proofToken)
    val target = canonicalCreationTarget(manifest.targetRelativePath)
    if (fileOps.exists(root, owner)) {
        if (fileOps.inspect(root, owner) != receipt.ownerIdentity) throw SecurityException("创建清理owner身份改变")
        val present = fileOps.listChildren(root, owner).toSet()
        if (!receipt.entries.keys.containsAll(present)) throw SecurityException("创建清理owner含未知内容")
        // 删除任何条目前完成整个剩余清单核验，新增/替换内容不会被当作可清理元数据。
        present.forEach { name ->
            if (fileOps.inspect(root, owner + name) != receipt.entries.getValue(name)) {
                throw SecurityException("创建清理条目身份或内容改变")
            }
        }
        if (!receipt.rolledBack) {
            if (!fileOps.exists(root, target) || fileOps.inspect(root, target) != manifest.identity) {
                throw SecurityException("已提交创建目标身份改变")
            }
            if (manifest.identity.kind == "file") fileOps.verifyCreationProof(root, target, owner, token, manifest.identity)
            if (manifest.identity.kind == "directory" && fileOps.exists(root, target + CREATE_DIRECTORY_MARKER)) {
                fileOps.verifyCreationProof(root, target, owner, token, manifest.identity)
                fileOps.releaseCreationProof(root, target, token)
            }
        }
        present.sortedBy { if (it == CREATE_MANIFEST) 1 else 0 }.forEach { name ->
            fileOps.deleteNonRecursive(root, owner + name)
        }
        fileOps.deleteNonRecursive(root, owner)
    }
    fileOps.deleteNonRecursive(root, receiptPath)
}

private fun prepareCleanupReceipt(
    fileOps: WorkspaceFileOps, root: Path, owner: List<String>, rolledBack: Boolean,
): CreationCleanupReceipt {
    val manifest = decodeCreateOwnershipManifest(fileOps.readLimited(root, owner + CREATE_MANIFEST, 8192))
        ?: throw SecurityException("创建清理缺少有效manifest")
    val token = manifest.proofToken ?: throw SecurityException("旧创建owner缺少持续证明，已保留")
    requireCurrentCreationProof(manifest)
    val target = canonicalCreationTarget(manifest.targetRelativePath)
    val expectedNames = when (manifest.identity.kind) {
        "file" -> if (rolledBack) setOf(CREATE_MANIFEST, CREATE_FILE_DELETE_READY) else setOf(CREATE_MANIFEST)
        "directory" -> if (rolledBack) setOf(CREATE_MANIFEST, CREATE_DIRECTORY_DELETE_READY) else setOf(CREATE_MANIFEST)
        else -> throw SecurityException("创建清理节点类型无效")
    }
    if (fileOps.listChildren(root, owner).toSet() != expectedNames) {
        throw SecurityException("创建owner内容不符合精确清理清单")
    }
    if (rolledBack) {
        if (fileOps.exists(root, target)) throw SecurityException("创建回滚目标仍存在")
        val phase = if (manifest.identity.kind == "file") CREATE_FILE_DELETE_READY else CREATE_DIRECTORY_DELETE_READY
        if (fileOps.readLimited(root, owner + phase, 128).toString(Charsets.UTF_8) != token) {
            throw SecurityException("创建回滚删除phase不匹配")
        }
    } else fileOps.verifyCreationProof(root, target, owner, token, manifest.identity)
    return CreationCleanupReceipt(
        operationId = owner.last(), manifest = manifest, rolledBack = rolledBack,
        ownerIdentity = fileOps.inspect(root, owner),
        entries = expectedNames.associateWith { fileOps.inspect(root, owner + it) },
    ).also { validateReceipt(it, owner, rolledBack) }
}

private fun validateReceipt(receipt: CreationCleanupReceipt, owner: List<String>, rolledBack: Boolean?) {
    requireCurrentCreationProof(receipt.manifest)
    canonicalCreationTarget(receipt.manifest.targetRelativePath)
    val names = when (receipt.manifest.identity.kind) {
        "file" -> if (receipt.rolledBack) setOf(CREATE_MANIFEST, CREATE_FILE_DELETE_READY) else setOf(CREATE_MANIFEST)
        "directory" -> if (receipt.rolledBack) setOf(CREATE_MANIFEST, CREATE_DIRECTORY_DELETE_READY) else setOf(CREATE_MANIFEST)
        else -> throw SecurityException("清理回执节点类型无效")
    }
    if (receipt.version != 1 || receipt.operationId != owner.last() ||
        (rolledBack != null && receipt.rolledBack != rolledBack) || receipt.ownerIdentity.kind != "directory" ||
        receipt.entries.keys != names || receipt.entries.values.any { it.kind != "file" || it.sha256 == null }) {
        throw SecurityException("创建清理回执结构无效")
    }
}

private fun canonicalCreationTarget(raw: String): List<String> = raw.split('/').also { parts ->
    if (parts.any { it.isBlank() || it == "." || it == ".." || '\\' in it || '\u0000' in it } ||
        parts.first() == CREATE_OWNERSHIP_DIRECTORY) throw SecurityException("创建清理目标不是规范工作区路径")
}
