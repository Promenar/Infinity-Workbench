package com.promenar.nexara.domain.tool

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.termux.TermuxCommandResult
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class CommandToolResultTest {
    private val sharedKeys = listOf(
        "error_code", "guidance", "exit_code", "timed_out", "stderr", "stdout",
        "stdout_original_length", "stderr_original_length", "length_unit",
        "stdout_truncated", "stderr_truncated", "truncated", "truncation",
    )

    @Test
    fun `shared fields come first in a fixed order and backend fields follow`() {
        val json = TermuxCommandResult(stdout = "ok", exitCode = 0, err = -1, errmsg = "x").toJson()

        assertThat(json.keys.take(sharedKeys.size)).containsExactlyElementsIn(sharedKeys).inOrder()
        assertThat(json.keys.drop(sharedKeys.size)).containsExactly("err", "errmsg", "errmsg_original_length", "errmsg_truncation_possible").inOrder()
    }

    @Test
    fun `success requires no error code no timeout and exit code zero`() {
        assertThat(CommandToolResult(exitCode = 0).toolStatus).isEqualTo("success")
        assertThat(CommandToolResult(exitCode = 1).toolStatus).isEqualTo("error")
        assertThat(CommandToolResult(exitCode = null).toolStatus).isEqualTo("error")
        assertThat(CommandToolResult(exitCode = 0, timedOut = true).toolStatus).isEqualTo("error")
        assertThat(CommandToolResult(exitCode = 0, errorCode = "X").toolStatus).isEqualTo("error")
    }

    @Test
    fun `truncation reports the removed part and unknown original length as null`() {
        val head = CommandToolResult(stdout = "tail", stdoutOriginalLength = 10, exitCode = 0).toJson()
        assertThat(head["stdout_truncated"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(head["truncation"]!!.jsonPrimitive.content).isEqualTo("head_removed")

        val unknown = CommandToolResult(
            stdout = "head", stdoutOriginalLength = null, stdoutTruncated = true,
            removedWhenTruncated = CommandTruncation.TAIL_REMOVED, exitCode = 0,
        ).toJson()
        assertThat(unknown["stdout_original_length"]).isEqualTo(JsonNull)
        assertThat(unknown["truncated"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(unknown["truncation"]!!.jsonPrimitive.content).isEqualTo("tail_removed")

        val none = CommandToolResult(stdout = "all", exitCode = 0).toJson()
        assertThat(none["truncation"]!!.jsonPrimitive.content).isEqualTo("none")
        assertThat(none["length_unit"]!!.jsonPrimitive.content).isEqualTo("UTF-16")
    }
}
