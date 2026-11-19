package com.shortformpet.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** 누적 경험치 5단계 */
enum class PetStage(val label: String, val minExp: Int) {
    EGG("알", 0),
    BABY("아기", 10),
    CHILD("어린이", 110),
    TEEN("청소년", 220),
    ADULT("어른", 350);

    val next: PetStage? get() = entries.getOrNull(ordinal + 1)

    companion object {
        fun of(exp: Int): PetStage = entries.last { exp >= it.minExp }
    }
}

/** 행복도 H 5단계 표정. 앱 아이콘도 이 단계를 따릅니다. */
enum class Mood(val label: String, val bucket: Int) {
    JOYFUL("신남", 1),
    SMILE("기분 좋음", 2),
    HUNGRY("배고픔", 3),
    SULKY("시무룩", 4),
    SICK("아픔", 5);

    companion object {
        fun of(h: Int): Mood = when {
            h >= 70 -> JOYFUL
            h >= 40 -> SMILE
            h >= 20 -> HUNGRY
            h >= 10 -> SULKY
            else -> SICK
        }
    }
}

data class PetState(
    val name: String,
    val exp: Int,
    /** 행복도 H 0~100. 알 단계에서는 100으로 고정 */
    val happiness: Int,
    val stage: PetStage,
    val mood: Mood,
    /** 순수 시청 t(분). 휴식 쿠폰으로 본 시간은 빠져 있습니다. */
    val todayMinutes: Double,
    val goalMinutes: Int,
    val fedToday: Boolean,
    /** 지금 시간대(05~15시 / 15~05시)에 H가 오르는 쓰다듬기 남은 횟수 */
    val petsLeft: Int,
    val lastReport: String?,
    val streak: Int,
    val bestStreak: Int,
    val shields: Int,
    val shieldProgress: Int,
    /** 0 없음, 1 리본(7일), 2 목도리(30일), 3 별 장식(100일) */
    val accessoryLevel: Int,
    val snacksAvailable: Int,
    /** 오늘 이대로 끝나면 새벽 정산에서 받을 경험치 */
    val expectedGain: Int,
    /** 0 방 안, 1 들판 */
    val scene: Int,
) {
    val isEgg: Boolean get() = stage == PetStage.EGG

    /** 누적 경험치 막대: 숫자(exp / 다음 단계 기준)와 같은 비율 */
    val expProgress: Float
        get() {
            val next = stage.next ?: return 1f
            return (exp.toFloat() / next.minExp.toFloat()).coerceIn(0f, 1f)
        }

    /** 알이 부화에 가까운 정도 (금이 가는 그림용) */
    val hatchProgress: Float get() = (exp.toFloat() / PetStage.BABY.minExp).coerceIn(0f, 1f)
}

data class SettleResult(
    val day: LocalDate,
    val minutes: Int,
    val gain: Int,
    val happinessBonus: Int,
    val evolvedTo: PetStage?,
    val streak: Int,
    val shieldUsed: Boolean,
    val streakBroken: Boolean,
    val message: String,
)

/**
 * 모찌 규칙 엔진 (README 도메인 규칙 2절).
 *
 * 행복도 H (0~100)
 *  - 실제 시간 20분마다 H − 1 (하루 −72)
 *  - 순수 시청 t가 목표 G를 넘으면 초과 5분마다 H − 1
 *  - 밥 던지기 명중 +20 (하루 1번), 쓰다듬기 +5 (05~15시 3번, 15~05시 3번), 간식 +15, 정산 때 연속일수 × 5
 *  - 알 단계에서는 변하지 않고, 부화하면 100에서 시작
 *
 * 새벽 5시 정산
 *  - 경험치 + round(100 × G / (t + G))   (t = 0 → +100, t = G → +50)
 *  - 연속일수: t ≤ G면 +1. G < t ≤ G + 30이면 보호권으로 지킴(이틀 연속 불가)
 *  - 측정 서비스가 꺼져 있던 날은 정산하지 않음
 *  - 성장했으면 그날(정산 직후의 하루) 앱을 처음 열 때 한 번만 성장 모션
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

    private var happiness: Int
        get() = prefs.getInt("happiness_h", 100)
        set(value) = prefs.edit { putInt("happiness_h", value.coerceIn(0, 100)) }

    private var streak: Int
        get() = prefs.getInt("streak", 0)
        set(value) = prefs.edit {
            putInt("streak", value)
            if (value > prefs.getInt("streak_best", 0)) putInt("streak_best", value)
        }

    val accessoryLevel: Int get() = prefs.getInt("acc_level", 0)

    /** 모찌 탭 배경: 0 방 안, 1 들판 */
    var scene: Int
        get() = prefs.getInt("scene", 0)
        set(value) = prefs.edit { putInt("scene", if (value == 1) 1 else 0) }

    private fun dayKey(day: LocalDate, name: String) = "p_${day}_$name"

    fun state(): PetState {
        catchUp()
        val now = Now.ms()
        applyDecay(now)
        val today = DayClock.logicalDay(now)
        val minutes = repo.countedMinutes(today)
        val goal = repo.goalFor(today)
        val e = exp
        val stage = PetStage.of(e)
        val h = if (stage == PetStage.EGG) 100 else happiness
        return PetState(
            name = name,
            exp = e,
            happiness = h,
            stage = stage,
            mood = Mood.of(h),
            todayMinutes = minutes,
            goalMinutes = goal,
            fedToday = prefs.getBoolean(dayKey(today, "fed"), false),
            petsLeft = (PETS_PER_HALF - prefs.getInt(dayKey(today, petHalfKey(now)), 0)).coerceAtLeast(0),
            lastReport = prefs.getString("last_report", null),
            streak = streak,
            bestStreak = prefs.getInt("streak_best", 0),
            shields = study.shields(),
            shieldProgress = study.shieldProgress(),
            accessoryLevel = accessoryLevel,
            snacksAvailable = study.snacksAvailable(now),
            expectedGain = expGain(minutes, goal),
            scene = scene,
        )
    }

    /**
     * 시간이 지나 줄어든 행복도를 반영합니다(20분마다 −1, 목표 초과 5분마다 −1).
     * 화면·서비스 어디서 불러도 지난 시각을 기준으로 한 번씩만 깎입니다.
     */
    fun applyDecay(now: Long = Now.ms()) {
        val last = prefs.getLong("h_last_ms", 0L)
        if (PetStage.of(exp) == PetStage.EGG || last <= 0L || now < last) {
            prefs.edit { putLong("h_last_ms", now) }
            return
        }
        val steps = ((now - last) / DECAY_MS).toInt()
        var h = happiness
        if (steps > 0) {
            h -= steps
            prefs.edit { putLong("h_last_ms", last + steps * DECAY_MS) }
        }
        // 오늘 목표 초과분 (5분마다 −1). 이미 깎은 만큼은 저장해 두고 차이만 반영합니다.
        val today = DayClock.logicalDay(now)
        val over = repo.countedMinutes(today) - repo.goalFor(today)
        val overSteps = if (over > 0) (over / OVER_STEP_MIN).toInt() else 0
        val applied = prefs.getInt(dayKey(today, "over"), 0)
        if (overSteps > applied) {
            h -= overSteps - applied
            prefs.edit { putInt(dayKey(today, "over"), overSteps) }
        }
        if (h != happiness) happiness = h
    }

    /**
     * 아직 정산하지 않은 지난 날들을 정산합니다. (오늘은 진행 중이라 제외)
     * 서비스·알람·화면 어디서 먼저 불러도 날짜마다 한 번만 정산됩니다.
     */
    fun catchUp(): List<SettleResult> {
        val today = DayClock.logicalDay()
        val yesterday = today.minusDays(1)
        val lastStr = prefs.getString("last_settled", null)
        if (lastStr == null) {
            prefs.edit { putString("last_settled", yesterday.toString()) }
            return emptyList()
        }
        var day = LocalDate.parse(lastStr).plusDays(1)
        if (!day.isBefore(today)) return emptyList()
        if (day.isBefore(today.minusDays(MAX_CATCH_UP_DAYS))) day = today.minusDays(MAX_CATCH_UP_DAYS)

        // 정산 전까지의 허기 감소를 먼저 반영합니다.
        applyDecay()
        val results = mutableListOf<SettleResult>()
        while (!day.isAfter(yesterday)) {
            if (repo.isTracked(day)) results += settle(day, today)
            val snapshot = listOf(PetStage.of(exp).name, happiness, streak, study.shields(), repo.goalFor(day))
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

    private fun settle(day: LocalDate, today: LocalDate): SettleResult {
        val minutes = repo.countedMinutes(day)
        val goal = repo.goalFor(day)
        val beforeStage = PetStage.of(exp)

        val gain = expGain(minutes, goal)
        exp += gain
        val afterStage = PetStage.of(exp)
        val evolvedTo = if (afterStage.ordinal > beforeStage.ordinal) afterStage else null
        if (evolvedTo != null) {
            prefs.edit {
                putString("evo_day", today.toString())
                putString("evo_stage", evolvedTo.name)
            }
            if (beforeStage == PetStage.EGG) {
                // 부화: 행복도 100에서 시작
                prefs.edit {
                    putInt("happiness_h", 100)
                    putLong("h_last_ms", Now.ms())
                }
            }
        }

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

        // 연속일수 보너스: H + 연속일수 × 5 (알 단계 제외)
        var bonus = 0
        if (afterStage != PetStage.EGG && streak > 0) {
            val beforeH = happiness
            happiness = beforeH + streak * STREAK_BONUS
            bonus = happiness - beforeH
        }

        val m = minutes.roundToInt()
        val message = buildString {
            append(Lines.settleHead(day.monthValue, day.dayOfMonth, formatMinutes(m), gain))
            if (streak > 0 && !shieldUsed) append(Lines.settleStreak(streak))
            if (bonus > 0) append(Lines.settleHappiness(bonus))
            if (evolvedTo != null) append(Lines.settleEvolved(name, evolvedTo.label))
            if (milestone != null) append(milestone)
            if (shieldUsed) append(Lines.settleShieldUsed(streak))
            if (broken) append(Lines.SETTLE_STREAK_BROKEN)
        }
        return SettleResult(day, m, gain, bonus, evolvedTo, streak, shieldUsed, broken, message)
    }

    // ------------------------------------------------------------ 돌보기

    /** 밥 던지기가 모찌에게 닿았을 때. 하루 1번 H + 20 */
    fun feed(): String {
        val now = Now.ms()
        val today = DayClock.logicalDay(now)
        if (PetStage.of(exp) == PetStage.EGG) return Lines.FEED_EGG
        if (prefs.getBoolean(dayKey(today, "fed"), false)) return Lines.FEED_ALREADY
        applyDecay(now)
        happiness += FEED_H
        prefs.edit { putBoolean(dayKey(today, "fed"), true) }
        return Lines.feedOk()
    }

    /** 쓰다듬기. 시간대마다 3번까지 H + 5, 그 뒤에는 반응만. 오른 H를 돌려줍니다. */
    fun pet(now: Long = Now.ms()): Int {
        if (PetStage.of(exp) == PetStage.EGG) return 0
        val today = DayClock.logicalDay(now)
        val key = dayKey(today, petHalfKey(now))
        val used = prefs.getInt(key, 0)
        if (used >= PETS_PER_HALF) return 0
        applyDecay(now)
        val before = happiness
        happiness = before + PET_H
        prefs.edit { putInt(key, used + 1) }
        return happiness - before
    }

    /** 할 일을 끝내고 받은 간식 주기. H + 15 (하루 최대 3번) */
    fun giveSnack(): String {
        if (PetStage.of(exp) == PetStage.EGG) return Lines.SNACK_EGG
        if (!study.consumeSnack()) return Lines.SNACK_NONE
        applyDecay()
        happiness += SNACK_H
        return Lines.snackOk()
    }

    // ------------------------------------------------------------ 성장 모션

    /** 오늘 성장한 단계가 있고 아직 모션을 안 보여 줬으면 그 단계. 보여 준 것으로 기록합니다. */
    fun takePendingEvolution(): PetStage? {
        val today = DayClock.logicalDay().toString()
        if (prefs.getString("evo_day", null) != today) return null
        if (prefs.getString("evo_shown", null) == today) return null
        prefs.edit { putString("evo_shown", today) }
        return try {
            PetStage.valueOf(prefs.getString("evo_stage", "") ?: "")
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ 개발자 테스트

    fun devAddHappiness(delta: Int) {
        applyDecay()
        happiness += delta
    }

    companion object {
        const val DEFAULT_NAME = "모찌"
        const val FEED_H = 20
        const val PET_H = 5
        const val SNACK_H = 15
        const val STREAK_BONUS = 5
        const val PETS_PER_HALF = 3
        const val DECAY_MS = 20 * 60_000L
        const val OVER_STEP_MIN = 5.0
        const val SHIELD_MAX_OVER_MINUTES = 30.0
        private const val MAX_CATCH_UP_DAYS = 60L
        val ACCESSORY_NAMES = listOf("", "리본", "목도리", "별 장식")

        /** 정산 경험치: round(100 × G / (t + G)) */
        fun expGain(minutes: Double, goal: Int): Int =
            (100.0 * goal / (minutes.coerceAtLeast(0.0) + goal)).roundToInt()

        /** 쓰다듬기 시간대: 05:00~14:59 / 15:00~04:59 */
        fun petHalfKey(now: Long): String {
            val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
            return if (hour in 5..14) "pet_am" else "pet_pm"
        }
    }
}
