package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.tool.ExecToolArguments
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class CommandStepLabelsTest {
    @Test fun `命令取智能体写的短标题`() {
        val step = commandStepLabel("shell_exec", """{"command":"mkdir -p a && git clone x","title":"克隆 Personal-Archive"}""", emptyMap())
        assertThat(step).isEqualTo(CommandStepLabel(CommandStepKind.RUN, "克隆 Personal-Archive"))
    }

    @Test fun `没写短标题时标题为空由界面显示原命令`() {
        assertThat(commandStepLabel("python_exec", """{"code":"print(1)"}""", emptyMap())?.label).isNull()
        assertThat(commandStepLabel("ssh_exec", """{"host":"fnos","command":"ls","title":"   "}""", emptyMap())?.label).isNull()
    }

    @Test fun `等待任务显示任务名与最长等待时间`() {
        val labels = mapOf("job-86c294b8-ab90" to "克隆 Personal-Archive")
        val wait = commandStepLabel("job_wait", """{"job_id":"job-86c294b8-ab90","timeout_seconds":600}""", labels)
        assertThat(wait).isEqualTo(CommandStepLabel(CommandStepKind.WAIT, "克隆 Personal-Archive", "job-86c294b8-ab90", 600))
        assertThat(commandStepLabel("job_wait", """{"job_id":"job-x"}""", labels)?.waitLimitSeconds).isEqualTo(60)
    }

    @Test fun `找不到任务记录时只给任务号`() {
        val stop = commandStepLabel("job_stop", """{"job_id":"job-86c294b8-ab90-4182"}""", emptyMap())
        assertThat(stop).isEqualTo(CommandStepLabel(CommandStepKind.STOP, null, "job-86c294b8-ab90-4182"))
        assertThat(shortJobId("job-86c294b8-ab90-4182")).isEqualTo("86c294b8")
    }

    @Test fun `其它工具不处理`() {
        assertThat(commandStepLabel("read_file", """{"path":"a"}""", emptyMap())).isNull()
        assertThat(commandStepLabel("job_list", "{}", emptyMap())).isNull()
    }

    @Test fun `短标题折叠空白并截断`() {
        val long = "克隆\n  仓库 " + "很".repeat(60)
        val title = ExecToolArguments.title(buildJsonObject { put("title", JsonPrimitive(long)) })!!
        assertThat(title).startsWith("克隆 仓库 很")
        assertThat(title.length).isEqualTo(40)
        assertThat(ExecToolArguments.title(buildJsonObject { put("title", JsonPrimitive(3)) })).isNull()
    }
}
