package com.promenar.nexara.data.local.db.recovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files

class DualDatabaseRecoveryBootstrapTest {
    @Test
    fun `normal single database startup does not invoke recovery`() {
        val fixture = fixture(null)
        assertThat(fixture.bootstrap.inspectOrResume()).isEqualTo(DualDatabaseBootstrapResult.Normal)
        assertThat(fixture.executor.beginCount).isEqualTo(0)
    }

    @Test
    fun `v17 plus v2 and v5 both produce explicit stable offers`() {
        listOf(2 to PUBLIC_V2_IDENTITY, 5 to INTERNAL_V5_IDENTITY).forEach { (version, identity) ->
            val sources = pair(version, identity)
            val fixture = fixture(sources)
            val first = fixture.bootstrap.inspectOrResume() as DualDatabaseBootstrapResult.RecoveryRequired
            val second = fixture.bootstrap.inspectOrResume() as DualDatabaseBootstrapResult.RecoveryRequired
            assertThat(first.offer.offerToken).isEqualTo(second.offer.offerToken)
            assertThat(first.offer.currentVersion).isEqualTo(version)
            assertThat(fixture.executor.beginCount).isEqualTo(0)
        }
    }

    @Test
    fun `source changes after offer fail closed without begin`() {
        val probe = FakeProbe(pair(2, PUBLIC_V2_IDENTITY))
        val fixture = fixture(probe = probe)
        val offer = (fixture.bootstrap.inspectOrResume() as DualDatabaseBootstrapResult.RecoveryRequired).offer
        probe.sources = pair(2, PUBLIC_V2_IDENTITY).let {
            it.copy(current = it.current.copy(files = listOf(file(CURRENT_DATABASE_NAME, "c"))))
        }.let { it.copy(current = it.current.copy(manifestSha256 = recoveryManifestSha256(it.current.files))) }
        assertThat(fixture.bootstrap.recover(offer.offerToken)).isInstanceOf(DualDatabaseBootstrapResult.Blocked::class.java)
        assertThat(fixture.executor.beginCount).isEqualTo(0)
    }

    @Test
    fun `unfinished journal is mutually exclusive with older restore blockers`() {
        val sources = pair(2, PUBLIC_V2_IDENTITY)
        val fixture = fixture(sources, blocker = "旧恢复仍未完成", persisted = record(sources))
        val result = fixture.bootstrap.inspectOrResume()
        assertThat(result).isInstanceOf(DualDatabaseBootstrapResult.Blocked::class.java)
        assertThat(fixture.executor.resumeCount).isEqualTo(0)
        assertThat(fixture.probe.blockerReads).isEqualTo(1)
    }

    private fun fixture(
        sources: DualDatabaseSourcePair? = null,
        blocker: String? = null,
        persisted: DualDatabaseRecoveryRecord? = null,
        probe: FakeProbe = FakeProbe(sources, blocker),
    ): Fixture {
        val root = Files.createTempDirectory("dual-db-bootstrap")
        val journal = MemoryJournal(persisted)
        val executor = FakeExecutor(journal)
        return Fixture(DualDatabaseRecoveryBootstrap(root, probe, journal) { executor }, probe, executor)
    }

    private fun pair(version: Int, identity: String) = DualDatabaseSourcePair(
        source("legacy", LEGACY_DATABASE_NAME, 17, PUBLIC_V17_IDENTITY, "a"),
        source("current", CURRENT_DATABASE_NAME, version, identity, "b"),
    )

    private fun source(role: String, name: String, version: Int, identity: String, hash: String): RecoveryDatabaseSource {
        val files = listOf(file(name, hash))
        return RecoveryDatabaseSource(role, name, version, identity, files, recoveryManifestSha256(files))
    }

    private fun file(name: String, char: String) = RecoveryFileFingerprint(name, 1, char.repeat(64))

    private fun record(sources: DualDatabaseSourcePair) = DualDatabaseRecoveryRecord(
        transactionId = "tx-1",
        stage = DualDatabaseRecoveryStage.SNAPSHOTTED,
        legacy = sources.legacy,
        current = sources.current,
        snapshotManifestSha256 = "d".repeat(64),
    )

    private data class Fixture(
        val bootstrap: DualDatabaseRecoveryBootstrap,
        val probe: FakeProbe,
        val executor: FakeExecutor,
    )

    private class FakeProbe(var sources: DualDatabaseSourcePair?, private val blocker: String? = null) :
        DualDatabaseRecoverySourceProbe {
        var blockerReads = 0
        override fun inspectDualSources() = sources
        override fun startupBlocker(): String? { blockerReads++; return blocker }
    }

    private class MemoryJournal(var value: DualDatabaseRecoveryRecord?) : DualDatabaseRecoveryJournal {
        override fun read() = value
        override fun write(record: DualDatabaseRecoveryRecord) { value = record }
    }

    private class FakeExecutor(private val journal: MemoryJournal) : DualDatabaseRecoveryExecutor {
        var beginCount = 0
        var resumeCount = 0
        override fun begin(sources: DualDatabaseSourcePair): DualDatabaseRecoveryRecord {
            beginCount++
            return DualDatabaseRecoveryRecord(
                transactionId = "tx-1",
                stage = DualDatabaseRecoveryStage.COMPLETE,
                legacy = sources.legacy,
                current = sources.current,
                snapshotManifestSha256 = "d".repeat(64),
                writerGateOpened = true,
            ).also(journal::write)
        }
        override fun resume(record: DualDatabaseRecoveryRecord): DualDatabaseRecoveryRecord {
            resumeCount++
            return record.copy(stage = DualDatabaseRecoveryStage.COMPLETE, writerGateOpened = true).also(journal::write)
        }
    }
}
