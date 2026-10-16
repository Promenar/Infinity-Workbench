package com.promenar.nexara.data.storage

import com.promenar.nexara.domain.storage.ExternalFolderException
import com.promenar.nexara.domain.storage.ExternalFolderPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExternalFolderPathsTest {
    @Test
    fun normalizesOnlySeparatorsAndRetainsSignificantWhitespace() {
        assertEquals("/", ExternalFolderPaths.normalize(""))
        assertEquals("/notes/file.txt", ExternalFolderPaths.normalize("//notes///file.txt/"))
        assertEquals("/空 格/ 文本 ", ExternalFolderPaths.normalize("空 格/ 文本 "))
        assertEquals(" 文本 ", ExternalFolderPaths.validateName(" 文本 "))
    }

    @Test
    fun rejectsTraversalHiddenGovernanceAndNonSingleNames() {
        listOf(".", "..", ".nexara", ".NEXARA-cache", ".recycle_bin", "a/b", "a\\b", "a\u0000b", "").forEach { name ->
            assertEquals("INVALID_PATH", assertThrows(ExternalFolderException::class.java) { ExternalFolderPaths.validateName(name) }.code)
        }
    }
}
