package com.promenar.nexara.data.local.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import org.junit.Assert.assertThrows
import java.io.IOException
import java.nio.file.Files

class LegacyDatabasePromoterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun promotePublishesCheckpointedLegacyMainAndKeepsRollbackSource() {
        val directory = temporaryFolder.newFolder("complete").toPath()
        write(directory.resolve("nexara.db"), "main")
        write(directory.resolve("nexara.db-wal"), "wal")
        write(directory.resolve("nexara.db-shm"), "shm")

        var prepared = false
        val result = LegacyDatabasePromoter.promote(directory) {
            prepared = true
            Files.deleteIfExists(directory.resolve("nexara.db-wal"))
            Files.deleteIfExists(directory.resolve("nexara.db-shm"))
        }

        assertThat(result).isEqualTo(LegacyDatabasePromotionResult.Promoted)
        assertThat(prepared).isTrue()
        assertThat(read(directory.resolve("nexara_v2.db"))).isEqualTo("main")
        assertThat(read(directory.resolve("nexara.db"))).isEqualTo("main")
    }

    @Test
    fun promoteFailsClosedWhenUnknownCurrentSidecarExists() {
        val directory = temporaryFolder.newFolder("unknown-sidecar").toPath()
        write(directory.resolve("nexara.db"), "main")
        write(directory.resolve("nexara_v2.db-wal"), "wal")

        assertThrows(IOException::class.java) {
            LegacyDatabasePromoter.promote(directory) { error("不得准备来源库") }
        }

        assertThat(read(directory.resolve("nexara.db"))).isEqualTo("main")
        assertThat(Files.exists(directory.resolve("nexara_v2.db"))).isFalse()
    }

    @Test
    fun promoteKeepsHistoricalDatabaseWhenCurrentDatabaseAlreadyExists() {
        val directory = temporaryFolder.newFolder("current-wins").toPath()
        write(directory.resolve("nexara.db"), "legacy")
        write(directory.resolve("nexara_v2.db"), "current")

        val result = LegacyDatabasePromoter.promote(directory) { error("不得准备历史库") }

        assertThat(result).isEqualTo(LegacyDatabasePromotionResult.CurrentDatabaseExists)
        assertThat(read(directory.resolve("nexara.db"))).isEqualTo("legacy")
        assertThat(read(directory.resolve("nexara_v2.db"))).isEqualTo("current")
    }

    @Test
    fun promoteFailsClosedWhenPreparationFailsWithoutPublishingCurrentDatabase() {
        val directory = temporaryFolder.newFolder("prepare-failure").toPath()
        write(directory.resolve("nexara.db"), "main")

        assertThrows(IOException::class.java) {
            LegacyDatabasePromoter.promote(directory) { throw IOException("checkpoint failed") }
        }

        assertThat(read(directory.resolve("nexara.db"))).isEqualTo("main")
        assertThat(Files.exists(directory.resolve("nexara_v2.db"))).isFalse()
    }

    @Test
    fun promoteDoesNothingWhenNeitherDatabaseExists() {
        val directory = temporaryFolder.newFolder("empty").toPath()

        assertThat(LegacyDatabasePromoter.promote(directory) { error("不得准备不存在的库") })
            .isEqualTo(LegacyDatabasePromotionResult.NoLegacyDatabase)
    }

    private fun write(path: java.nio.file.Path, value: String) {
        Files.write(path, value.toByteArray())
    }

    private fun read(path: java.nio.file.Path): String = String(Files.readAllBytes(path))
}
