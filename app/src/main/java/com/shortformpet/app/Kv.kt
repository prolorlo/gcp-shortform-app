package com.shortformpet.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 키-값 저장소. 실제 앱에서는 SharedPreferences(기기 내부 파일)에 저장되어
 * 앱을 껐다 켜거나 휴대폰을 재부팅해도 유지됩니다.
 * 규칙 로직(PetEngine, StudyManager 등)은 이 인터페이스만 사용하므로 PC에서도 그대로 테스트할 수 있습니다.
 */
interface KV {
    fun getLong(key: String, def: Long): Long
    fun getInt(key: String, def: Int): Int
    fun getString(key: String, def: String?): String?
    fun getBoolean(key: String, def: Boolean): Boolean
    fun keys(): Set<String>

    /** 여러 값을 한 번에 저장합니다. */
    fun edit(block: KVEditor.() -> Unit)
}

interface KVEditor {
    fun putLong(key: String, value: Long)
    fun putInt(key: String, value: Int)
    fun putString(key: String, value: String?)
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
}

class SharedPrefsKV(private val prefs: SharedPreferences) : KV {
    override fun getLong(key: String, def: Long) = prefs.getLong(key, def)
    override fun getInt(key: String, def: Int) = prefs.getInt(key, def)
    override fun getString(key: String, def: String?) = prefs.getString(key, def)
    override fun getBoolean(key: String, def: Boolean) = prefs.getBoolean(key, def)
    override fun keys(): Set<String> = prefs.all.keys

    override fun edit(block: KVEditor.() -> Unit) {
        val e = prefs.edit()
        object : KVEditor {
            override fun putLong(key: String, value: Long) { e.putLong(key, value) }
            override fun putInt(key: String, value: Int) { e.putInt(key, value) }
            override fun putString(key: String, value: String?) { e.putString(key, value) }
            override fun putBoolean(key: String, value: Boolean) { e.putBoolean(key, value) }
            override fun remove(key: String) { e.remove(key) }
        }.block()
        // apply(): 메모리에는 즉시 반영되고 디스크에는 곧바로 비동기로 기록됩니다.
        e.apply()
    }
}

/** 앱이 쓰는 저장소 묶음 */
class Stores(val usage: KV, val settings: KV, val pet: KV, val study: KV) {
    companion object {
        fun of(context: Context): Stores {
            val app = context.applicationContext
            fun kv(name: String) = SharedPrefsKV(app.getSharedPreferences(name, Context.MODE_PRIVATE))
            return Stores(kv("usage"), kv("settings"), kv("pet"), kv("study"))
        }
    }
}

/** 현재 시각. 테스트에서 날짜를 넘겨 보기 위해 바꿔 끼울 수 있습니다. */
object Now {
    @Volatile var provider: () -> Long = { System.currentTimeMillis() }

    /** 개발자 테스트용 가상 시각(디버그 빌드 전용, DevTools 참고). 평소에는 0 */
    @Volatile var offsetMs: Long = 0L

    fun ms(): Long = provider() + offsetMs
}

/** 날짜가 들어간 키(prefix_2026-11-14_...)를 가진 오래된 기록 정리 */
fun KV.removeDatedKeysBefore(limit: java.time.LocalDate) {
    val old = keys().filter { key ->
        val datePart = key.split("_").getOrNull(1) ?: return@filter false
        val date = try {
            java.time.LocalDate.parse(datePart)
        } catch (e: Exception) {
            return@filter false
        }
        date.isBefore(limit)
    }
    if (old.isNotEmpty()) edit { old.forEach { remove(it) } }
}
