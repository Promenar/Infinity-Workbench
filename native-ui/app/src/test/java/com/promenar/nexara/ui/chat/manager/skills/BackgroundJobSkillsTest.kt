package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.exec.*
import com.promenar.nexara.data.jobs.*
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test

class BackgroundJobSkillsTest {
    @Test fun `工具风险和会话隔离与列表不含日志`()=runBlocking<Unit> {
        val root=Files.createTempDirectory("jobs-skill").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val manager=BackgroundJobManager(scope,BackgroundJobStore(File(root,"store")),File(root,"runs"),
            BuiltinCommandRunner(listOf("/usr/bin/perl","-e","setpgrp(0,0); exec @ARGV"),"/bin/sh",ProcessGroupSignaller{pid,sig->ProcessBuilder("/bin/kill","-$sig","-$pid").start().waitFor()},{File(root,"scratch")},killGraceMillis=100),
            prepareWorkspace={BackgroundJobLocation(root,root,false,false)},prepareCommand={_,source,_,location,minutes->PreparedBackgroundCommand(BuiltinCommandRequest(listOf("/bin/sh","-c",source),location.commandDirectory,mapOf("PATH" to "/usr/bin:/bin"),timeoutSeconds=minutes*60))})
        try {
            val tools=backgroundJobTools(manager).associateBy{it.id}
            assertThat(tools.values.map{it.risk}).containsExactly(ToolRisk.SESSION_STATE,ToolRisk.SESSION_STATE,ToolRisk.SESSION_STATE,ToolRisk.SESSION_STATE)
            val own=context("own");val other=context("other")
            val result=runBackgroundExec("shell_exec","""{"type":"object","properties":{"command":{"type":"string"},$BACKGROUND_EXEC_SCHEMA},"required":["command"],"additionalProperties":false}""",buildJsonObject{put("command","echo done");put("background",true)},own,manager,com.promenar.nexara.domain.jobs.BackgroundJobRunner.SHELL,"command")
            assertThat(result.status).isEqualTo("success")
            val id=Json.parseToJsonElement(result.content).jsonObject.getValue("job_id").jsonPrimitive.content
            val rejected=tools.getValue("job_status").execute(buildJsonObject{put("job_id",id)},other)
            assertThat(rejected.content).contains("JOB_NOT_FOUND")
            val listed=tools.getValue("job_list").execute(buildJsonObject{},own)
            assertThat(listed.content).doesNotContain("log_tail")
            val badWait=tools.getValue("job_wait").execute(buildJsonObject{put("job_id",id);put("timeout_seconds",601)},own)
            assertThat(badWait.status).isEqualTo("error")
            manager.waitFor("own",id,5)
        } finally{scope.cancel();root.deleteRecursively()}
    }
    private fun context(id:String)=object:SkillExecutionContext {override val sessionId=id;override val agentId="a";override val workspacePath:String?=null;override val workspaceRootUuid="root"}
}
