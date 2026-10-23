package com.promenar.nexara.data.schedule

import com.promenar.nexara.domain.schedule.ScheduledTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 应用位于前台时用进程内计时器准点触发定时任务，不等待系统闹钟或 JobScheduler 的对齐窗口。
 *
 * 系统排期保持不变，作为后台兜底；两条路径对同一次到期的重复回调由控制器的领取去重。
 * 触发在 [scope] 中独立启动：领取会改写任务行并让列表重新发射，若在收集块内执行，整轮生成会被取消。
 */
class ForegroundScheduleTicker(
    private val scope: CoroutineScope,
    private val tasks: Flow<List<ScheduledTask>>,
    private val foreground: Flow<Boolean>,
    private val clock: () -> Long,
    private val fire: suspend (String) -> Unit,
) {
    /** 同一次到期（任务与计划时刻）只由计时器尝试一次；列表因其它任务变化而重发时不重复领取。 */
    private val attempted = mutableSetOf<Pair<String, Long>>()

    suspend fun run() {
        combine(foreground, tasks) { visible, list ->
            if (visible) list.filter { it.enabled && it.nextFireAtMillis != null } else emptyList()
        }.collectLatest { pending ->
            attempted.retainAll(pending.map { it.id to it.nextFireAtMillis!! }.toSet())
            while (true) {
                val now = clock()
                val (due, future) = pending.partition { it.nextFireAtMillis!! <= now }
                due.forEach { task ->
                    if (attempted.add(task.id to task.nextFireAtMillis!!)) scope.launch {
                        // 失败结果由控制器记在任务行上；计时器只负责准点，不能因单次异常退出。
                        try { fire(task.id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
                    }
                }
                val next = future.minOfOrNull { it.nextFireAtMillis!! } ?: awaitCancellation()
                delay(next - now)
            }
        }
    }
}
