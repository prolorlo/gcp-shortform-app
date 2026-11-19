package com.shortformpet.app

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 지금 화면이 릴스·쇼츠 시청 화면인지 판별합니다.
 *
 * 지정한 뷰 ID 노드가 "화면에 보이고 + 기기 물리 화면 높이의 60% 이상"일 때만 인정합니다.
 * (홈 피드의 쇼츠 묶음, 하단 탭 버튼, 분할 화면처럼 작은 요소는 재지 않습니다)
 *
 * 화면의 글자, 영상 제목, 계정 정보는 읽지 않습니다. 뷰 ID 존재 여부와 크기만 확인합니다.
 * 앱 업데이트로 ID가 바뀌면 아래 목록만 고치면 됩니다.
 */
object ShortFormDetector {

    val YOUTUBE_SHORTS_IDS = listOf(
        "com.google.android.youtube:id/reel_player_page_container",
        "com.google.android.youtube:id/reel_recycler",
        "com.google.android.youtube:id/reel_watch_player",
    )

    val INSTAGRAM_REELS_IDS = listOf(
        "com.instagram.android:id/clips_viewer_view_pager",
        "com.instagram.android:id/reel_viewer_root",
    )

    private const val MIN_HEIGHT_RATIO = 0.6f

    /**
     * app: 숏폼 화면이면 그 앱, 아니면 null
     * foregroundPackage: 맨 앞 앱 (집중 세션의 앱 단위 차단에 씁니다)
     */
    data class Result(val app: ShortFormApp?, val foregroundPackage: String?)

    /** [screenHeight] = 기기 물리 화면 높이(px) */
    fun detect(root: AccessibilityNodeInfo?, screenHeight: Int): Result {
        if (root == null) return Result(null, null)
        val pkg = root.packageName?.toString()
        val app = ShortFormApp.fromPackage(pkg) ?: return Result(null, pkg)
        val ids = when (app) {
            ShortFormApp.YOUTUBE_SHORTS -> YOUTUBE_SHORTS_IDS
            ShortFormApp.INSTAGRAM_REELS -> INSTAGRAM_REELS_IDS
        }
        return Result(if (hasFullScreenNode(root, ids, screenHeight)) app else null, pkg)
    }

    private fun hasFullScreenNode(root: AccessibilityNodeInfo, ids: List<String>, screenHeight: Int): Boolean {
        val minHeight = (screenHeight * MIN_HEIGHT_RATIO).toInt()
        val rect = Rect()
        for (id in ids) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(id)
            } catch (e: Exception) {
                emptyList()
            }
            for (node in nodes) {
                if (!node.isVisibleToUser) continue
                node.getBoundsInScreen(rect)
                if (rect.height() >= minHeight) return true
            }
        }
        return false
    }
}
