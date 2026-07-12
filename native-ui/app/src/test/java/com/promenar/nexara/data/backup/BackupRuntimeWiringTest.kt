package com.promenar.nexara.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class BackupRuntimeWiringTest {
    @Test
    fun `application completes bounded recovery before provider manager and background writers`() {
        val source = Files.readAllBytes(
            Path.of("app/src/main/java/com/promenar/nexara/NexaraApplication.kt")
        ).toString(Charsets.UTF_8)
        val create = source.indexOf("backupRuntime = createBackupRuntime()")
        val recover = source.indexOf("backupRuntime.recoverBeforeWriters")
        val provider = source.indexOf("ProviderManager.init(this, secretStore)")
        val vectorWriter = source.indexOf("vectorizationQueue.resumeInterruptedTasks()")

        assertThat(create).isAtLeast(0)
        assertThat(source).contains("BackupRuntime.createAndroid(this, database, secretStore)")
        assertThat(recover).isGreaterThan(create)
        assertThat(provider).isGreaterThan(recover)
        assertThat(vectorWriter).isGreaterThan(provider)
    }
}
