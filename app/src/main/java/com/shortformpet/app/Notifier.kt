package com.shortformpet.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.time.LocalDate

/**
 * 알림.
 *  - 누적 알림: 목표 전 30분마다, 목표를 넘긴 뒤 15분마다
 *  - 연속 시청 알림: 한 번에 20분 넘게 이어 볼 때
 *  - 세션 완료, 하루 정산
 *
 * 남은 할 일은 돌아가며 한 개씩 보여주고, 잠금 화면에는 개수만 보입니다.
 * 밤 12시~새벽 5시에는 할 일 대신 수면 권유 문구를 씁니다.
 */
object Notifier {
    private const val CH_ALERT = "watch_alert"
    private const val CH_DAILY = "daily_report"
    private const val CH_STUDY = "study"
    private const val ID_ALERT = 1001
    private const val ID_DAILY = 1002
    private const val ID_CONTINUOUS = 1003
    private const val ID_STUDY = 1004

    val SLEEP_LINE get() = Lines.SLEEP

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, Lines.CHANNEL_ALERT, NotificationManager.IMPORTANCE_HIGH).apply {
                description = Lines.CHANNEL_ALERT_DESC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DAILY, Lines.CHANNEL_DAILY, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = Lines.CHANNEL_DAILY_DESC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_STUDY, Lines.CHANNEL_STUDY, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = Lines.CHANNEL_STUDY_DESC
            },
        )
        // 소리·진동 없이 알림 창에만 머무는 채널
        nm.createNotificationChannel(
            NotificationChannel(CH_FOCUS, Lines.CHANNEL_FOCUS, NotificationManager.IMPORTANCE_LOW).apply {
                description = Lines.CHANNEL_FOCUS_DESC
                setShowBadge(false)
            },
        )
    }

    private const val CH_FOCUS = "focus_ongoing"
    private const val ID_FOCUS = 1005
    /** 지금 띄워 둔 고정 알림의 끝나는 시각(벽시계). 0이면 없음, -1이면 아직 확인 전 */
    @Volatile private var focusShownEnd = -1L

    /**
     * 집중 세션 고정 알림을 지금 상태에 맞춥니다. 화면·측정 서비스에서 1초마다 불러도 됩니다.
     * 세션 중이면 남은 시간이 줄어드는 알림을 띄우고(끝나는 시각에 저절로 사라짐), 아니면 지웁니다.
     */
    fun updateFocus(context: Context) {
        val study = StudyManager(Stores.of(context))
        val now = Now.ms()
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!study.sessionActive(now)) {
            if (focusShownEnd != 0L) {
                nm.cancel(ID_FOCUS)
                focusShownEnd = 0L
            }
            return
        }
        if (!canNotify(context)) return
        val remaining = study.sessionRemainingMs(now)
        // 개발자 도구로 시각을 옮겨도 알림 시계는 실제 시각 기준이어야 하므로 남은 시간으로 계산합니다.
        val endWall = System.currentTimeMillis() + remaining
        if (focusShownEnd > 0L && kotlin.math.abs(endWall - focusShownEnd) < 5_000L) return
        createChannels(context)
        focusShownEnd = endWall

        val todo = study.todos(now).firstOrNull { !it.done }?.text
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_STUDY)
        val pi = PendingIntent.getActivity(context, ID_FOCUS, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun base() = Notification.Builder(context, CH_FOCUS)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle(Lines.FOCUS_TITLE)
            .setWhen(endWall)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
        val notification = base()
            .setContentText(Lines.focusBody(todo))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(base().setContentText(Lines.FOCUS_PUBLIC).build())
            .setContentIntent(pi)
            .setTimeoutAfter(remaining)
            .build()
        nm.notify(ID_FOCUS, notification)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** 알림 본문에 넣을 줄(할 일 내용 포함)과 잠금 화면용 줄(개수만) */
    private fun todoLines(study: StudyManager, petName: String): Pair<String?, String> {
        if (DayClock.isLateNight()) return Lines.says(petName, Lines.SLEEP) to Lines.SLEEP
        val count = study.remainingTodoCount()
        val next = study.nextRemainingTodo() ?: return null to ""
        return Lines.nextTodo(next) to Lines.todosLeftPublic(count)
    }

    /** 누적 알림. thresholdMinutes = 방금 넘어선 기준(30, 60, … 목표 이후엔 15분 간격) */
    fun watchAlert(context: Context, day: LocalDate, thresholdMinutes: Int) {
        if (!canNotify(context)) return
        createChannels(context)
        val stores = Stores.of(context)
        val repo = UsageRepository(stores)
        val state = PetEngine(stores).state()
        val (todoLine, publicLine) = todoLines(StudyManager(stores), state.name)

        val title = Lines.watchTitle(formatMinutes(thresholdMinutes))
        val breakdown = ShortFormApp.entries
            .map { it to repo.millis(day, it) }
            .filter { it.second >= 60_000 }
            .joinToString(" · ") { "${it.first.label} ${formatDuration(it.second)}" }
        val goal = repo.dailyGoalMinutes
        val goalLine = when {
            thresholdMinutes > goal -> Lines.watchGoalOver(formatMinutes(goal), formatMinutes(thresholdMinutes - goal))
            thresholdMinutes == goal -> Lines.watchGoalReached(formatMinutes(goal))
            else -> Lines.watchGoalLeft(formatMinutes(goal - thresholdMinutes))
        }
        val petLine = if (DayClock.isLateNight()) null else Lines.says(state.name, PetEngine.speech(state.mood, state.stage))

        val body = listOfNotNull(breakdown, goalLine, petLine, todoLine).filter { it.isNotBlank() }.joinToString("\n")
        notify(context, CH_ALERT, ID_ALERT, title, body, publicLine)
    }

    /** 연속 시청 알림 */
    fun continuousAlert(context: Context, continuousMinutes: Int) {
        if (!canNotify(context)) return
        createChannels(context)
        val stores = Stores.of(context)
        val name = PetEngine(stores).name
        val (todoLine, publicLine) = todoLines(StudyManager(stores), name)
        val body = listOfNotNull(Lines.continuousBody(continuousMinutes), todoLine).joinToString("\n")
        notify(context, CH_ALERT, ID_CONTINUOUS, Lines.says(name, Lines.CONTINUOUS_TITLE), body, publicLine)
    }

    fun sessionCompleted(context: Context, event: StudyEvent.SessionCompleted) {
        if (!canNotify(context)) return
        createChannels(context)
        val body = buildString {
            append(
                if (event.couponMinutes > 0) Lines.sessionDoneCoupon(event.couponMinutes)
                else Lines.SESSION_DONE_COUPON_MAX,
            )
            if (event.shieldGranted) append(Lines.SESSION_DONE_SHIELD)
        }
        notify(context, CH_STUDY, ID_STUDY, Lines.sessionDoneTitle(event.sessionMinutes), body, "")
    }

    fun dailyReport(context: Context, message: String) {
        if (!canNotify(context)) return
        createChannels(context)
        notify(context, CH_DAILY, ID_DAILY, Lines.DAILY_TITLE, message, "")
    }

    private fun notify(context: Context, channel: String, id: Int, title: String, body: String, publicText: String) {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // 잠금 화면에서 내용을 숨기도록 설정한 경우 보이는 버전: 할 일 내용 없이 개수만
        val publicVersion = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle(title)
            .setContentText(publicText)
            .build()
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}

object WatchAlerts {
    /** 누적 알림: 목표 전 30분마다, 목표 이후 15분마다. 같은 기준은 하루에 한 번만. */
    fun checkCumulative(context: Context, repo: UsageRepository, day: LocalDate) {
        val counted = (repo.countedMillis(day) / 60_000L).toInt()
        val threshold = UsageRepository.alertThreshold(counted, repo.dailyGoalMinutes)
        if (threshold >= 30 && threshold > repo.lastAlertMinutes(day)) {
            repo.setLastAlertMinutes(day, threshold)
            repo.increment(day, SyncManager.COUNT_ALERT_CUMULATIVE)
            Notifier.watchAlert(context, day, threshold)
        }
    }
}
