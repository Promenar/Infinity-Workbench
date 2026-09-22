package com.promenar.nexara.data.repository

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** 测量重新打开 Room 连接后的完整会话反序列化；不冒充系统冷缓存或 UI 首帧。 */
@LargeTest
class SessionLoadPerformanceTest {
    @Test
    fun oneThousandMessagesLoadWithinRepositoryBudget() {
        measureMessageLoad(1_000)
    }

    @Test
    fun oneThousandRoundsLoadWithinRepositoryBudget() {
        measureMessageLoad(2_000)
    }

    private fun measureMessageLoad(messageCount: Int) {
        runBlocking<Unit> {
            check(Build.VERSION.SDK_INT == 36) { "会话性能夹具只允许固定 API 36" }
            val fixture = requireNotNull(InstrumentationRegistry.getArguments().getString("nexaraPerfFixtureId"))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName = "session-perf-${UUID.randomUUID()}.db"
            val sessionId = "session-performance"
            val messageReads = AtomicInteger()
            fun openDatabase(): NexaraDatabase = Room.databaseBuilder(context, NexaraDatabase::class.java, databaseName)
                .setQueryCallback(object : RoomDatabase.QueryCallback {
                    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                        if (sqlQuery.startsWith("SELECT", ignoreCase = true) &&
                            Regex("\\bFROM\\s+`?messages`?\\b", RegexOption.IGNORE_CASE).containsMatchIn(sqlQuery)) {
                            messageReads.incrementAndGet()
                        }
                    }
                }, Executor { it.run() })
                .build()
            try {
                openDatabase().withClosedConnection { database ->
                    database.withTransaction {
                        database.sessionDao().insert(SessionEntity(
                            id = sessionId, agentId = "__performance__", title = "千轮合成会话",
                            createdAt = 0L, updatedAt = messageCount.toLong(),
                        ))
                        database.messageDao().insertAll(List(messageCount) { index ->
                            MessageEntity(
                                id = "message-$index", sessionId = sessionId,
                                role = if (index % 2 == 0) "user" else "assistant",
                                content = "消息 $index：" + "中文与 Emoji 🧪 的会话性能样本。".repeat(12),
                                createdAt = index.toLong(),
                            )
                        })
                    }
                }
                val elapsedMillis = mutableListOf<Double>()
                repeat(5) { sample ->
                    openDatabase().withClosedConnection { database ->
                        val repository = SessionRepository(database.sessionDao(), database.messageDao())
                        messageReads.set(0)
                        val begin = SystemClock.elapsedRealtimeNanos()
                        val session = requireNotNull(repository.getById(sessionId))
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - begin) / 1_000_000.0
                        elapsedMillis += elapsed
                        assertThat(session.messages).hasSize(messageCount)
                        assertThat(session.messages.first().id).isEqualTo("message-0")
                        assertThat(session.messages.last().id).isEqualTo("message-${messageCount - 1}")
                        assertThat(session.messages.last().content).contains("🧪")
                        assertThat(messageReads.get()).isEqualTo(1)
                        Log.i("SessionLoadPerf", "SESSION_LOAD_PERF fixtureId=$fixture messages=$messageCount " +
                            "sample=$sample elapsedMs=$elapsed messageQueries=${messageReads.get()} " +
                            "scope=reopened-room-connection fingerprint=${Build.FINGERPRINT}")
                    }
                }
                assertThat(elapsedMillis.max()).isLessThan(500.0)
            } finally {
                check(context.deleteDatabase(databaseName)) { "无法清理专用会话性能夹具" }
            }
        }
    }
    private suspend fun <T> NexaraDatabase.withClosedConnection(block: suspend (NexaraDatabase) -> T): T =
        try { block(this) } finally { close() }

}
