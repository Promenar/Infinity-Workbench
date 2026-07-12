package com.promenar.nexara.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class RestoreRelayManifestTest {
    @Test
    fun `relay is explicit non-exported isolated process with no intent filter`() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        val relay = manifest.substringAfter("android:name=\".data.backup.RestoreRelayActivity\"")
            .substringBefore("</activity>")

        assertThat(relay).contains("android:exported=\"false\"")
        assertThat(relay).contains("android:process=\":restore_relay\"")
        assertThat(relay).doesNotContain("intent-filter")
    }

    @Test
    fun `relay source carries only fixed action and main pid`() {
        val source = read("app/src/main/java/com/promenar/nexara/data/backup/RestoreRelayActivity.kt")

        assertThat(source).contains("putExtra(EXTRA_MAIN_PID, Process.myPid())")
        assertThat(source).doesNotContain("packageBytes")
        assertThat(source).doesNotContain("password")
        assertThat(source).doesNotContain("txId")
    }

    @Test
    fun `relay application exits before runtime and writer initialization`() {
        val source = read("app/src/main/java/com/promenar/nexara/NexaraApplication.kt")
        val onCreate = source.substringAfter("override fun onCreate()")
            .substringBefore("fun retryStartupRecovery")

        assertThat(onCreate.indexOf("restoreRelayEarlyExit = true"))
            .isLessThan(onCreate.indexOf("backupRuntime = createBackupRuntime()"))
        assertThat(onCreate.indexOf("return"))
            .isLessThan(onCreate.indexOf("startBackupRecovery()"))
    }

    private fun read(path: String): String = String(Files.readAllBytes(Path.of(path)), Charsets.UTF_8)
}
