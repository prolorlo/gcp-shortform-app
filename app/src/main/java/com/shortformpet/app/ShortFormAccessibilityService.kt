package com.shortformpet.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.time.LocalDate

/**
 * 숏폼 시청 시간 측정 서비스.
 *
 * 1초마다 깨어나서
 *  1) 직전 1초 동안 숏폼 화면이었다면 그 시간을 오늘(새벽 5시 기준) 기록에 더하고
 *  2) 단계별 알림(누적 30분/15분, 연속 20분)을 보내고
 *  3) 현재 화면이 숏폼인지 다시 판별합니다.
 *     - 공부 세션 중이면 그 화면에서 빠져나오게 합니다(쇼츠·릴스는 뒤로 가기, 틱톡은 홈).
 *     - 목표를 넘긴 상태에서 새로 들어왔으면 진입 안내 화면을 3초 보여줍니다.
 * 화면이 꺼져 있으면 측정하지 않고 5초 간격으로 쉽니다.
 */
class ShortFormAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var repo: UsageRepository
    private lateinit var study: StudyManager
    private lateinit var pet: PetEngine
    private lateinit var power: PowerManager
    private var overlay: EntryGuideOverlay? = null

    private var lastTickElapsed = 0L
    private var lastDetectElapsed = 0L
    private var dirty = true
    private var lastDay: LocalDate? = null

    /** 아직 저장하지 않은 "측정 서비스가 켜져 있던 시간" (1분마다 저장) */
    private var pendingTrackedMs = 0L

    /** 연속 시청: 숏폼 화면에 머문 시간. 5분 이상 벗어나면 0으로 */
    private var continuousMs = 0L
    private var continuousAlertLevel = 0
    private var lastShortFormElapsed = -1L

    private var lastBlockElapsed = 0L
    private var lastBlockToastElapsed = 0L

    private val tick = object : Runnable {
        override fun run() {
            try {
                onTick()
            } catch (e: Exception) {
                // 한 번의 오류로 측정이 멈추지 않게 합니다.
            }
            handler.postDelayed(this, if (power.isInteractive) TICK_MS else IDLE_TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val stores = Stores.of(this)
        repo = UsageRepository(stores)
        study = StudyManager(stores)
        pet = PetEngine(stores)
        power = getSystemService(PowerManager::class.java)
        overlay = EntryGuideOverlay(this)
        Notifier.createChannels(this)
        DailyResetScheduler.schedule(this)
        lastTickElapsed = SystemClock.elapsedRealtime()
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 측정 대상 앱에서 화면이 바뀌면 다음 틱에서 바로 다시 판별
        if (event != null) dirty = true
    }

    override fun onInterrupt() {}

    /** 사용자가 접근성 서비스를 끌 때 불립니다. 세션 중이었다면 실패 처리합니다. */
    override fun onUnbind(intent: Intent?): Boolean {
        if (::study.isInitialized) {
            flushTracked(DayClock.logicalDay())
            study.failSession()
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        overlay?.dismiss()
        running = false
        currentApp = null
        super.onDestroy()
    }

    private fun onTick() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val raw = (nowElapsed - lastTickElapsed).coerceAtLeast(0L)
        val delta = raw.coerceAtMost(MAX_DELTA_MS)
        lastTickElapsed = nowElapsed

        val now = Now.ms()
        val day = DayClock.logicalDay(now)

        if (day != lastDay) {
            // 새벽 5시가 지나면 여기서 새 날짜가 시작되고 어제가 정산됩니다.
            val previous = lastDay
            if (previous != null && day.isBefore(previous)) {
                pendingTrackedMs = 0L // 개발자 테스트 초기화로 날짜가 되돌아간 경우
            } else if (previous != null) {
                // 5시 이전 몫은 어제의 측정 시간으로
                val sinceReset = (now - DayClock.startOfDayMillis(day)).coerceIn(0L, raw)
                pendingTrackedMs += raw - sinceReset
                flushTracked(previous)
                pendingTrackedMs = sinceReset
            } else {
                pendingTrackedMs += raw
            }
            lastDay = day
            repo.markTracked(day)
            val results = pet.catchUp()
            if (results.isNotEmpty()) Notifier.dailyReport(this, results.last().message)
            DailyResetScheduler.schedule(this)
            SyncScheduler.kick(this)
        } else {
            pendingTrackedMs += raw
        }
        if (pendingTrackedMs >= TRACKED_FLUSH_MS) flushTracked(day)

        // 끝난 세션 완료 처리, 다 쓴 쿠폰 끄기
        for (event in study.sync(now)) {
            if (event is StudyEvent.SessionCompleted) Notifier.sessionCompleted(this, event)
        }
        val couponOn = study.couponActive(now)
        val sessionOn = study.sessionActive(now)
        // 집중 중이면 고정 알림 유지, 끝났거나 포기했으면 지움 (바뀔 때만 실제로 알림을 고칩니다)
        Notifier.updateFocus(this)

        // 1) 직전 구간 동안 보고 있던 앱에 시간 적립
        val watching = currentApp
        if (watching != null) {
            // 휴식 쿠폰을 켠 동안의 시청은 기록에는 남기되 목표·기분·알림에서는 뺍니다.
            repo.addWatch(day, watching, delta, exempt = couponOn)
            lastShortFormElapsed = nowElapsed
            if (!couponOn) {
                continuousMs += delta
                checkAlerts(day)
            }
        } else if (continuousMs > 0L && nowElapsed - lastShortFormElapsed >= AWAY_RESET_MS) {
            continuousMs = 0L
            continuousAlertLevel = 0
        }

        // 2) 화면 꺼짐 → 측정 중지
        if (!power.isInteractive) {
            currentApp = null
            dirty = true
            overlay?.dismiss()
            return
        }

        // 3) 현재 화면 다시 판별 (이벤트가 있었거나 2초가 지났을 때)
        if (dirty || nowElapsed - lastDetectElapsed >= DETECT_INTERVAL_MS) {
            dirty = false
            lastDetectElapsed = nowElapsed
            val result = try {
                ShortFormDetector.detect(rootInActiveWindow)
            } catch (e: Exception) {
                ShortFormDetector.Result(null, null)
            }
            lastForegroundPackage = result.foregroundPackage
            var detected = result.app
            if (detected != null && sessionOn) {
                blockForSession(detected, day, nowElapsed)
                detected = null
            } else if (detected != null && watching == null) {
                onEnterShortForm(day, nowElapsed, couponOn)
            }
            currentApp = detected
        }
    }

    private fun flushTracked(day: LocalDate) {
        if (pendingTrackedMs > 0L) repo.addTracked(day, pendingTrackedMs)
        pendingTrackedMs = 0L
    }

    private fun checkAlerts(day: LocalDate) {
        WatchAlerts.checkCumulative(this, repo, day)
        // 연속 시청 알림: 20분, 40분, …
        val level = (continuousMs / CONTINUOUS_ALERT_MS).toInt()
        if (level > continuousAlertLevel) {
            continuousAlertLevel = level
            repo.increment(day, SyncManager.COUNT_ALERT_CONTINUOUS)
            Notifier.continuousAlert(this, level * (CONTINUOUS_ALERT_MS / 60_000L).toInt())
        }
    }

    /** 공부 세션 중 차단: 쇼츠·릴스 화면만 빠져나오고(일반 영상·피드는 그대로), 틱톡은 홈으로 */
    private fun blockForSession(app: ShortFormApp, day: LocalDate, nowElapsed: Long) {
        if (nowElapsed - lastBlockElapsed < BLOCK_INTERVAL_MS) return
        lastBlockElapsed = nowElapsed
        performGlobalAction(if (app == ShortFormApp.TIKTOK) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
        repo.increment(day, SyncManager.COUNT_SESSION_BLOCK)
        dirty = true
        if (nowElapsed - lastBlockToastElapsed >= BLOCK_TOAST_INTERVAL_MS) {
            lastBlockToastElapsed = nowElapsed
            val todo = study.nextRemainingTodo()
            val message = if (todo != null) {
                Lines.blockedWithTodo(pet.name, todo)
            } else {
                Lines.blockedNoTodo(pet.name)
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    /** 진입 안내: 목표를 넘긴 상태에서 숏폼을 새로 켰을 때. 차단하지 않고 3초 뒤 사라집니다. */
    private fun onEnterShortForm(day: LocalDate, nowElapsed: Long, couponOn: Boolean) {
        if (couponOn || !repo.entryGuideEnabled) return
        val awayLongEnough = lastShortFormElapsed < 0L || nowElapsed - lastShortFormElapsed >= ENTRY_GUIDE_AWAY_MS
        if (!awayLongEnough) return
        val goal = repo.dailyGoalMinutes
        val countedMs = repo.countedMillis(day)
        if (countedMs <= goal * 60_000L) return
        val guide = overlay ?: return
        if (guide.isShowing) return

        val state = pet.state()
        val todoLine = when {
            DayClock.isLateNight() -> Lines.SLEEP
            else -> study.nextRemainingTodo()?.let { Lines.guideTodo(it) }
        }
        val shown = guide.show(
            state = state,
            title = Lines.guideTitle(state.name),
            lines = listOfNotNull(Lines.guideUsage(formatDuration(countedMs), formatMinutes(goal)), todoLine),
        )
        if (shown) repo.increment(day, SyncManager.COUNT_ENTRY_GUIDE)
    }

    companion object {
        private const val TICK_MS = 1_000L
        private const val IDLE_TICK_MS = 5_000L
        private const val DETECT_INTERVAL_MS = 2_000L
        private const val MAX_DELTA_MS = 3_000L
        private const val TRACKED_FLUSH_MS = 60_000L
        private const val CONTINUOUS_ALERT_MS = 20 * 60_000L
        private const val AWAY_RESET_MS = 5 * 60_000L
        private const val ENTRY_GUIDE_AWAY_MS = 30_000L
        private const val BLOCK_INTERVAL_MS = 1_500L
        private const val BLOCK_TOAST_INTERVAL_MS = 5_000L

        /** 화면 표시용 상태 (같은 프로세스에서만 의미 있음) */
        @Volatile var running = false
        @Volatile var currentApp: ShortFormApp? = null
        @Volatile var lastForegroundPackage: String? = null
    }
}
