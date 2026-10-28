package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.linux.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal class LinuxPackageMetadata(private val runtime: LinuxRuntime, private val file: File) : LinuxPackageProvenance {
    private val mutex = Mutex()

    override suspend fun worldSnapshot(): Set<String>? = withContext(Dispatchers.IO) {
        if (runtime.state.value !is LinuxRuntimeState.Ready) null
        else if (!LinuxPackageStorage.checked(runtime.paths.rootfs, "etc/apk/world").isFile) null
        else world()
    }

    override suspend fun attributeNewPackages(before: Set<String>, origin: LinuxPackageOrigin) {
        if (runtime.state.value !is LinuxRuntimeState.Ready) return
        update { records, world ->
            (world - before).filter { it !in records && LinuxPackageParsing.validName(it) }.forEach { records[it] = origin }
        }
    }

    suspend fun origins(): Map<String, LinuxPackageOrigin> = update { _, _ -> }

    suspend fun record(names: Set<String>, origin: LinuxPackageOrigin, overwrite: Boolean = true) {
        update { records, world -> (names intersect world).filter(LinuxPackageParsing::validName).forEach {
            if (overwrite || it !in records) records[it] = origin
        } }
    }

    suspend fun clear(names: Set<String>) { update { records, _ -> names.forEach(records::remove) } }

    private fun world() = LinuxPackageParsing.world(LinuxPackageStorage.read(runtime.paths.rootfs, "etc/apk/world"))

    private suspend fun update(change: (MutableMap<String, LinuxPackageOrigin>, Set<String>) -> Unit): Map<String, LinuxPackageOrigin> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val state = runtime.state.value
                if (state !is LinuxRuntimeState.Ready && state !is LinuxRuntimeState.Installing) return@withLock emptyMap()
                val generation = generation() ?: return@withLock emptyMap()
                val installedAt = (state as? LinuxRuntimeState.Ready)?.info?.installedAtMs
                val world = world()
                val saved = LinuxPackageStorage.read(checkNotNull(file.parentFile), file.name)
                val document = runCatching { Json.parseToJsonElement(saved).jsonObject }.getOrNull()
                val records = mutableMapOf<String, LinuxPackageOrigin>()
                val savedTimestamp = (document?.get("installedAt") as? JsonPrimitive)?.longOrNull
                val sameInstallation = savedTimestamp == null || installedAt == savedTimestamp
                if ((document?.get("schema") as? JsonPrimitive)?.intOrNull == 1 &&
                    (document["environment"] as? JsonPrimitive)?.content == generation && sameInstallation) {
                    (document["packages"] as? JsonObject)?.forEach { (name, value) ->
                        if (name in world) decode(value)?.let { records[name] = it }
                    }
                }
                change(records, world)
                val encoded = buildJsonObject {
                    put("schema", 1)
                    put("environment", generation)
                    // 安装引导先写来源，首次 Ready 时再绑定运行时持久化的安装时间。
                    put("installedAt", installedAt?.let(::JsonPrimitive) ?: JsonNull)
                    putJsonObject("packages") { records.toSortedMap().forEach { (name, origin) -> put(name, encode(origin)) } }
                }.toString()
                // 读操作也清除过期条目，防止包卸载后重装时恢复到旧来源。
                if (encoded != saved) LinuxPackageStorage.atomicWrite(checkNotNull(file.parentFile), file.name, encoded)
                records.toMap()
            }
        }

    private fun generation(): String? {
        val root = runtime.paths.rootfs
        val release = LinuxPackageStorage.checked(root, "etc/alpine-release")
        if (!root.isDirectory || !release.isFile) return null
        val attributes = Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        // Android 不支持 birthtime 时 creationTime 回退为 mtime，目录正常变更不能使来源失效。
        return com.promenar.infinity.data.repository.PersistentFileIdentity.read(root.toPath())
    }

    private fun encode(origin: LinuxPackageOrigin) = buildJsonObject {
        when (origin) {
            LinuxPackageOrigin.User -> put("kind", "user")
            LinuxPackageOrigin.Preset -> put("kind", "preset")
            is LinuxPackageOrigin.Agent -> {
                put("kind", "agent"); put("sessionId", origin.sessionId)
                origin.sessionTitle?.let { put("sessionTitle", it) }
            }
        }
    }

    private fun decode(element: JsonElement): LinuxPackageOrigin? = runCatching {
        val value = element.jsonObject
        when (value["kind"]?.jsonPrimitive?.content) {
            "user" -> LinuxPackageOrigin.User
            "preset" -> LinuxPackageOrigin.Preset
            "agent" -> LinuxPackageOrigin.Agent(value.getValue("sessionId").jsonPrimitive.content,
                value["sessionTitle"]?.jsonPrimitive?.contentOrNull)
            else -> null
        }
    }.getOrNull()
}
