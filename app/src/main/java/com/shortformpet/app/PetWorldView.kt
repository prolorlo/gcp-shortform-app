package com.shortformpet.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 모찌 탭 전체 화면 (DESIGN 5.1).
 *
 * - 배경: 방 안(창문 · 우드 바닥 · 고양이 방석) / 들판(하늘 · 잔디 · 나비)
 * - 모찌가 바닥 위를 스스로 걷고, 서서 두리번거리고, 방석에서 쉬고, 들판에서 뛰어놉니다.
 *   아래로 갈수록(앞쪽일수록) 조금 크게 그려 원근감을 줍니다.
 * - 모찌를 누르면 폴짝 뛰며 반응(쓰다듬기).
 * - 밥 던지기 모드: 화면 아래 쑥떡을 위로 밀어 던지면 포물선으로 날아가고, 모찌에게 닿으면 받아먹습니다.
 *
 * 위쪽 HUD와 아래 도크는 이 뷰 위에 얹히므로 [insetTop] / [insetBottom]만큼은 걷는 자리에서 뺍니다.
 */
class PetWorldView(context: Context) : View(context) {

    // ------------------------------------------------------------------ 바깥에서 정하는 값

    private val painter = MochiPainter()

    /** 0 방 안, 1 들판 */
    var scene = 0
        set(value) {
            if (field != value) {
                field = value
                state = St.IDLE
                stateUntil = 0L
                invalidate()
            }
        }
    var insetTop = 0f
        set(value) { field = value; invalidate() }
    var insetBottom = 0f
        set(value) { field = value; invalidate() }
    /** 탭이 보이고 화면이 켜져 있을 때만 움직입니다(배터리). */
    var active = false
        set(value) {
            field = value
            lastFrame = 0L
            if (value) postInvalidateOnAnimation()
        }
    var happiness = 100
    var idleText: String = ""
        set(value) { if (field != value) { field = value; if (tempUntil < SystemClock.uptimeMillis()) invalidate() } }

    var onPetTapped: (() -> Unit)? = null
    var onFoodCaught: (() -> Unit)? = null
    var onFoodMissed: (() -> Unit)? = null

    /** 밥 던지기 모드 */
    var aiming = false
        set(value) {
            if (field == value) return
            field = value
            food = if (value) Food.REST else Food.NONE
            if (value) {
                state = St.APPROACH
                stateUntil = SystemClock.uptimeMillis() + 60_000
                pickApproachTarget()
            } else if (state == St.APPROACH) {
                state = St.IDLE
                stateUntil = 0L
            }
            invalidate()
        }

    fun setPet(state: PetState) {
        painter.stage = state.stage
        painter.mood = state.mood
        painter.hatch = state.hatchProgress
        painter.accessory = state.accessoryLevel
        happiness = state.happiness
        contentDescription = "${state.name}, ${state.stage.label}, ${state.mood.label}. 두 번 눌러 쓰다듬기"
    }

    /** 말풍선을 잠깐 바꿉니다 */
    fun say(text: String, ms: Long = 4_000) {
        tempText = text
        tempUntil = SystemClock.uptimeMillis() + ms
        invalidate()
    }

    /** 폴짝 + 하트 (간식, 쓰다듬기) */
    fun react() {
        reactStart = SystemClock.uptimeMillis()
        invalidate()
    }

    /** 숫자가 떠오르는 효과 (+5) */
    fun floatText(text: String) {
        floats += Floater(text, SystemClock.uptimeMillis())
        invalidate()
    }

    /** 성장 모션: 반짝임 + 크게 폴짝 */
    fun celebrate() {
        celebrateStart = SystemClock.uptimeMillis()
        reactStart = celebrateStart
        invalidate()
    }

    // ------------------------------------------------------------------ 내부 상태

    private enum class St { IDLE, WALK, REST, PLAY, APPROACH, EAT }
    private enum class Food { NONE, REST, DRAG, FLY, LANDED }

    private val d = resources.displayMetrics.density
    private val motion: Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (e: Exception) {
        true
    }

    private var state = St.IDLE
    private var stateUntil = 0L
    private var goingToRest = false
    /** 모찌 위치: 0~1 비율(걷는 영역 안). 크기가 바뀌어도 자리를 지킵니다. */
    private var px = 0.5f
    private var py = 0.55f
    private var tx = 0.5f
    private var ty = 0.55f
    private var look = 0f
    private var walkPhase = 0f
    private var sit = 0f
    private var t = 0f
    private var lastFrame = 0L
    private var nextBlink = 0L
    private var blinkUntil = 0L

    private var tempText: String? = null
    private var tempUntil = 0L
    private var reactStart = -10_000L
    private var celebrateStart = -10_000L

    private class Floater(val text: String, val start: Long)
    private val floats = mutableListOf<Floater>()

    // 먹이
    private var food = Food.NONE
    private var fx = 0f
    private var fy = 0f
    private var fvx = 0f
    private var fvy = 0f
    private var landY = 0f
    private var landedAt = 0L
    private var eatUntil = 0L
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var downOnPet = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()
    private val pose = MochiPainter.Pose()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = P.INK
        textSize = 14f * resources.displayMetrics.scaledDensity
    }
    private val floatPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = P.ACCENT_TEXT
        textSize = 15f * resources.displayMetrics.scaledDensity
        typeface = P.SEMI
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    private var bubbleText = ""
    private var bubbleLayout: StaticLayout? = null

    init {
        isClickable = true
        isFocusable = true
    }

    // ------------------------------------------------------------------ 좌표

    private val areaTop get() = insetTop
    private val areaBottom get() = height - insetBottom
    /** 방 안: 벽과 바닥 경계, 들판: 지평선 */
    private val horizon get() = areaTop + (areaBottom - areaTop) * 0.36f
    private val walkTop get() = horizon + (areaBottom - horizon) * 0.22f
    private val walkBottom get() = areaBottom - (areaBottom - horizon) * 0.12f
    private fun sx(fx: Float) = width * (0.14f + 0.72f * fx)
    private fun sy(fy: Float) = walkTop + (walkBottom - walkTop) * fy
    private val baseR get() = min(width.toFloat(), (areaBottom - areaTop)) * 0.15f
    private fun depthScale(fy: Float) = 0.92f + 0.14f * fy
    private val foodR get() = 14f * d
    private val foodRestX get() = width / 2f
    private val foodRestY get() = areaBottom - 36f * d
    /** 방석 위치(비율) */
    private val cushionX = 0.8f
    private val cushionY = 0.18f

    private fun petR(): Float = painter.radius(baseR * depthScale(py))

    /** 모찌 몸 중심(화면 좌표) */
    private fun petCenterX() = sx(px)
    private fun petCenterY() = sy(py) - petR() * 0.95f - currentJump()

    private fun currentJump(): Float {
        val e = (SystemClock.uptimeMillis() - reactStart) / 1000f
        val big = SystemClock.uptimeMillis() - celebrateStart < 1500
        val dur = if (big) 0.9f else 0.6f
        var j = if (e in 0f..dur) sin(e / dur * PI.toFloat()) * baseR * (if (big) 0.8f else 0.35f) else 0f
        if (state == St.PLAY && !walkPhase.isNaN()) j += abs(sin(walkPhase * 0.5f)) * baseR * 0.25f
        return j
    }

    // ------------------------------------------------------------------ 행동

    private fun now() = SystemClock.uptimeMillis()

    private fun nextBehavior() {
        val night = DayClock.isLateNight()
        val tired = happiness < 40 || night
        val r = Random.nextFloat()
        goingToRest = false
        when {
            painter.stage == PetStage.EGG -> setState(St.IDLE, 4_000)
            scene == 0 && ((tired && r < 0.55f) || r < 0.15f) -> {
                goingToRest = true
                tx = cushionX; ty = cushionY
                setState(St.WALK, 8_000)
            }
            scene == 1 && r < 0.45f -> {
                tx = Random.nextFloat(); ty = Random.nextFloat()
                setState(St.PLAY, 2_000L + Random.nextLong(3_000))
            }
            r < 0.6f -> {
                tx = (px + Random.nextFloat() * 0.8f - 0.4f).coerceIn(0f, 1f)
                ty = (py + Random.nextFloat() * 0.6f - 0.3f).coerceIn(0f, 1f)
                setState(St.WALK, 2_000L + Random.nextLong(3_000))
            }
            else -> {
                // 다음에 갈 쪽을 먼저 바라봅니다
                look = if (Random.nextBoolean()) -1f else 1f
                setState(St.IDLE, 2_000L + Random.nextLong(2_000))
            }
        }
    }

    private fun setState(s: St, ms: Long) {
        state = s
        stateUntil = now() + ms
    }

    private fun pickApproachTarget() {
        tx = 0.5f + (Random.nextFloat() - 0.5f) * 0.2f
        ty = 0.5f
    }

    private fun step(dt: Float) {
        t += dt * (2 * PI.toFloat() / 2.4f)
        val n = now()
        if (n > nextBlink) {
            blinkUntil = n + 140
            nextBlink = n + 2_500 + Random.nextLong(3_000)
        }
        val sleepyNow = DayClock.isLateNight() || (state == St.REST && happiness < 40)
        painter.sleepy = sleepyNow && state != St.EAT

        if (!motion || painter.stage == PetStage.EGG) {
            walkPhase = Float.NaN
            return
        }

        if (state != St.APPROACH && state != St.EAT && n > stateUntil) {
            if (state == St.WALK && goingToRest && reached()) setState(St.REST, 4_000L + Random.nextLong(4_000))
            else nextBehavior()
        }

        when (state) {
            St.WALK, St.PLAY, St.APPROACH -> {
                val speed = (if (state == St.PLAY) 0.42f else 0.26f) * (if (happiness < 20) 0.6f else 1f)
                val dx = tx - px
                val dy = (ty - py) * 0.8f
                val dist = hypot(dx, dy)
                if (dist > 0.01f) {
                    val k = min(1f, speed * dt / dist)
                    px += dx * k
                    py += (ty - py) * k
                    look = (dx / 0.15f).coerceIn(-1f, 1f)
                    walkPhase = (if (walkPhase.isNaN()) 0f else walkPhase) + dt * 9f
                } else {
                    walkPhase = Float.NaN
                    if (state == St.APPROACH) {
                        look = 0f
                    } else if (goingToRest) {
                        setState(St.REST, 4_000L + Random.nextLong(4_000))
                    } else if (state == St.PLAY) {
                        tx = Random.nextFloat(); ty = Random.nextFloat()
                    }
                }
                // 먹이가 날아오면 그쪽으로 조금 따라갑니다(받아먹기 쉽게)
                if (state == St.APPROACH && food == Food.FLY) {
                    val foodFx = ((fx / width) - 0.14f) / 0.72f
                    tx = foodFx.coerceIn(0f, 1f)
                }
                sit = (sit - dt * 3f).coerceAtLeast(0f)
            }
            St.REST -> {
                walkPhase = Float.NaN
                sit = (sit + dt * 2f).coerceAtMost(1f)
                look = 0f
            }
            St.EAT -> {
                walkPhase = Float.NaN
                if (n > eatUntil) {
                    state = St.IDLE
                    stateUntil = n + 1_500
                }
            }
            St.IDLE -> {
                walkPhase = Float.NaN
                sit = (sit - dt * 3f).coerceAtLeast(0f)
            }
        }
        stepFood(dt)
    }

    private fun reached() = hypot(tx - px, ty - py) < 0.02f

    private fun stepFood(dt: Float) {
        when (food) {
            Food.FLY -> {
                fx += fvx * dt
                fy += fvy * dt
                fvy += GRAVITY * d * dt
                val hitR = petR() * 1.05f + foodR
                if (hypot(fx - petCenterX(), fy - petCenterY()) < hitR) {
                    food = Food.NONE
                    state = St.EAT
                    eatUntil = now() + 1_600
                    reactStart = now()
                    onFoodCaught?.invoke()
                    return
                }
                if (fvy > 0 && fy >= landY) {
                    fy = landY
                    food = Food.LANDED
                    landedAt = now()
                    onFoodMissed?.invoke()
                }
                if (fx < -foodR * 4 || fx > width + foodR * 4) {
                    food = Food.LANDED
                    landedAt = now()
                    onFoodMissed?.invoke()
                }
            }
            Food.LANDED -> {
                if (now() - landedAt > 900) {
                    food = if (aiming) Food.REST else Food.NONE
                }
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ 터치

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                downOnPet = hitsPet(ev.x, ev.y)
                if (aiming) {
                    // 던지는 동안 옆 탭으로 넘어가지 않게 합니다.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (food == Food.REST && hypot(ev.x - foodRestX, ev.y - foodRestY) < 56 * d) {
                        food = Food.DRAG
                        fx = ev.x; fy = ev.y
                        velocity?.recycle()
                        velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                    }
                } else if (downOnPet) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (food == Food.DRAG) {
                    velocity?.addMovement(ev)
                    fx = ev.x
                    fy = ev.y.coerceAtLeast(areaBottom - 160 * d)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (food == Food.DRAG) {
                    velocity?.addMovement(ev)
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    val vy = velocity?.yVelocity ?: 0f
                    velocity?.recycle()
                    velocity = null
                    if (vy < -600 * d) launch(vx, vy) else food = Food.REST
                    invalidate()
                } else if (downOnPet && hypot(ev.x - downX, ev.y - downY) < 24 * d) {
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (food == Food.DRAG) food = Food.REST
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        reactStart = now()
        onPetTapped?.invoke()
        invalidate()
        return true
    }

    private fun hitsPet(x: Float, y: Float): Boolean {
        val r = petR()
        return hypot(x - petCenterX(), y - petCenterY()) < r * 1.3f
    }

    private fun launch(vx: Float, vy: Float) {
        food = Food.FLY
        val k = 0.62f
        fvx = (vx * k).coerceIn(-900 * d, 900 * d)
        fvy = (vy * k).coerceIn(-2100 * d, -500 * d)
        // 꼭대기에서 140dp쯤 아래로 떨어지면 바닥에 닿은 것으로 봅니다.
        val apexRise = fvy * fvy / (2 * GRAVITY * d)
        landY = (fy - apexRise + 140 * d).coerceIn(walkTop, foodRestY)
    }

    // ------------------------------------------------------------------ 그리기

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val n = now()
        val dt = if (lastFrame == 0L) 0f else ((n - lastFrame) / 1000f).coerceAtMost(0.05f)
        lastFrame = n
        if (active) step(dt)

        if (scene == 0) drawRoom(canvas) else drawField(canvas)

        // 모찌
        val r = baseR * depthScale(py)
        pose.t = t
        pose.walk = walkPhase
        pose.look = look
        pose.jump = currentJump()
        pose.sit = sit
        pose.chew = state == St.EAT
        pose.blink = n < blinkUntil
        pose.shadow = true
        val groundY = sy(py) - if (sit > 0f) r * 0.12f * sit else 0f
        painter.draw(canvas, sx(px), groundY, r, pose)

        // 하트
        val e = (n - reactStart) / 1000f
        if (e in 0f..1.4f) {
            val alpha = ((1f - e / 1.4f) * 255).toInt().coerceIn(0, 255)
            val rise = e * r * 0.6f
            val cx = sx(px)
            val y0 = petCenterY() - petR() * 1.4f
            painter.heart(canvas, cx - r * 0.45f, y0 + r * 0.3f - rise, r * 0.12f, alpha)
            painter.heart(canvas, cx, y0 - rise * 1.2f, r * 0.15f, alpha)
            painter.heart(canvas, cx + r * 0.45f, y0 + r * 0.2f - rise * 0.9f, r * 0.1f, alpha)
        }
        drawCelebrate(canvas, n)
        drawFood(canvas)
        drawFloats(canvas, n)
        drawBubble(canvas, n)

        if (active) postInvalidateOnAnimation()
    }

    private fun drawRoom(canvas: Canvas) {
        val w = width.toFloat()
        val hz = horizon
        fill.style = Paint.Style.FILL
        fill.color = P.ROOM_WALL
        canvas.drawRect(0f, 0f, w, hz, fill)
        fill.color = P.ROOM_FLOOR
        canvas.drawRect(0f, hz, w, height.toFloat(), fill)
        // 걸레받이
        line.color = Color.rgb(200, 196, 176)
        line.strokeWidth = 2f * d
        canvas.drawLine(0f, hz, w, hz, line)
        // 마루 판자 (앞으로 올수록 간격이 넓게)
        line.color = Color.rgb(214, 209, 186)
        line.strokeWidth = 1f * d
        var y = hz
        var gap = 14f * d
        while (y < height) {
            y += gap
            canvas.drawLine(0f, y, w, y, line)
            gap *= 1.25f
        }

        // 창문
        val wallTop = areaTop
        val wallH = hz - wallTop
        if (wallH > 60 * d) {
            val night = DayClock.isLateNight() || DayClock.hour() >= 19
            rect.set(w * 0.1f, wallTop + wallH * 0.18f, w * 0.42f, hz - wallH * 0.16f)
            fill.color = if (night) Color.rgb(54, 64, 82) else Color.rgb(220, 234, 240)
            canvas.drawRoundRect(rect, 6 * d, 6 * d, fill)
            if (night) {
                fill.color = Color.rgb(240, 236, 210)
                canvas.drawCircle(rect.left + rect.width() * 0.7f, rect.top + rect.height() * 0.32f, rect.height() * 0.11f, fill)
            } else {
                fill.color = Color.WHITE
                canvas.drawCircle(rect.left + rect.width() * 0.3f, rect.top + rect.height() * 0.62f, rect.height() * 0.12f, fill)
                canvas.drawCircle(rect.left + rect.width() * 0.42f, rect.top + rect.height() * 0.58f, rect.height() * 0.15f, fill)
            }
            line.color = Color.rgb(176, 184, 166)
            line.strokeWidth = 2f * d
            canvas.drawRoundRect(rect, 6 * d, 6 * d, line)
            canvas.drawLine(rect.centerX(), rect.top, rect.centerX(), rect.bottom, line)
            canvas.drawLine(rect.left, rect.centerY(), rect.right, rect.centerY(), line)
            // 창턱
            fill.color = Color.rgb(226, 230, 218)
            canvas.drawRect(rect.left - 6 * d, rect.bottom, rect.right + 6 * d, rect.bottom + 5 * d, fill)
            // 화분
            val potX = w * 0.82f
            val potBottom = hz
            fill.color = Color.rgb(196, 170, 140)
            rect.set(potX - 14 * d, potBottom - 26 * d, potX + 14 * d, potBottom)
            canvas.drawRoundRect(rect, 4 * d, 4 * d, fill)
            line.color = P.ACCENT
            line.strokeWidth = 3f * d
            canvas.drawLine(potX, potBottom - 26 * d, potX - 10 * d, potBottom - 50 * d, line)
            canvas.drawLine(potX, potBottom - 26 * d, potX + 8 * d, potBottom - 56 * d, line)
            canvas.drawLine(potX, potBottom - 26 * d, potX + 2 * d, potBottom - 44 * d, line)
        }

        // 러그
        fill.color = Color.rgb(214, 220, 200)
        val rugCy = sy(0.55f)
        rect.set(w * 0.16f, rugCy - (walkBottom - walkTop) * 0.26f, w * 0.84f, rugCy + (walkBottom - walkTop) * 0.26f)
        canvas.drawOval(rect, fill)

        // 고양이 방석
        val cx = sx(cushionX)
        val cy = sy(cushionY)
        val cr = baseR * depthScale(cushionY) * 1.25f
        fill.color = P.CUSHION
        rect.set(cx - cr, cy - cr * 0.42f, cx + cr, cy + cr * 0.36f)
        canvas.drawOval(rect, fill)
        line.color = Color.rgb(154, 165, 140)
        line.strokeWidth = 1.5f * d
        canvas.drawOval(rect, line)
        fill.color = Color.rgb(228, 236, 220)
        rect.set(cx - cr * 0.7f, cy - cr * 0.26f, cx + cr * 0.7f, cy + cr * 0.2f)
        canvas.drawOval(rect, fill)
    }

    private fun drawField(canvas: Canvas) {
        val w = width.toFloat()
        val hz = horizon
        val night = DayClock.isLateNight() || DayClock.hour() >= 19
        fill.style = Paint.Style.FILL
        fill.color = if (night) Color.rgb(206, 214, 224) else P.FIELD_SKY
        canvas.drawRect(0f, 0f, w, hz + 20 * d, fill)
        // 해 / 달
        fill.color = if (night) Color.rgb(250, 248, 232) else Color.rgb(250, 236, 196)
        canvas.drawCircle(w * 0.8f, areaTop + (hz - areaTop) * 0.35f, 18 * d, fill)
        // 먼 언덕
        fill.color = Color.rgb(200, 216, 186)
        path.reset()
        path.moveTo(0f, hz)
        path.cubicTo(w * 0.25f, hz - 36 * d, w * 0.45f, hz - 10 * d, w * 0.6f, hz - 24 * d)
        path.cubicTo(w * 0.78f, hz - 40 * d, w * 0.9f, hz - 12 * d, w, hz - 18 * d)
        path.lineTo(w, hz + 30 * d)
        path.lineTo(0f, hz + 30 * d)
        path.close()
        canvas.drawPath(path, fill)
        // 잔디
        fill.color = P.FIELD_GRASS
        path.reset()
        path.moveTo(0f, hz + 8 * d)
        path.quadTo(w * 0.5f, hz - 8 * d, w, hz + 10 * d)
        path.lineTo(w, height.toFloat())
        path.lineTo(0f, height.toFloat())
        path.close()
        canvas.drawPath(path, fill)
        // 풀잎
        line.color = Color.rgb(176, 198, 156)
        line.strokeWidth = 1.5f * d
        for (i in 0 until 14) {
            val gx = w * ((i * 37 % 100) / 100f)
            val gy = walkTop + (areaBottom - walkTop) * ((i * 53 % 100) / 100f)
            canvas.drawLine(gx, gy, gx - 3 * d, gy - 8 * d, line)
            canvas.drawLine(gx, gy, gx + 3 * d, gy - 9 * d, line)
        }
        // 민들레 홀씨
        for ((fxr, fyr) in listOf(0.2f to 0.15f, 0.86f to 0.42f, 0.1f to 0.7f)) {
            val x = w * fxr
            val y = sy(fyr) - 22 * d
            line.color = Color.rgb(150, 170, 130)
            canvas.drawLine(x, y, x, y + 22 * d, line)
            line.color = Color.rgb(250, 250, 246)
            for (k in 0 until 8) {
                val a = k * PI.toFloat() / 4
                canvas.drawLine(x, y, x + cos(a) * 7 * d, y + sin(a) * 7 * d, line)
            }
        }
        // 나비
        if (!night) {
            val bx = w * 0.5f + sin(t * 0.21f) * w * 0.32f
            val by = horizon + (walkTop - horizon) * 0.5f + sin(t * 0.37f) * 24 * d
            val flap = abs(sin(t * 3.4f))
            fill.color = Color.rgb(240, 200, 110)
            rect.set(bx - 9 * d * flap, by - 6 * d, bx, by + 4 * d)
            canvas.drawOval(rect, fill)
            rect.set(bx, by - 6 * d, bx + 9 * d * flap, by + 4 * d)
            canvas.drawOval(rect, fill)
            if (state == St.PLAY) look = ((bx - sx(px)) / (w * 0.3f)).coerceIn(-1f, 1f)
        }
    }

    private fun drawFood(canvas: Canvas) {
        if (food == Food.NONE) return
        val x: Float
        val y: Float
        var scale = 1f
        var alpha = 255
        when (food) {
            Food.REST -> { x = foodRestX; y = foodRestY + sin(t * 2) * 2 * d }
            Food.DRAG -> { x = fx; y = fy }
            Food.FLY -> {
                x = fx; y = fy
                scale = (1f - (foodRestY - fy) / (height * 1.4f)).coerceIn(0.55f, 1f)
            }
            Food.LANDED -> {
                x = fx; y = fy
                scale = 0.7f
                alpha = (255 * (1f - (now() - landedAt) / 900f)).toInt().coerceIn(0, 255)
            }
            Food.NONE -> return
        }
        val r = foodR * scale
        if (food == Food.REST) {
            fill.color = Color.argb(30, 28, 25, 23)
            rect.set(x - r, y + r * 0.7f, x + r, y + r * 1.1f)
            canvas.drawOval(rect, fill)
        }
        fill.color = Color.argb(alpha, 141, 176, 106)
        rect.set(x - r * 1.15f, y - r * 0.85f, x + r * 1.15f, y + r * 0.85f)
        canvas.drawOval(rect, fill)
        line.color = Color.argb(alpha, 23, 25, 26)
        line.strokeWidth = 1.5f * d
        canvas.drawOval(rect, line)
        fill.color = Color.argb(alpha * 160 / 255, 255, 255, 255)
        canvas.drawCircle(x - r * 0.4f, y - r * 0.3f, r * 0.22f, fill)
        if (food == Food.REST) {
            // 위로 던지라는 작은 화살표
            line.color = P.SUB
            line.strokeWidth = 2f * d
            val ay = y - r * 2.4f + sin(t * 2) * 3 * d
            canvas.drawLine(x, ay + 10 * d, x, ay - 2 * d, line)
            canvas.drawLine(x - 5 * d, ay + 3 * d, x, ay - 2 * d, line)
            canvas.drawLine(x + 5 * d, ay + 3 * d, x, ay - 2 * d, line)
        }
    }

    private fun drawCelebrate(canvas: Canvas, n: Long) {
        val e = (n - celebrateStart) / 1000f
        if (e !in 0f..1.8f) return
        val cx = sx(px)
        val cy = petCenterY()
        val alpha = ((1f - e / 1.8f) * 255).toInt().coerceIn(0, 255)
        fill.color = Color.argb(alpha, 240, 196, 60)
        for (i in 0 until 10) {
            val a = i * (2 * PI.toFloat() / 10) + e
            val dist = baseR * (0.8f + e * 1.1f)
            val s = 4 * d * (1f - e / 1.8f) + 2 * d
            val x = cx + cos(a) * dist
            val y = cy + sin(a) * dist * 0.8f
            path.reset()
            path.moveTo(x, y - s * 2)
            path.lineTo(x + s * 0.6f, y)
            path.lineTo(x, y + s * 2)
            path.lineTo(x - s * 0.6f, y)
            path.close()
            canvas.drawPath(path, fill)
        }
    }

    private fun drawFloats(canvas: Canvas, n: Long) {
        val it = floats.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val e = (n - f.start) / 1000f
            if (e > 1.4f) { it.remove(); continue }
            floatPaint.alpha = ((1f - e / 1.4f) * 255).toInt().coerceIn(0, 255)
            canvas.drawText(f.text, sx(px) + petR() * 0.9f, petCenterY() - petR() - e * 40 * d, floatPaint)
        }
    }

    private fun drawBubble(canvas: Canvas, n: Long) {
        val text = if (tempText != null && n < tempUntil) tempText!! else idleText
        if (text.isBlank()) return
        val maxW = (min(width * 0.7f, 260 * d)).toInt()
        if (text != bubbleText || bubbleLayout == null || bubbleLayout!!.width > maxW) {
            bubbleText = text
            val tw = min(textPaint.measureText(text).toInt() + 1, maxW)
            bubbleLayout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, tw)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.15f)
                .build()
        }
        val layout = bubbleLayout ?: return
        val padH = 14 * d
        val padV = 10 * d
        val bw = layout.width + padH * 2
        val bh = layout.height + padV * 2
        val petX = sx(px)
        val headY = petCenterY() - petR() * 1.25f
        var left = petX - bw / 2
        left = left.coerceIn(12 * d, width - bw - 12 * d)
        var top = headY - bh - 12 * d
        top = top.coerceAtLeast(areaTop + 8 * d)
        rect.set(left, top, left + bw, top + bh)
        fill.color = P.SURFACE
        canvas.drawRoundRect(rect, 12 * d, 12 * d, fill)
        line.color = P.LINE
        line.strokeWidth = 1 * d
        canvas.drawRoundRect(rect, 12 * d, 12 * d, line)
        // 꼬리
        val tailX = petX.coerceIn(rect.left + 16 * d, rect.right - 16 * d)
        path.reset()
        path.moveTo(tailX - 7 * d, rect.bottom - 0.5f * d)
        path.lineTo(tailX, rect.bottom + 8 * d)
        path.lineTo(tailX + 7 * d, rect.bottom - 0.5f * d)
        canvas.drawPath(path, fill)
        canvas.drawLine(tailX - 7 * d, rect.bottom, tailX, rect.bottom + 8 * d, line)
        canvas.drawLine(tailX, rect.bottom + 8 * d, tailX + 7 * d, rect.bottom, line)
        canvas.save()
        canvas.translate(left + padH, top + padV)
        layout.draw(canvas)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        velocity?.recycle()
        velocity = null
        super.onDetachedFromWindow()
    }

    private companion object {
        /** 먹이 중력 (dp/s²) */
        const val GRAVITY = 2400f
    }
}
