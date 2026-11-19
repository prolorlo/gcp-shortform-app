package com.shortformpet.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 새벽 5시 알람.
 *
 * 하루 초기화 자체는 DayClock(현재 시각 − 5시간의 날짜)으로 계산되므로 알람이 몇 분 늦어도 기록은 정확합니다.
 * 알람은 측정 서비스가 잠들어 있어도 5시에 어제를 정산하고, 결과 알림을 보내고,
 * 정산 직후 하루 기록을 서버로 보내는 작업을 예약하기 위한 것입니다.
 * 정확한 알람 권한(SCHEDULE_EXACT_ALARM)이 필요 없는 setAndAllowWhileIdle 을 사용합니다.
 */
object DailyResetScheduler {
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, DailyResetReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 가상 시각(개발자 테스트)을 쓰는 중이면 실제 시각으로 되돌려 예약합니다.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, DayClock.nextResetMillis() - Now.offsetMs, pi)
    }
}

class DailyResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val stores = Stores.of(context)
        for (event in StudyManager(stores).sync()) {
            if (event is StudyEvent.SessionCompleted) Notifier.sessionCompleted(context, event)
        }
        val results = PetEngine(stores).catchUp()
        if (results.isNotEmpty()) Notifier.dailyReport(context, results.last().message)
        DailyResetScheduler.schedule(context)
        SyncScheduler.kick(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        DailyResetScheduler.schedule(context)
        SyncScheduler.kick(context)
    }
}
