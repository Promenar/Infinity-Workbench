package com.promenar.infinity.data.browser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserFieldPolicyTest {
    private fun field(
        inputType: String? = "text",
        autocomplete: String? = null,
        fieldName: String? = null,
        placeholder: String? = null,
        name: String = "",
        editable: Boolean = true,
    ) = BrowserElement("e1", "textbox", name, tag = "input", inputType = inputType, autocomplete = autocomplete,
        fieldName = fieldName, placeholder = placeholder, editable = editable)

    @Test fun `密码类型与密码自动填充归为登录交接`() {
        assertThat(BrowserFieldPolicy.classify(field(inputType = "password"))).isEqualTo(BrowserSensitiveKind.PASSWORD)
        assertThat(BrowserFieldPolicy.classify(field(inputType = "PASSWORD"))).isEqualTo(BrowserSensitiveKind.PASSWORD)
        assertThat(BrowserFieldPolicy.classify(field(autocomplete = "section-a current-password"))).isEqualTo(BrowserSensitiveKind.PASSWORD)
        assertThat(BrowserFieldPolicy.classify(field(autocomplete = "new-password"))).isEqualTo(BrowserSensitiveKind.PASSWORD)
        assertThat(BrowserSensitiveKind.PASSWORD.handoff).isEqualTo(BrowserHandoffReason.LOGIN)
    }

    @Test fun `支付自动填充与卡号提示归为支付交接`() {
        assertThat(BrowserFieldPolicy.classify(field(autocomplete = "cc-number"))).isEqualTo(BrowserSensitiveKind.PAYMENT)
        assertThat(BrowserFieldPolicy.classify(field(autocomplete = "billing cc-csc"))).isEqualTo(BrowserSensitiveKind.PAYMENT)
        assertThat(BrowserFieldPolicy.classify(field(fieldName = "cardNumber"))).isEqualTo(BrowserSensitiveKind.PAYMENT)
        assertThat(BrowserFieldPolicy.classify(field(placeholder = "请输入银行卡号"))).isEqualTo(BrowserSensitiveKind.PAYMENT)
        assertThat(BrowserFieldPolicy.classify(field(name = "CVV"))).isEqualTo(BrowserSensitiveKind.PAYMENT)
        assertThat(BrowserSensitiveKind.PAYMENT.handoff).isEqualTo(BrowserHandoffReason.PAYMENT)
    }

    @Test fun `一次性验证码归为验证交接`() {
        assertThat(BrowserFieldPolicy.classify(field(autocomplete = "one-time-code"))).isEqualTo(BrowserSensitiveKind.ONE_TIME_CODE)
        assertThat(BrowserFieldPolicy.classify(field(placeholder = "短信验证码"))).isEqualTo(BrowserSensitiveKind.ONE_TIME_CODE)
        assertThat(BrowserFieldPolicy.classify(field(fieldName = "otp"))).isEqualTo(BrowserSensitiveKind.ONE_TIME_CODE)
    }

    @Test fun `以文本提示命名的密码框也被识别`() {
        assertThat(BrowserFieldPolicy.classify(field(fieldName = "user_pwd"))).isEqualTo(BrowserSensitiveKind.PASSWORD)
        assertThat(BrowserFieldPolicy.classify(field(fieldName = "card_cvv"))).isEqualTo(BrowserSensitiveKind.PAYMENT)
        assertThat(BrowserFieldPolicy.classify(field(fieldName = "shotput"))).isNull()
        assertThat(BrowserFieldPolicy.classify(field(fieldName = "pwd"))).isEqualTo(BrowserSensitiveKind.PASSWORD)
        assertThat(BrowserFieldPolicy.classify(field(name = "登录密码"))).isEqualTo(BrowserSensitiveKind.PASSWORD)
        assertThat(BrowserFieldPolicy.classify(field(placeholder = "Passcode"))).isEqualTo(BrowserSensitiveKind.PASSWORD)
    }

    @Test fun `普通字段与非输入元素不受限`() {
        assertThat(BrowserFieldPolicy.classify(field(fieldName = "q", placeholder = "搜索"))).isNull()
        assertThat(BrowserFieldPolicy.classify(field(inputType = "email", autocomplete = "username"))).isNull()
        // 链接名里出现“忘记密码”不是可填写字段。
        assertThat(BrowserFieldPolicy.classify(BrowserElement("e2", "link", "忘记密码？", tag = "a"))).isNull()
    }
}
