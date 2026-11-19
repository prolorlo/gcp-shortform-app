package com.shortformpet.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * "하루"의 기준을 새벽 5시로 바꿔 주는 시계.
 *
 * 오전 5:00 ~ 다음날 오전 4:59:59 를 하나의 날짜로 봅니다.
 * 예) 11월 7일 새벽 2시에 본 쇼츠 → 11월 6일 기록.
 * 저장소 키가 이 날짜로 정해지므로, 5시가 지나면 "오늘 누적"은 자동으로 0부터 시작합니다.
 */
object DayClock {
    const val RESET_HOUR = 5

    fun logicalDay(millis: Long = Now.ms()): LocalDate =
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .minusHours(RESET_HOUR.toLong())
            .toLocalDate()

    /** 다음 새벽 5시(초기화 시각)의 epoch millis. */
    fun nextResetMillis(now: Long = Now.ms()): Long {
        val zone = ZoneId.systemDefault()
        val current = Instant.ofEpochMilli(now).atZone(zone)
        var next = current.toLocalDate().atTime(RESET_HOUR, 0).atZone(zone)
        if (!next.isAfter(current)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }

    /** 그 날짜(새벽 5시 기준)가 시작된 시각 */
    fun startOfDayMillis(day: LocalDate): Long =
        day.atTime(RESET_HOUR, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 23:30~04:59: 할 일 대신 수면 권유 문구를 쓰고, 모찌가 졸린 시간대 */
    fun isLateNight(millis: Long = Now.ms()): Boolean {
        val z = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return z.hour < RESET_HOUR || (z.hour == 23 && z.minute >= 30)
    }

    /** 0~23시 */
    fun hour(millis: Long = Now.ms()): Int = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).hour

    /** 다음 오전 [hour]시의 epoch millis (오전 쪽지 알람용) */
    fun nextAtHour(hour: Int, now: Long = Now.ms()): Long {
        val zone = ZoneId.systemDefault()
        val current = Instant.ofEpochMilli(now).atZone(zone)
        var next = current.toLocalDate().atTime(hour, 0).atZone(zone)
        if (!next.isAfter(current)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }
}

/** 1시간 30분 / 45분 / 0분 */
fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}시간 ${m}분"
        h > 0 -> "${h}시간"
        else -> "${m}분"
    }
}

fun formatMinutes(minutes: Int): String = formatDuration(minutes * 60_000L)

/** 24:05 형식 (타이머용) */
fun formatClock(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
