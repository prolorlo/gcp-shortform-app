package com.shortformpet.app

import java.time.LocalDate

/** 전송 시점의 기기·앱 정보 (선택 항목) */
data class DeviceInfo(
    val appVersion: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val youtubeVersion: String?,
    val instagramVersion: String?,
)

/**
 * 서버 동기화 대기 목록과 전송 내용(하루 한 건)을 만듭니다.
 *
 * 보내는 것: 익명 ID, 날짜, 앱별 시청 시간, 측정 시간, 알림 횟수,
 *           (선택) 앱·기기 버전, 목표 시간, 캐릭터 단계·행복도, 앱 실행 횟수, 공부 모드 기록, 연속일수
 * 보내지 않는 것: 화면 내용, 영상 제목, 계정, 캐릭터 이름, 할 일 내용, 위치·연락처
 */
class SyncManager(private val stores: Stores) {

    private val settings = stores.settings
    private val repo = UsageRepository(stores)
    private val study = StudyManager(stores)

    fun pendingDays(): List<LocalDate> =
        (settings.getString("sync_pending", "") ?: "").split(",").filter { it.isNotBlank() }.mapNotNull {
            try {
                LocalDate.parse(it)
            } catch (e: Exception) {
                null
            }
        }

    /** 전송에 실패한 날은 목록에 남아 다음 전송 때 함께 보냅니다. (최대 60일치) */
    fun enqueue(day: LocalDate) {
        val days = (pendingDays() + day).distinct().sorted().takeLast(UsageRepository.KEEP_DAYS.toInt())
        settings.edit { putString("sync_pending", days.joinToString(",")) }
    }

    fun markSent(day: LocalDate) {
        val days = pendingDays().filter { it != day }
        settings.edit {
            putString("sync_pending", days.joinToString(","))
            putString("sync_last_sent", day.toString())
        }
    }

    /** Firebase Realtime Database 경로: daily/<익명 ID>/<날짜> */
    fun path(day: LocalDate): String = "daily/${repo.anonId}/$day"

    fun buildPayload(day: LocalDate, device: DeviceInfo?): String {
        val snap = (stores.pet.getString("snap_$day", null) ?: "").split("|")
        val json = Json()
        // 필수
        json.str("anonId", repo.anonId)
        json.str("date", day.toString())
        json.obj("watchSec") {
            num("youtubeShorts", repo.millis(day, ShortFormApp.YOUTUBE_SHORTS) / 1000)
            num("instagramReels", repo.millis(day, ShortFormApp.INSTAGRAM_REELS) / 1000)
        }
        json.num("trackedSec", repo.trackedMillis(day) / 1000)
        json.obj("alerts") {
            num("cumulative", repo.count(day, COUNT_ALERT_CUMULATIVE))
            num("continuous", repo.count(day, COUNT_ALERT_CONTINUOUS))
            num("entryGuide", repo.count(day, COUNT_ENTRY_GUIDE))
        }
        // 선택
        if (device != null) {
            json.str("appVersion", device.appVersion)
            json.obj("device") {
                str("manufacturer", device.manufacturer)
                str("model", device.model)
                str("android", device.androidVersion)
            }
            json.obj("targetAppVersions") {
                str("youtube", device.youtubeVersion)
                str("instagram", device.instagramVersion)
            }
        }
        snap.getOrNull(4)?.toIntOrNull()?.let { json.num("goalMin", it) }
        if (snap.size >= 4) {
            json.obj("pet") {
                str("stage", snap[0])
                num("happiness", snap[1].toIntOrNull() ?: 0)
            }
            json.obj("streak") {
                num("days", snap[2].toIntOrNull() ?: 0)
                num("shields", snap[3].toIntOrNull() ?: 0)
            }
        }
        json.num("appLaunches", repo.count(day, COUNT_LAUNCH))
        json.obj("study") {
            num("sessionsStarted", study.sessionsStarted(day))
            num("sessionsCompleted", study.sessionsCompleted(day))
            num("sessionsFailed", study.sessionsFailed(day))
            num("sessionBlocks", repo.count(day, COUNT_SESSION_BLOCK))
            num("todosTotal", study.todosTotal(day))
            num("todosDone", study.todosDone(day))
            num("couponUsedSec", study.couponUsedMs(day) / 1000)
            num("couponWatchSec", repo.exemptMillis(day) / 1000)
        }
        return json.build()
    }

    companion object {
        const val COUNT_ALERT_CUMULATIVE = "alert_cum"
        const val COUNT_ALERT_CONTINUOUS = "alert_cont"
        const val COUNT_ENTRY_GUIDE = "entry_guide"
        const val COUNT_LAUNCH = "launch"
        const val COUNT_SESSION_BLOCK = "sess_block"
    }
}

/** 외부 라이브러리 없이 쓰는 아주 작은 JSON 작성기 */
class Json {
    private val sb = StringBuilder("{")
    private var first = true

    private fun key(k: String) {
        if (!first) sb.append(',')
        first = false
        sb.append('"').append(escape(k)).append("\":")
    }

    fun str(k: String, v: String?) {
        key(k)
        if (v == null) sb.append("null") else sb.append('"').append(escape(v)).append('"')
    }

    fun num(k: String, v: Number) {
        key(k)
        sb.append(v.toString())
    }

    fun obj(k: String, block: Json.() -> Unit) {
        key(k)
        sb.append(Json().apply(block).build())
    }

    fun build(): String = "$sb}"

    private fun escape(s: String): String = buildString {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}
