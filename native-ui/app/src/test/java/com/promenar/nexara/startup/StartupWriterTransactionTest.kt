package com.promenar.nexara.startup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Test

class StartupWriterTransactionTest {
    @Test
    fun `every synchronous failure rolls all observer listener and job counts back to zero then retry commits once`() {
        val preflightPhases = 4
        val sideEffectCount = 7

        for (failureIndex in 0 until preflightPhases + sideEffectCount) {
            val counters = IntArray(sideEffectCount)
            var injectedFailure: Int? = failureIndex
            val transaction = StartupWriterTransaction(
                preflight = {
                    repeat(preflightPhases) { phase ->
                        if (injectedFailure == phase) error("preflight-$phase")
                    }
                    Unit
                },
                registrations = {
                    List(sideEffectCount) { index ->
                        StartupWriterRegistration(
                            register = {
                                counters[index]++
                                if (injectedFailure == preflightPhases + index) error("register-$index")
                            },
                            rollback = { counters[index]-- },
                        )
                    }
                },
            )

            assertThat(runCatching { transaction.commit() }.isFailure).isTrue()
            assertThat(counters.asList()).containsExactlyElementsIn(List(sideEffectCount) { 0 }).inOrder()

            injectedFailure = null
            transaction.commit()
            assertThat(counters.asList()).containsExactlyElementsIn(List(sideEffectCount) { 1 }).inOrder()
        }
    }

    @Test
    fun `background failure remains visible without blocking operational readiness`() = runBlocking {
        val health = StartupBackgroundHealthMonitor { _, _ -> }

        val jobs = listOf(
            StartupBackgroundTask.LOCAL_MODEL_AUTO_LOAD,
            StartupBackgroundTask.VECTOR_RESUME,
        ).map { task ->
            health.launch(this, task) { error("private failure detail") }
        }
        jobs.forEach { it.join() }

        assertThat(health.state.value[StartupBackgroundTask.LOCAL_MODEL_AUTO_LOAD])
            .isEqualTo(StartupBackgroundTaskHealth.Failed)
        assertThat(health.state.value[StartupBackgroundTask.VECTOR_RESUME])
            .isEqualTo(StartupBackgroundTaskHealth.Failed)
    }

    @Test
    fun `committed session retains rollback handles and closes them once in reverse`() {
        val closed = mutableListOf<Int>()
        val session = StartupWriterTransaction(
            preflight = { Unit },
            registrations = {
                List(3) { index ->
                    StartupWriterRegistration(register = {}, rollback = { closed += index })
                }
            },
        ).commit()

        session.rollback()
        session.rollback()

        assertThat(closed).containsExactly(2, 1, 0).inOrder()
    }

    @Test
    fun `provider event failure is isolated and later events still run`() = runBlocking {
        val handled = mutableListOf<Int>()
        val health = StartupBackgroundHealthMonitor { _, _ -> }

        health.collectResilient(
            task = StartupBackgroundTask.PROVIDER_CONFIGURATION,
            events = flow {
                emit(1)
                emit(2)
            },
        ) { value ->
            handled += value
            if (value == 1) error("first event fails")
        }

        assertThat(handled).containsExactly(1, 2).inOrder()
        assertThat(health.state.value[StartupBackgroundTask.PROVIDER_CONFIGURATION])
            .isEqualTo(StartupBackgroundTaskHealth.Healthy)
    }
}
