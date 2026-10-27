package com.promenar.nexara.data.subagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 每个批次文件只有一个写者；待写快照只保留最新版本，等待者确认对应版本已落盘。 */
internal class SubagentCheckpointWriter(
    private val scope: CoroutineScope,
    private val write: suspend (SubagentBatch) -> Unit,
) {
    private class Lane {
        var revision = 0L
        var pending: Pair<Long, SubagentBatch>? = null
        var worker: Job? = null
        val receipts = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
    }
    private val lock = Any()
    private val lanes = mutableMapOf<Pair<String, String>, Lane>()

    internal class Write(val receipt: Deferred<Unit>, private val worker: Job) {
        fun start() { worker.start() }
    }

    /** 只登记快照，调用方离开状态锁后才能启动写者。 */
    fun enqueue(batch: SubagentBatch): Write {
        val receipt = CompletableDeferred<Unit>()
        val worker = synchronized(lock) {
            val lane = lanes.getOrPut(batch.sessionId to batch.stepId) { Lane() }
            val revision = ++lane.revision
            lane.pending = revision to batch
            lane.receipts += revision to receipt
            lane.worker ?: scope.launch(start = CoroutineStart.LAZY) { drain(lane) }.also { job ->
                lane.worker = job
                job.invokeOnCompletion { failure ->
                    if (failure != null) synchronized(lock) {
                        if (lane.worker === job) {
                            lane.worker = null
                            lane.pending = null
                            lane.receipts.forEach { it.second.completeExceptionally(failure) }
                            lane.receipts.clear()
                        }
                    }
                }
            }
        }
        return Write(receipt, worker)
    }

    private suspend fun drain(lane: Lane) {
        while (true) {
            val snapshot = synchronized(lock) {
                lane.pending.also {
                    lane.pending = null
                    if (it == null) lane.worker = null
                }
            } ?: return
            val result = runCatching { write(snapshot.second) }
            synchronized(lock) {
                val completed = lane.receipts.filter { it.first <= snapshot.first }
                lane.receipts.removeAll(completed.toSet())
                completed.forEach { (_, receipt) ->
                    result.fold({ receipt.complete(Unit) }, { receipt.completeExceptionally(it) })
                }
            }
        }
    }
}
