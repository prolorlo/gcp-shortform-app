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
 * 홈 화면 산책([Mode.HOME])은 따로 돌아다니는 [HomeWalkOverlay]가 맡습니다.
 */
class EdgePeekPetOverlay(private val service: AccessibilityService, private val repo: UsageRepository) {

    enum class Mode { NONE, TRANSIENT, COUPON, HOME }

    private val wm: WindowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var bubble: TextView? = null
    private var pet: PetView? = null
    private var holder: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private val walker = HomeWalkOverlay(service, repo)
    var mode = Mode.NONE
        private set

    private val Int.dp: Int get() = (this * service.resources.displayMetrics.density).toInt()

    /** 몇 초 동안만 보이는 말 (연속 시청 절반, 목표 70%, 칭찬) */
    fun showTransient(state: PetState, text: String, ms: Long) {
        ensure(state)
        mode = Mode.TRANSIENT
        applyMode()
        setText(text)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (mode == Mode.TRANSIENT) hide() }, ms)
    }

    /** 휴식 쿠폰을 켜고 보는 동안 남은 시간 */
    fun showCoupon(state: PetState, clock: String) {
        ensure(state)
        mode = Mode.COUPON
        applyMode()
        handler.removeCallbacksAndMessages(null)
        setText(Lines.peekCoupon(clock))
    }

    /** 홈 화면 산책 (설정에서 켰을 때) */
    fun showHome(state: PetState) {
        if (mode == Mode.HOME && walker.isShowing) {
            walker.update(state)
            return
        }
        removeEdge()
        mode = Mode.HOME
        walker.show(state)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        mode = Mode.NONE
        walker.hide()
        removeEdge()
    }

    private fun removeEdge() {
        handler.removeCallbacksAndMessages(null)
        val v = root ?: return
        root = null
        bubble = null
        pet = null
        holder = null
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

    /** 빼꼼(알림·쿠폰): 몸 오른쪽은 화면 끝에 걸치되, 두 눈은 안쪽에 남깁니다. */
    private fun applyMode() {
        val h = holder ?: return
        val p = pet ?: return
        val lp = h.layoutParams as LinearLayout.LayoutParams
        lp.width = PEEK_W.dp
        h.layoutParams = lp
        p.translationX = (PET - PEEK_W + PEEK_OUT).dp.toFloat()
        p.look = -0.6f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensure(state: PetState) {
        // 홈 산책 중이었다면 산책 모찌는 들여보냅니다.
        if (mode == Mode.HOME) walker.hide()
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
        // 모찌를 얼마나 화면 끝에 걸칠지는 [applyMode]에서 정합니다.
        val holder = FrameLayout(service).apply { clipChildren = true }
        val p = PetView(service).apply {
            setState(state)
            shadow = false
            look = -0.6f
            contentDescription = "빼꼼 모찌"
        }
        holder.addView(p, FrameLayout.LayoutParams(PET.dp, PET.dp))
        layout.addView(holder, LinearLayout.LayoutParams(PEEK_W.dp, PET.dp))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = clampY((service.resources.displayMetrics.heightPixels * repo.peekY).toInt())
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
                        lp.y = clampY((startY + dy).toInt())
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
        this.holder = holder
        params = lp
    }

    /** 위아래로 끌어도 모찌가 화면 아래로 빠져 잘리지 않게 합니다. */
    private fun clampY(y: Int): Int {
        val maxY = service.resources.displayMetrics.heightPixels - PET.dp - 24.dp
        return y.coerceIn(0, maxY.coerceAtLeast(0))
    }

    private companion object {
        /** 모찌 그림 크기 (dp) */
        const val PET = 64
        /** 빼꼼할 때 화면 안에 보이는 폭 (dp). 예전 44dp는 눈까지 잘렸습니다. */
        const val PEEK_W = 56
        /** 빼꼼할 때 화면 밖으로 나가는 정도 (dp) */
        const val PEEK_OUT = 4
    }
}
