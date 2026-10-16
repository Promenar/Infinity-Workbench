package com.promenar.nexara.data.remote.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

class McpLoopbackNetworkSecurityTest {
    @Test
    fun `主配置与调试配置只对精确127环回域放行明文`() {
        listOf("main", "debug").forEach { sourceSet ->
            val candidates = listOf(
                File("src/$sourceSet/res/xml/network_security_config.xml"),
                File("app/src/$sourceSet/res/xml/network_security_config.xml"),
            )
            val config = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder().parse(candidates.first { it.isFile })
            val bases = config.getElementsByTagName("base-config")
            assertThat(bases.length).isEqualTo(1)
            assertThat((bases.item(0) as Element).getAttribute("cleartextTrafficPermitted"))
                .isEqualTo("false")

            val domains = config.getElementsByTagName("domain")
            assertThat(domains.length).isEqualTo(1)
            val domain = domains.item(0) as Element
            assertThat(domain.textContent.trim()).isEqualTo("127.0.0.1")
            assertThat(domain.getAttribute("includeSubdomains")).isEqualTo("false")
            assertThat((domain.parentNode as Element).getAttribute("cleartextTrafficPermitted"))
                .isEqualTo("true")
        }
    }
}
