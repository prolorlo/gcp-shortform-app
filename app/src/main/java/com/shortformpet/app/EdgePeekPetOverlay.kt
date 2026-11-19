package com.shortformpet.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 빼꼼 모찌 (DESIGN 5.4).
 * 화면 오른쪽 끝에서 모찌가 얼굴을 반쯤 내밀고 짧게 말합니다. 접근성 오버레이라 권한이 더 필요 없고,
 * 작은 창(64dp + 말풍선)만 차지해서 그 밖의 터치·스크롤은 그대로 아래 앱으로 갑니다.
 * 위아래로 끌어 자리를 옮길 수 있고, 누르면 들어갑니다.
 */
class EdgePeekPetOverlay(private val service: AccessibilityService, private val repo: UsageRepository) {

    enum class Mode { NONE, TRANSIENT, COUPON, HOME }

    private val wm: WindowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var bubble: TextView? = null
    private var pet: PetView? = null
    private var params: WindowManager.LayoutParams? = null
    var mode = Mode.NONE
        private set

    private val Int.dp: Int get() = (this * service.resources.displayMetrics.density).toInt()

    /** 몇 초 동안만 보이는 말 (연속 시청 절반, 목표 70%, 칭찬) */
    fun showTransient(state: PetState, text: String, ms: Long) {
        ensure(state)
        mode = Mode.TRANSIENT
        setText(text)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (mode == Mode.TRANSIENT) hide() }, ms)
    }

    /** 휴식 쿠폰을 켜고 보는 동안 남은 시간 */
    fun showCoupon(state: PetState, clock: String) {
        ensure(state)
        mode = Mode.COUPON
        handler.removeCallbacksAndMessages(null)
        setText(Lines.peekCoupon(clock))
    }

    /** 홈 화면 산책 (설정에서 켰을 때) */
    fun showHome(state: PetState) {
        if (mode == Mode.HOME) return
        ensure(state)
        mode = Mode.HOME
        handler.removeCallbacksAndMessages(null)
        setText(Lines.PEEK_HOME.random())
        handler.postDelayed({ if (mode == Mode.HOME) setText(null) }, 3_000)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        mode = Mode.NONE
        val v = root ?: return
        root = null
        bubble = null
        pet = null
        try {
            wm.removeView(v)
        } catch (e: Exception) {
        }
    }

    private fun setText(text: String?) {
        val b = bubble ?: return
        b.text = text ?: ""
        b.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensure(state: PetState) {
        pet?.let { it.setState(state); return }
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val b = TextView(service).apply {
            textSize = 13f
            setTextColor(P.INK)
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            background = roundRect(P.SURFACE, 12.dp.toFloat(), 1.dp, P.LINE)
            maxWidth = 200.dp
            fontFeatureSettings = "tnum"
        }
        layout.addView(b, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 4.dp })
        // 모찌는 오른쪽으로 20dp 밀어 화면 끝에 걸쳐 보이게 합니다.
        val holder = FrameLayout(service).apply { clipChildren = true }
        val p = PetView(service).apply {
            setState(state)
            shadow = false
            translationX = 20.dp.toFloat()
            contentDescription = "빼꼼 모찌"
        }
        holder.addView(p, FrameLayout.LayoutParams(64.dp, 64.dp))
        layout.addView(holder, LinearLayout.LayoutParams(44.dp, 64.dp))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = (service.resources.displayMetrics.heightPixels * repo.peekY).toInt()
        }

        // 위아래로 끌기, 짧게 누르면 들어가기
        var downRawY = 0f
        var startY = 0
        var moved = false
        holder.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downRawY = ev.rawY; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downRawY
                    if (abs(dy) > 8.dp) moved = true
                    if (moved) {
                        lp.y = (startY + dy).toInt().coerceAtLeast(0)
                        try { wm.updateViewLayout(layout, lp) } catch (e: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) repo.peekY = lp.y / service.resources.displayMetrics.heightPixels.toFloat()
                    else hide()
                }
            }
            true
        }
        try {
            wm.addView(layout, lp)
        } catch (e: Exception) {
            return
        }
        layout.translationX = 80.dp.toFloat()
        layout.animate().translationX(0f).setDuration(260).start()
        root = layout
        bubble = b
        pet = p
        params = lp
    }
}
