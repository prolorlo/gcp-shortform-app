package com.shortformpet.app

import java.time.LocalDate
import java.util.UUID

/**
 * 시청 시간과 설정 저장소. 기기 내부에 먼저 저장합니다.
 *
 * usage 키 형식 (날짜는 새벽 5시 기준)
 *  - u_2026-11-14_YOUTUBE_SHORTS : 그날 해당 앱 시청 ms (휴식 쿠폰으로 본 시간 포함)
 *  - x_2026-11-14                : 그날 휴식 쿠폰을 켜고 본 ms (기분·경험치·체력·목표 판정에서 제외)
 *  - tracked_2026-11-14          : 그날 측정 서비스가 켜져 있었는지
 *  - tms_2026-11-14              : 그날 측정 서비스가 켜져 있던 총 ms
 *  - alertmin_2026-11-14         : 그날 마지막으로 알린 누적 알림 기준(분)
 *  - n_2026-11-14_<이름>          : 그날 횟수 기록 (알림, 진입 안내, 앱 실행 등)
 */
class UsageRepository(private val stores: Stores) {

    private val usage = stores.usage
    private val settings = stores.settings

    private fun usageKey(day: LocalDate, app: ShortFormApp) = "u_${day}_${app.name}"

    /** [exempt] = 휴식 쿠폰을 켠 상태에서 본 시간 */
    fun addWatch(day: LocalDate, app: ShortFormApp, ms: Long, exempt: Boolean) {
        if (ms <= 0) return
        val key = usageKey(day, app)
        usage.edit {
            putLong(key, usage.getLong(key, 0L) + ms)
            if (exempt) putLong("x_$day", usage.getLong("x_$day", 0L) + ms)
            putBoolean("tracked_$day", true)
        }
    }

    fun millis(day: LocalDate, app: ShortFormApp): Long = usage.getLong(usageKey(day, app), 0L)

    /** 기록상 총 시청 시간 (쿠폰으로 본 시간 포함) */
    fun totalMillis(day: LocalDate): Long = ShortFormApp.entries.sumOf { millis(day, it) }

    fun exemptMillis(day: LocalDate): Long = usage.getLong("x_$day", 0L)

    /** 목표·기분·정산에 반영되는 시청 시간 (총 시청 − 휴식 쿠폰 시청) */
    fun countedMillis(day: LocalDate): Long = (totalMillis(day) - exemptMillis(day)).coerceAtLeast(0L)

    fun countedMinutes(day: LocalDate): Double = countedMillis(day) / 60_000.0

    // ---- 측정 여부 ----

    fun addTracked(day: LocalDate, ms: Long) {
        if (ms <= 0) return
        usage.edit {
            putLong("tms_$day", usage.getLong("tms_$day", 0L) + ms)
            putBoolean("tracked_$day", true)
        }
    }

    fun markTracked(day: LocalDate) {
        if (!isTracked(day)) usage.edit { putBoolean("tracked_$day", true) }
    }

    fun trackedMillis(day: LocalDate): Long = usage.getLong("tms_$day", 0L)

    fun isTracked(day: LocalDate): Boolean = usage.getBoolean("tracked_$day", false)

    // ---- 단계별 누적 알림 ----

    fun lastAlertMinutes(day: LocalDate): Int = usage.getInt("alertmin_$day", 0)

    fun setLastAlertMinutes(day: LocalDate, minutes: Int) = usage.edit { putInt("alertmin_$day", minutes) }

    // ---- 횟수 기록 ----

    fun increment(day: LocalDate, name: String) {
        val key = "n_${day}_$name"
        usage.edit { putInt(key, usage.getInt(key, 0) + 1) }
    }

    fun count(day: LocalDate, name: String): Int = usage.getInt("n_${day}_$name", 0)

    /** 60일보다 오래된 기록 정리 */
    fun cleanup(today: LocalDate) {
        val limit = today.minusDays(KEEP_DAYS)
        stores.usage.removeDatedKeysBefore(limit)
        stores.study.removeDatedKeysBefore(limit)
        stores.pet.removeDatedKeysBefore(limit)
    }

    // ---- 설정 ----

    /** 하루 목표 시청 시간(분). 30~120분, 30분 단위, 기본 60분 */
    var dailyGoalMinutes: Int
        get() = settings.getInt("goal_minutes", 60).coerceIn(GOAL_MIN, GOAL_MAX)
        set(value) = settings.edit { putInt("goal_minutes", value.coerceIn(GOAL_MIN, GOAL_MAX)) }

    /** 공부 세션 길이(분): 25 또는 50 */
    var sessionMinutes: Int
        get() = if (settings.getInt("session_minutes", 25) == 50) 50 else 25
        set(value) = settings.edit { putInt("session_minutes", if (value == 50) 50 else 25) }

    /** 목표 초과 시 진입 안내 화면 표시 여부 */
    var entryGuideEnabled: Boolean
        get() = settings.getBoolean("entry_guide", true)
        set(value) = settings.edit { putBoolean("entry_guide", value) }

    /** 수집 항목이 바뀌면 CONSENT_VERSION을 올려 다시 동의를 받습니다. */
    var consentAccepted: Boolean
        get() = settings.getInt("consent_version", 0) >= CONSENT_VERSION
        set(value) = settings.edit { putInt("consent_version", if (value) CONSENT_VERSION else 0) }

    /** 설치 시 한 번 만드는 무작위 익명 ID (이름·이메일과 무관) */
    val anonId: String
        get() {
            settings.getString("anon_id", null)?.let { return it }
            val id = UUID.randomUUID().toString()
            settings.edit { putString("anon_id", id) }
            return id
        }

    companion object {
        const val GOAL_MIN = 30
        const val GOAL_MAX = 120
        const val KEEP_DAYS = 60L
        const val CONSENT_VERSION = 2

        /**
         * 누적 알림 기준(분): 목표 전에는 30분마다, 목표를 넘긴 뒤에는 15분마다.
         * 지금까지 본 시간이 넘어선 가장 큰 기준을 돌려줍니다. (아직 30분 미만이면 0)
         */
        fun alertThreshold(countedMinutes: Int, goalMinutes: Int): Int = when {
            countedMinutes < goalMinutes -> countedMinutes / 30 * 30
            else -> goalMinutes + (countedMinutes - goalMinutes) / 15 * 15
        }
    }
}
