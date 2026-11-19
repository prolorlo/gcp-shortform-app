package com.shortformpet.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 진입 안내 화면 (F8).
 *
 * 목표를 넘긴 상태에서 쇼츠·릴스·틱톡을 켜면 캐릭터가 3초 동안 전체 화면으로 나옵니다.
 * "계속 보기" 버튼이 항상 있어 막지 않으며, 3초가 지나면 스스로 사라집니다.
 * 접근성 서비스 오버레이(TYPE_ACCESSIBILITY_OVERLAY)라 추가 권한이 필요 없습니다.
 */
class EntryGuideOverlay(private val service: AccessibilityService) {

    private val wm: WindowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null

    val isShowing: Boolean get() = view != null

    private val Int.dp: Int get() = (this * service.resources.displayMetrics.density).toInt()

    fun show(state: PetState, title: String, lines: List<String>): Boolean {
        if (view != null) return false
        val ink = P.INK
        val sub = P.SUB
        
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(P.BG)
            setPadding(32.dp, 48.dp, 32.dp, 48.dp)
            // 화면 아래 앱으로 터치가 새지 않게 합니다.
            isClickable = true
        }
        val pet = PetView(service).apply {
            stage = state.stage
            mood = state.mood
            stageProgress = state.stageProgress
            accessoryLevel = state.accessoryLevel
        }
        root.addView(pet, LinearLayout.LayoutParams(220.dp, 220.dp))
        root.addView(
            TextView(service).apply {
                text = title
                textSize = 24f
                typeface = P.SEMI
                setTextColor(ink)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp },
        )
        for (line in lines) {
            root.addView(
                TextView(service).apply {
                    text = line
                    textSize = 16f
                    setTextColor(sub)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 10.dp },
            )
        }
        val countdown = TextView(service).apply {
            textSize = 14f
            setTextColor(sub)
            gravity = Gravity.CENTER
        }
        root.addView(
            countdown,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 28.dp },
        )
        root.addView(
            Button(service).apply {
                text = Lines.GUIDE_CONTINUE
                isAllCaps = false
                textSize = 16f
                stateListAnimator = null
                typeface = P.MEDIUM
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(P.INK)
                    cornerRadius = 14.dp.toFloat()
                }
                setOnClickListener { dismiss() }
            },
            LinearLayout.LayoutParams(220.dp, 52.dp).apply { topMargin = 12.dp },
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try {
            wm.addView(root, params)
        } catch (e: Exception) {
            return false
        }
        view = root

        // 3초 카운트다운 후 자동으로 닫힘
        for (i in 0 until SHOW_SECONDS) {
            handler.postDelayed({ countdown.text = Lines.guideCountdown(SHOW_SECONDS - i) }, i * 1000L)
        }
        handler.postDelayed({ dismiss() }, SHOW_SECONDS * 1000L)
        return true
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        val v = view ?: return
        view = null
        try {
            wm.removeView(v)
        } catch (e: Exception) {
            // 이미 제거된 경우
        }
    }

    companion object {
        private const val SHOW_SECONDS = 3
    }
}
