package com.promenar.infinity.data.rag

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * 对话记录的时间线：时间不进入向量正文，只在注入提示词时写在每条记录前，并用于按时间检索与新近度排序。
 * 记录的时间取所属轮次的消息时间，不取写入向量库的时间。
 */
internal object MemoryTimeline {
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
    private val NOW = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE", Locale.US)

    /** 同一批对话候选中，最新一条在选取时最多获得的加分；只用于打破相关度接近时的先后。 */
    const val RECENCY_WEIGHT = 0.05f

    /** “2026-10-19 14:32 · 2 days ago”。 */
    fun stamp(at: Long, now: Long, zone: ZoneId): String =
        "${STAMP.format(Instant.ofEpochMilli(at).atZone(zone))} · ${relative(at, now, zone)}"

    fun currentTime(now: Long, zone: ZoneId): String = "${NOW.format(Instant.ofEpochMilli(now).atZone(zone))} (${zone.id})"

    /** 一小时内按分钟，当天按小时，之后按日历天、周、月、年。 */
    fun relative(at: Long, now: Long, zone: ZoneId): String {
        val elapsed = now - at
        if (elapsed < 60_000L) return "just now"
        if (elapsed < 3_600_000L) return "${elapsed / 60_000L} min ago"
        val days = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(at).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
        )
        return when {
            days <= 0L -> "${elapsed / 3_600_000L} h ago"
            days == 1L -> "yesterday"
            days < 14L -> "$days days ago"
            days < 60L -> "${days / 7} weeks ago"
            days < 730L -> "${days / 30} months ago"
            else -> "${days / 365} years ago"
        }
    }

    /**
     * 按时间先后给候选加分：最早的不加分，最新的加 [RECENCY_WEIGHT]，中间按名次线性。
     * 用名次而非时长，使几分钟内的前后纠正与相隔数月的记录都能区分先后。
     */
    fun recencyBonus(times: Map<String, Long>): Map<String, Float> {
        if (times.size < 2) return times.mapValues { 0f }
        val ordered = times.entries.sortedWith(compareBy({ it.value }, { it.key }))
        val last = (ordered.size - 1).toFloat()
        return ordered.withIndex().associate { (rank, entry) -> entry.key to RECENCY_WEIGHT * rank / last }
    }
}

/** 按时间检索的范围，左闭右开，毫秒时间戳。 */
data class MemoryTimeRange(val from: Long, val to: Long) {
    operator fun contains(at: Long): Boolean = at in from until to
}

/**
 * 问题在回顾过去的对话并给出了时间（“上周二我们聊了什么”“what did we discuss yesterday”）时返回该时间范围。
 * 只有时间、没有回顾意味的问题（“今天天气怎么样”）返回 null，避免把普通问题限定到某段时间。
 */
internal fun parseRecallTimeRange(query: String, now: ZonedDateTime): MemoryTimeRange? {
    if (!RECALL_CUE.containsMatchIn(query)) return null
    return parseTimeRange(query, now)
}

internal fun parseTimeRange(query: String, now: ZonedDateTime): MemoryTimeRange? {
    val text = query.lowercase(Locale.ROOT)
    val today = now.toLocalDate()
    val zone = now.zone
    fun day(date: LocalDate) = range(date, date.plusDays(1), zone)
    fun since(date: LocalDate) = MemoryTimeRange(date.atStartOfDay(zone).toInstant().toEpochMilli(), now.toInstant().toEpochMilli() + 1)
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val monthStart = today.withDayOfMonth(1)

    ISO_DATE.find(text)?.let { m ->
        return dateOrNull(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let(::day)
    }
    ZH_DATE.find(text)?.let { m ->
        val month = m.groupValues[2].toInt()
        val dayOfMonth = m.groupValues[3].toInt()
        val year = m.groupValues[1].takeIf(String::isNotEmpty)?.toInt()
        val date = if (year != null) dateOrNull(year, month, dayOfMonth) else {
            // 未写年份时取最近一次已经过去的该日期。
            dateOrNull(today.year, month, dayOfMonth)?.let { if (it.isAfter(today)) dateOrNull(today.year - 1, month, dayOfMonth) else it }
        }
        return date?.let(::day)
    }
    ZH_WEEKDAY.find(text)?.let { m ->
        val prefix = m.groupValues[1]
        val dow = zhDayOfWeek(m.groupValues[3]) ?: return@let
        val date = when {
            prefix.startsWith("上") -> weekStart.minusWeeks(1).with(TemporalAdjusters.nextOrSame(dow))
            prefix.isNotEmpty() -> weekStart.with(TemporalAdjusters.nextOrSame(dow))
            else -> today.with(TemporalAdjusters.previousOrSame(dow))
        }
        if (!date.isAfter(today)) return day(date)
    }
    EN_WEEKDAY.find(text)?.let { m ->
        val dow = DayOfWeek.valueOf(m.groupValues[2].uppercase(Locale.ROOT))
        // 英文的 last Tuesday 指最近一个已过去的星期二，不同于中文的“上周二”。
        val date = if (m.groupValues[1] == "last") today.with(TemporalAdjusters.previous(dow))
            else today.with(TemporalAdjusters.previousOrSame(dow))
        return day(date)
    }
    ZH_DAYS_AGO.find(text)?.let { m -> number(m.groupValues[1])?.let { return day(today.minusDays(it)) } }
    EN_DAYS_AGO.find(text)?.let { m -> number(m.groupValues[1])?.let { return day(today.minusDays(it)) } }
    ZH_LAST_DAYS.find(text)?.let { m -> number(m.groupValues[1])?.let { return since(today.minusDays(it - 1)) } }
    EN_LAST_DAYS.find(text)?.let { m -> number(m.groupValues[1])?.let { return since(today.minusDays(it - 1)) } }
    ZH_WEEKS_AGO.find(text)?.let { m ->
        number(m.groupValues[1])?.let { val start = weekStart.minusWeeks(it); return range(start, start.plusWeeks(1), zone) }
    }
    EN_WEEKS_AGO.find(text)?.let { m ->
        number(m.groupValues[1])?.let { val start = weekStart.minusWeeks(it); return range(start, start.plusWeeks(1), zone) }
    }
    ZH_MONTHS_AGO.find(text)?.let { m ->
        number(m.groupValues[1])?.let { val start = monthStart.minusMonths(it); return range(start, start.plusMonths(1), zone) }
    }
    return when {
        text.contains("大前天") -> day(today.minusDays(3))
        text.contains("前天") || text.contains("day before yesterday") -> day(today.minusDays(2))
        text.contains("昨天") || text.contains("昨日") || text.contains("昨晚") || text.contains("yesterday") -> day(today.minusDays(1))
        ZH_LAST_WEEK.containsMatchIn(text) || text.contains("last week") -> range(weekStart.minusWeeks(1), weekStart, zone)
        ZH_LAST_MONTH.containsMatchIn(text) || text.contains("last month") -> range(monthStart.minusMonths(1), monthStart, zone)
        text.contains("去年") || text.contains("last year") -> range(today.withDayOfYear(1).minusYears(1), today.withDayOfYear(1), zone)
        ZH_THIS_WEEK.containsMatchIn(text) || text.contains("this week") -> since(weekStart)
        ZH_THIS_MONTH.containsMatchIn(text) || text.contains("this month") -> since(monthStart)
        text.contains("今年") || text.contains("this year") -> since(today.withDayOfYear(1))
        text.contains("今天") || text.contains("今日") || text.contains("今早") || text.contains("今晚") || text.contains("today") -> since(today)
        text.contains("最近") || text.contains("近来") || text.contains("recently") || text.contains("lately") -> since(today.minusDays(6))
        else -> null
    }
}

private fun range(start: LocalDate, end: LocalDate, zone: ZoneId) =
    MemoryTimeRange(start.atStartOfDay(zone).toInstant().toEpochMilli(), end.atStartOfDay(zone).toInstant().toEpochMilli())

private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? = runCatching { LocalDate.of(year, month, day) }.getOrNull()

private fun zhDayOfWeek(token: String): DayOfWeek? = when (token) {
    "一" -> DayOfWeek.MONDAY
    "二" -> DayOfWeek.TUESDAY
    "三" -> DayOfWeek.WEDNESDAY
    "四" -> DayOfWeek.THURSDAY
    "五" -> DayOfWeek.FRIDAY
    "六" -> DayOfWeek.SATURDAY
    "日", "天", "七" -> DayOfWeek.SUNDAY
    else -> null
}

/** 阿拉伯数字或一到九十九的中文数字；超过一年的天数不作时间范围。 */
private fun number(token: String): Long? {
    token.toLongOrNull()?.let { return it.takeIf { n -> n in 1..366 } }
    val digits = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    val value = when {
        token == "十" -> 10
        token.length == 1 -> digits[token[0]]
        token.length == 2 && token[0] == '十' -> digits[token[1]]?.plus(10)
        token.length == 2 && token[1] == '十' -> digits[token[0]]?.times(10)
        token.length == 3 && token[1] == '十' -> digits[token[0]]?.let { tens -> digits[token[2]]?.plus(tens * 10) }
        else -> null
    }
    return value?.toLong()
}

private const val NUM = "([0-9]{1,3}|[一二两三四五六七八九十]{1,3})"
private val ISO_DATE = Regex("(?<![0-9])(20[0-9]{2})[-/.]([0-9]{1,2})[-/.]([0-9]{1,2})(?![0-9])")
private val ZH_DATE = Regex("(?:(20[0-9]{2})年)?([0-9]{1,2})月([0-9]{1,2})[日号]")
private val ZH_WEEKDAY = Regex("(上个?|这个?|本)?(周|星期|礼拜)([一二三四五六日天七])")
private val EN_WEEKDAY = Regex("\\b(last|on)\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")
private val ZH_DAYS_AGO = Regex("${NUM}天(?:之)?前")
private val EN_DAYS_AGO = Regex("\\b([0-9]{1,3}) days? ago\\b")
private val ZH_LAST_DAYS = Regex("(?:最近|近|过去|这)${NUM}天")
private val EN_LAST_DAYS = Regex("\\b(?:last|past) ([0-9]{1,3}) days\\b")
private val ZH_WEEKS_AGO = Regex("${NUM}(?:个)?(?:周|星期|礼拜)(?:之)?前")
private val EN_WEEKS_AGO = Regex("\\b([0-9]{1,2}) weeks? ago\\b")
private val ZH_MONTHS_AGO = Regex("${NUM}个?月(?:之)?前")
private val ZH_LAST_WEEK = Regex("上(?:个)?(?:周|星期|礼拜)")
private val ZH_LAST_MONTH = Regex("上(?:个)?月")
private val ZH_THIS_WEEK = Regex("(?:这|本)(?:个)?(?:周|星期|礼拜)")
private val ZH_THIS_MONTH = Regex("(?:这个|本)月")

/** 回顾过去对话的提示词：谈论、提到、记得、决定等。 */
private val RECALL_CUE = Regex(
    "聊|谈|讨论|说过|说的|讲过|讲的|提到|提过|问过|问的|告诉|记得|决定|定下|做了|做过|干了|发生|我们|咱们|上次|那次|之前" +
        "|(?i)\\b(talk(ed)?|discuss(ed)?|mention(ed)?|said|told|asked|remember|decided|did (we|i)|we|conversation|chat(ted)?)\\b",
)
