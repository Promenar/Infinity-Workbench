package com.promenar.infinity.ui.chat.manager

import com.promenar.infinity.domain.ask.AskUserAnswer
import com.promenar.infinity.domain.ask.AskUserLimits
import com.promenar.infinity.domain.ask.AskUserRequest

/** 返回首个违反约束的具体原因；空结果表示校验通过。 */
internal object AskUserValidation {
    fun request(request: AskUserRequest): String? {
        if (request.questions.size !in 1..AskUserLimits.MAX_QUESTIONS) {
            return "questions 必须包含 1 至 ${AskUserLimits.MAX_QUESTIONS} 个问题"
        }
        val headers = mutableSetOf<String>()
        request.questions.forEachIndexed { index, question ->
            val prefix = "questions[$index]"
            if (question.header.isBlank()) return "$prefix.header 不得为空白"
            if (question.header.codePointCount(0, question.header.length) > AskUserLimits.MAX_HEADER_CHARS) {
                return "$prefix.header 不得超过 ${AskUserLimits.MAX_HEADER_CHARS} 个字符"
            }
            if (!headers.add(question.header)) return "$prefix.header 不得重复"
            if (question.question.isBlank()) return "$prefix.question 不得为空白"
            if (question.options.size !in AskUserLimits.MIN_OPTIONS..AskUserLimits.MAX_OPTIONS) {
                return "$prefix.options 必须包含 ${AskUserLimits.MIN_OPTIONS} 至 ${AskUserLimits.MAX_OPTIONS} 个选项"
            }
            val labels = mutableSetOf<String>()
            question.options.forEachIndexed { optionIndex, option ->
                if (option.label.isBlank()) return "$prefix.options[$optionIndex].label 不得为空白"
                if (option.description.isNotEmpty() && option.description.isBlank()) return "$prefix.options[$optionIndex].description 不得为空白"
                if (!labels.add(option.label)) return "$prefix.options[$optionIndex].label 不得重复"
            }
        }
        return null
    }

    fun answer(request: AskUserRequest, answer: AskUserAnswer): String? {
        if (answer.skipped && (answer.answers.isNotEmpty() || answer.other != null)) {
            return "跳过时 answers 和 other 必须为空"
        }
        val questions = request.questions.associateBy { it.header }
        answer.answers.forEach { (header, labels) ->
            val question = questions[header] ?: return "未知问题 header：$header"
            if (!question.multiSelect && labels.size > 1) return "问题 $header 只能选择一项"
            if (labels.size != labels.distinct().size) return "问题 $header 的选项不得重复"
            val available = question.options.mapTo(mutableSetOf()) { it.label }
            if (labels.any { it !in available }) return "问题 $header 包含未知选项"
        }
        return null
    }
}
