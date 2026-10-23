package com.promenar.nexara.data.jobs

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class JobFilesTest {
    @Test fun `清理副本不会沿符号链接删除原文件`() {
        val root=Files.createTempDirectory("job-cleanup").toFile()
        try {
            val outside=File(root,"outside").apply{mkdir()}
            File(outside,"keep").writeText("safe")
            val run=File(root,"run").apply{mkdir()}
            Files.createSymbolicLink(File(run,"link").toPath(),outside.toPath())
            deleteJobTree(run)
            assertThat(run.exists()).isFalse()
            assertThat(File(outside,"keep").readText()).isEqualTo("safe")
        } finally {deleteJobTree(root)}
    }
}
