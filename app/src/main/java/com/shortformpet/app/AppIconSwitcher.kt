package com.shortformpet.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 행복도 H 5단계에 따라 홈 화면 앱 아이콘(표정)을 바꿉니다 (DESIGN 5.2).
 *
 * AndroidManifest의 activity-alias 5개 중 하나만 켜 둡니다. 일부 런처(One UI 등)는 앱이 앞에 있을 때
 * 아이콘을 바꾸면 화면이 깜빡이거나 작업이 닫힐 수 있어서,
 * 단계가 바뀌었을 때만, 그리고 앱이 뒤로 내려간 시점(onStop · 정산 · 측정 서비스)에만 바꿉니다.
 */
object AppIconSwitcher {

    private val ALIASES = listOf("AliasMoodJoyful", "AliasMoodSmile", "AliasMoodHungry", "AliasMoodSulky", "AliasMoodSick")
    private const val KEY = "icon_bucket"

    /** 지금 켜 둬야 할 단계 (1~5). 설정에서 끄면 1단계(기본 얼굴) */
    fun desiredBucket(context: Context): Int {
        val stores = Stores.of(context)
        if (!UsageRepository(stores).dynamicIconEnabled) return 1
        return PetEngine(stores).state().mood.bucket
    }

    /** 단계가 달라졌을 때만 바꿉니다. 앱이 앞에 있을 때는 부르지 마세요. */
    fun applyIfNeeded(context: Context) {
        try {
            val stores = Stores.of(context)
            val want = desiredBucket(context)
            val current = stores.pet.getInt(KEY, 1)
            if (want == current) return
            val pm = context.packageManager
            // 새 아이콘을 먼저 켜고 나머지를 끕니다(잠깐이라도 아이콘이 사라지지 않게).
            pm.setComponentEnabledSetting(component(context, want), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            for (b in 1..ALIASES.size) {
                if (b == want) continue
                pm.setComponentEnabledSetting(component(context, b), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            stores.pet.edit { putInt(KEY, want) }
        } catch (e: Exception) {
            // 아이콘을 못 바꿔도 앱은 그대로 씁니다.
        }
    }

    fun currentBucket(context: Context): Int = Stores.of(context).pet.getInt(KEY, 1)

    private fun component(context: Context, bucket: Int) =
        ComponentName(context.packageName, "${context.packageName}.${ALIASES[bucket - 1]}")
}
