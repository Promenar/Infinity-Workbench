package com.promenar.infinity.background.schedule

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.ScheduledTaskEntity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class AndroidScheduleBackendTest {
    @Test fun `间隔首轮为联网持久化单次Job之后转为周期且降级至少十五分钟`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backend = AndroidScheduleBackend(context)
        val jobs = context.getSystemService(JobScheduler::class.java)
        val task = ScheduledTaskEntity("s", "session", "检查", "检查状态", "interval", "5", "UTC", true, false, true,
            System.currentTimeMillis() + 15 * 60_000, 0, null, null, null, null, 1, 4321)
        backend.schedule(task)
        val first = jobs.getPendingJob(4321)!!
        assertThat(first.isPeriodic).isFalse()
        assertThat(first.isPersisted).isTrue()
        assertThat(first.networkType).isEqualTo(JobInfo.NETWORK_TYPE_ANY)
        backend.schedule(task.copy(runCount = 1))
        val periodic = jobs.getPendingJob(4321)!!
        assertThat(periodic.isPeriodic).isTrue()
        assertThat(periodic.intervalMillis).isAtLeast(15 * 60_000L)
        assertThat(periodic.extras.getBoolean(AndroidScheduleBackend.EXTRA_PERIODIC)).isTrue()
        backend.schedule(task.copy(runCount = 1, catchUp = true))
        assertThat(jobs.getPendingJob(4321)!!.isPeriodic).isFalse()
        backend.cancel(task)
        assertThat(jobs.getPendingJob(4321)).isNull()
    }
    @Test fun `非精确首轮登记后台时刻而单次不降频`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backend = AndroidScheduleBackend(context)
        val jobs = context.getSystemService(JobScheduler::class.java)
        val now = System.currentTimeMillis()
        val task = ScheduledTaskEntity("s", "session", "检查", "检查状态", "interval", "1", "UTC", false, false, true,
            now + 60_000L, 0, null, null, null, null, now, 4322)
        for ((type, value) in listOf("interval" to "1", "cron" to "* * * * *")) {
            backend.schedule(task.copy(triggerType = type, triggerValue = value))
            assertThat(jobs.getPendingJob(4322)!!.minLatencyMillis).isIn(899_000L..900_000L)
        }
        backend.schedule(task.copy(triggerType = "once", triggerValue = "2026-10-23T00:01"))
        assertThat(jobs.getPendingJob(4322)!!.minLatencyMillis).isIn(59_000L..60_000L)
        backend.schedule(task.copy(runCount = 1, lastFiredAtMillis = now))
        assertThat(jobs.getPendingJob(4322)!!.intervalMillis).isEqualTo(15 * 60_000L)
        backend.cancel(task)
    }

}
