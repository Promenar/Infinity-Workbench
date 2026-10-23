package com.promenar.nexara.data.schedule

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.*
import com.promenar.nexara.domain.schedule.*
import com.promenar.nexara.domain.trigger.*
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class DefaultScheduledTaskControllerTest {
    private lateinit var db: NexaraDatabase
    private lateinit var context: Context
    private lateinit var controller: DefaultScheduledTaskController
    private val backend = FakeBackend()
    private var now = Instant.parse("2026-10-23T00:00:00Z").toEpochMilli()
    private val requests = mutableListOf<SystemTurnRequest>()
    private var outcome: SystemTurnOutcome = SystemTurnOutcome.Completed
    private var entered: CompletableDeferred<Unit>? = null
    private var release: CompletableDeferred<Unit>? = null

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java).allowMainThreadQueries().build()
        db.sessionDao().insert(SessionEntity("s", "a", createdAt = now, updatedAt = now))
        controller = DefaultScheduledTaskController(context, db, object : SystemTurnDispatcher {
            override suspend fun request(request: SystemTurnRequest): SystemTurnOutcome {
                requests += request
                entered?.complete(Unit)
                release?.await()
                return outcome
            }
        }, backend) { now }
    }
    @After fun close() { db.close() }
    private suspend fun create(trigger: ScheduleTrigger = ScheduleTrigger.Interval(15), exact: Boolean = false, max: Int? = null, expiry: Long? = null): ScheduledTask =
        controller.create("s", ScheduleCreateArgs("检查", "检查状态", trigger, exact, max, expiry)).task

    @Test fun `同一到期回调重复到达只执行一次并持久记录结果`() = runBlocking {
        val task = create()
        now = task.nextFireAtMillis!!
        controller.fire(task.id); controller.fire(task.id)
        assertThat(requests).hasSize(1)
        assertThat(controller.find(task.id)!!.runCount).isEqualTo(1)
        assertThat(controller.find(task.id)!!.lastResult).isEqualTo(ScheduleRunResult.COMPLETED)
    }
    @Test fun `并发回调在生成挂起时也不能重复领取`() = runBlocking {
        val task = create()
        now = task.nextFireAtMillis!!
        entered = CompletableDeferred(); release = CompletableDeferred()
        val first = launch { controller.fire(task.id) }
        entered!!.await()
        controller.fire(task.id)
        assertThat(requests).hasSize(1)
        release!!.complete(Unit); first.join()
    }
    @Test fun `错过多个间隔仅补发一次并从当前时间计算下一次`() = runBlocking {
        val task = create()
        now += 3 * 60 * 60_000L
        controller.reconcile(); controller.fire(task.id); controller.fire(task.id)
        assertThat(requests).hasSize(1)
        assertThat(requests.single().catchUp).isTrue()
        assertThat(controller.find(task.id)!!.nextFireAtMillis).isEqualTo(now + 15 * 60_000)
    }
    @Test fun `系统闹钟对齐造成的短暂延迟不标记补发`() = runBlocking {
        val aligned = create(ScheduleTrigger.Once("2026-10-23T00:30", "UTC"), exact = true)
        now = aligned.nextFireAtMillis!! + 89_000L
        controller.fire(aligned.id)
        assertThat(requests.single().catchUp).isFalse()
        val late = create(ScheduleTrigger.Once("2026-10-23T01:00", "UTC"), exact = true)
        now = late.nextFireAtMillis!! + 10 * 60_000L
        controller.fire(late.id)
        assertThat(requests.last().catchUp).isTrue()
    }
    @Test fun `单次任务错过后补发并自动停用`() = runBlocking {
        val task = create(ScheduleTrigger.Once("2026-10-23T00:30", "UTC"))
        now += 60 * 60_000L
        controller.reconcile(); controller.fire(task.id)
        assertThat(requests.single().catchUp).isTrue()
        assertThat(controller.find(task.id)!!.enabled).isFalse()
    }
    @Test fun `次数上限和过期使任务自动停用`() = runBlocking {
        val task = create(max = 1)
        now = task.nextFireAtMillis!!
        controller.fire(task.id)
        assertThat(controller.find(task.id)!!.enabled).isFalse()
        val expiryTask = create(expiry = now + 20 * 60_000)
        now += 25 * 60_000
        controller.fire(expiryTask.id)
        assertThat(controller.find(expiryTask.id)!!.enabled).isFalse()
        assertThat(requests).hasSize(1)
    }
    @Test fun `暂停执行中的等待不会在完成回调被重新启用`() = runBlocking {
        val task = create()
        now = task.nextFireAtMillis!!
        entered = CompletableDeferred(); release = CompletableDeferred()
        val fire = launch { controller.fire(task.id) }
        entered!!.await(); controller.pause(task.id); fire.join()
        assertThat(controller.find(task.id)!!.enabled).isFalse()
        assertThat(controller.find(task.id)!!.nextFireAtMillis).isNull()
        assertThat(controller.find(task.id)!!.lastResult).isEqualTo(ScheduleRunResult.FAILED)
    }
    @Test fun `删除与完成竞争不能复活记录`() = runBlocking {
        val task = create()
        now = task.nextFireAtMillis!!
        entered = CompletableDeferred(); release = CompletableDeferred()
        val fire = launch { controller.fire(task.id) }
        entered!!.await(); controller.delete(task.id); fire.join()
        assertThat(controller.find(task.id)).isNull()
    }
    @Test fun `重启遗留占位记失败且不重复发起`() = runBlocking {
        val task = create()
        now = task.nextFireAtMillis!!
        assertThat(db.scheduledTaskDao().claim(task.id, "interrupted", now)).isEqualTo(1)
        controller.initialize(); controller.fire(task.id)
        assertThat(requests).isEmpty()
        assertThat(controller.find(task.id)!!.runCount).isEqualTo(1)
        assertThat(controller.find(task.id)!!.lastResult).isEqualTo(ScheduleRunResult.FAILED)
    }
    @Test fun `权限撤销后五分钟任务下次实际排期不早于十五分钟`() = runBlocking {
        backend.exact = true
        val task = create(ScheduleTrigger.Interval(5), exact = true)
        backend.exact = false
        controller.reconcile()
        assertThat(controller.find(task.id)!!.exactEffective).isFalse()
        assertThat(controller.find(task.id)!!.nextFireAtMillis).isEqualTo(now + 15 * 60_000)
    }
    @Test fun `每会话五项与全局二十项上限并检查会话归属`() = runBlocking {
        repeat(5) { create() }
        assertFails { create() }
        val own = controller.list("s").first()
        assertFails { controller.pauseForSession("other", own.id) }
        for (index in 1..3) {
            val session = "s$index"
            db.sessionDao().insert(SessionEntity(session, "a", createdAt = now, updatedAt = now))
            repeat(5) { controller.create(session, ScheduleCreateArgs("检查", "检查", ScheduleTrigger.Interval(15))) }
        }
        db.sessionDao().insert(SessionEntity("overflow", "a", createdAt = now, updatedAt = now))
        assertFails { controller.create("overflow", ScheduleCreateArgs("检查", "检查", ScheduleTrigger.Interval(15))) }
    }
    @Test fun `删除会话取消排期且外键覆盖直接删除路径`() = runBlocking {
        val task = create()
        controller.deleteForSession("s")
        assertThat(backend.cancelled).contains(task.id)
        assertThat(controller.list("s")).isEmpty()
        val direct = create()
        db.sessionDao().deleteById("s")
        assertThat(controller.find(direct.id)).isNull()
    }
    @Test fun `需要用户与忙碌超时分别记录且系统stopReason持久化`() = runBlocking {
        outcome = SystemTurnOutcome.NeedsUser
        val task = create()
        now = task.nextFireAtMillis!!; controller.fire(task.id)
        assertThat(controller.find(task.id)!!.lastResult).isEqualTo(ScheduleRunResult.NEEDS_USER)
        outcome = SystemTurnOutcome.Skipped(SystemTurnSkipReason.BUSY_TIMEOUT)
        now = controller.find(task.id)!!.nextFireAtMillis!!; controller.fire(task.id)
        assertThat(controller.find(task.id)!!.lastResult).isEqualTo(ScheduleRunResult.SKIPPED_BUSY)
        now = controller.find(task.id)!!.nextFireAtMillis!!
        entered = CompletableDeferred(); release = CompletableDeferred()
        val fire = launch { controller.fire(task.id) }
        entered!!.await(); controller.stopped(task.id, 3); fire.join()
        assertThat(db.scheduledTaskDao().get(task.id)!!.stopReason).isEqualTo(3)
        assertThat(controller.find(task.id)!!.lastResult).isEqualTo(ScheduleRunResult.FAILED)
    }
    @Test fun `已完成的周期Job保留系统周期而不是重置排期`() = runBlocking {
        val task = create()
        now = task.nextFireAtMillis!!
        controller.fire(task.id, reschedule = false)
        val before = backend.scheduled.size
        controller.reconcile(preservePeriodicTaskId = task.id)
        assertThat(backend.scheduled).hasSize(before)
        assertThat(controller.find(task.id)!!.nextFireAtMillis).isEqualTo(now + 15 * 60_000)
    }
    @Test fun `旧执行的停止回调不能覆盖下一轮占位`() = runBlocking {
        val task = create()
        now = task.nextFireAtMillis!!
        controller.fire(task.id, executionToken = "old")
        now = controller.find(task.id)!!.nextFireAtMillis!!
        entered = CompletableDeferred(); release = CompletableDeferred()
        val fire = launch { controller.fire(task.id, executionToken = "new") }
        entered!!.await()
        assertThat(controller.stopped(task.id, 3, "old")).isFalse()
        assertThat(db.scheduledTaskDao().get(task.id)!!.claimToken).isEqualTo("new")
        release!!.complete(Unit); fire.join()
        assertThat(controller.find(task.id)!!.lastResult).isEqualTo(ScheduleRunResult.COMPLETED)
    }
    @Test fun `删除事务前取消排期保留记录且失败时可恢复成功时不遗留排期`() = runBlocking {
        val task = create()
        now = task.nextFireAtMillis!!
        controller.cancelForSession("s")
        val cancelledAt = backend.cancelled.size
        val scheduledAt = backend.scheduled.size
        controller.reconcile()
        controller.fire(task.id)
        assertThat(requests).isEmpty()
        assertThat(controller.find(task.id)).isNotNull()
        assertThat(backend.cancelled).hasSize(cancelledAt)
        assertThat(backend.scheduled).hasSize(scheduledAt)
        // 删除失败，finally 恢复仍在的记录。
        controller.resumeSchedulingForSession("s")
        assertThat(backend.scheduled.size).isGreaterThan(scheduledAt)
        controller.fire(task.id)
        assertThat(requests).hasSize(1)
        // 删除成功，预先保存的排期已取消，级联后无需再查找旧行。
        controller.cancelForSession("s")
        val afterCancel = backend.scheduled.size
        db.sessionDao().deleteById("s")
        controller.resumeSchedulingForSession("s")
        assertThat(controller.find(task.id)).isNull()
        assertThat(backend.scheduled).hasSize(afterCancel)
    }
    @Test fun `创建排期失败不残留启用记录且恢复失败保持暂停`() = runBlocking {
        backend.failSchedule = true
        assertFails { create() }
        assertThat(controller.list("s")).isEmpty()
        backend.failSchedule = false
        val task = create()
        controller.pause(task.id)
        backend.failSchedule = true
        assertFails { controller.resume(task.id) }
        assertThat(controller.find(task.id)!!.enabled).isFalse()
        assertThat(controller.find(task.id)!!.nextFireAtMillis).isNull()
    }
    @Test fun `系统重排失败停用该任务并保留可恢复记录`() = runBlocking {
        val task = create()
        backend.failSchedule = true
        controller.reconcile()
        assertThat(controller.find(task.id)!!.enabled).isFalse()
        assertThat(controller.find(task.id)!!.lastResult).isEqualTo(ScheduleRunResult.FAILED)
    }
    @Test fun `生产撤队回调保留已发请求的真实完成结果且任务保持暂停`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val withdrawn = mutableListOf<String>()
        val actual = DefaultScheduledTaskController(context, db, object : SystemTurnDispatcher {
            override suspend fun request(request: SystemTurnRequest): SystemTurnOutcome {
                started.complete(Unit)
                completed.await()
                return SystemTurnOutcome.Completed
            }
        }, backend, withdrawPending = { withdrawn += it }) { now }
        val task = actual.create("s", ScheduleCreateArgs("检查", "检查状态", ScheduleTrigger.Interval(15))).task
        now = task.nextFireAtMillis!!
        val execution = launch { actual.fire(task.id) }
        started.await()
        actual.pause(task.id)
        assertThat(withdrawn).containsExactly(task.id)
        assertThat(execution.isActive).isTrue()
        completed.complete(Unit)
        execution.join()
        assertThat(actual.find(task.id)!!.enabled).isFalse()
        assertThat(actual.find(task.id)!!.lastResult).isEqualTo(ScheduleRunResult.COMPLETED)
        assertThat(actual.find(task.id)!!.nextFireAtMillis).isNull()
    }
    @Test fun `固定间隔不把五分钟生成耗时叠加到下一次触发`() = runBlocking {
        val task = create()
        now = task.nextFireAtMillis!!
        val startedAt = now
        entered = CompletableDeferred(); release = CompletableDeferred()
        val execution = launch { controller.fire(task.id, reschedule = false) }
        entered!!.await()
        now += 5 * 60_000L
        release!!.complete(Unit); execution.join()
        assertThat(controller.find(task.id)!!.nextFireAtMillis).isEqualTo(startedAt + 15 * 60_000L)
        now = startedAt + 15 * 60_000L
        controller.fire(task.id)
        assertThat(requests).hasSize(2)
    }

    @Test fun `生成跨过多个间隔只合并一次补发后回到未来排期`() = runBlocking {
        val task = create()
        now = task.nextFireAtMillis!!
        entered = CompletableDeferred(); release = CompletableDeferred()
        val execution = launch { controller.fire(task.id) }
        entered!!.await()
        now += 35 * 60_000L
        release!!.complete(Unit); execution.join()
        assertThat(controller.find(task.id)!!.nextFireAtMillis).isEqualTo(now)
        controller.reconcile(preservePeriodicTaskId = task.id)
        assertThat(db.scheduledTaskDao().get(task.id)!!.catchUp).isTrue()
        controller.fire(task.id)
        controller.fire(task.id)
        assertThat(requests).hasSize(2)
        assertThat(requests.last().catchUp).isTrue()
        assertThat(controller.find(task.id)!!.nextFireAtMillis).isEqualTo(now + 15 * 60_000L)
    }

    @Test fun `cron不因生成耗时额外跳过尚未到来的合法触发`() = runBlocking {
        val task = create(ScheduleTrigger.Cron("*/15 * * * *", "UTC"))
        now = task.nextFireAtMillis!!
        val startedAt = now
        entered = CompletableDeferred(); release = CompletableDeferred()
        val execution = launch { controller.fire(task.id) }
        entered!!.await(); now += 5 * 60_000L
        release!!.complete(Unit); execution.join()
        assertThat(controller.find(task.id)!!.nextFireAtMillis).isEqualTo(startedAt + 15 * 60_000L)
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        try { block(); throw AssertionError("预期参数校验拒绝") } catch (_: IllegalArgumentException) { }
    }
    private class FakeBackend : ScheduleBackend {
        var exact = false
        var failSchedule = false
        val scheduled = mutableListOf<ScheduledTaskEntity>()
        val cancelled = mutableListOf<String>()
        override fun canUseExact() = exact
        override fun schedule(task: ScheduledTaskEntity) {
            if (failSchedule) throw IllegalStateException("系统拒绝排期")
            scheduled += task
        }
        override fun cancel(task: ScheduledTaskEntity) { cancelled += task.id }
    }
}
