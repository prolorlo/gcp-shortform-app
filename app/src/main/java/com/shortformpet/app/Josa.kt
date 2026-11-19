package com.shortformpet.app

/** 받침에 따라 조사를 고릅니다. 모찌가 / 별님이 / 어른으로 */
object Josa {
    private fun finalConsonant(word: String): Int? {
        val c = word.lastOrNull() ?: return null
        if (c !in '가'..'힣') return null
        return (c - '가') % 28 // 0 = 받침 없음, 8 = ㄹ
    }

    /** 이/가 조사만. 한글이 아니면 "이(가)" */
    fun subject(word: String): String = when (finalConsonant(word)) {
        null -> "이(가)"
        0 -> "가"
        else -> "이"
    }

    /** 단어 + 이/가 (이름용: 한글이 아니면 "가") */
    fun iGa(word: String): String = word + if ((finalConsonant(word) ?: 0) == 0) "가" else "이"

    /** 단어 + 을/를 (리본을, 목도리를) */
    fun eulReul(word: String): String = word + if ((finalConsonant(word) ?: 0) == 0) "를" else "을"

    /** 단어 + 으로/로 (받침 없음 또는 ㄹ 받침 → 로) */
    fun euro(word: String): String {
        val f = finalConsonant(word) ?: 0
        return word + if (f == 0 || f == 8) "로" else "으로"
    }
}
