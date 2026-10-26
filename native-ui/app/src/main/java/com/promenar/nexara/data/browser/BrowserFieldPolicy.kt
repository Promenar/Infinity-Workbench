package com.promenar.nexara.data.browser

import java.util.Locale

/** Agent 不得代填的字段类别；遇到时把页面交给用户。 */
enum class BrowserSensitiveKind(val modelLabel: String, val handoff: BrowserHandoffReason) {
    PASSWORD("password", BrowserHandoffReason.LOGIN),
    PAYMENT("payment", BrowserHandoffReason.PAYMENT),
    ONE_TIME_CODE("verification code", BrowserHandoffReason.VERIFICATION),
}

object BrowserFieldPolicy {
    private val passwordHint = Regex("""pass(word|wd|code)|(?<![a-z])pwd(?![a-z])|密码|口令""", RegexOption.IGNORE_CASE)
    private val paymentHint = Regex("""card.?(number|num|no)|(?<![a-z])(cvv|cvc|csc)(?![a-z])|security.?code|card.?expir|卡号|银行卡|信用卡|安全码|支付密码""", RegexOption.IGNORE_CASE)
    private val codeHint = Regex("""(?<![a-z])otp(?![a-z])|one.?time|verification.?code|验证码|校验码|动态码""", RegexOption.IGNORE_CASE)

    /** 注入脚本复用的同一组提示词，用于在页面内就地隐去取值。 */
    val pageHintPattern: String = listOf(passwordHint, paymentHint, codeHint).joinToString("|") { "(?:${it.pattern})" }

    fun classify(element: BrowserElement): BrowserSensitiveKind? {
        if (!element.editable && element.inputType == null) return null
        if (element.inputType?.lowercase(Locale.ROOT) == "password") return BrowserSensitiveKind.PASSWORD
        val tokens = element.autocomplete.orEmpty().lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
        when {
            tokens.any { it == "current-password" || it == "new-password" } -> return BrowserSensitiveKind.PASSWORD
            tokens.any { it == "one-time-code" } -> return BrowserSensitiveKind.ONE_TIME_CODE
            tokens.any { it.startsWith("cc-") || it.startsWith("transaction-") } -> return BrowserSensitiveKind.PAYMENT
        }
        val hints = listOfNotNull(element.fieldName, element.placeholder, element.name).joinToString(" ")
        return when {
            passwordHint.containsMatchIn(hints) -> BrowserSensitiveKind.PASSWORD
            paymentHint.containsMatchIn(hints) -> BrowserSensitiveKind.PAYMENT
            codeHint.containsMatchIn(hints) -> BrowserSensitiveKind.ONE_TIME_CODE
            else -> null
        }
    }
}
