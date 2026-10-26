package com.promenar.nexara.data.memory

import androidx.room.withTransaction
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.startup.StartupWriteBarrier
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomLongTermMemoryRepository(
    private val database: NexaraDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : LongTermMemoryRepository {
    private val dao get() = database.longTermMemoryDao()

    override fun observeAll(): Flow<List<LongTermMemory>> = dao.observeAll()
        .map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.IO)
    override suspend fun getAll(): List<LongTermMemory> = withContext(Dispatchers.IO) { dao.getAll().map { it.toDomain() } }
    override suspend fun get(id: String): LongTermMemory? = withContext(Dispatchers.IO) { dao.get(id)?.toDomain() }

    override suspend fun save(kind: LongTermMemoryKind, title: String, content: String, id: String?, source: LongTermMemorySource?): LongTermMemoryWrite = withContext(Dispatchers.IO) {
        val (cleanTitle, cleanContent) = LongTermMemoryValidation.validate(title, content)
        StartupWriteBarrier.requireWritable()
        database.withTransaction {
            val rows = dao.getAll()
            val previous = if (id != null) {
                rows.firstOrNull { it.id == id } ?: throw LongTermMemoryException(LongTermMemoryFailure.NOT_FOUND)
            } else rows.firstOrNull { it.title.trim().equals(cleanTitle, ignoreCase = true) }
            if (previous == null) requireCapacity(rows.size)
            val memory = LongTermMemory(
                id = previous?.id ?: UUID.randomUUID().toString(), kind = kind, title = cleanTitle, content = cleanContent,
                sourceAgentId = if (source == null) previous?.sourceAgentId else source.agentId,
                sourceSessionId = if (source == null) previous?.sourceSessionId else source.sessionId,
                sourceMessageId = if (source == null) previous?.sourceMessageId else source.messageId,
                createdAt = previous?.createdAt ?: now(), updatedAt = now(),
            )
            dao.upsert(memory.toEntity())
            LongTermMemoryWrite(memory, previous?.toDomain())
        }
    }

    override suspend fun delete(id: String): LongTermMemory? = withContext(Dispatchers.IO) {
        StartupWriteBarrier.requireWritable()
        database.withTransaction { dao.get(id)?.toDomain()?.also { dao.delete(id) } }
    }

    override suspend fun restore(memory: LongTermMemory) = withContext(Dispatchers.IO) {
        LongTermMemoryValidation.validateId(memory.id)
        val (title, content) = LongTermMemoryValidation.validate(memory.title, memory.content)
        if (title != memory.title || content != memory.content) throw LongTermMemoryException(LongTermMemoryFailure.INVALID_RESTORE)
        StartupWriteBarrier.requireWritable()
        database.withTransaction {
            if (dao.get(memory.id) == null) requireCapacity(dao.getAll().size)
            dao.upsert(memory.toEntity())
        }
    }

    private fun requireCapacity(count: Int) {
        if (count >= LongTermMemoryValidation.MAX_MEMORIES) throw LongTermMemoryException(LongTermMemoryFailure.LIMIT_REACHED)
    }
}
