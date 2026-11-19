package com.shortformpet.app

/** 측정 대상: 인스타 릴스 · 유튜브 쇼츠 두 가지만 잽니다. */
enum class ShortFormApp(val label: String, val short: String, val pkg: String) {
    YOUTUBE_SHORTS("유튜브 쇼츠", "쇼츠", "com.google.android.youtube"),
    INSTAGRAM_REELS("인스타 릴스", "릴스", "com.instagram.android");

    companion object {
        fun fromPackage(pkg: String?): ShortFormApp? = entries.firstOrNull { it.pkg == pkg }
    }
}
