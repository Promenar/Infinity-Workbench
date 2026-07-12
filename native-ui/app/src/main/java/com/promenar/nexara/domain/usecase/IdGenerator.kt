package com.promenar.nexara.domain.usecase

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

object IdGenerator {
    private val sequence = MonotonicIdSequence()

    fun agent(): String = sequence.next("agent")
    fun session(): String = sequence.next("session")
    fun message(prefix: String = "msg"): String = sequence.next(prefix)
    fun document(): String = sequence.next("doc")
    fun folder(): String = sequence.next("folder")
    fun uuid(): String = UUID.randomUUID().toString()
    fun skill(): String = sequence.next("skill")
}

internal class MonotonicIdSequence(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val last = AtomicLong(Long.MIN_VALUE)

    fun next(prefix: String): String {
        val value = last.updateAndGet { previous -> maxOf(clock(), previous + 1) }
        return "${prefix}_$value"
    }
}
