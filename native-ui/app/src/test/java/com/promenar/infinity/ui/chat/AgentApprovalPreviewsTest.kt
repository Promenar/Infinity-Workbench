package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.jobs.BackgroundJobExitAction
import com.promenar.infinity.domain.jobs.SftpTransferDirection
import com.promenar.infinity.domain.schedule.ScheduleTrigger
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class AgentApprovalPreviewsTest {
    private val zone = "Asia/Shanghai"
    private val now = LocalDateTime.of(2026, 10, 23, 10, 0).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `间隔任务预览给出接下来三次触发`() {
        val preview = scheduleApprovalPreview(
            json("""{"name":"检查 CI","prompt":"看构建","trigger":{"type":"interval","every_minutes":30}}"""),
            now, zone, exactAlarmAvailable = true, canScheduleExact = true,
        )!!
        assertThat(preview.trigger).isEqualTo(ScheduleTrigger.Interval(30))
        assertThat(preview.upcoming).containsExactly(now + 30 * 60_000L, now + 60 * 60_000L, now + 90 * 60_000L).inOrder()
        assertThat(preview.invalid).isFalse()
        assertThat(preview.exactEffective).isFalse()
    }

    @Test
    fun `请求精确但未授权时保留前台节奏预览并按次数上限截断`() {
        val preview = scheduleApprovalPreview(
            json("""{"name":"轮询","prompt":"看部署","trigger":{"type":"interval","every_minutes":5},"exact":true,"max_runs":2}"""),
            now, zone, exactAlarmAvailable = true, canScheduleExact = false,
        )!!
        assertThat(preview.exactRequested).isTrue()
        assertThat(preview.exactEffective).isFalse()
        assertThat(preview.upcoming).containsExactly(now + 5 * 60_000L, now + 10 * 60_000L).inOrder()
    }

    @Test
    fun `一分钟间隔与倒计时使用传入时钟生成审批预览`() {
        val interval = scheduleApprovalPreview(
            json("""{"name":"轮询","prompt":"状态","trigger":{"type":"interval","every_minutes":1}}"""),
            now, zone, false, false,
        )!!
        assertThat(interval.invalid).isFalse()
        assertThat(interval.countdownMinutes).isNull()
        assertThat(interval.upcoming).containsExactly(now + 60_000L, now + 120_000L, now + 180_000L).inOrder()
        val once = scheduleApprovalPreview(
            json("""{"name":"提醒","prompt":"状态","trigger":{"type":"once","in_minutes":2}}"""),
            now + 30_987L, zone, false, false,
        )!!
        assertThat(once.trigger).isEqualTo(ScheduleTrigger.Once("2026-10-23T10:02:30", zone))
        assertThat(once.invalid).isFalse()
        assertThat(once.upcoming).containsExactly(now + 150_000L)
        assertThat(once.countdownMinutes).isEqualTo(2)
    }

    @Test
    fun `校验失败标记为无效，无法解析时不提供结构化预览`() {
        val past = scheduleApprovalPreview(
            json("""{"name":"提醒","prompt":"续费","trigger":{"type":"once","at":"2026-10-22T09:00"}}"""),
            now, zone, exactAlarmAvailable = false, canScheduleExact = false,
        )!!
        assertThat(past.invalid).isTrue()
        assertThat(past.upcoming).isEmpty()
        assertThat(past.countdownMinutes).isNull()
        assertThat(scheduleApprovalPreview(json("""{"name":"x"}"""), now, zone, false, false)).isNull()
    }

    @Test
    fun `仅 background 为真时给出后台运行预览并规范默认值`() {
        assertThat(backgroundRunPreview(json("""{"command":"ls"}"""), keepAliveAvailable = true)).isNull()
        assertThat(backgroundRunPreview(json("""{"command":"make","background":true}"""), keepAliveAvailable = false))
            .isEqualTo(BackgroundRunPreview(120, BackgroundJobExitAction.NOTIFY, keepAliveAvailable = false))
        assertThat(backgroundRunPreview(json("""{"background":true,"max_runtime_minutes":5000,"on_exit":"wake"}"""), true))
            .isEqualTo(BackgroundRunPreview(1_440, BackgroundJobExitAction.WAKE, keepAliveAvailable = true))
    }

    @Test
    fun `传输审批预览按工具定方向，缺路径时不生成，未知范围按工作区`() {
        assertThat(sftpTransferApprovalPreview("sftp_upload", json("""{"host":"nas","local_path":"/out/a.tar","remote_path":"/srv/a.tar","overwrite":true}""")))
            .isEqualTo(SftpTransferApprovalPreview(SftpTransferDirection.UPLOAD, "workspace", "/out/a.tar", "/srv/a.tar", overwrite = true))
        assertThat(sftpTransferApprovalPreview("sftp_download", json("""{"host":"nas","local_path":"/root/a","remote_path":"/srv/a","local_scope":"linux"}""")))
            .isEqualTo(SftpTransferApprovalPreview(SftpTransferDirection.DOWNLOAD, "linux", "/root/a", "/srv/a", overwrite = false))
        assertThat(sftpTransferApprovalPreview("sftp_upload", json("""{"local_path":"/a","remote_path":"/b","local_scope":"system"}"""))?.localScope)
            .isEqualTo("workspace")
        // 字符串 "true" 不是布尔值，不当作允许覆盖。
        assertThat(sftpTransferApprovalPreview("sftp_upload", json("""{"local_path":"/a","remote_path":"/b","overwrite":"true"}"""))?.overwrite)
            .isFalse()
        assertThat(sftpTransferApprovalPreview("sftp_upload", json("""{"local_path":"/a"}"""))).isNull()
        assertThat(sftpTransferApprovalPreview("sftp_write", json("""{"local_path":"/a","remote_path":"/b"}"""))).isNull()
    }

    @Test
    fun `传输审批类别写成文件传输而远程命令不变`() {
        val target = """["remote_path","nas","/srv/a.tar",{"sshHostId":"h","sshIdentity":"d","oneShot":true,"direction":"upload","host":"nas"}]"""
        assertThat(approvalKindLabel("sftp_upload", target)).isEqualTo(com.promenar.infinity.R.string.chat_approval_kind_file_transfer)
        assertThat(approvalKindLabel("sftp_download", null)).isEqualTo(com.promenar.infinity.R.string.chat_approval_kind_file_transfer)
        assertThat(approvalKindLabel("ssh_exec", null)).isEqualTo(com.promenar.infinity.R.string.chat_approval_kind_ssh_command)
        assertThat(approvalKindLabel("read_file", null)).isEqualTo(com.promenar.infinity.R.string.chat_approval_kind_tool)
        // 显示特判不改变后端归类：传输仍按 SSH 后端参与路由与风险判断。
        assertThat(com.promenar.infinity.domain.tool.CommandToolClassification.backendFor("sftp_upload"))
            .isEqualTo(com.promenar.infinity.domain.tool.CommandToolBackend.SSH)
    }

    @Test
    fun `传输审批说明按方向写明主机且取不到主机时不显示`() {
        val upload = """["remote_path","nas","/srv/a.tar",{"oneShot":true,"host":"nas"}]"""
        assertThat(approvalKindDetail("sftp_upload", upload))
            .isEqualTo(ApprovalKindDetailText(com.promenar.infinity.R.string.chat_approval_kind_upload_detail, listOf("nas")))
        assertThat(approvalKindDetail("sftp_download", """["remote_path","fnos","/var/dump.sql",{}]"""))
            .isEqualTo(ApprovalKindDetailText(com.promenar.infinity.R.string.chat_approval_kind_download_detail, listOf("fnos")))
        assertThat(approvalKindDetail("sftp_upload", null)).isNull()
        assertThat(approvalKindDetail("sftp_upload", "不是 JSON")).isNull()
        assertThat(approvalKindDetail("ssh_exec", null))
            .isEqualTo(ApprovalKindDetailText(com.promenar.infinity.R.string.chat_approval_kind_ssh_command_detail))
    }

    @Test
    fun `传输主机优先取元数据并剥离控制字符`() {
        assertThat(sftpTransferHost("""["remote_path","alias",{"host":"nas"}]""")).isEqualTo("nas")
        assertThat(sftpTransferHost("""["ssh_host","nas"]""")).isEqualTo("nas")
        assertThat(sftpTransferHost("""["process","nas","ls"]""")).isNull()
        assertThat(sftpTransferHost("""["remote_path","",{}]""")).isNull()
        assertThat(sftpTransferHost("""["remote_path","na\u202es",{}]""")).isEqualTo("nas")
        assertThat(sftpTransferDirection("sftp_write")).isNull()
    }
}
