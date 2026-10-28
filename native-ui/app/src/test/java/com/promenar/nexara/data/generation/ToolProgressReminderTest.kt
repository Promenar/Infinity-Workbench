package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolProgressReminderTest {
    private var now = 0L
    private val reminder = ToolProgressReminder(clock = { now })

    @Test fun `三轮不提醒四轮提醒且未输出正文时持续提醒`() {
        repeat(3) { reminder.onToolsCompleted() }
        assertThat(reminder.shouldRemind()).isFalse()
        reminder.onToolsCompleted()
        assertThat(reminder.shouldRemind()).isTrue()
        assertThat(reminder.shouldRemind()).isTrue()
        reminder.onToolsCompleted()
        assertThat(reminder.shouldRemind()).isTrue()
    }

    @Test fun `没有工具轮时不因计时提醒`() {
        now = 600_000L
        assertThat(reminder.shouldRemind()).isFalse()
    }

    @Test fun `恰好六十秒不提醒超过六十秒提醒`() {
        reminder.onToolsCompleted()
        now = 60_000L
        assertThat(reminder.shouldRemind()).isFalse()
        now = 60_001L
        assertThat(reminder.shouldRemind()).isTrue()
    }

    @Test fun `四轮以内正文清空轮数并重置计时`() {
        repeat(3) { reminder.onToolsCompleted() }
        now = 50_000L
        reminder.onUserFacingText("正在核对资料")
        repeat(3) { reminder.onToolsCompleted() }
        now = 110_000L
        assertThat(reminder.shouldRemind()).isFalse()
        now++
        assertThat(reminder.shouldRemind()).isTrue()
    }

    @Test fun `触发后正文清零空白内容不能清零`() {
        repeat(4) { reminder.onToolsCompleted() }
        reminder.onUserFacingText(" \n\t")
        assertThat(reminder.shouldRemind()).isTrue()
        now = 90_000L
        reminder.onUserFacingText("已经找到资料")
        assertThat(reminder.shouldRemind()).isFalse()
        now = 150_000L
        assertThat(reminder.shouldRemind()).isFalse()
    }

    @Test fun `收尾轮始终不提醒且阈值可注入`() {
        val custom = ToolProgressReminder(clock = { now }, toolRoundThreshold = 2, silenceThresholdMillis = 10)
        custom.onToolsCompleted()
        assertThat(custom.shouldRemind()).isFalse()
        custom.onToolsCompleted()
        now = 11L
        assertThat(custom.shouldRemind()).isTrue()
        assertThat(custom.shouldRemind(finalAnswerMode = true)).isFalse()
    }

    @Test fun `生成开始重置起点与已有轮数`() {
        repeat(4) { reminder.onToolsCompleted() }
        now = 100_000L
        reminder.start()
        assertThat(reminder.shouldRemind()).isFalse()
        reminder.onToolsCompleted()
        now = 160_000L
        assertThat(reminder.shouldRemind()).isFalse()
        now++
        assertThat(reminder.shouldRemind()).isTrue()
    }
}
