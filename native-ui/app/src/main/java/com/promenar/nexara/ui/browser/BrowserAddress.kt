package com.promenar.nexara.ui.browser

import java.net.URLEncoder

private const val SEARCH_URL = "https://www.google.com/search?q="

private val WEB_URL = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

// 缺协议时视为网址的主机：localhost、IPv4，或末段为字母的域名（含中文等国际化域名）。
private val BARE_HOST = Regex(
    "^(localhost|\\d{1,3}(\\.\\d{1,3}){3}|([\\p{L}\\p{N}-]+\\.)+\\p{L}{2,})(:\\d{1,5})?([/?#]\\S*)?$",
)

/**
 * 地址栏输入转为要打开的地址：网页地址原样打开，缺协议的主机补 https，其余（含 javascript:、file: 等非网页协议）按 Google 搜索。
 * 空白返回 null。
 */
internal fun browserAddressTarget(input: String): String? {
    val text = input.trim()
    if (text.isEmpty()) return null
    return when {
        WEB_URL.matches(text) -> text
        BARE_HOST.matches(text) -> "https://$text"
        else -> SEARCH_URL + URLEncoder.encode(text, "UTF-8")
    }
}
