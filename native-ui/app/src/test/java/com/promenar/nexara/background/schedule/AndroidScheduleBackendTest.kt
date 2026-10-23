package com.promenar.nexara.background.schedule

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.ScheduledTaskEntity
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
}
