package com.promenar.nexara.data.generation

import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

/** 确认点不随备份传播；正文与工具结果由会话数据库保存。 */
class FileHeadlessInteractionPersistence(file: File) : HeadlessInteractionPersistence {
    private val file = AtomicFile(file)
    override suspend fun read(): GenerationInteraction? = readAll().firstOrNull()

    override suspend fun readAll(): List<GenerationInteraction> = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) emptyList()
        else file.openRead().bufferedReader().use {
            val element = Json.parseToJsonElement(it.readText())
            if (element is JsonArray) Json.decodeFromJsonElement(ListSerializer(GenerationInteraction.serializer()), element)
            else listOf(Json.decodeFromJsonElement(GenerationInteraction.serializer(), element))
        }
    }

    override suspend fun write(interaction: GenerationInteraction?) = writeAll(listOfNotNull(interaction))

    override suspend fun writeAll(interactions: List<GenerationInteraction>) = withContext(Dispatchers.IO) {
        com.promenar.nexara.startup.StartupWriteBarrier.requireWritable(subsystem = com.promenar.nexara.startup.StartupSubsystem.HEADLESS_INTERACTIONS)
        if (interactions.isEmpty()) file.delete()
        else {
            file.baseFile.parentFile?.mkdirs()
            val output = file.startWrite()
            try {
                output.write(Json.encodeToString(ListSerializer(GenerationInteraction.serializer()), interactions).toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (failure: Throwable) {
                file.failWrite(output)
                throw failure
            }
        }
    }
}
