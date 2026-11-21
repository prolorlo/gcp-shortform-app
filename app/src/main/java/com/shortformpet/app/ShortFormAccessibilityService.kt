package com.shortformpet.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 숏폼 시청 시간 측정 서비스.
 *
 * 1초마다 깨어나서
 *  1) 직전 1초 동안 숏폼 화면이었다면 그 시간을 오늘(새벽 5시 기준) 기록에 더하고
 *  2) 단계별 알림(누적 30분/15분, 연속 시청 주기)을 보내고
 *  3) 현재 화면이 숏폼인지 다시 판별합니다.
 *     - 집중 세션 중: 인스타는 앱 전체를 닫고(홈), 유튜브는 처음 열 때 과제용인지 물어
 *       "응"이면 일반 영상만 열어 두고 쇼츠는 뒤로 가기로 막습니다.
 *     - 목표를 넘긴 상태나 약속 시간표 시간에 새로 들어왔으면 진입 안내 화면을 3초 보여줍니다.
 *  4) 빼꼼 모찌: 연속 시청 주기의 절반·목표 70%에서 잠깐, 쿠폰 사용 중엔 남은 시간, 스스로 끄면 칭찬
 * 화면이 꺼져 있으면 측정하지 않고 5초 간격으로 쉽니다.
 */
class ShortFormAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var repo: UsageRepository
    private lateinit var study: StudyManager
    private lateinit var pet: PetEngine
    private lateinit var power: PowerManager
    private var overlay: EntryGuideOverlay? = null
    private var peek: EdgePeekPetOverlay? = null
    private var screenHeight = 0
    private var launcherPackage: String? = null

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
    private var lastGuideElapsed = -GUIDE_COOLDOWN_MS
    private var lastMinuteElapsed = 0L
    private var lastPraiseElapsed = 0L
    /** 이번에 숏폼 화면에 들어와서 이어 본 시간 (칭찬 판단용) */
    private var runMs = 0L
    private var peekHalfShown = false
    /** 직전 틱에 남아 있던 쿠폰 시간 (만료를 걸친 틱은 남은 만큼만 면제) */
    private var prevCouponLeft = 0L
    private var prevCouponOn = false

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
        peek = EdgePeekPetOverlay(this, repo)
        screenHeight = physicalScreenHeight()
        launcherPackage = try {
            packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
        Notifier.createChannels(this)
        DailyResetScheduler.schedule(this)
        lastTickElapsed = SystemClock.elapsedRealtime()
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 화면이 바뀌면 다음 틱에서 바로 다시 판별
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
        peek?.hide()
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
        var couponEnded = false
        for (event in study.sync(now)) {
            if (event is StudyEvent.SessionCompleted) Notifier.sessionCompleted(this, event)
            if (event is StudyEvent.CouponEnded) couponEnded = true
        }
        val couponOn = study.couponActive(now)
        val sessionOn = study.sessionActive(now)
        // 집중 중이면 고정 알림 유지, 끝났거나 포기했으면 지움 (바뀔 때만 실제로 알림을 고칩니다)
        Notifier.updateFocus(this)

        // 1) 직전 구간 동안 보고 있던 앱에 시간 적립
        val watching = currentApp
        if (watching != null) {
            // 휴식 쿠폰을 켠 동안의 시청은 기록에는 남기되 순수 시청 t에서는 뺍니다.
            // 쿠폰이 이 틱 도중에 끝났으면 남아 있던 만큼만 면제합니다.
            val exempt = if (prevCouponOn) minOf(delta, prevCouponLeft) else 0L
            repo.addWatch(day, watching, delta, exempt)
            lastShortFormElapsed = nowElapsed
            runMs += delta
            if (!couponOn) {
                continuousMs += delta
                checkAlerts(day)
            }
            updatePeekWhileWatching(day, couponOn, now)
            if (couponEnded && !sessionOn) showCouponEnded()
        } else if (continuousMs > 0L && nowElapsed - lastShortFormElapsed >= AWAY_RESET_MS) {
            continuousMs = 0L
            continuousAlertLevel = 0
            peekHalfShown = false
        }
        prevCouponOn = couponOn
        prevCouponLeft = study.couponRemainingMs(now)

        // 1분마다: 행복도 감소 반영, 위젯 갱신, (앱이 뒤에 있을 때) 아이콘 표정 바꾸기
        if (nowElapsed - lastMinuteElapsed >= 60_000L) {
            lastMinuteElapsed = nowElapsed
            pet.applyDecay(now)
            MochiWidgetProvider.update(this)
            if (!MainActivity.inForeground) AppIconSwitcher.applyIfNeeded(this)
        }

        // 2) 화면 꺼짐 → 측정 중지 (5초 유예는 IDLE_TICK 간격)
        if (!power.isInteractive) {
            currentApp = null
            dirty = true
            overlay?.dismiss()
            peek?.hide()
            return
        }

        // 3) 현재 화면 다시 판별 (이벤트가 있었거나 1초가 지났을 때)
        if (dirty || nowElapsed - lastDetectElapsed >= DETECT_INTERVAL_MS) {
            dirty = false
            lastDetectElapsed = nowElapsed
            val result = try {
                ShortFormDetector.detect(rootInActiveWindow, screenHeight)
            } catch (e: Exception) {
                ShortFormDetector.Result(null, null)
            }
            lastForegroundPackage = result.foregroundPackage
            var detected = result.app
            if (sessionOn && blockForSession(result, day, nowElapsed)) {
                detected = null
            } else if (detected != null && watching == null) {
                runMs = 0L
                onEnterShortForm(day, nowElapsed, couponOn)
            }
            if (detected == null && watching != null) onLeftShortForm(day)
            currentApp = detected
            updateHomeWalk(result.foregroundPackage)
        }
    }

    private fun flushTracked(day: LocalDate) {
        if (pendingTrackedMs > 0L) repo.addTracked(day, pendingTrackedMs)
        pendingTrackedMs = 0L
    }

    private fun checkAlerts(day: LocalDate) {
        // 누적 알림과 연속 알림이 같은 순간 겹치면 누적 알림 1건만 보냅니다.
        val sentCumulative = WatchAlerts.checkCumulative(this, repo, day)
        // 연속 시청 알림: 설정한 주기마다
        val intervalMs = repo.continuousAlertMinutes * 60_000L
        val level = (continuousMs / intervalMs).toInt()
        if (level > continuousAlertLevel) {
            continuousAlertLevel = level
            if (!sentCumulative) {
                repo.increment(day, SyncManager.COUNT_ALERT_CONTINUOUS)
                Notifier.continuousAlert(this, (level * intervalMs / 60_000L).toInt())
            }
        }
    }

    /**
     * 집중 세션 중 차단. 막았으면 true (그 화면은 시청으로 재지 않음)
     *  - 인스타그램: 앱 전체 → 홈
     *  - 유튜브: 쇼츠는 언제나 뒤로 가기. 일반 화면은 처음 열 때 과제용인지 묻고, 아니라면 홈
     */
    private fun blockForSession(result: ShortFormDetector.Result, day: LocalDate, nowElapsed: Long): Boolean {
        val pkg = result.foregroundPackage
        val action: Int
        val message: String
        when {
            pkg == ShortFormApp.INSTAGRAM_REELS.pkg -> {
                action = GLOBAL_ACTION_HOME
                val todo = study.nextRemainingTodo()
                message = if (todo != null) Lines.blockedWithTodo(pet.name, todo) else Lines.blockedNoTodo(pet.name)
            }
            pkg == ShortFormApp.YOUTUBE_SHORTS.pkg && result.app != null -> {
                action = GLOBAL_ACTION_BACK
                message = Lines.blockedShorts(pet.name)
            }
            pkg == ShortFormApp.YOUTUBE_SHORTS.pkg -> {
                if (study.youtubeAllowed()) return false
                if (!study.youtubeAsked()) {
                    askYoutube()
                    return true
                }
                action = GLOBAL_ACTION_HOME
                message = Lines.blockedNoTodo(pet.name)
            }
            else -> return false
        }
        if (nowElapsed - lastBlockElapsed < BLOCK_INTERVAL_MS) return true
        lastBlockElapsed = nowElapsed
        performGlobalAction(action)
        repo.increment(day, SyncManager.COUNT_SESSION_BLOCK)
        dirty = true
        if (nowElapsed - lastBlockToastElapsed >= BLOCK_TOAST_INTERVAL_MS) {
            lastBlockToastElapsed = nowElapsed
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        return true
    }

    /** 집중 중 유튜브를 처음 열었을 때: 과제용인지 묻는 화면 (답할 때까지 유지) */
    private fun askYoutube() {
        val guide = overlay ?: return
        if (guide.isShowing) return
        guide.show(
            state = pet.state(),
            title = Lines.YT_QUESTION_TITLE,
            lines = listOf(Lines.YT_QUESTION_BODY),
            primary = Lines.YT_YES to {
                study.setYoutubeAnswer(true)
                dirty = true
            },
            secondary = Lines.YT_NO to {
                study.setYoutubeAnswer(false)
                performGlobalAction(GLOBAL_ACTION_HOME)
            },
            seconds = 0,
        )
    }

    /**
     * 진입 안내: 목표를 넘긴 뒤, 또는 약속 시간표 시간에 숏폼을 새로 켰을 때.
     * 3초 뒤 사라지고, 한 번 보이면 4분 동안은 다시 띄우지 않습니다.
     */
    private fun onEnterShortForm(day: LocalDate, nowElapsed: Long, couponOn: Boolean) {
        if (couponOn || !repo.entryGuideEnabled) return
        val awayLongEnough = lastShortFormElapsed < 0L || nowElapsed - lastShortFormElapsed >= ENTRY_GUIDE_AWAY_MS
        if (!awayLongEnough) return
        if (nowElapsed - lastGuideElapsed < GUIDE_COOLDOWN_MS) return
        val goal = repo.goalFor(day)
        val countedMs = repo.countedMillis(day)
        val z = Instant.ofEpochMilli(Now.ms()).atZone(ZoneId.systemDefault())
        val slot = repo.scheduleSlots().firstOrNull { it.contains(z.hour * 60 + z.minute) }
        val overGoal = countedMs > goal * 60_000L
        if (!overGoal && slot == null) return
        val guide = overlay ?: return
        if (guide.isShowing) return

        val state = pet.state()
        val todoLine = when {
            DayClock.isLateNight() -> Lines.SLEEP
            else -> study.nextRemainingTodo()?.let { Lines.guideTodo(it) }
        }
        val title = if (slot != null) Lines.guideScheduleTitle(state.name) else Lines.guideTitle(state.name)
        val usage = if (slot != null) Lines.guideSchedule(slot.label()) else Lines.guideUsage(formatDuration(countedMs), formatMinutes(goal))
        val shown = guide.show(
            state = state,
            title = title,
            lines = listOfNotNull(usage, todoLine),
            primary = Lines.GUIDE_STOP to {
                performGlobalAction(GLOBAL_ACTION_BACK)
                dirty = true
            },
            secondary = Lines.GUIDE_DISMISS to {},
        )
        if (shown) {
            lastGuideElapsed = nowElapsed
            peek?.hide()
            repo.increment(day, SyncManager.COUNT_ENTRY_GUIDE)
        }
    }

    /** 숏폼을 보는 동안 빼꼼 모찌: 쿠폰 남은 시간, 연속 주기 절반, 목표 70% */
    private fun updatePeekWhileWatching(day: LocalDate, couponOn: Boolean, now: Long) {
        val p = peek ?: return
        if (!repo.peekEnabled || overlay?.isShowing == true) return
        if (couponOn) {
            p.showCoupon(pet.state(), formatClock(study.couponRemainingMs(now)))
            return
        }
        if (p.mode == EdgePeekPetOverlay.Mode.COUPON) p.hide()
        val intervalMs = repo.continuousAlertMinutes * 60_000L
        if (!peekHalfShown && intervalMs >= 2 * 60_000L && continuousMs >= intervalMs / 2) {
            peekHalfShown = true
            p.showTransient(pet.state(), Lines.peekContinuous((continuousMs / 60_000L).toInt()), PEEK_MS)
            return
        }
        val goalMs = repo.goalFor(day) * 60_000L
        val counted = repo.countedMillis(day)
        if (counted >= goalMs * 7 / 10 && counted < goalMs && repo.count(day, COUNT_PEEK_GOAL) == 0) {
            repo.increment(day, COUNT_PEEK_GOAL)
            p.showTransient(pet.state(), Lines.peekGoal(formatDuration(goalMs - counted)), PEEK_MS)
        }
    }

    /**
     * 숏폼 화면을 벗어났을 때. 목표 안에서 짧게(1분 이상, 연속 주기 절반 이하) 보고 스스로 껐으면 칭찬.
     * 하루 2번까지, 3시간에 한 번.
     */
    private fun onLeftShortForm(day: LocalDate) {
        val p = peek ?: return
        if (p.mode == EdgePeekPetOverlay.Mode.COUPON) p.hide()
        if (!repo.peekEnabled || study.sessionActive()) return
        val intervalMs = repo.continuousAlertMinutes * 60_000L
        val withinGoal = repo.countedMillis(day) < repo.goalFor(day) * 60_000L
        if (!withinGoal || runMs < 60_000L || runMs > intervalMs / 2) return
        if (repo.count(day, COUNT_PRAISE) >= 2) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (lastPraiseElapsed > 0L && nowElapsed - lastPraiseElapsed < 3 * 3_600_000L) return
        lastPraiseElapsed = nowElapsed
        repo.increment(day, COUNT_PRAISE)
        p.showTransient(pet.state(), Lines.PEEK_PRAISE.random(), 1_800)
    }

    /** 쿠폰이 숏폼을 보다가 끝났을 때: 바로 집중으로 이어 갈지 묻기 */
    private fun showCouponEnded() {
        val guide = overlay ?: return
        if (guide.isShowing) return
        peek?.hide()
        val state = pet.state()
        guide.show(
            state = state,
            title = Lines.couponEndTitle(state.name),
            lines = listOf(Lines.COUPON_END_BODY),
            primary = Lines.COUPON_END_FOCUS to {
                if (study.startSession(25)) {
                    Toast.makeText(this, Lines.says(state.name, Lines.sessionStart(25)), Toast.LENGTH_SHORT).show()
                    Notifier.updateFocus(this)
                }
                dirty = true
            },
            secondary = Lines.GUIDE_DISMISS to {},
            seconds = 5,
        )
    }

    /** 홈 화면 산책: 설정에서 켰고 홈 화면이 앞에 있을 때만 */
    private fun updateHomeWalk(fg: String?) {
        val p = peek ?: return
        val onHome = fg != null && fg == launcherPackage
        // 홈 화면 산책은 '빼꼼 모찌'(숙폼 중 알림) 스위치와 따로 켭니다.
        if (onHome && repo.homeWalkEnabled) {
            p.showHome(pet.state())
        } else if (p.mode == EdgePeekPetOverlay.Mode.HOME) {
            p.hide()
        }
    }

    /** 기기 물리 화면 높이 (숏폼 판별 60% 기준) */
    private fun physicalScreenHeight(): Int {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            maxOf(b.height(), b.width())
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            maxOf(m.heightPixels, m.widthPixels)
        }
    }

    companion object {
        private const val TICK_MS = 1_000L
        private const val IDLE_TICK_MS = 5_000L
        private const val DETECT_INTERVAL_MS = 1_000L
        private const val MAX_DELTA_MS = 3_000L
        private const val TRACKED_FLUSH_MS = 60_000L
        private const val AWAY_RESET_MS = 5 * 60_000L
        private const val ENTRY_GUIDE_AWAY_MS = 30_000L
        private const val GUIDE_COOLDOWN_MS = 4 * 60_000L
        private const val BLOCK_INTERVAL_MS = 1_500L
        private const val BLOCK_TOAST_INTERVAL_MS = 5_000L
        private const val PEEK_MS = 4_000L
        private const val COUNT_PEEK_GOAL = "peek_goal"
        private const val COUNT_PRAISE = "praise"

        /** 화면 표시용 상태 (같은 프로세스에서만 의미 있음) */
        @Volatile var running = false
        @Volatile var currentApp: ShortFormApp? = null
        @Volatile var lastForegroundPackage: String? = null
    }
}
