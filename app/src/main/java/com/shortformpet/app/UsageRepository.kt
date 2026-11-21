package com.shortformpet.app

import java.time.LocalDate
import java.util.UUID

/** 약속 시간표 한 칸. 분 단위(0~1439). end가 start보다 작으면 자정을 넘깁니다. */
data class ScheduleSlot(val startMin: Int, val endMin: Int) {
    fun contains(minuteOfDay: Int): Boolean =
        if (startMin <= endMin) minuteOfDay in startMin until endMin
        else minuteOfDay >= startMin || minuteOfDay < endMin

    fun label(): String = "%d:%02d ~ %d:%02d".format(startMin / 60, startMin % 60, endMin / 60, endMin % 60)

    fun encode() = "$startMin-$endMin"

    companion object {
        fun decode(s: String): ScheduleSlot? {
            val p = s.split("-")
            val a = p.getOrNull(0)?.toIntOrNull() ?: return null
            val b = p.getOrNull(1)?.toIntOrNull() ?: return null
            if (a !in 0..1439 || b !in 0..1439 || a == b) return null
            return ScheduleSlot(a, b)
        }
    }
}

/**
 * 시청 시간과 설정 저장소. 기기 내부에 먼저 저장합니다.
 *
 * usage 키 형식 (날짜는 새벽 5시 기준)
 *  - u_2026-11-14_YOUTUBE_SHORTS : 그날 해당 앱 시청 ms (휴식 쿠폰으로 본 시간 포함)
 *  - x_2026-11-14                : 그날 휴식 쿠폰을 켜고 본 ms (순수 시청 t에서 빠짐)
 *  - g_2026-11-14                : 그날의 목표 G(분) 스냅샷
 *  - tracked_2026-11-14          : 그날 측정 서비스가 켜져 있었는지
 *  - tms_2026-11-14              : 그날 측정 서비스가 켜져 있던 총 ms
 *  - alertmin_2026-11-14         : 그날 마지막으로 알린 누적 알림 기준(분)
 *  - n_2026-11-14_<이름>          : 그날 횟수 기록 (알림, 진입 안내, 앱 실행 등)
 */
class UsageRepository(private val stores: Stores) {

    private val usage = stores.usage
    private val settings = stores.settings

    private fun usageKey(day: LocalDate, app: ShortFormApp) = "u_${day}_${app.name}"

    /** [exemptMs] = 그중 휴식 쿠폰이 켜져 있던 동안 본 ms */
    fun addWatch(day: LocalDate, app: ShortFormApp, ms: Long, exemptMs: Long) {
        if (ms <= 0) return
        val key = usageKey(day, app)
        val ex = exemptMs.coerceIn(0L, ms)
        usage.edit {
            putLong(key, usage.getLong(key, 0L) + ms)
            if (ex > 0) putLong("x_$day", usage.getLong("x_$day", 0L) + ex)
            putBoolean("tracked_$day", true)
        }
        snapshotGoal(day)
    }

    fun millis(day: LocalDate, app: ShortFormApp): Long = usage.getLong(usageKey(day, app), 0L)

    /** 기록상 총 시청 시간 (쿠폰으로 본 시간 포함) */
    fun totalMillis(day: LocalDate): Long = ShortFormApp.entries.sumOf { millis(day, it) }

    fun exemptMillis(day: LocalDate): Long = usage.getLong("x_$day", 0L)

    /** 순수 시청 t = 총 시청 − 휴식 쿠폰 시청. 목표·행복도·정산·알림 판단에 씁니다. */
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
        snapshotGoal(day)
    }

    fun trackedMillis(day: LocalDate): Long = usage.getLong("tms_$day", 0L)

    fun isTracked(day: LocalDate): Boolean = usage.getBoolean("tracked_$day", false)

    // ---- 목표 스냅샷 ----

    private fun snapshotGoal(day: LocalDate) {
        if (usage.getInt("g_$day", 0) == 0) usage.edit { putInt("g_$day", dailyGoalMinutes) }
    }

    /** 그날 적용된 목표. 기록이 없으면 지금 목표 */
    fun goalFor(day: LocalDate): Int = usage.getInt("g_$day", 0).takeIf { it > 0 } ?: dailyGoalMinutes

    // ---- 단계별 누적 알림 ----

    fun lastAlertMinutes(day: LocalDate): Int = usage.getInt("alertmin_$day", 0)

    fun setLastAlertMinutes(day: LocalDate, minutes: Int) = usage.edit { putInt("alertmin_$day", minutes) }

    // ---- 횟수 기록 ----

    fun increment(day: LocalDate, name: String) {
        val key = "n_${day}_$name"
        usage.edit { putInt(key, usage.getInt(key, 0) + 1) }
    }

    fun count(day: LocalDate, name: String): Int = usage.getInt("n_${day}_$name", 0)

    /** 60일보다 오래된 기록 정리. 아직 서버로 못 보낸 날은 남겨 둡니다. */
    fun cleanup(today: LocalDate) {
        val limit = today.minusDays(KEEP_DAYS)
        val pending = SyncManager(stores).pendingDays().minOrNull()
        val effective = if (pending != null && pending.isBefore(limit)) pending else limit
        stores.usage.removeDatedKeysBefore(effective)
        stores.study.removeDatedKeysBefore(effective)
        stores.pet.removeDatedKeysBefore(effective)
    }

    // ---- 설정 ----

    /** 하루 목표 G(분). 30~60분, 10분 단위, 기본 60분 */
    var dailyGoalMinutes: Int
        get() = (settings.getInt("goal_minutes", GOAL_MAX).coerceIn(GOAL_MIN, GOAL_MAX) / 10) * 10
        set(value) {
            val v = value.coerceIn(GOAL_MIN, GOAL_MAX)
            settings.edit { putInt("goal_minutes", v) }
            // 오늘 목표도 함께 바꿉니다(정산은 그날 마지막 목표로).
            usage.edit { putInt("g_${DayClock.logicalDay()}", v) }
        }

    /** 연속 시청 알림 주기(분). 1~45분, 기본 20분 */
    var continuousAlertMinutes: Int
        get() = settings.getInt("continuous_minutes", 20).coerceIn(1, 45)
        set(value) = settings.edit { putInt("continuous_minutes", value.coerceIn(1, 45)) }

    /** 집중 세션 길이(분): 5, 25, 50 */
    var sessionMinutes: Int
        get() = settings.getInt("session_minutes", 25).let { if (it in SESSION_LENGTHS) it else 25 }
        set(value) = settings.edit { putInt("session_minutes", if (value in SESSION_LENGTHS) value else 25) }

    /** 목표 초과·시간표 3초 진입 안내 */
    var entryGuideEnabled: Boolean
        get() = settings.getBoolean("entry_guide", true)
        set(value) = settings.edit { putBoolean("entry_guide", value) }

    /** 오전 일일 집중 쪽지 */
    var morningNoteEnabled: Boolean
        get() = settings.getBoolean("morning_note", true)
        set(value) = settings.edit { putBoolean("morning_note", value) }

    /** 빼꼼 모찌 (숏폼 화면 가장자리) */
    var peekEnabled: Boolean
        get() = settings.getBoolean("peek", true)
        set(value) = settings.edit { putBoolean("peek", value) }

    /** 빼꼼 모찌 홈 화면 산책 */
    var homeWalkEnabled: Boolean
        get() = settings.getBoolean("home_walk", false)
        set(value) = settings.edit { putBoolean("home_walk", value) }

    /** 행복도에 따라 앱 아이콘 바꾸기 */
    var dynamicIconEnabled: Boolean
        get() = settings.getBoolean("dynamic_icon", true)
        set(value) = settings.edit { putBoolean("dynamic_icon", value) }

    /** 하루 첫 방문 연속일수 화면 */
    var streakScreenEnabled: Boolean
        get() = settings.getBoolean("streak_screen", true)
        set(value) = settings.edit { putBoolean("streak_screen", value) }

    /** 사용 설명서를 봤는지 */
    var manualSeen: Boolean
        get() = settings.getBoolean("manual_seen", false)
        set(value) = settings.edit { putBoolean("manual_seen", value) }

    /** 빼꼼 모찌 세로 위치(화면 높이 비율) */
    var peekY: Float
        get() = settings.getInt("peek_y_permille", 420) / 1000f
        set(value) = settings.edit { putInt("peek_y_permille", (value.coerceIn(0.1f, 0.85f) * 1000).toInt()) }

    /** 홈 화면 산책 모찌: 구석에 숨은 쪽(-1 왼쪽, 1 오른쪽, 0 돌아다님) */
    var homePeekSide: Int
        get() = settings.getInt("home_peek_side", 0).coerceIn(-1, 1)
        set(value) = settings.edit { putInt("home_peek_side", value.coerceIn(-1, 1)) }

    /** 홈 화면 산책 모찌 위치(화면 너비·높이 비율) */
    var homeX: Float
        get() = settings.getInt("home_x_permille", 500) / 1000f
        set(value) = settings.edit { putInt("home_x_permille", (value.coerceIn(0f, 1f) * 1000).toInt()) }
    var homeY: Float
        get() = settings.getInt("home_y_permille", 600) / 1000f
        set(value) = settings.edit { putInt("home_y_permille", (value.coerceIn(0f, 1f) * 1000).toInt()) }

    // ---- 약속 시간표 (최대 3칸) ----

    fun scheduleSlots(): List<ScheduleSlot> =
        (settings.getString("schedule", "") ?: "").split(",").mapNotNull { ScheduleSlot.decode(it) }.take(MAX_SLOTS)

    fun addScheduleSlot(slot: ScheduleSlot): Boolean {
        val list = scheduleSlots()
        if (list.size >= MAX_SLOTS) return false
        settings.edit { putString("schedule", (list + slot).joinToString(",") { it.encode() }) }
        return true
    }

    fun removeScheduleSlot(index: Int) {
        val list = scheduleSlots().toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        settings.edit { putString("schedule", list.joinToString(",") { it.encode() }) }
    }

    fun inSchedule(millis: Long = Now.ms()): Boolean {
        val z = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
        val m = z.hour * 60 + z.minute
        return scheduleSlots().any { it.contains(m) }
    }

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
        const val GOAL_MAX = 60
        val GOAL_OPTIONS = listOf(30, 40, 50, 60)
        val SESSION_LENGTHS = listOf(5, 25, 50)
        val CONTINUOUS_OPTIONS = listOf(1, 5, 10, 15, 20, 25, 30, 35, 40, 45)
        const val MAX_SLOTS = 3
        const val KEEP_DAYS = 60L
        const val CONSENT_VERSION = 3

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
