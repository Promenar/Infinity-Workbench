package com.promenar.nexara.background.schedule

import android.app.Application
import android.app.job.JobParameters
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.PersistableBundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.backup.BackupStartupState
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.ScheduledTaskEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.schedule.DefaultScheduledTaskController
import com.promenar.nexara.data.schedule.ScheduleBackend
import com.promenar.nexara.domain.schedule.ScheduleCreateArgs
import com.promenar.nexara.domain.schedule.ScheduleRunResult
import com.promenar.nexara.domain.schedule.ScheduleTrigger
import com.promenar.nexara.domain.trigger.SystemTurnDispatcher
import com.promenar.nexara.domain.trigger.SystemTurnOutcome
import com.promenar.nexara.domain.trigger.SystemTurnRequest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class ScheduledTaskJobServiceLifecycleTest {
    private lateinit var database: NexaraDatabase
    private lateinit var controller: DefaultScheduledTaskController
    private lateinit var service: ScheduledTaskJobService
    private lateinit var app: NexaraApplication
    private var executionJob: Job? = null
    private var onScheduled: (() -> Unit)? = null
    private val entered = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private val rearmed = CompletableDeferred<Unit>()
    private var now = 1_800_000_000_000L

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java).allowMainThreadQueries().build()
        database.sessionDao().insert(SessionEntity("session", "agent", createdAt = now, updatedAt = now))
        val backend = object : ScheduleBackend {
            override fun canUseExact() = false
            override fun cancel(task: ScheduledTaskEntity) = Unit
            override fun schedule(task: ScheduledTaskEntity) {
                // 与平台后端一致：已领取的轮次不能登记下一个系统窗口。
                if (task.claimToken == null && task.enabled && task.runCount > 0) rearmed.complete(Unit)
                onScheduled?.invoke()
            }
        }
        controller = DefaultScheduledTaskController(context, database, object : SystemTurnDispatcher {
            override suspend fun request(request: SystemTurnRequest): SystemTurnOutcome {
                entered.complete(Unit)
                release.await()
                return SystemTurnOutcome.Completed
            }
        }, backend, withdrawPending = {}) { now }
        app = mockk(relaxed = true)
        every { app.startupState } returns MutableStateFlow(BackupStartupState.Ready)
        every { app.scheduledTaskController } returns controller
        service = Robolectric.buildService(ScheduledTaskJobService::class.java).create().get()
        ReflectionHelpers.setField(service, "mApplication", app)
        // JVM 配置不打包应用资源；只替换通知文案，生命周期和数据库仍使用真实实例。
        val notificationResources = mockk<Resources>()
        every { notificationResources.getString(R.string.schedule_interrupted) } returns "定时执行被系统中断"
        val serviceContext = object : ContextWrapper(service.baseContext) {
            override fun getResources(): Resources = notificationResources
        }
        ReflectionHelpers.setField(service, "mBase", serviceContext)
    }

    @After fun cleanup() = runBlocking {
        release.complete(Unit)
        service.onDestroy()
        withTimeout(5_000) { executionJob?.join() }
        database.close()
        Dispatchers.resetMain()
    }

    @Test fun `暂停撤回Job窗口并销毁服务后已发轮次仍保存真实完成`() = runBlocking {
        val (id, params) = start(JobParameters.STOP_REASON_CANCELLED_BY_APP)
        controller.pause(id)
        assertThat(service.onStopJob(params)).isFalse()
        service.onDestroy()
        release.complete(Unit)
        val finished = withTimeout(5_000) { controller.observe("session").first { it.single().lastResult != null }.single() }
        assertThat(finished.lastResult).isEqualTo(ScheduleRunResult.COMPLETED)
        assertThat(finished.enabled).isFalse()
        assertThat(finished.nextFireAtMillis).isNull()
    }

    @Test fun `暂停后快速恢复在旧窗口结束后重新登记启用排期`() = runBlocking {
        val (id, params) = start(JobParameters.STOP_REASON_CANCELLED_BY_APP)
        controller.pause(id)
        service.onStopJob(params)
        controller.resume(id)
        release.complete(Unit)
        withTimeout(5_000) { rearmed.await() }
        val finished = controller.find(id)!!
        assertThat(finished.lastResult).isEqualTo(ScheduleRunResult.COMPLETED)
        assertThat(finished.enabled).isTrue()
        assertThat(finished.nextFireAtMillis).isNotNull()
    }

    @Test fun `系统超时先关闭领取令牌再取消来源并保存停止原因`() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val notified = CompletableDeferred<Unit>()
        val (id, params) = start(JobParameters.STOP_REASON_TIMEOUT)
        coEvery { app.stopSystemTurnSource(id) } coAnswers {
            val stored = database.scheduledTaskDao().get(id)!!
            assertThat(stored.claimToken).isNull()
            assertThat(stored.lastResult).isEqualTo(ScheduleRunResult.FAILED.name)
            assertThat(stored.stopReason).isEqualTo(JobParameters.STOP_REASON_TIMEOUT)
            stopped.complete(Unit)
        }
        coEvery { app.systemTriggerNotifications.failed(any(), any(), any()) } coAnswers { notified.complete(Unit) }
        service.onStopJob(params)
        withTimeout(5_000) { stopped.await(); notified.await() }
    }

    @Test fun `后台Job提前送达不能领取一分钟前台计划且完成窗口后重新排期`() = runBlocking {
        val task = controller.create("session", ScheduleCreateArgs("检查", "检查状态", ScheduleTrigger.Interval(1))).task
        now = task.nextFireAtMillis!!
        val row = database.scheduledTaskDao().get(task.id)!!
        val rescheduled = CompletableDeferred<Unit>()
        onScheduled = { rescheduled.complete(Unit) }
        release.complete(Unit)
        val params = mockk<JobParameters>(relaxed = true)
        every { params.extras } returns PersistableBundle().apply {
            putString(AndroidScheduleBackend.EXTRA_TASK_ID, task.id)
        }
        every { params.jobId } returns row.jobId
        assertThat(service.onStartJob(params)).isTrue()
        val running = ReflectionHelpers.getField<Map<Int, Any>>(service, "running")
        executionJob = running[row.jobId]?.let { ReflectionHelpers.getField<Job>(it, "job") }
        withTimeout(5_000) { rescheduled.await(); executionJob?.join() }
        assertThat(controller.find(task.id)!!.runCount).isEqualTo(0)
        assertThat(entered.isCompleted).isFalse()
        assertThat(controller.find(task.id)!!.nextFireAtMillis).isEqualTo(task.nextFireAtMillis)
    }

    private suspend fun start(stopReason: Int): Pair<String, JobParameters> {
        val task = controller.create("session", ScheduleCreateArgs("检查", "检查状态", ScheduleTrigger.Interval(15))).task
        now = task.nextFireAtMillis!!
        val row = database.scheduledTaskDao().get(task.id)!!
        val extras = PersistableBundle().apply {
            putString(AndroidScheduleBackend.EXTRA_TASK_ID, task.id)
            putBoolean(AndroidScheduleBackend.EXTRA_PERIODIC, true)
        }
        val params = mockk<JobParameters>(relaxed = true)
        every { params.extras } returns extras
        every { params.jobId } returns row.jobId
        every { params.stopReason } returns stopReason
        assertThat(service.onStartJob(params)).isTrue()
        // 仅供清理等待：数据库必须晚于实际执行协程的全部收尾关闭。
        val running = ReflectionHelpers.getField<Map<Int, Any>>(service, "running")
        executionJob = ReflectionHelpers.getField<Job>(running.getValue(row.jobId), "job")
        withTimeout(5_000) { entered.await() }
        return task.id to params
    }
}
