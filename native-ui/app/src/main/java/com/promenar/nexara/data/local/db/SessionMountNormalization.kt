package com.promenar.nexara.data.local.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/**
 * 挂载只属于会话，保留来源的只读标记。旧版全局挂载在打开数据库时分配给会话：
 * 不升 schema 版本，从旧备份恢复的全局行也会在下次打开时得到同样处理。
 */
internal fun normalizeSessionMounts(db: SupportSQLiteDatabase) {
    val pending = db.query(
        "SELECT EXISTS(SELECT 1 FROM external_mounts WHERE scope_key = '')",
    ).use { it.moveToFirst() && it.getInt(0) != 0 }
    if (!pending) return
    db.beginTransaction()
    try {
        val sessions = db.query("SELECT id FROM sessions ORDER BY id").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val globals = db.query("SELECT id, alias FROM external_mounts WHERE scope_key = '' ORDER BY created_at, id").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
        }
        globals.forEach { (id, originalAlias) ->
            sessions.forEach { sessionId ->
                var alias = originalAlias
                var suffix = 2
                while (db.query("SELECT 1 FROM external_mounts WHERE scope_key = ? AND alias = ?", arrayOf(sessionId, alias)).use { it.moveToFirst() }) {
                    alias = "$originalAlias-${suffix++}"
                }
                db.execSQL(
                    """INSERT INTO external_mounts
                        (id, alias, source_type, source_id, scope_key, read_only, created_at, unavailable_reason)
                        SELECT ?, ?, source_type, source_id, ?, read_only, created_at, unavailable_reason
                        FROM external_mounts WHERE id = ?""".trimIndent(),
                    arrayOf(UUID.randomUUID().toString(), alias, sessionId, id),
                )
            }
        }
        // 不依赖连接是否启用外键，显式清理被删除挂载的导入索引。
        db.execSQL("DELETE FROM external_snapshots WHERE mount_id IN (SELECT id FROM external_mounts WHERE scope_key = '')")
        db.execSQL("DELETE FROM external_mounts WHERE scope_key = ''")
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

/** 规整失败不阻止数据库打开：DAO 已不返回全局行，下次打开会重试。 */
internal class SessionMountNormalizationCallback(
    private val onFailure: (Throwable) -> Unit,
) : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        runCatching { normalizeSessionMounts(db) }.onFailure(onFailure)
    }
}
