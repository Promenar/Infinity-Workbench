package com.promenar.nexara.domain.schedule

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** 五段 cron，字段顺序为分、时、日、月、周；日与周同时受限时取或。 */
class CronExpression private constructor(
    private val minutes: Field,
    private val hours: Field,
    private val daysOfMonth: Field,
    private val months: Field,
    private val daysOfWeek: Field,
) {
    /** 返回严格晚于输入的执行时间；八年内无匹配结果时返回空值。 */
    fun nextAfter(afterMillis: Long, zoneId: String): Long? =
        nextAfter(afterMillis, parseZone(zoneId))

    fun nextTimes(afterMillis: Long, zoneId: String, count: Int): List<Long> {
        require(count >= 0) { "预览数量不能为负数" }
        val zone = parseZone(zoneId)
        val result = mutableListOf<Long>()
        var cursor = afterMillis
        repeat(count) {
            val next = nextAfter(cursor, zone) ?: return result
            result.add(next)
            cursor = next
        }
        return result
    }

    private fun nextAfter(afterMillis: Long, zone: ZoneId): Long? {
        if (afterMillis == Long.MAX_VALUE) return null
        val after = Instant.ofEpochMilli(afterMillis)
        val horizon = minOf(after.atZone(zone).plusYears(SEARCH_YEARS).toInstant(), MAX_MILLIS_INSTANT)
        val rules = zone.rules

        // 按合法偏移的上下界包住候选日期，也覆盖回拨跨过午夜的历史时区规则。
        var date = after.atOffset(ZoneOffset.MIN).toLocalDate()
        val lastDate = horizon.atOffset(ZoneOffset.MAX).toLocalDate()
        var earliest: Instant? = null
        while (!date.isAfter(lastDate)) {
            val currentEarliest = earliest
            if (currentEarliest != null && !date.atStartOfDay().toInstant(ZoneOffset.MAX).isBefore(currentEarliest)) {
                return currentEarliest.toEpochMilli()
            }
            if (date.monthValue in months && matchesDay(date)) {
                for (hour in hours.values) {
                    for (minute in minutes.values) {
                        val local = date.atTime(hour, minute)
                        // 缺失时间没有偏移；重复时间逐个比较，不能只使用默认偏移。
                        for (offset in rules.getValidOffsets(local)) {
                            val candidate = local.toInstant(offset)
                            if (candidate.isAfter(after) && !candidate.isAfter(horizon) &&
                                (earliest == null || candidate.isBefore(earliest))
                            ) {
                                earliest = candidate
                            }
                        }
                    }
                }
            }
            date = date.plusDays(1)
        }
        return earliest?.toEpochMilli()
    }

    private fun matchesDay(date: LocalDate): Boolean {
        val matchesMonthDay = date.dayOfMonth in daysOfMonth
        val matchesWeekDay = date.dayOfWeek.value % 7 in daysOfWeek
        return when {
            daysOfMonth.unrestricted -> matchesWeekDay
            daysOfWeek.unrestricted -> matchesMonthDay
            else -> matchesMonthDay || matchesWeekDay
        }
    }

    private class Field(val values: IntArray, val unrestricted: Boolean) {
        operator fun contains(value: Int): Boolean = values.binarySearch(value) >= 0
    }

    companion object {
        private const val SEARCH_YEARS = 8L
        private val MAX_MILLIS_INSTANT = Instant.ofEpochMilli(Long.MAX_VALUE)

        fun parse(expression: String): CronExpression {
            val fields = expression.trim().split(Regex("\\s+"))
            require(fields.size == 5) { "cron 表达式必须包含分、时、日、月、周五个字段" }
            return CronExpression(
                minutes = parseField(fields[0], 0, 59),
                hours = parseField(fields[1], 0, 23),
                daysOfMonth = parseField(fields[2], 1, 31),
                months = parseField(fields[3], 1, 12),
                daysOfWeek = parseField(fields[4], 0, 7, sundayAlias = true),
            )
        }

        private fun parseField(raw: String, minimum: Int, maximum: Int, sundayAlias: Boolean = false): Field {
            val selected = sortedSetOf<Int>()
            for (item in raw.split(',')) {
                val stepped = item.split('/')
                require(stepped.size in 1..2) { "cron 步长格式无效：$raw" }
                val step = if (stepped.size == 2) parseNumber(stepped[1]) else 1
                require(step > 0) { "cron 步长必须为正整数：$raw" }
                val base = stepped[0]
                val start: Int
                val end: Int
                if (base == "*") {
                    start = minimum
                    end = maximum
                } else {
                    val range = base.split('-')
                    require(range.size in 1..2) { "cron 范围格式无效：$raw" }
                    start = parseNumber(range[0])
                    end = when {
                        range.size == 2 -> parseNumber(range[1])
                        stepped.size == 2 -> maximum
                        else -> start
                    }
                    require(start in minimum..maximum && end in minimum..maximum && start <= end) {
                        "cron 字段超出有效范围 $minimum..$maximum：$raw"
                    }
                }
                for (value in start..end step step) {
                    selected.add(if (sundayAlias) value % 7 else value)
                }
            }
            return Field(selected.toIntArray(), unrestricted = raw == "*")
        }

        private fun parseNumber(raw: String): Int {
            require(raw.isNotEmpty() && raw.all { it in '0'..'9' }) { "cron 数值必须为非负整数：$raw" }
            return requireNotNull(raw.toIntOrNull()) { "cron 数值超出整数范围：$raw" }
        }

        private fun parseZone(raw: String): ZoneId = try {
            ZoneId.of(raw)
        } catch (error: DateTimeException) {
            throw IllegalArgumentException("时区无效：$raw", error)
        }
    }
}
