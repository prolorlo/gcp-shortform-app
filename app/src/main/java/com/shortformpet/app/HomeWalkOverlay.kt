package com.shortformpet.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/**
 * 홈 화면 산책 모찌 (설정 > 홈 화면 산책).
 *
 * - 홈 화면 위를 모찌가 이리저리 걸어 다니다가 멈춰 쉬고, 가끔 말풍선으로 말합니다.
 * - 끌어서 화면 왼쪽·오른쪽 끝에 놓으면 옆으로 쏙 들어가 두 눈만 빼꼼 내밀어 화면을 거의 가리지 않습니다.
 *   다시 화면 안쪽으로 끌어내면 산책을 이어 갑니다. 숨은 자리는 다음에도 기억합니다.
 * - 모찌 창은 모찌 크기만큼만 터치를 받고, 화면 밖으로 나간 만큼 창을 줄여 나머지 터치는 그대로 홈 화면으로 갑니다.
 * - 말풍선은 터치를 받지 않는 별도 창이며 화면 안에서 잘리지 않게 자리를 맞춥니다(위에 자리가 없으면 아래로).
 */
class HomeWalkOverlay(private val service: AccessibilityService, private val repo: UsageRepository) {

    private enum class Act { IDLE, WALK, HELD, TUCK, PEEK }

    private val wm: WindowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private val Int.dp: Int get() = (this * density).toInt()
    private fun dp(v: Float) = v * density
    private val size = SIZE_DP.dp
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private var walker: WalkerView? = null
    private var walkerLp: WindowManager.LayoutParams? = null
    private var bubble: BubbleView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var bubbleAdded = false

    val isShowing: Boolean get() = walker != null

    private var act = Act.IDLE
    private var actUntil = 0L
    /** 숨은 쪽: -1 왼쪽, 1 오른쪽, 0 돌아다님 */
    private var side = 0
    /** 모찌 칸(size × size)의 중심(화면 px). 숨을 때는 일부가 화면 밖으로 나갑니다. */
    private var x = 0f
    private var y = 0f
    private var tx = 0f
    private var ty = 0f
    private var screenW = 0
    private var screenH = 0
    private var nextScreenCheck = 0L
    private var lastFrame = 0L
    private var running = false
    private var nextChat = 0L
    private var night = false

    // 그림 상태
    private var t = 0f
    private var walkPhase = Float.NaN
    private var look = 0f
    private var jumpStart = -10_000L
    private var nextBlink = 0L
    private var blinkUntil = 0L

    // 지금 보이는 모찌 창 자리 (말풍선 위치 계산용)
    private var visL = 0
    private var visR = 0
    private var winTop = 0

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            step()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private val hideBubble = Runnable { removeBubble() }

    private fun now() = SystemClock.uptimeMillis()

    // ------------------------------------------------------------------ 보이기 · 숨기기

    fun show(state: PetState) {
        if (walker != null) {
            update(state)
            return
        }
        readScreen()
        val v = WalkerView()
        walker = v
        update(state)
        night = DayClock.isLateNight()
        side = repo.homePeekSide
        y = clampY(repo.homeY * screenH)
        x = if (side != 0) peekX(side) else clampX(repo.homeX * screenW)
        look = -side.toFloat()
        act = if (side != 0) Act.PEEK else Act.IDLE
        actUntil = now() + 1_500
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }
        walkerLp = lp
        place() // 붙이기 전에 창 자리·크기를 먼저 맞춥니다
        try {
            wm.addView(v, lp)
        } catch (_: Exception) {
            walker = null
            walkerLp = null
            return
        }
        running = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(frame)
        // 숨어 있을 때는 화면을 가리지 않게 조용히 있습니다.
        if (side == 0) say(Lines.PEEK_HOME.random(), 3_000)
        nextChat = now() + 25_000 + Random.nextLong(20_000)
    }

    fun update(state: PetState) {
        walker?.painter?.apply {
            stage = state.stage
            mood = state.mood
            hatch = state.hatchProgress
            accessory = state.accessoryLevel
        }
    }

    fun hide() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        handler.removeCallbacksAndMessages(null)
        val v = walker ?: return
        savePos()
        try {
            wm.removeView(v)
        } catch (e: Exception) {
        }
        walker = null
        walkerLp = null
        removeBubble()
        bubble = null
        bubbleLp = null
    }

    private fun savePos() {
        if (screenW <= 0 || screenH <= 0) return
        repo.homePeekSide = side
        repo.homeX = x / screenW
        repo.homeY = y / screenH
    }

    // ------------------------------------------------------------------ 화면 · 자리

    private fun readScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width()
            screenH = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            screenW = m.widthPixels
            screenH = m.heightPixels
        }
    }

    private fun clampX(v: Float): Float {
        val lo = size / 2f + 4.dp
        val hi = (screenW - size / 2f - 4.dp).coerceAtLeast(lo)
        return v.coerceIn(lo, hi)
    }

    private fun clampY(v: Float): Float {
        val lo = TOP_DP.dp + size / 2f
        val hi = (screenH - BOTTOM_DP.dp - size / 2f).coerceAtLeast(lo)
        return v.coerceIn(lo, hi)
    }

    private val maxR get() = size * 0.34f

    /** 숨었을 때 모찌 중심 x: 몸 절반 이상은 화면 밖, 두 눈은 화면 안에 딱 남깁니다. */
    private fun peekX(s: Int): Float {
        val p = walker?.painter ?: return if (s > 0) screenW.toFloat() else 0f
        val rx = p.bodyHalfWidth(maxR)
        val u = maxR / MochiPainter.UNIT_R
        // 화면 안쪽을 바라보면 눈이 0.08rx 안쪽으로 오므로 먼 쪽 눈은 중심에서 0.26rx 바깥에 있습니다.
        val inset = 0.26f * rx + 3.4f * u + dp(3f)
        return if (s > 0) screenW - inset else inset
    }

    /** 창이 화면 밖으로 나간 만큼 줄이고, 그만큼 그림을 옮겨 그립니다. */
    private fun place() {
        val v = walker ?: return
        val lp = walkerLp ?: return
        val left = (x - size / 2f).roundToInt()
        val top = (y - size / 2f).roundToInt()
        val l = left.coerceAtLeast(0)
        val r = (left + size).coerceAtMost(screenW).coerceAtLeast(l + 1)
        v.offsetX = (left - l).toFloat()
        if (lp.x != l || lp.y != top || lp.width != r - l) {
            lp.x = l
            lp.y = top
            lp.width = r - l
            if (v.isAttachedToWindow) {
                try {
                    wm.updateViewLayout(v, lp)
                } catch (_: Exception) {
                }
            }
        }
        v.invalidate()
        visL = l
        visR = r
        winTop = top
        placeBubble(false)
    }

    // ------------------------------------------------------------------ 움직임

    private fun step() {
        val n = now()
        val dt = if (lastFrame == 0L) 0f else ((n - lastFrame) / 1000f).coerceAtMost(0.05f)
        lastFrame = n
        t += dt * (2 * PI.toFloat() / 2.4f)
        if (n > nextBlink) {
            blinkUntil = n + 140
            nextBlink = n + 2_500 + Random.nextLong(3_000)
        }
        // 화면을 돌리면 자리를 다시 맞춥니다.
        if (n > nextScreenCheck) {
            nextScreenCheck = n + 2_000
            night = DayClock.isLateNight()
            val w = screenW
            val h = screenH
            readScreen()
            if ((w != screenW || h != screenH) && act != Act.HELD) {
                y = clampY(y)
                x = if (side != 0) peekX(side) else clampX(x)
                if (act == Act.WALK) act = Act.IDLE
            }
        }

        when (act) {
            Act.IDLE -> {
                walkPhase = Float.NaN
                if (n > actUntil) pickTarget(n)
                chat(n)
            }
            Act.WALK -> {
                val dx = tx - x
                val dy = ty - y
                val dist = hypot(dx, dy)
                val sp = dp(WALK_DPS) * dt
                if (dist <= sp || dist < 1f) {
                    x = tx
                    y = ty
                    act = Act.IDLE
                    actUntil = n + 2_000 + Random.nextLong(4_000)
                    walkPhase = Float.NaN
                } else {
                    x += dx / dist * sp
                    y += dy / dist * sp
                    walkPhase = (if (walkPhase.isNaN()) 0f else walkPhase) + dt * 9f
                    look = (dx / dp(30f)).coerceIn(-1f, 1f)
                }
                chat(n)
            }
            Act.HELD -> walkPhase = Float.NaN
            Act.TUCK -> {
                // 종종걸음으로 옆으로 쏙
                val target = peekX(side)
                val dx = target - x
                val sp = dp(TUCK_DPS) * dt
                if (abs(dx) <= sp) {
                    x = target
                    act = Act.PEEK
                    walkPhase = Float.NaN
                    savePos()
                } else {
                    x += sign(dx) * sp
                    walkPhase = (if (walkPhase.isNaN()) 0f else walkPhase) + dt * 14f
                    look = sign(dx)
                }
            }
            Act.PEEK -> {
                walkPhase = Float.NaN
                // 화면 안쪽을 빼꼼 바라봅니다
                look += (-side - look) * min(1f, dt * 6f)
            }
        }
        walker?.painter?.sleepy = night && (act == Act.IDLE || act == Act.PEEK)
        place()
    }

    private fun pickTarget(n: Long) {
        val p = walker?.painter
        if (p == null || p.stage == PetStage.EGG) {
            // 알은 제자리에서 흔들흔들
            actUntil = n + 4_000
            return
        }
        tx = clampX(x + (Random.nextFloat() * 2f - 1f) * screenW * 0.45f)
        ty = clampY(y + (Random.nextFloat() * 2f - 1f) * screenH * 0.3f)
        act = Act.WALK
    }

    private fun chat(n: Long) {
        if (n < nextChat || bubbleAdded) return
        nextChat = n + 30_000 + Random.nextLong(30_000)
        say(Lines.HOME_CHAT.random(), 3_000)
    }

    // ------------------------------------------------------------------ 터치

    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var sideBeforeDrag = 0

    private fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                startX = x
                startY = y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    sideBeforeDrag = side
                    side = 0
                    act = Act.HELD
                    say(Lines.HOME_HELD, 1_500)
                }
                if (dragging) {
                    x = (startX + dx).coerceIn(0f, screenW.toFloat())
                    y = clampY(startY + dy)
                    place()
                }
            }
            MotionEvent.ACTION_UP -> if (dragging) release() else tap()
            MotionEvent.ACTION_CANCEL -> if (dragging) release()
        }
        return true
    }

    private fun tap() {
        jumpStart = now()
        say(Lines.HOME_TAP.random(), 2_000)
    }

    /** 놓은 자리가 화면 끝이면 옆으로 숨고, 안쪽이면 다시 돌아다닙니다. */
    private fun release() {
        dragging = false
        val edge = dp(EDGE_SNAP_DP)
        when {
            x < edge -> tuck(-1)
            x > screenW - edge -> tuck(1)
            else -> {
                side = 0
                x = clampX(x)
                act = Act.IDLE
                actUntil = now() + 1_200
                if (sideBeforeDrag != 0) say(Lines.HOME_OUT, 2_000)
                savePos()
            }
        }
    }

    private fun tuck(s: Int) {
        side = s
        act = Act.TUCK
        if (sideBeforeDrag == 0) say(Lines.HOME_HIDE, 2_000)
    }

    // ------------------------------------------------------------------ 말풍선

    private fun say(text: String, ms: Long) {
        if (walker == null) return
        val b = bubble ?: BubbleView().also { bubble = it }
        b.setText(text)
        val lp = bubbleLp ?: WindowManager.LayoutParams(
            b.bw, b.bh,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }.also { bubbleLp = it }
        lp.width = b.bw
        lp.height = b.bh
        if (!bubbleAdded) {
            placeBubble(true)
            try {
                wm.addView(b, lp)
                bubbleAdded = true
            } catch (e: Exception) {
                return
            }
        } else {
            placeBubble(true)
        }
        handler.removeCallbacks(hideBubble)
        handler.postDelayed(hideBubble, ms)
    }

    private fun removeBubble() {
        handler.removeCallbacks(hideBubble)
        val b = bubble
        if (bubbleAdded && b != null) {
            try {
                wm.removeView(b)
            } catch (e: Exception) {
            }
        }
        bubbleAdded = false
    }

    /** 모찌 머리 위(자리가 없으면 발 아래)에 두되, 화면 밖으로 잘리지 않게 맞춥니다. */
    private fun placeBubble(force: Boolean) {
        val b = bubble ?: return
        val lp = bubbleLp ?: return
        if (!bubbleAdded && !force) return
        val m = 8.dp
        val cx = (visL + visR) / 2f
        val headTop = winTop + size * 0.1f
        var by = (headTop - b.bh).roundToInt()
        val tailDown = by >= TOP_DP.dp
        if (!tailDown) by = winTop + size - 2.dp
        by = by.coerceIn(m, (screenH - b.bh - m).coerceAtLeast(m))
        val bx = (cx - b.bw / 2f).roundToInt().coerceIn(m, (screenW - b.bw - m).coerceAtLeast(m))
        val tail = (cx - bx).coerceIn(14f * density, b.bw - 14f * density)
        if (b.tailDown != tailDown || abs(b.tailX - tail) > 0.5f) {
            b.tailDown = tailDown
            b.tailX = tail
            b.invalidate()
        }
        if (force || lp.x != bx || lp.y != by) {
            lp.x = bx
            lp.y = by
            if (bubbleAdded) {
                try {
                    wm.updateViewLayout(b, lp)
                } catch (e: Exception) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ 그리는 뷰

    @SuppressLint("ViewConstructor")
    private inner class WalkerView : View(service) {
        val painter = MochiPainter()
        private val pose = MochiPainter.Pose()
        /** 창이 화면 왼쪽 밖으로 잘렸을 때 그림을 그만큼 왼쪽으로 (0 이하) */
        var offsetX = 0f

        init {
            contentDescription = "산책하는 모찌. 끌어서 화면 끝에 두면 숨어요"
        }

        override fun onDraw(canvas: Canvas) {
            val s = size.toFloat()
            val r = maxR
            val e = (now() - jumpStart) / 1000f
            pose.t = t
            pose.walk = walkPhase
            pose.look = look
            pose.jump = if (e in 0f..0.6f) sin(e / 0.6f * PI.toFloat()) * r * 0.35f else 0f
            pose.blink = now() < blinkUntil
            pose.held = act == Act.HELD
            pose.expr = if (act == Act.HELD) MochiPainter.Expr.SURPRISED else MochiPainter.Expr.NORMAL
            pose.shadow = act != Act.HELD && act != Act.PEEK
            painter.draw(canvas, offsetX + s / 2f, s - 6f * density, r, pose)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = onTouch(event)
    }

    /** 꼬리 달린 말풍선 (터치 없음) */
    @SuppressLint("ViewConstructor")
    private inner class BubbleView : View(service) {
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = P.INK
            textSize = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 13f, service.resources.displayMetrics,
            )
            fontFeatureSettings = "tnum"
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = P.SURFACE }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = P.LINE
            strokeWidth = density
        }
        private val path = Path()
        private val rect = RectF()
        private val padH = 12.dp
        private val padV = 8.dp
        private val tailH = 7.dp
        private var layout: StaticLayout? = null
        var bw = 0
        var bh = 0
        var tailX = 0f
        var tailDown = true

        fun setText(text: String) {
            val maxW = BUBBLE_MAX_DP.dp - padH * 2
            val tw = min(ceil(tp.measureText(text)).toInt() + 1, maxW)
            val l = StaticLayout.Builder.obtain(text, 0, text.length, tp, tw)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.1f)
                .build()
            layout = l
            bw = tw + padH * 2
            bh = l.height + padV * 2 + tailH
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val l = layout ?: return
            val half = density / 2f
            val top = if (tailDown) half else tailH.toFloat()
            val bottom = if (tailDown) (bh - tailH).toFloat() else bh - half
            rect.set(half, top, bw - half, bottom)
            val rr = 12f * density
            canvas.drawRoundRect(rect, rr, rr, fill)
            canvas.drawRoundRect(rect, rr, rr, stroke)
            // 꼬리: 몸통 테두리를 덮고 두 변만 선을 긋습니다
            val tw = 7f * density
            path.reset()
            if (tailDown) {
                path.moveTo(tailX - tw, bottom - half)
                path.lineTo(tailX, bh - half)
                path.lineTo(tailX + tw, bottom - half)
            } else {
                path.moveTo(tailX - tw, top + half)
                path.lineTo(tailX, half)
                path.lineTo(tailX + tw, top + half)
            }
            path.close()
            canvas.drawPath(path, fill)
            val tipY = if (tailDown) bh - half else half
            val baseY = if (tailDown) bottom else top
            canvas.drawLine(tailX - tw, baseY, tailX, tipY, stroke)
            canvas.drawLine(tailX, tipY, tailX + tw, baseY, stroke)
            canvas.save()
            canvas.translate(padH.toFloat(), top + padV)
            l.draw(canvas)
            canvas.restore()
        }
    }

    private companion object {
        /** 모찌 창 크기 (dp) */
        const val SIZE_DP = 72
        /** 걷는 속도 · 숨으러 가는 속도 (dp/s) */
        const val WALK_DPS = 55f
        const val TUCK_DPS = 220f
        /** 모찌 중심이 화면 끝에서 이만큼 안이면 옆으로 숨습니다 (dp) */
        const val EDGE_SNAP_DP = 56f
        /** 상태 표시줄 · 아래 독/내비게이션 바는 비워 둡니다 (dp) */
        const val TOP_DP = 32
        const val BOTTOM_DP = 72
        const val BUBBLE_MAX_DP = 200
    }
}
