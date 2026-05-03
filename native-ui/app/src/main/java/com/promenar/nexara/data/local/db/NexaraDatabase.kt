package com.promenar.nexara.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.promenar.nexara.data.local.db.dao.ArtifactDao
import com.promenar.nexara.data.local.db.dao.AttachmentDao
import com.promenar.nexara.data.local.db.dao.AuditLogDao
import com.promenar.nexara.data.local.db.dao.ContextSummaryDao
import com.promenar.nexara.data.local.db.dao.DocumentDao
import com.promenar.nexara.data.local.db.dao.DocumentTagDao
import com.promenar.nexara.data.local.db.dao.FolderDao
import com.promenar.nexara.data.local.db.dao.KgEdgeDao
import com.promenar.nexara.data.local.db.dao.KgJitCacheDao
import com.promenar.nexara.data.local.db.dao.KgNodeDao
import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.dao.TagDao
import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.dao.VectorizationTaskDao
import com.promenar.nexara.data.local.db.entity.ArtifactEntity
import com.promenar.nexara.data.local.db.entity.AttachmentEntity
import com.promenar.nexara.data.local.db.entity.AuditLogEntity
import com.promenar.nexara.data.local.db.entity.ContextSummaryEntity
import com.promenar.nexara.data.local.db.entity.DocumentEntity
import com.promenar.nexara.data.local.db.entity.DocumentTagEntity
import com.promenar.nexara.data.local.db.entity.FolderEntity
import com.promenar.nexara.data.local.db.entity.KgEdgeEntity
import com.promenar.nexara.data.local.db.entity.KgJitCacheEntity
import com.promenar.nexara.data.local.db.entity.KgNodeEntity
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.local.db.entity.TagEntity
import com.promenar.nexara.data.local.db.entity.VectorEntity
import com.promenar.nexara.data.local.db.entity.VectorFtsEntity
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        FolderEntity::class,
        DocumentEntity::class,
        VectorEntity::class,
        VectorFtsEntity::class,
        ContextSummaryEntity::class,
        TagEntity::class,
        DocumentTagEntity::class,
        KgNodeEntity::class,
        KgEdgeEntity::class,
        KgJitCacheEntity::class,
        VectorizationTaskEntity::class,
        AuditLogEntity::class,
        ArtifactEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class NexaraDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun folderDao(): FolderDao
    abstract fun documentDao(): DocumentDao
    abstract fun vectorDao(): VectorDao
    abstract fun contextSummaryDao(): ContextSummaryDao
    abstract fun tagDao(): TagDao
    abstract fun documentTagDao(): DocumentTagDao
    abstract fun kgNodeDao(): KgNodeDao
    abstract fun kgEdgeDao(): KgEdgeDao
    abstract fun kgJitCacheDao(): KgJitCacheDao
    abstract fun vectorizationTaskDao(): VectorizationTaskDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun artifactDao(): ArtifactDao
}
