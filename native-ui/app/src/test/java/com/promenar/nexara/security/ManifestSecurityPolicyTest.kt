package com.promenar.nexara.security

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Test
import org.w3c.dom.Document

class ManifestSecurityPolicyTest {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test
    fun `manifest disables backup and references explicit backup rules`() {
        val application = parse("app/src/main/AndroidManifest.xml")
            .getElementsByTagName("application")
            .item(0)

        assertThat(application.attributes.getNamedItemNS(androidNamespace, "allowBackup").nodeValue)
            .isEqualTo("false")
        assertThat(application.attributes.getNamedItemNS(androidNamespace, "fullBackupContent").nodeValue)
            .isEqualTo("@xml/backup_rules")
        assertThat(application.attributes.getNamedItemNS(androidNamespace, "dataExtractionRules").nodeValue)
            .isEqualTo("@xml/data_extraction_rules")
        assertThat(application.attributes.getNamedItemNS(androidNamespace, "usesCleartextTraffic").nodeValue)
            .isEqualTo("false")
    }

    @Test
    fun `network security config rejects cleartext traffic`() {
        val baseConfig = parse("app/src/main/res/xml/network_security_config.xml")
            .getElementsByTagName("base-config")
            .item(0)

        assertThat(baseConfig.attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)
            .isEqualTo("false")
    }

    @Test
    fun `backup policy excludes all application storage domains`() {
        val legacyRules = parse("app/src/main/res/xml/backup_rules.xml")
        val extractionRules = parse("app/src/main/res/xml/data_extraction_rules.xml")

        assertExcludedDomains(legacyRules, setOf("root", "file", "database", "sharedpref", "external"))
        assertExcludedDomains(extractionRules, setOf("root", "file", "database", "sharedpref", "external"))
    }

    private fun assertExcludedDomains(document: Document, expected: Set<String>) {
        val excludes = document.getElementsByTagName("exclude")
        val domains = buildSet {
            for (index in 0 until excludes.length) {
                add(excludes.item(index).attributes.getNamedItem("domain").nodeValue)
            }
        }
        assertThat(domains).containsAtLeastElementsIn(expected)
    }

    private fun parse(path: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return factory.newDocumentBuilder().parse(File(path))
    }
}
