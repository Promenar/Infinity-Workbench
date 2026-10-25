package com.promenar.nexara.data.linux

import java.time.Instant
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneOffsetTransitionRule.TimeDefinition
import java.time.zone.ZoneRules
import kotlin.math.abs

/** minirootfs 不依赖 zoneinfo；无法准确表达的规则只采用启动时的固定偏移。 */
internal object LinuxLaunchTimezone {
    fun posix(rules: ZoneRules, now: Instant): String {
        val current = rules.getOffset(now)
        val fallback = fixed(current)
        val recurring = rules.transitionRules
        if (recurring.size != 2) return fallback
        val standard = recurring.first().standardOffset
        if (recurring.any { it.standardOffset != standard } || rules.getStandardOffset(now) != standard) return fallback
        val start = recurring.singleOrNull { it.offsetBefore == standard && it.offsetAfter != standard } ?: return fallback
        val daylight = start.offsetAfter
        val end = recurring.singleOrNull { it.offsetBefore == daylight && it.offsetAfter == standard } ?: return fallback
        val startRule = transition(start) ?: return fallback
        val endRule = transition(end) ?: return fallback
        // 最终年循环规则可能尚未生效，历史或临时规则不能被误套成循环规则。
        val year = now.atOffset(current).year
        val transitions = (year - 1..year + 1).flatMap { y -> recurring.map { it.createTransition(y) } }.sortedBy { it.instant }
        if (transitions.lastOrNull { it.instant <= now }?.offsetAfter != current) return fallback
        var cursor = now
        for (expected in transitions.filter { it.instant > now }.take(2)) {
            if (rules.nextTransition(cursor) != expected) return fallback
            cursor = expected.instant
        }
        return "STD${offset(-standard.totalSeconds)}DST${offset(-daylight.totalSeconds)},$startRule,$endRule"
    }

    private fun transition(rule: ZoneOffsetTransitionRule): String? {
        val weekday = rule.dayOfWeek ?: return null
        val day = rule.dayOfMonthIndicator
        val month = rule.month
        val week = when {
            day == -1 -> 5
            day in 1..22 && (day - 1) % 7 == 0 -> (day - 1) / 7 + 1
            month.minLength() == month.maxLength() && day == month.maxLength() - 6 -> 5
            else -> return null
        }
        val correction = when (rule.timeDefinition) {
            TimeDefinition.UTC -> rule.offsetBefore.totalSeconds
            TimeDefinition.STANDARD -> rule.offsetBefore.totalSeconds - rule.standardOffset.totalSeconds
            TimeDefinition.WALL -> 0
        }
        val seconds = rule.localTime.toSecondOfDay() + (if (rule.isMidnightEndOfDay) 86400 else 0) + correction
        if (seconds !in 0..86400) return null
        return "M${month.value}.$week.${weekday.value % 7}/${offset(seconds)}"
    }

    private fun fixed(value: ZoneOffset): String {
        val seconds = value.totalSeconds
        if (seconds == 0) return "UTC0"
        val digits = offset(abs(seconds)).split(':').let { parts ->
            parts.first().padStart(2, '0') + parts.drop(1).joinToString("")
        }
        return "<${if (seconds > 0) "+" else "-"}$digits>${offset(-seconds)}"
    }

    private fun offset(seconds: Int): String {
        val total = abs(seconds)
        val minutes = total / 60 % 60
        val remainder = total % 60
        return buildString {
            if (seconds < 0) append('-')
            append(total / 3600)
            if (minutes != 0 || remainder != 0) append(":${minutes.toString().padStart(2, '0')}")
            if (remainder != 0) append(":${remainder.toString().padStart(2, '0')}")
        }
    }
}
