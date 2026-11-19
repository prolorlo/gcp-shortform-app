package com.shortformpet.app

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 현재 화면이 숏폼(쇼츠/릴스/틱톡) 시청 화면인지 판별합니다.
 *
 * - 틱톡: 앱 전체가 숏폼이므로 앱이 앞에 있으면 시청 중으로 봅니다.
 * - 유튜브/인스타그램: 전체 화면 숏폼 플레이어의 뷰 ID가 "화면에 보이고 + 화면 높이의 60% 이상"일 때만 인정합니다.
 *   (홈 피드의 쇼츠 묶음, 하단 릴스 탭 버튼 같은 작은 요소로 인한 오감지 방지)
 *
 * 화면의 글자, 영상 제목, 계정 정보는 읽지 않습니다. 뷰 ID 존재 여부만 확인합니다.
 *
 * ※ 유튜브·인스타그램 업데이트로 ID가 바뀌면 아래 목록만 수정하면 됩니다.
 *   앱 메인 화면의 "지금 감지 상태"로 바로 확인할 수 있습니다.
 */
object ShortFormDetector {

    val YOUTUBE_SHORTS_IDS = listOf(
        "com.google.android.youtube:id/reel_player_page_container",
        "com.google.android.youtube:id/reel_recycler",
        "com.google.android.youtube:id/reel_watch_player",
        "com.google.android.youtube:id/reel_watch_fragment_root",
        "com.google.android.youtube:id/reel_progress_bar",
    )

    val INSTAGRAM_REELS_IDS = listOf(
        "com.instagram.android:id/clips_viewer_view_pager",
        "com.instagram.android:id/clips_viewer_container",
        "com.instagram.android:id/clips_video_container",
        "com.instagram.android:id/clips_swipe_refresh_container",
    )

    /** 숏폼 플레이어로 인정할 최소 높이 비율 */
    private const val MIN_HEIGHT_RATIO = 0.6f

    data class Result(val app: ShortFormApp?, val foregroundPackage: String?)

    fun detect(root: AccessibilityNodeInfo?): Result {
        if (root == null) return Result(null, null)
        val pkg = root.packageName?.toString()
        val app = ShortFormApp.fromPackage(pkg) ?: return Result(null, pkg)
        val detected = when (app) {
            ShortFormApp.TIKTOK -> app
            ShortFormApp.YOUTUBE_SHORTS -> if (hasFullScreenNode(root, YOUTUBE_SHORTS_IDS)) app else null
            ShortFormApp.INSTAGRAM_REELS -> if (hasFullScreenNode(root, INSTAGRAM_REELS_IDS)) app else null
        }
        return Result(detected, pkg)
    }

    private fun hasFullScreenNode(root: AccessibilityNodeInfo, ids: List<String>): Boolean {
        val rootRect = Rect()
        root.getBoundsInScreen(rootRect)
        val minHeight = (rootRect.height() * MIN_HEIGHT_RATIO).toInt()
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
