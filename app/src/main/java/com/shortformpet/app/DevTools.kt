package com.shortformpet.app

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * 개발자 테스트 도구. 디버그 빌드(Android Studio의 Run, assembleDebug)에서만 동작합니다.
 * 릴리스 빌드에서는 메뉴가 보이지 않고 가상 시각도 적용되지 않습니다.
 *
 * 핵심은 "가상 시각": 앱이 보는 현재 시각을 앞으로 밀어서(Now.offsetMs)
 * 기기 날짜를 바꾸지 않고도 새벽 5시 정산·세션 종료·쿠폰 소진을 바로 확인합니다.
 * 밀어 둔 시간은 저장되므로 앱을 껐다 켜도 유지됩니다.
 */
object DevTools {
    private const val KEY_OFFSET = "dev_offset_ms"

    fun isDebugBuild(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** 가상 시각이 켜져 있는 동안에는 서버로 기록을 보내지 않습니다(가짜 날짜가 섞이지 않게). */
    val timeShifted: Boolean get() = Now.offsetMs != 0L

    /** 프로세스가 시작될 때 저장해 둔 가상 시각을 불러옵니다. */
    fun load(context: Context) {
        Now.offsetMs = if (isDebugBuild(context)) Stores.of(context).settings.getLong(KEY_OFFSET, 0L) else 0L
    }

    /** 시각을 앞으로 밉니다. */
    fun shift(context: Context, ms: Long) {
        if (!isDebugBuild(context) || ms <= 0L) return
        val offset = Now.offsetMs + ms
        Stores.of(context).settings.edit { putLong(KEY_OFFSET, offset) }
        Now.offsetMs = offset
    }

    /** 가짜 시청 시간 추가. 실제 시청과 똑같이 기록되고, 누적 알림 기준을 넘으면 알림도 옵니다. */
    fun addWatch(context: Context, app: ShortFormApp, minutes: Int) {
        if (!isDebugBuild(context)) return
        val repo = UsageRepository(Stores.of(context))
        val day = DayClock.logicalDay()
        repo.addWatch(day, app, minutes * 60_000L, 0L)
        WatchAlerts.checkCumulative(context, repo, day)
    }

    /**
     * 다음 날 오전 5시 1분으로 넘기고 바로 정산합니다.
     * [tracked] = false 이면 오늘을 "측정이 안 된 날"로 남긴 채 넘깁니다(정산·연속일수 보류 확인용).
     */
    fun skipToNextDay(context: Context, tracked: Boolean): List<SettleResult> {
        if (!isDebugBuild(context)) return emptyList()
        val stores = Stores.of(context)
        val today = DayClock.logicalDay()
        if (tracked) UsageRepository(stores).markTracked(today)
        shift(context, DayClock.nextResetMillis() - Now.ms() + 60_000L)
        StudyManager(stores).sync()
        val results = PetEngine(stores).catchUp()
        if (results.isNotEmpty()) Notifier.dailyReport(context, results.last().message)
        DailyResetScheduler.schedule(context)
        return results
    }

    /** 기록·캐릭터·공부 데이터를 모두 지우고 실제 시각으로 돌아갑니다. 설정(목표, 동의)은 남깁니다. */
    fun resetAll(context: Context) {
        if (!isDebugBuild(context)) return
        val stores = Stores.of(context)
        for (kv in listOf(stores.usage, stores.pet, stores.study)) {
            val keys = kv.keys().toList()
            kv.edit { keys.forEach { remove(it) } }
        }
        stores.settings.edit {
            remove(KEY_OFFSET)
            remove("sync_pending")
            remove("sync_last_sent")
        }
        Now.offsetMs = 0L
        DailyResetScheduler.schedule(context)
    }
}

/** 프로세스 시작 시(화면·측정 서비스·알람 어느 쪽이 먼저든) 가상 시각을 불러옵니다. */
class ShortFormPetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DevTools.load(this)
    }
}
