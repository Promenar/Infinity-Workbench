package com.promenar.nexara.domain.schedule

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.*

data class ScheduleCreateArgs(
    val name: String,
    val prompt: String,
    val trigger: ScheduleTrigger,
    val exactRequested: Boolean = false,
    val maxRuns: Int? = null,
    val expiresAtMillis: Long? = null,
)

data class ScheduleCreateResult(val task: ScheduledTask, val notice: String? = null)

object ScheduleCalculation {
    fun minimumMinutes(exactEffective: Boolean): Int = if (exactEffective) 5 else 15

    fun parse(args: JsonObject, zoneId: String): ScheduleCreateArgs {
        ZoneId.of(zoneId)
        fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("$key 必须是字符串。")
        fun JsonObject.integer(key: String): Int = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw IllegalArgumentException("$key 必须是整数。")
        val raw = args["trigger"] as? JsonObject ?: throw IllegalArgumentException("trigger 必须是对象。")
        val trigger = when (raw.text("type")) {
            "once" -> ScheduleTrigger.Once(raw.text("at"), zoneId)
            "interval" -> ScheduleTrigger.Interval(raw.integer("every_minutes"))
            "cron" -> ScheduleTrigger.Cron(raw.text("expr"), zoneId)
            else -> throw IllegalArgumentException("trigger.type 必须是 once、interval 或 cron。")
        }
        val exact = if ("exact" in args) (args["exact"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw IllegalArgumentException("exact 必须是布尔值。") else false
        val expiry = if ("expires_at" in args) {
            val value = args.text("expires_at")
            runCatching { Instant.parse(value).toEpochMilli() }.getOrElse {
                resolveLocal(value, zoneId)
            }
        } else null
        return ScheduleCreateArgs(args.text("name"), args.text("prompt"), trigger, exact,
            if ("max_runs" in args) args.integer("max_runs") else null, expiry)
    }

    fun validate(args: ScheduleCreateArgs, nowMillis: Long, exactEffective: Boolean) {
        require(args.name.isNotBlank() && args.name.codePointCount(0, args.name.length) <= 60) { "名称必须为 1 到 60 字符。" }
        require(args.prompt.isNotBlank() && args.prompt.codePointCount(0, args.prompt.length) <= 4000) { "提示必须为 1 到 4000 字符。" }
        require(args.maxRuns == null || args.maxRuns > 0) { "max_runs 必须是正整数。" }
        require(args.expiresAtMillis == null || args.expiresAtMillis > nowMillis) { "到期时间必须在未来。" }
        val minimum = minimumMinutes(exactEffective || args.exactRequested)
        when (val trigger = args.trigger) {
            is ScheduleTrigger.Once -> require(resolveLocal(trigger.localDateTime, trigger.zoneId) > nowMillis) { "单次触发时间必须在未来。" }
            is ScheduleTrigger.Interval -> require(trigger.everyMinutes >= minimum) { "当前调度模式的间隔至少为 $minimum 分钟。" }
            is ScheduleTrigger.Cron -> {
                val times = CronExpression.parse(trigger.expression).nextTimes(nowMillis, trigger.zoneId, 64)
                require(times.isNotEmpty()) { "cron 在未来 8 年内没有触发时间。" }
                require(times.zipWithNext().all { (a, b) -> b - a >= minimum * 60_000L }) { "cron 的最小间隔至少为 $minimum 分钟。" }
            }
        }
        val first = nextAfter(args.trigger, nowMillis, exactEffective)
        require(first != null && (args.expiresAtMillis == null || first < args.expiresAtMillis)) { "到期时间前没有可用触发时间。" }
    }

    /** 缺失的本地时间拒绝创建；重复的本地时间取较早的有效偏移。 */
    fun resolveLocal(value: String, zoneId: String): Long {
        val local = try { LocalDateTime.parse(value) } catch (_: java.time.DateTimeException) { throw IllegalArgumentException("本地日期时间无效。") }
        val zone = ZoneId.of(zoneId)
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) { "所选本地时间处于夏令时跳过区间。" }
        return local.toInstant(offsets.first()).toEpochMilli()
    }

    fun nextAfter(trigger: ScheduleTrigger, afterMillis: Long, exactEffective: Boolean): Long? = when (trigger) {
        is ScheduleTrigger.Once -> resolveLocal(trigger.localDateTime, trigger.zoneId).takeIf { it > afterMillis }
        is ScheduleTrigger.Interval -> Math.addExact(afterMillis, maxOf(trigger.everyMinutes, minimumMinutes(exactEffective)) * 60_000L)
        is ScheduleTrigger.Cron -> CronExpression.parse(trigger.expression).nextAfter(afterMillis, trigger.zoneId)
    }

    /** 失去精确权限时，cron 的下一次实际排期同样遵守非精确间隔下限。 */
    fun nextAfterRun(trigger: ScheduleTrigger, firedAtMillis: Long, exactEffective: Boolean): Long? =
        nextAfter(trigger, if (trigger is ScheduleTrigger.Cron) firedAtMillis + minimumMinutes(exactEffective) * 60_000L - 1 else firedAtMillis, exactEffective)

    fun exhausted(runCount: Int, maxRuns: Int?, expiresAtMillis: Long?, nowMillis: Long): Boolean =
        (maxRuns != null && runCount >= maxRuns) || (expiresAtMillis != null && nowMillis >= expiresAtMillis)
}

/** 审批界面使用的纯函数；与工具执行使用同一参数校验和实际渠道能力。 */
fun previewScheduleTriggers(
    args: JsonObject,
    nowMillis: Long,
    zoneId: String,
    exactAlarmAvailable: Boolean,
    canScheduleExact: Boolean,
    count: Int = 3,
): List<Long> {
    require(count in 1..64)
    val parsed = ScheduleCalculation.parse(args, zoneId)
    val effective = parsed.exactRequested && exactAlarmAvailable && canScheduleExact
    ScheduleCalculation.validate(parsed, nowMillis, effective)
    val result = mutableListOf<Long>()
    var after = nowMillis
    repeat(minOf(count, parsed.maxRuns ?: count)) {
        val next = (if (result.isEmpty()) ScheduleCalculation.nextAfter(parsed.trigger, after, effective)
            else ScheduleCalculation.nextAfterRun(parsed.trigger, after, effective)) ?: return result
        if (parsed.expiresAtMillis != null && next >= parsed.expiresAtMillis) return result
        result += next
        after = next
    }
    return result
}
