package com.promenar.infinity.domain.media

import java.math.BigDecimal
import kotlin.math.ceil

data class KeyframePlan(val timestampsSeconds: List<Double>, val note: String = "")

/** 只计算时间戳，不读取视频；有限次计算防止极小间隔产生巨大列表。 */
fun planKeyframes(
    durationSeconds: Double?,
    frames: Int? = null,
    intervalSeconds: Double? = null,
    startSeconds: Double? = null,
    endSeconds: Double? = null,
): KeyframePlan {
    require(frames == null || intervalSeconds == null) { "frames 与 interval_seconds 不能同时提供。" }
    require(frames == null || frames > 0) { "frames 必须是正整数。" }
    require(intervalSeconds == null || intervalSeconds.isFinite() && intervalSeconds > 0) { "interval_seconds 必须是正有限数。" }
    require(listOfNotNull(startSeconds, endSeconds).all(Double::isFinite)) { "时间范围必须是有限数。" }
    val duration = durationSeconds?.takeIf { it.isFinite() && it > 0 }
    val start = (startSeconds ?: 0.0).coerceAtLeast(0.0).let { if (duration == null) it else it.coerceAtMost(duration) }
    if (duration == null) {
        require(intervalSeconds == null && (frames ?: 8) <= 8) { "时长未知时只接受最多 8 帧的保守请求，不能指定 interval_seconds。" }
        require(endSeconds == null || start < endSeconds.coerceAtLeast(0.0)) { "时间范围无效：start_seconds 必须小于 end_seconds。" }
        return KeyframePlan(listOf(start), "时长未知，仅尝试起点首帧；未推断视频持续时长。")
    }
    val end = (endSeconds ?: duration).coerceIn(0.0, duration)
    require(start < end) { "时间范围无效：start_seconds 必须小于 end_seconds。" }
    val count = frames ?: if (intervalSeconds == null) 8 else ceil((end - start) / intervalSeconds).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    val timestamps = (0 until minOf(count, 32)).map { index ->
        if (intervalSeconds != null) start + index * intervalSeconds else start + (index + 0.5) * ((end - start) / count)
    }.filter { it < end }
    val resumeAt = if (intervalSeconds != null) start + timestamps.size * intervalSeconds else start + timestamps.size * ((end - start) / count)
    val note = if (count > 32) "超过单次 32 帧上限，已保留前 32 帧；请从 t=${BigDecimal.valueOf(resumeAt).stripTrailingZeros().toPlainString()}s 继续查看。" else ""
    return KeyframePlan(timestamps, note)
}
