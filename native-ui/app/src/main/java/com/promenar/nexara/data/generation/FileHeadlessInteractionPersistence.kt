package com.promenar.nexara.data.generation

import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** 确认点不随备份传播；正文与工具结果由会话数据库保存。 */
class FileHeadlessInteractionPersistence(file: File) : HeadlessInteractionPersistence {
    private val file = AtomicFile(file)
    override suspend fun read(): GenerationInteraction? = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) null
        else file.openRead().bufferedReader().use { Json.decodeFromString<GenerationInteraction>(it.readText()) }
    }
    override suspend fun write(interaction: GenerationInteraction?) = withContext(Dispatchers.IO) {
        com.promenar.nexara.startup.StartupWriteBarrier.requireWritable(subsystem = com.promenar.nexara.startup.StartupSubsystem.HEADLESS_INTERACTIONS)
        if (interaction == null) file.delete()
        else {
            file.baseFile.parentFile?.mkdirs()
            val output = file.startWrite()
            try {
                output.write(Json.encodeToString(GenerationInteraction.serializer(), interaction).toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (failure: Throwable) {
                file.failWrite(output)
                throw failure
            }
        }
    }
}
