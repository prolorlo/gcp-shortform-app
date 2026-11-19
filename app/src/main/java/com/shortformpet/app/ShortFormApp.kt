package com.shortformpet.app

/** 측정 대상 숏폼 서비스 */
enum class ShortFormApp(val label: String, val packages: Set<String>) {
    YOUTUBE_SHORTS("유튜브 쇼츠", setOf("com.google.android.youtube")),
    INSTAGRAM_REELS("인스타 릴스", setOf("com.instagram.android")),
    TIKTOK(
        "틱톡",
        setOf(
            "com.zhiliaoapp.musically",     // TikTok (글로벌/한국)
            "com.zhiliaoapp.musically.go",  // TikTok Lite
            "com.ss.android.ugc.trill",     // TikTok (일부 지역)
        ),
    );

    companion object {
        fun fromPackage(pkg: String?): ShortFormApp? =
            if (pkg == null) null else entries.firstOrNull { pkg in it.packages }
    }
}
