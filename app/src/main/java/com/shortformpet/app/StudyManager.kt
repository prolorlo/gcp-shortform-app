package com.shortformpet.app

import java.time.LocalDate

data class Todo(val text: String, val done: Boolean)

sealed class StudyEvent {
    /** 세션 완료: 받은 쿠폰(분), 보호권을 새로 받았는지 */
    data class SessionCompleted(
        val sessionMinutes: Int,
        val couponMinutes: Int,
        val shieldGranted: Boolean,
        /** 오늘 받을 수 있는 쿠폰(20분)을 이미 다 받아서 못 받았을 때 */
        val couponCapped: Boolean = false,
    ) : StudyEvent()
    object CouponEnded : StudyEvent()
}

/**
 * 공부 모드: 오늘의 할 일(최대 3개), 집중 세션, 휴식 쿠폰, 간식, 연속일수 보호권.
 *
 * 모든 상태는 저장소에 시각(ms)과 함께 기록하므로, 앱이나 측정 서비스가 꺼졌다 켜져도
 * 진행 중인 세션·쿠폰·할 일이 그대로 이어집니다.
 *
 * study 키
 *  - todos / todos_day            : 할 일 목록("D|내용" 또는 "U|내용" 줄), 목록의 날짜
 *  - sess_start / sess_end / sess_min : 진행 중인 세션 (없으면 sess_end = 0)
 *  - coupon_on_since              : 쿠폰을 켠 시각 (꺼져 있으면 0)
 *  - shield / shield_progress     : 보호권 보유 수(0~1), 다음 보호권까지 완료한 세션 수
 *  - s_<날짜>_<이름>               : 그날 기록 (세션 시작/완료/실패, 할 일, 쿠폰, 간식)
 */
class StudyManager(stores: Stores) {

    private val kv = stores.study

    private fun dayKey(day: LocalDate, name: String) = "s_${day}_$name"
    private fun dayInt(day: LocalDate, name: String) = kv.getInt(dayKey(day, name), 0)
    private fun dayLong(day: LocalDate, name: String) = kv.getLong(dayKey(day, name), 0L)
    private fun addDayInt(day: LocalDate, name: String, by: Int = 1) =
        kv.edit { putInt(dayKey(day, name), dayInt(day, name) + by) }

    // ------------------------------------------------------------ 오늘의 할 일

    /** 날짜가 바뀌면 끝낸 항목은 지우고, 못 끝낸 항목은 오늘로 넘깁니다. */
    private fun rolloverTodos(today: LocalDate) {
        if (kv.getString("todos_day", null) == today.toString()) return
        val carried = readTodos().filter { !it.done }
        writeTodos(carried, today)
        kv.edit { putInt("todo_rot", 0) }
        if (carried.isNotEmpty()) noteTodoTotal(today, carried.size)
    }

    private fun readTodos(): List<Todo> =
        (kv.getString("todos", "") ?: "").lines().filter { it.length > 2 && it[1] == '|' }
            .map { Todo(it.substring(2), it[0] == 'D') }

    private fun writeTodos(list: List<Todo>, day: LocalDate) = kv.edit {
        putString("todos", list.joinToString("\n") { (if (it.done) "D|" else "U|") + it.text })
        putString("todos_day", day.toString())
    }

    private fun noteTodoTotal(day: LocalDate, size: Int) {
        if (size > dayInt(day, "todo_total")) kv.edit { putInt(dayKey(day, "todo_total"), size) }
    }

    fun todos(now: Long = Now.ms()): List<Todo> {
        rolloverTodos(DayClock.logicalDay(now))
        return readTodos()
    }

    /** 하루 최대 3개. 추가했으면 true */
    fun addTodo(text: String, now: Long = Now.ms()): Boolean {
        val clean = text.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_TODO_LENGTH)
        val today = DayClock.logicalDay(now)
        val list = todos(now)
        if (clean.isEmpty() || list.size >= MAX_TODOS) return false
        writeTodos(list + Todo(clean, false), today)
        noteTodoTotal(today, list.size + 1)
        return true
    }

    /** 할 일을 끝냄 → 간식 1개 획득 (하루 최대 3개) */
    fun completeTodo(index: Int, now: Long = Now.ms()): Boolean {
        val today = DayClock.logicalDay(now)
        val list = todos(now).toMutableList()
        val item = list.getOrNull(index) ?: return false
        if (item.done) return false
        list[index] = item.copy(done = true)
        writeTodos(list, today)
        kv.edit {
            putInt(dayKey(today, "todo_done"), dayInt(today, "todo_done") + 1)
            putInt(dayKey(today, "snack_earned"), (dayInt(today, "snack_earned") + 1).coerceAtMost(MAX_SNACKS))
        }
        return true
    }

    /**
     * 방금 누른 완료를 되돌립니다(잘못 눌렀을 때).
     * 완료 횟수와 받은 간식을 하나씩 돌려놓되, 이미 먹인 간식 수보다 적어지지는 않습니다.
     */
    fun undoCompleteTodo(index: Int, now: Long = Now.ms()): Boolean {
        val today = DayClock.logicalDay(now)
        val list = todos(now).toMutableList()
        val item = list.getOrNull(index) ?: return false
        if (!item.done) return false
        list[index] = item.copy(done = false)
        writeTodos(list, today)
        kv.edit {
            putInt(dayKey(today, "todo_done"), (dayInt(today, "todo_done") - 1).coerceAtLeast(0))
            putInt(dayKey(today, "snack_earned"), (dayInt(today, "snack_earned") - 1).coerceAtLeast(dayInt(today, "snack_fed")))
        }
        return true
    }

    /** 아직 못 끝낸 항목만 지울 수 있습니다. */
    fun removeTodo(index: Int, now: Long = Now.ms()): Boolean {
        val list = todos(now).toMutableList()
        val item = list.getOrNull(index) ?: return false
        if (item.done) return false
        list.removeAt(index)
        writeTodos(list, DayClock.logicalDay(now))
        return true
    }

    fun remainingTodoCount(now: Long = Now.ms()): Int = todos(now).count { !it.done }

    /** 알림에 쓸 남은 할 일. 부를 때마다 다음 항목으로 돌아가며 보여줍니다. */
    fun nextRemainingTodo(now: Long = Now.ms()): String? {
        val remaining = todos(now).filter { !it.done }
        if (remaining.isEmpty()) return null
        val rot = kv.getInt("todo_rot", 0)
        kv.edit { putInt("todo_rot", (rot + 1) % 1000) }
        return remaining[rot % remaining.size].text
    }

    // ------------------------------------------------------------ 간식

    fun snacksAvailable(now: Long = Now.ms()): Int {
        val today = DayClock.logicalDay(now)
        return (dayInt(today, "snack_earned") - dayInt(today, "snack_fed")).coerceAtLeast(0)
    }

    /** 간식 1개 사용. 성공하면 true (경험치는 PetEngine이 올립니다) */
    fun consumeSnack(now: Long = Now.ms()): Boolean {
        if (snacksAvailable(now) <= 0) return false
        addDayInt(DayClock.logicalDay(now), "snack_fed")
        return true
    }

    // ------------------------------------------------------------ 집중 세션

    fun sessionActive(now: Long = Now.ms()): Boolean {
        val end = kv.getLong("sess_end", 0L)
        return end > 0L && now < end
    }

    fun sessionRemainingMs(now: Long = Now.ms()): Long =
        (kv.getLong("sess_end", 0L) - now).coerceAtLeast(0L)

    fun sessionLengthMinutes(): Int = kv.getInt("sess_min", 25)

    /** 5분 착수 · 25분 · 50분 세션 시작. 세션 중에는 휴식 쿠폰을 끕니다. */
    fun startSession(minutes: Int, now: Long = Now.ms()): Boolean {
        sync(now)
        if (kv.getLong("sess_end", 0L) > 0L) return false
        val len = if (minutes in UsageRepository.SESSION_LENGTHS) minutes else 25
        setCoupon(false, now)
        kv.edit {
            putLong("sess_start", now)
            putLong("sess_end", now + len * 60_000L)
            putInt("sess_min", len)
        }
        addDayInt(DayClock.logicalDay(now), "sess_started")
        return true
    }

    /** "세션 포기" 또는 세션 중 접근성 서비스가 꺼짐 → 실패 처리, 차단 해제 */
    fun failSession(now: Long = Now.ms()): Boolean {
        sync(now)
        if (kv.getLong("sess_end", 0L) <= 0L) return false
        clearSession()
        addDayInt(DayClock.logicalDay(now), "sess_failed")
        return true
    }

    private fun clearSession() = kv.edit {
        remove("sess_start"); remove("sess_end"); remove("sess_min"); remove("sess_yt_ok"); remove("sess_yt_asked")
    }

    /** 이번 세션에서 유튜브를 과제용으로 허락했는지 (쇼츠는 그래도 막습니다) */
    fun youtubeAllowed(): Boolean = kv.getBoolean("sess_yt_ok", false)

    /** 이번 세션에서 유튜브 질문을 이미 했는지 */
    fun youtubeAsked(): Boolean = kv.getBoolean("sess_yt_asked", false)

    fun setYoutubeAnswer(allowed: Boolean) = kv.edit {
        putBoolean("sess_yt_asked", true)
        putBoolean("sess_yt_ok", allowed)
    }

    // ------------------------------------------------------------ 휴식 쿠폰

    private fun couponOnSince(): Long = kv.getLong("coupon_on_since", 0L)

    private fun couponLeftStored(day: LocalDate): Long =
        (dayLong(day, "coupon_earned") - dayLong(day, "coupon_used")).coerceAtLeast(0L)

    /** 오늘 남은 쿠폰 시간(ms). 켜 둔 동안에는 실시간으로 줄어듭니다. 당일에만 쓸 수 있습니다. */
    fun couponRemainingMs(now: Long = Now.ms()): Long {
        val today = DayClock.logicalDay(now)
        val left = couponLeftStored(today)
        val since = couponOnSince()
        if (since <= 0L || DayClock.logicalDay(since) != today) return left
        return (left - (now - since).coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    fun couponActive(now: Long = Now.ms()): Boolean {
        val since = couponOnSince()
        return since > 0L && DayClock.logicalDay(since) == DayClock.logicalDay(now) && couponRemainingMs(now) > 0L
    }

    fun couponUsedMs(day: LocalDate): Long = dayLong(day, "coupon_used")

    /** 사용자가 공부 카드에서 직접 켜고 끕니다. 세션 중이거나 남은 시간이 없으면 켤 수 없습니다. */
    fun setCoupon(on: Boolean, now: Long = Now.ms()): Boolean {
        settleCoupon(now)
        if (!on) {
            kv.edit { remove("coupon_on_since") }
            return true
        }
        if (sessionActive(now) || couponRemainingMs(now) <= 0L) return false
        kv.edit { putLong("coupon_on_since", now) }
        return true
    }

    /** 켜 둔 시간만큼 사용량에 반영합니다. 다 썼거나 날짜가 바뀌었으면 끕니다. */
    private fun settleCoupon(now: Long): Boolean {
        val since = couponOnSince()
        if (since <= 0L) return false
        val today = DayClock.logicalDay(now)
        val sinceDay = DayClock.logicalDay(since)
        if (sinceDay != today) {
            // 새벽 5시가 지나면 어제 쿠폰은 사라집니다.
            val left = couponLeftStored(sinceDay)
            kv.edit {
                putLong(dayKey(sinceDay, "coupon_used"), dayLong(sinceDay, "coupon_used") + left)
                remove("coupon_on_since")
            }
            return true
        }
        val left = couponLeftStored(today)
        val elapsed = (now - since).coerceAtLeast(0L)
        val used = minOf(elapsed, left)
        val depleted = elapsed >= left
        kv.edit {
            putLong(dayKey(today, "coupon_used"), dayLong(today, "coupon_used") + used)
            if (depleted) remove("coupon_on_since") else putLong("coupon_on_since", now)
        }
        return depleted
    }

    // ------------------------------------------------------------ 보호권

    fun shields(): Int = kv.getInt("shield", 0)
    fun shieldProgress(): Int = kv.getInt("shield_progress", 0)

    /** 정산에서 보호권을 썼을 때 */
    fun useShield(): Boolean {
        if (shields() <= 0) return false
        kv.edit { putInt("shield", 0) }
        return true
    }

    // ------------------------------------------------------------ 주기적 처리

    /**
     * 측정 서비스(1초마다), 화면, 새벽 5시 알람에서 부릅니다.
     * 끝난 세션을 완료 처리하고(쿠폰·보호권 지급), 다 쓴 쿠폰을 끕니다.
     * 시각 기준이라 앱이 꺼져 있던 사이에 세션이 끝났어도 다음에 켜질 때 정상 처리됩니다.
     */
    fun sync(now: Long = Now.ms()): List<StudyEvent> {
        val events = mutableListOf<StudyEvent>()

        val end = kv.getLong("sess_end", 0L)
        if (end > 0L && now >= end) {
            val len = sessionLengthMinutes()
            val day = DayClock.logicalDay(end)
            clearSession()

            // 5분 착수는 시작을 돕는 용도라 쿠폰·보호권 없이 완료만 기록합니다.
            val couponMin = couponFor(len)
            val earnedBefore = dayLong(day, "coupon_earned")
            val earnedAfter = (earnedBefore + couponMin * 60_000L).coerceAtMost(MAX_COUPON_MS_PER_DAY)

            var progress = shieldProgress() + if (len >= 25) 1 else 0
            var shield = shields()
            var granted = false
            if (len < 25) {
                // 보호권 진행 없음
            } else if (shield == 0 && progress >= SESSIONS_PER_SHIELD) {
                shield = 1; progress = 0; granted = true
            } else if (shield >= 1) {
                // 보호권은 최대 1개. 갖고 있는 동안에는 4회까지만 쌓아 둡니다.
                progress = progress.coerceAtMost(SESSIONS_PER_SHIELD - 1)
            }
            kv.edit {
                putInt(dayKey(day, "sess_done"), dayInt(day, "sess_done") + 1)
                putLong(dayKey(day, "coupon_earned"), earnedAfter)
                putInt("shield", shield)
                putInt("shield_progress", progress)
            }
            events += StudyEvent.SessionCompleted(len, ((earnedAfter - earnedBefore) / 60_000L).toInt(), granted, couponMin > 0 && earnedAfter == earnedBefore)
        }

        if (couponOnSince() > 0L && settleCoupon(now)) events += StudyEvent.CouponEnded
        return events
    }

    // ------------------------------------------------------------ 동기화용 기록

    fun sessionsStarted(day: LocalDate) = dayInt(day, "sess_started")
    fun sessionsCompleted(day: LocalDate) = dayInt(day, "sess_done")
    fun sessionsFailed(day: LocalDate) = dayInt(day, "sess_failed")
    fun todosTotal(day: LocalDate) = dayInt(day, "todo_total")
    fun todosDone(day: LocalDate) = dayInt(day, "todo_done")

    companion object {
        const val MAX_TODOS = 3
        const val MAX_SNACKS = 3
        const val MAX_TODO_LENGTH = 30
        const val SESSIONS_PER_SHIELD = 5
        const val MAX_COUPON_MS_PER_DAY = 20 * 60_000L

        /** 세션 길이별 휴식 쿠폰(분): 5분 0, 25분 5, 50분 10 */
        fun couponFor(minutes: Int): Int = when (minutes) {
            50 -> 10
            25 -> 5
            else -> 0
        }
    }
}
