package com.shortformpet.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL

/**
 * Firebase 설정.
 *
 * Firebase 콘솔에서 Realtime Database를 만든 뒤 그 주소를 아래에 넣어 주세요.
 * 예) "https://shortformpet-default-rtdb.asia-southeast1.firebasedatabase.app"
 * 비어 있으면 전송하지 않고 기기에만 쌓아 둡니다(최대 60일치). 주소를 넣으면 밀린 기록부터 보냅니다.
 *
 * 외부 라이브러리(Firebase SDK) 없이 Realtime Database의 REST API로 HTTPS 전송합니다.
 *   PUT <주소>/daily/<익명 ID>/<날짜>.json
 */
object SyncConfig {
    const val FIREBASE_DB_URL = ""
}

object SyncScheduler {
    private const val JOB_ID = 5001

    /** 보낼 기록이 있으면 네트워크가 연결됐을 때 전송 작업을 예약합니다. 실패하면 시스템이 간격을 늘려 가며 재시도합니다. */
    fun kick(context: Context) {
        if (SyncConfig.FIREBASE_DB_URL.isBlank()) return
        if (DevTools.timeShifted) return // 개발자 테스트의 가짜 날짜는 보내지 않음
        if (SyncManager(Stores.of(context)).pendingDays().isEmpty()) return
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(30 * 60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .build()
        try {
            scheduler.schedule(job)
        } catch (e: Exception) {
            // 예약 실패 시 다음 기회(앱 실행, 다음 정산)에 다시 시도
        }
    }
}

class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val allSent = SyncUploader.uploadPending(applicationContext)
            jobFinished(params, !allSent)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

object SyncUploader {

    /** 대기 중인 날짜를 오래된 것부터 한 건씩 보냅니다. 모두 보냈으면 true */
    fun uploadPending(context: Context): Boolean {
        val base = SyncConfig.FIREBASE_DB_URL.trim().trimEnd('/')
        if (base.isEmpty() || DevTools.timeShifted) return true
        val manager = SyncManager(Stores.of(context))
        val device = deviceInfo(context)
        for (day in manager.pendingDays()) {
            val ok = try {
                put("$base/${manager.path(day)}.json", manager.buildPayload(day, device))
            } catch (e: Exception) {
                false
            }
            if (!ok) return false
            manager.markSent(day)
        }
        return true
    }

    private fun put(url: String, body: String): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }

    private fun deviceInfo(context: Context): DeviceInfo {
        val pm = context.packageManager
        fun version(pkg: String): String? = try {
            pm.getPackageInfo(pkg, 0).versionName
        } catch (e: Exception) {
            null
        }
        return DeviceInfo(
            appVersion = version(context.packageName) ?: "",
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            androidVersion = Build.VERSION.RELEASE ?: "",
            youtubeVersion = version("com.google.android.youtube"),
            instagramVersion = version("com.instagram.android"),
        )
    }
}
