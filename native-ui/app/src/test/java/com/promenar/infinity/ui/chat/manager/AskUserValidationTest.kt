package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.ask.AskUserAnswer
import com.promenar.infinity.domain.ask.AskUserOption
import com.promenar.infinity.domain.ask.AskUserQuestion
import com.promenar.infinity.domain.ask.AskUserRequest
import org.junit.Test

class AskUserValidationTest {
    private val valid = AskUserRequest(listOf(AskUserQuestion("方案", "选哪个？", options = listOf(AskUserOption("甲"), AskUserOption("乙")))))

    @Test fun `请求限制分别给出错误`() {
        val q = valid.questions.single()
        val invalid = listOf(
            valid.copy(questions = emptyList()),
            valid.copy(questions = List(5) { q.copy(header = "$it") }),
            valid.copy(questions = listOf(q.copy(header = " "))),
            valid.copy(questions = listOf(q.copy(header = "1234567890123"))),
            valid.copy(questions = listOf(q.copy(question = " "))),
            valid.copy(questions = listOf(q.copy(options = listOf(AskUserOption("甲"))))),
            valid.copy(questions = listOf(q.copy(options = List(5) { AskUserOption("$it") }))),
            valid.copy(questions = listOf(q.copy(options = listOf(AskUserOption(" "), AskUserOption("乙"))))),
            valid.copy(questions = listOf(q.copy(options = listOf(AskUserOption("甲"), AskUserOption("甲"))))),
            valid.copy(questions = listOf(q, q)),
        )
        assertThat(AskUserValidation.request(valid)).isNull()
        invalid.forEach { assertThat(AskUserValidation.request(it)).isNotEmpty() }
    }

    @Test fun `回答校验选项单选自由回答和跳过`() {
        val multi = valid.copy(questions = listOf(valid.questions.single().copy(multiSelect = true)))
        assertThat(AskUserValidation.answer(valid, AskUserAnswer(mapOf("方案" to listOf("甲"))))).isNull()
        assertThat(AskUserValidation.answer(multi, AskUserAnswer(mapOf("方案" to listOf("甲", "乙"))))).isNull()
        assertThat(AskUserValidation.answer(valid, AskUserAnswer(other = "自由回答"))).isNull()
        assertThat(AskUserValidation.answer(valid, AskUserAnswer(skipped = true))).isNull()
        listOf(
            AskUserAnswer(mapOf("未知" to listOf("甲"))),
            AskUserAnswer(mapOf("方案" to listOf("未知"))),
            AskUserAnswer(mapOf("方案" to listOf("甲", "乙"))),
            AskUserAnswer(mapOf("方案" to listOf("甲", "甲"))),
            AskUserAnswer(mapOf("方案" to listOf("甲")), skipped = true),
            AskUserAnswer(other = "自由回答", skipped = true),
        ).forEach { assertThat(AskUserValidation.answer(valid, it)).isNotEmpty() }
    }
}
