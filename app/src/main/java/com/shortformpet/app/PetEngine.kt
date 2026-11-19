package com.shortformpet.app

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 베타 사용 기간(5일)에 맞춘 성장 기준 */
enum class PetStage(val label: String, val minExp: Int) {
    EGG("알", 0),
    BABY("아기", 10),
    CHILD("어린이", 100),
    TEEN("청소년", 220),
    ADULT("어른", 400);

    val next: PetStage? get() = entries.getOrNull(ordinal + 1)
    val previous: PetStage? get() = entries.getOrNull(ordinal - 1)

    companion object {
        fun of(exp: Int): PetStage = entries.last { exp >= it.minExp }
    }
}

enum class Mood(val label: String) {
    HAPPY("행복"),
    OKAY("보통"),
    SULKY("시무룩"),
    SICK("아픔");

    companion object {
        fun of(score: Int): Mood = when {
            score >= 70 -> HAPPY
            score >= 45 -> OKAY
            score >= 25 -> SULKY
            else -> SICK
        }
    }
}

data class PetState(
    val name: String,
    val exp: Int,
    val health: Int,
    val stage: PetStage,
    val mood: Mood,
    val moodScore: Int,
    /** 목표·기분에 반영되는 오늘 시청(분). 휴식 쿠폰으로 본 시간은 빠져 있습니다. */
    val todayMinutes: Double,
    val goalMinutes: Int,
    val fedToday: Boolean,
    val lastReport: String?,
    val streak: Int,
    val bestStreak: Int,
    val shields: Int,
    val shieldProgress: Int,
    /** 0 없음, 1 리본(7일), 2 목도리(30일), 3 별 장식(100일) */
    val accessoryLevel: Int,
    val snacksAvailable: Int,
) {
    /** 다음 단계까지 진행률 0.0~1.0 (어른이면 1.0) */
    val stageProgress: Float
        get() {
            val next = stage.next ?: return 1f
            return ((exp - stage.minExp).toFloat() / (next.minExp - stage.minExp)).coerceIn(0f, 1f)
        }
}

data class SettleResult(
    val day: LocalDate,
    val minutes: Int,
    val gain: Int,
    val healthDelta: Int,
    val evolvedTo: PetStage?,
    val devolvedTo: PetStage?,
    val streak: Int,
    val shieldUsed: Boolean,
    val streakBroken: Boolean,
    val message: String,
)

/**
 * 다마고치 규칙 엔진.
 *
 *   점수(W) = 100 × 60 / (W + 60)      W = 그날 숏폼 시청 분 (휴식 쿠폰으로 본 시간 제외)
 *
 * - 실시간 기분: 오늘 W로 계속 계산 (0분=100, 60분=50, 120분≈33)
 * - 새벽 5시 정산: 전날 점수만큼 경험치 증가 (체력 30 미만이면 절반)
 * - 체력: 목표 이하 +10, 초과 시 30분마다 -5 (하루 최대 -30)
 * - 체력 0: 한 단계 작아지고 체력 50으로 회복
 * - 연속일수: 목표 이하인 날 +1, 넘긴 날 0. 30분 이내 초과는 보호권으로 유지(이틀 연속 불가)
 * - 측정 서비스가 꺼져 있던 날은 정산하지 않음 (연속일수도 늘지도 끊기지도 않음)
 *
 * 경험치·체력·연속일수는 모두 저장소(pet)에 기록되어 앱을 껐다 켜도 유지됩니다.
 */
class PetEngine(stores: Stores) {

    private val prefs = stores.pet
    private val repo = UsageRepository(stores)
    private val study = StudyManager(stores)
    private val syncQueue = SyncManager(stores)

    var name: String
        get() = prefs.getString("name", DEFAULT_NAME) ?: DEFAULT_NAME
        set(value) = prefs.edit { putString("name", value.trim().take(10).ifEmpty { DEFAULT_NAME }) }

    private var exp: Int
        get() = prefs.getInt("exp", 0)
        set(value) = prefs.edit { putInt("exp", value.coerceAtLeast(0)) }

    private var health: Int
        get() = prefs.getInt("health", 80)
        set(value) = prefs.edit { putInt("health", value.coerceIn(0, 100)) }

    private var streak: Int
        get() = prefs.getInt("streak", 0)
        set(value) = prefs.edit {
            putInt("streak", value)
            if (value > prefs.getInt("streak_best", 0)) putInt("streak_best", value)
        }

    val accessoryLevel: Int get() = prefs.getInt("acc_level", 0)

    fun state(): PetState {
        catchUp()
        val now = Now.ms()
        val today = DayClock.logicalDay(now)
        val minutes = repo.countedMinutes(today)
        val score = score(minutes)
        val e = exp
        return PetState(
            name = name,
            exp = e,
            health = health,
            stage = PetStage.of(e),
            mood = Mood.of(score),
            moodScore = score,
            todayMinutes = minutes,
            goalMinutes = repo.dailyGoalMinutes,
            fedToday = prefs.getString("fed_day", null) == today.toString(),
            lastReport = prefs.getString("last_report", null),
            streak = streak,
            bestStreak = prefs.getInt("streak_best", 0),
            shields = study.shields(),
            shieldProgress = study.shieldProgress(),
            accessoryLevel = accessoryLevel,
            snacksAvailable = study.snacksAvailable(now),
        )
    }

    /**
     * 아직 정산하지 않은 지난 날들을 정산합니다. (오늘은 진행 중이라 제외)
     * 서비스·알람·화면 어디서 먼저 불러도 날짜마다 한 번만 정산됩니다.
     * 정산한 날은 서버 동기화 대기 목록에 올립니다.
     */
    fun catchUp(): List<SettleResult> {
        val today = DayClock.logicalDay()
        val yesterday = today.minusDays(1)
        val lastStr = prefs.getString("last_settled", null)
        if (lastStr == null) {
            // 첫 실행: 오늘부터 키우기 시작
            prefs.edit { putString("last_settled", yesterday.toString()) }
            return emptyList()
        }
        var day = LocalDate.parse(lastStr).plusDays(1)
        if (!day.isBefore(today)) return emptyList()
        if (day.isBefore(today.minusDays(MAX_CATCH_UP_DAYS))) day = today.minusDays(MAX_CATCH_UP_DAYS)

        val results = mutableListOf<SettleResult>()
        while (!day.isAfter(yesterday)) {
            if (repo.isTracked(day)) results += settle(day)
            // 그날 정산 직후의 캐릭터 상태를 남겨 두었다가 동기화 때 함께 보냅니다.
            val snapshot = listOf(PetStage.of(exp).name, health, streak, study.shields(), repo.dailyGoalMinutes)
                .joinToString("|")
            val settled = day
            prefs.edit {
                putString("snap_$settled", snapshot)
                putString("last_settled", settled.toString())
            }
            syncQueue.enqueue(day)
            day = day.plusDays(1)
        }

        if (results.isNotEmpty()) {
            prefs.edit { putString("last_report", results.last().message) }
            repo.cleanup(today)
        }
        return results
    }

    private fun settle(day: LocalDate): SettleResult {
        val minutes = repo.countedMinutes(day)
        val goal = repo.dailyGoalMinutes
        val beforeStage = PetStage.of(exp)

        // 경험치
        var gain = score(minutes)
        if (health < 30) gain /= 2
        exp += gain

        // 체력
        val healthDelta = if (minutes <= goal) {
            10
        } else {
            -(5 * ceil((minutes - goal) / 30.0).toInt()).coerceAtMost(30)
        }
        health += healthDelta

        var devolvedTo: PetStage? = null
        if (health <= 0) {
            val target = PetStage.of(exp).previous ?: PetStage.EGG
            exp = target.minExp
            health = 50
            devolvedTo = target
        }
        val afterStage = PetStage.of(exp)
        val evolvedTo = if (devolvedTo == null && afterStage.ordinal > beforeStage.ordinal) afterStage else null

        // 연속일수
        val before = streak
        var shieldUsed = false
        var broken = false
        var milestone: String? = null
        if (minutes <= goal) {
            streak = before + 1
            val level = when (before + 1) {
                7 -> 1
                30 -> 2
                100 -> 3
                else -> 0
            }
            if (level > accessoryLevel) {
                prefs.edit { putInt("acc_level", level) }
                milestone = Lines.settleMilestone(before + 1, name, ACCESSORY_NAMES[level])
            }
        } else {
            val usedYesterday = prefs.getString("last_shield_day", null) == day.minusDays(1).toString()
            if (before > 0 && minutes - goal <= SHIELD_MAX_OVER_MINUTES && !usedYesterday && study.useShield()) {
                shieldUsed = true
                prefs.edit { putString("last_shield_day", day.toString()) }
            } else {
                broken = before > 0
                streak = 0
            }
        }

        val m = minutes.roundToInt()
        val message = buildString {
            append(Lines.settleHead(day.monthValue, day.dayOfMonth, formatMinutes(m), gain))
            append(Lines.settleHealth(healthDelta))
            if (streak > 0 && !shieldUsed) append(Lines.settleStreak(streak))
            if (evolvedTo != null) append(Lines.settleEvolved(name, evolvedTo.label))
            if (devolvedTo != null) append(Lines.settleDevolved(name, devolvedTo.label))
            if (milestone != null) append(milestone)
            if (shieldUsed) append(Lines.settleShieldUsed(streak))
            if (broken) append(Lines.SETTLE_STREAK_BROKEN)
        }
        return SettleResult(day, m, gain, healthDelta, evolvedTo, devolvedTo, streak, shieldUsed, broken, message)
    }

    /** 하루 한 번 밥 주기. 오늘 시청이 목표 이하일 때만 +5 경험치 */
    fun feed(): String {
        val today = DayClock.logicalDay()
        if (prefs.getString("fed_day", null) == today.toString()) {
            return Lines.FEED_ALREADY
        }
        val minutes = repo.countedMinutes(today)
        if (minutes > repo.dailyGoalMinutes) {
            return Lines.feedFull(formatMinutes(minutes.toInt()))
        }
        exp += FEED_EXP
        prefs.edit { putString("fed_day", today.toString()) }
        return Lines.feedOk(FEED_EXP)
    }

    /** 할 일을 끝내고 받은 간식 주기. 시청 시간과 관계없이 +5 경험치 (하루 최대 3번) */
    fun giveSnack(): String {
        if (!study.consumeSnack()) return Lines.SNACK_NONE
        exp += SNACK_EXP
        return Lines.snackOk(SNACK_EXP)
    }

    companion object {
        const val DEFAULT_NAME = "모찌"
        const val FEED_EXP = 5
        const val SNACK_EXP = 5
        const val SHIELD_MAX_OVER_MINUTES = 30.0
        private const val MAX_CATCH_UP_DAYS = 60L
        val ACCESSORY_NAMES = listOf("", "리본", "목도리", "별 장식")

        fun score(minutes: Double): Int = (100.0 * 60.0 / (minutes.coerceAtLeast(0.0) + 60.0)).roundToInt()

        /** 대사는 Lines.kt에 있습니다 */
        fun speech(mood: Mood, stage: PetStage): String = Lines.idle(mood, stage)
    }
}
