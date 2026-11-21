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
import android.view.ViewConfiguration
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt
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
 * - 모찌를 잡고 끌면(또는 꾹 누르면) 손에 들려 대롱대롱 흔들리고, 빠르게 밀다 놓으면 던져져
 *   벽·천장·바닥에 부딪혀 말랑하게 찌그러지며 튕기다가 굴러서 멈춘 뒤 다시 일어섭니다.
 * - 밥 던지기 모드: 화면 아래 쑥떡을 위로 밀어 던지면 포물선을 끝까지 끊기지 않고 날아갑니다.
 *   내려오는 밥이 모찌 입에 정확히 닿으면 입을 벌렸다가 "앙" 받아먹고(받아먹기 모션),
 *   빗나가면 바닥에 통통 떨어진 뒤 모찌가 걸어가서 주워 먹습니다(주워 먹기 모션).
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
    /** 밥을 먹었을 때. inAir = 날아오는 밥을 입으로 정확히 받아먹음, false = 바닥에서 주워 먹음 */
    var onFoodCaught: ((inAir: Boolean) -> Unit)? = null
    /** 밥이 바닥에 떨어졌을 때 (모찌가 주우러 갑니다) */
    var onFoodDropped: (() -> Unit)? = null

    /** 던지는 것: false 밥(쑥떡), true 간식(사탕). 바꿀 때는 [aiming]을 꺼두고 바꿉니다. */
    var snack = false
        set(value) { field = value; invalidate() }

    /** 밥 · 간식 던지기 모드 */
    var aiming = false
        set(value) {
            if (field == value) return
            field = value
            food = if (value) Food.REST else Food.NONE
            if (value) {
                state = St.APPROACH
                stateUntil = SystemClock.uptimeMillis() + 60_000
                pickApproachTarget()
            } else {
                gape = 0f
                if (state == St.APPROACH || state == St.FETCH) {
                    state = St.IDLE
                    stateUntil = 0L
                }
            }
            invalidate()
        }

    fun setPet(state: PetState) {
        painter.stage = state.stage
        painter.mood = state.mood
        painter.hatch = state.hatchProgress
        painter.accessory = state.accessoryLevel
        happiness = state.happiness
        contentDescription = "${state.name}, ${state.stage.label}, ${state.mood.label}. 두 번 눌러 쓰다듬기, 끌어서 들고 던지기"
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
        heartStart = reactStart
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
        heartStart = celebrateStart
        invalidate()
    }

    // ------------------------------------------------------------------ 내부 상태

    /** FETCH: 떨어진 밥으로 걸어감, PICKUP: 바닥에서 주워 먹음, CATCH: 날아온 밥을 받아먹음 */
    private enum class St { IDLE, WALK, REST, PLAY, APPROACH, FETCH, PICKUP, CATCH }
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
    private var heartStart = -10_000L

    /** side: 1 모찌 오른쪽 위, -1 왼쪽 위 */
    private class Floater(val text: String, val start: Long, val side: Float = 1f)
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
    /** 한 번이라도 바닥에 닿았으면 더는 받아먹지 않고 주워 먹습니다 */
    private var foodTouched = false
    /** 받아먹으려고 입을 벌린 정도(0~1)와 살짝 뛰어오른 높이(px) */
    private var gape = 0f
    private var catchLift = 0f
    private var catchStart = -10_000L
    private var catchLanded = true
    private var catchX = 0f
    private var catchY = 0f
    private var pickupStart = -10_000L
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var downOnPet = false

    // 잡기 · 던지기 (화면 좌표로 움직입니다)
    private enum class Grab { NONE, PENDING, HELD, FLYING, SETTLING }
    private var grab = Grab.NONE
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downTime = 0L
    private var fingerX = 0f
    private var fingerY = 0f
    /** 몸 중심과 속도 */
    private var bx = 0f
    private var by = 0f
    private var bvx = 0f
    private var bvy = 0f
    /** 들린 동안: 손가락(매달린 점) 속도·가속도와 진자 각도 */
    private var pivPrevX = 0f
    private var pivPrevY = 0f
    private var pivVx = 0f
    private var pivVy = 0f
    private var hangLen = 0f
    private var theta = 0f
    private var omega = 0f
    /** 날 때 회전(라디안)과 회전 속도 */
    private var spin = 0f
    private var spinV = 0f
    /** 날아가는 동안 바닥으로 삼는 깊이(0~1) */
    private var floorDepth = 0.5f
    private var bounces = 0
    private var hardHit = false
    /** 말랑 찌그러짐 스프링 (세로·가로), 기준점은 마지막으로 부딪힌 쪽 */
    private var sqY = 0f
    private var sqYv = 0f
    private var sqX = 0f
    private var sqXv = 0f
    private var sqPivX = 0f
    private var sqPivY = 0f
    private var dizzyUntil = 0L

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
    /** 높이 날수록(멀수록) 밥을 작게 그립니다 */
    private fun flyScale(y: Float) = (1f - (foodRestY - y) / (height * 1.4f)).coerceIn(0.55f, 1f)
    /** 방석 위치(비율) */
    private val cushionX = 0.8f
    private val cushionY = 0.18f

    private val airborne get() = grab == Grab.HELD || grab == Grab.FLYING || grab == Grab.SETTLING
    private fun depthNow() = if (airborne) floorDepth else py
    private fun maxRNow() = baseR * depthScale(depthNow())
    private fun petR(): Float = painter.radius(maxRNow())

    /** 모찌 몸 중심(화면 좌표) */
    private fun petCenterX() = if (airborne) bx else sx(px)
    private fun petCenterY() = if (airborne) by else sy(py) - painter.centerAboveGround(maxRNow()) - currentJump()

    private fun currentJump(): Float {
        val e = (SystemClock.uptimeMillis() - reactStart) / 1000f
        val big = SystemClock.uptimeMillis() - celebrateStart < 1500
        val dur = if (big) 0.9f else 0.6f
        var j = if (e in 0f..dur) sin(e / dur * PI.toFloat()) * baseR * (if (big) 0.8f else 0.35f) else 0f
        if (state == St.PLAY && !walkPhase.isNaN()) j += abs(sin(walkPhase * 0.5f)) * baseR * 0.25f
        // 받아먹기: 다가오는 밥을 향해 살짝 발돋움 → "앙" 하고 폴짝
        j += catchLift
        if (state == St.CATCH) {
            val ce = (SystemClock.uptimeMillis() - catchStart) / 1000f
            if (ce in 0f..CATCH_HOP) j += sin(ce / CATCH_HOP * PI.toFloat()) * baseR * 0.24f
        }
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
        painter.sleepy = sleepyNow && state != St.CATCH && state != St.PICKUP && state != St.FETCH && gape < 0.05f && !airborne

        stepSquash(dt)
        if (grab == Grab.PENDING && n - downTime > LONG_PRESS_MS) startHold()
        if (airborne) {
            // 작은 단계로 나눠 계산해야 빠르게 던져도 벽을 뚫지 않습니다.
            var left = dt
            while (left > 0f) {
                val h = min(left, 1f / 240f)
                when (grab) {
                    Grab.HELD -> stepHeld(h)
                    Grab.FLYING -> stepFlying(h)
                    Grab.SETTLING -> stepSettling(h)
                    else -> {}
                }
                left -= h
            }
            walkPhase = Float.NaN
            stepFood(dt)
            return
        }

        if (!motion || painter.stage == PetStage.EGG) {
            walkPhase = Float.NaN
            // 애니메이션 끄기: 밥은 그대로 날아가고, 떨어지면 바로 먹은 것으로 칩니다.
            stepFood(dt)
            if (food == Food.LANDED) {
                food = Food.NONE
                onFoodCaught?.invoke(false)
            }
            if ((state == St.CATCH || state == St.PICKUP) && n > eatUntil) setState(St.IDLE, 1_500)
            return
        }

        // 떨어진 밥: 잠깐 바라보다가 주우러 갑니다
        if (food == Food.LANDED && state != St.FETCH && state != St.PICKUP && state != St.CATCH &&
            n - landedAt > FETCH_DELAY_MS
        ) startFetch()

        val busy = state == St.APPROACH || state == St.FETCH || state == St.PICKUP || state == St.CATCH
        if (!busy && n > stateUntil) {
            if (state == St.WALK && goingToRest && reached()) setState(St.REST, 4_000L + Random.nextLong(4_000))
            else nextBehavior()
        }

        when (state) {
            St.WALK, St.PLAY, St.APPROACH, St.FETCH -> {
                val base = when (state) {
                    St.PLAY -> 0.42f
                    St.FETCH -> 0.5f // 밥이다! 서둘러 갑니다
                    else -> 0.26f
                }
                val speed = base * (if (happiness < 20) 0.6f else 1f)
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
                    if (state == St.FETCH) {
                        startPickup()
                    } else if (state == St.APPROACH) {
                        look = 0f
                    } else if (goingToRest) {
                        setState(St.REST, 4_000L + Random.nextLong(4_000))
                    } else if (state == St.PLAY) {
                        tx = Random.nextFloat(); ty = Random.nextFloat()
                    }
                }
                sit = (sit - dt * 3f).coerceAtLeast(0f)
            }
            St.REST -> {
                walkPhase = Float.NaN
                sit = (sit + dt * 2f).coerceAtMost(1f)
                look = 0f
            }
            St.CATCH -> {
                walkPhase = Float.NaN
                sit = (sit - dt * 3f).coerceAtLeast(0f)
                // 폴짝 뛰었다가 내려오며 말랑하게 찌그러집니다
                if (!catchLanded && n - catchStart >= (CATCH_HOP * 1000).toLong()) {
                    catchLanded = true
                    sqYv += 2.4f
                }
                if (n > eatUntil) setState(St.IDLE, 1_500)
            }
            St.PICKUP -> {
                walkPhase = Float.NaN
                sit = (sit - dt * 3f).coerceAtLeast(0f)
                // 고개를 가장 깊이 숙인 순간 냠 하고 물어갑니다
                if (food == Food.LANDED && n - pickupStart >= (PICK_BITE * 1000).toLong()) {
                    food = Food.NONE
                    heartStart = n
                    floats += Floater("냠", n, -1f)
                    onFoodCaught?.invoke(false)
                }
                if (n > eatUntil) setState(St.IDLE, 1_500)
            }
            St.IDLE -> {
                walkPhase = Float.NaN
                sit = (sit - dt * 3f).coerceAtLeast(0f)
            }
        }
        // 날아가는 밥 · 떨어진 밥을 눈으로 따라갑니다
        if ((food == Food.FLY || food == Food.LANDED) && state != St.FETCH && state != St.PICKUP && state != St.CATCH) {
            look = ((fx - petCenterX()) / (width * 0.3f)).coerceIn(-1f, 1f)
        }
        stepFood(dt)
        updateGape(dt)
    }

    private fun reached() = hypot(tx - px, ty - py) < 0.02f

    private fun stepFood(dt: Float) {
        // 작은 단계로 나눠야 빠른 밥이 모찌 입을 그냥 지나치지 않습니다.
        var left = dt
        while (left > 0f && food == Food.FLY) {
            val h = min(left, 1f / 240f)
            stepFlyingFood(h)
            left -= h
        }
    }

    /**
     * 날아가는 밥: 모찌 몸을 스쳐도 끊기지 않고 포물선을 끝까지 그립니다.
     * 벽·천장에 맞으면 튕기고, 바닥에 닿으면 통통 튀다가 멈추고 모찌가 주우러 갑니다.
     */
    private fun stepFlyingFood(h: Float) {
        fx += fvx * h
        fy += fvy * h
        fvy += GRAVITY * d * h
        val minX = foodR * 1.5f
        val maxX = width - foodR * 1.5f
        if (fx < minX && fvx < 0f) { fx = minX; fvx = -fvx * 0.5f }
        if (fx > maxX && fvx > 0f) { fx = maxX; fvx = -fvx * 0.5f }
        val ceil = areaTop + foodR
        if (fy < ceil && fvy < 0f) { fy = ceil; fvy = -fvy * 0.3f }

        // 내려오는 밥이 입에 정확히 닿으면 받아먹습니다 (올라가는 중에는 그대로 지나갑니다)
        if (fvy > 0f && canCatch()) {
            val mx = petCenterX()
            val my = petCenterY() + painter.mouthOffsetY(maxRNow())
            if (hypot(fx - mx, fy - my) < petR() * 0.5f + foodR) {
                catchFood(mx, my)
                return
            }
        }

        if (fvy > 0f && fy >= landY) {
            fy = landY
            if (!foodTouched) {
                foodTouched = true
                onFoodDropped?.invoke()
            }
            if (fvy > 320f * d) {
                fvy = -fvy * 0.3f
                fvx *= 0.55f
            } else {
                fvy = 0f
                fvx = 0f
                food = Food.LANDED
                landedAt = now()
            }
        }
    }

    private fun canCatch() = !foodTouched && !airborne && painter.stage != PetStage.EGG &&
        state != St.CATCH && state != St.PICKUP

    /** 밥이 입 가까이 내려오면 "아~" 하고 입을 벌리며 살짝 발돋움합니다. */
    private fun updateGape(dt: Float) {
        var target = 0f
        var lift = 0f
        if (food == Food.FLY && canCatch() && fvy > -250f * d) {
            val my = petCenterY() + painter.mouthOffsetY(maxRNow())
            val dist = hypot(fx - petCenterX(), fy - my)
            if (dist < petR() * 2.6f && fy < my + petR() * 0.4f) {
                target = 1f
                if (fy < my) lift = baseR * 0.16f
            }
        }
        gape += (target - gape) * min(1f, dt * 14f)
        catchLift += (lift * gape - catchLift) * min(1f, dt * 10f)
    }

    /** 받아먹기: "앙" 다물며 폴짝 → 착지하며 말랑 → ^^ 눈으로 오물오물, 몸을 좌우로 흔듭니다. */
    private fun catchFood(mx: Float, my: Float) {
        val n = now()
        food = Food.NONE
        state = St.CATCH
        catchStart = n
        eatUntil = n + 1_900
        catchLanded = false
        catchX = mx; catchY = my
        gape = 0f
        walkPhase = Float.NaN
        goingToRest = false
        sqPivX = 0f; sqPivY = 0f
        sqYv -= 1.4f
        heartStart = n + 450
        floats += Floater("냠!", n, -1f)
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        onFoodCaught?.invoke(true)
    }

    /** 떨어진 밥 바로 뒤로 걸어갑니다(밥이 발 앞에 오도록). */
    private fun startFetch() {
        goingToRest = false
        tx = (((fx / width.coerceAtLeast(1)) - 0.14f) / 0.72f).coerceIn(-0.1f, 1.1f)
        ty = depthOfGround(fy - foodR * 0.4f).coerceIn(0f, 1f)
        setState(St.FETCH, 15_000)
    }

    /** 주워 먹기: 고개를 숙이듯 납작 → 냠 → 오물오물 */
    private fun startPickup() {
        state = St.PICKUP
        pickupStart = now()
        eatUntil = pickupStart + 1_700
        walkPhase = Float.NaN
        look = 0f
    }

    /** 주워 먹을 때 납작해지는 정도 (0~0.18) */
    private fun pickupBow(e: Float): Float = when {
        e < 0f -> 0f
        e < PICK_BITE -> sin(e / PICK_BITE * PI.toFloat() / 2f) * 0.18f
        e < PICK_BITE + 0.25f -> 0.18f * (1f - (e - PICK_BITE) / 0.25f)
        else -> abs(sin((e - PICK_BITE) * 9f)) * 0.03f
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
                } else if (downOnPet && (grab == Grab.FLYING || grab == Grab.SETTLING)) {
                    // 날아가는 모찌를 공중에서 낚아챕니다.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    fingerX = ev.x; fingerY = ev.y
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                    val cx = bx; val cy = by
                    grab = Grab.PENDING
                    startHold(cx, cy, keepDepth = true)
                } else if (downOnPet && grab == Grab.NONE) {
                    // 누른 채로 끌거나 잠깐 꾹 누르면 잡습니다. 바로 떼면 쓰다듬기.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    grab = Grab.PENDING
                    downTime = now()
                    fingerX = ev.x; fingerY = ev.y
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (grab == Grab.PENDING || grab == Grab.HELD) {
                    velocity?.addMovement(ev)
                    fingerX = ev.x; fingerY = ev.y
                    if (grab == Grab.PENDING && hypot(ev.x - downX, ev.y - downY) > touchSlop) startHold()
                    invalidate()
                }
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
                } else if (grab == Grab.HELD) {
                    velocity?.addMovement(ev)
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    val vy = velocity?.yVelocity ?: 0f
                    velocity?.recycle()
                    velocity = null
                    release(vx, vy)
                } else if (grab == Grab.PENDING) {
                    grab = Grab.NONE
                    velocity?.recycle()
                    velocity = null
                    if (hypot(ev.x - downX, ev.y - downY) < 24 * d) performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (food == Food.DRAG) food = Food.REST
                if (grab == Grab.HELD) release(0f, 0f)
                if (grab == Grab.PENDING) grab = Grab.NONE
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    // ------------------------------------------------------------------ 잡기 · 던지기

    /** 손에 들기: 모찌가 손가락 아래에 매달려 진자처럼 흔들립니다. */
    private fun startHold(cx: Float = petCenterX(), cy: Float = petCenterY(), keepDepth: Boolean = false) {
        if (grab != Grab.PENDING) return
        if (!keepDepth) floorDepth = py
        sqPivX = 0f; sqPivY = 0f
        grab = Grab.HELD
        bx = cx; by = cy
        bvx = 0f; bvy = 0f
        pivPrevX = fingerX; pivPrevY = fingerY
        pivVx = 0f; pivVy = 0f
        // 지금 잡은 자리에서 시작해, 손가락 바로 아래(머리 꼭대기를 잡은 모양)로 끌어올립니다.
        val dx = cx - fingerX
        val dy = cy - fingerY
        hangLen = max(hypot(dx, dy), 4f * d)
        theta = atan2(-dx, dy).coerceIn(-1.1f, 1.1f)
        omega = 0f
        spin = 0f; spinV = 0f
        state = St.IDLE
        sit = 0f
        reactStart = -10_000L
        catchLift = 0f
        gape = 0f
        // 들어 올릴 때 쭉 늘어나는 말랑함
        sqYv -= 2.2f
        sqPivX = 0f; sqPivY = 0f
        say(Lines.held(painter.mood), 2_000)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun stepHeld(dt: Float) {
        val maxR = maxRNow()
        val targetLen = painter.halfHeightTop(maxR) * 0.92f
        hangLen += (targetLen - hangLen) * min(1f, dt * 16f)

        // 손가락 속도·가속도 (부드럽게 걸러서)
        val vx = (fingerX - pivPrevX) / dt
        val vy = (fingerY - pivPrevY) / dt
        pivPrevX = fingerX; pivPrevY = fingerY
        val k = min(1f, dt * 30f)
        val nvx = pivVx + (vx - pivVx) * k
        val nvy = pivVy + (vy - pivVy) * k
        val lim = 30_000f * d
        val ax = ((nvx - pivVx) / dt).coerceIn(-lim, lim)
        val ay = ((nvy - pivVy) / dt).coerceIn(-lim, lim)
        pivVx = nvx; pivVy = nvy

        // 움직이는 손에 매달린 진자: θ'' = -(g + a_y)/L·sinθ + a_x/L·cosθ - c·θ'
        val g = GRAVITY_BODY * d
        val alpha = -(g + ay) / hangLen * sin(theta) + ax / hangLen * cos(theta) - SWING_DAMP * omega
        omega += alpha * dt
        theta += omega * dt
        if (abs(theta) > 1.35f) {
            theta = theta.coerceIn(-1.35f, 1.35f)
            omega *= -0.3f
        }

        val nx = fingerX - hangLen * sin(theta)
        val ny = fingerY + hangLen * cos(theta)
        bvx = (nx - bx) / dt
        bvy = (ny - by) / dt
        bx = nx
        by = ny
        // 바닥 아래로는 내려가지 않습니다(바닥에 닿으면 그 깊이가 새 바닥)
        val ground = by + painter.centerAboveGround(maxR)
        if (ground > sy(floorDepth)) floorDepth = depthOfGround(ground).coerceIn(0f, 1f)
    }

    /** 놓기: 손을 빠르게 움직이다 놓으면 그 속도로 던져집니다. 천천히 놓으면 톡 떨어집니다. */
    private fun release(fvx: Float, fvy: Float) {
        // 손가락 속도 + 흔들리던 몸의 속도
        var vx = fvx * 0.92f + (-hangLen * cos(theta) * omega) * 0.5f
        var vy = fvy * 0.92f + (-hangLen * sin(theta) * omega) * 0.5f
        val sp = hypot(vx, vy)
        val maxSp = MAX_THROW * d
        if (sp > maxSp) { vx *= maxSp / sp; vy *= maxSp / sp }
        bvx = vx; bvy = vy
        spin = theta
        spinV = omega * 0.7f + vx / max(petR(), 1f) * 0.35f
        bounces = 0
        hardHit = false
        grab = Grab.FLYING
        val maxR = maxRNow()
        val ground = by + painter.centerAboveGround(maxR)
        if (ground > sy(1f)) by = sy(1f) - painter.centerAboveGround(maxR)
        if (ground >= sy(floorDepth) - 1f) floorDepth = depthOfGround(ground).coerceIn(0f, 1f)
        invalidate()
    }

    private fun depthOfGround(groundY: Float) = (groundY - walkTop) / max(walkBottom - walkTop, 1f)

    private fun stepFlying(dt: Float) {
        val maxR = maxRNow()
        val below = painter.centerAboveGround(maxR)
        val above = painter.halfHeightTop(maxR)
        val side = painter.halfWidth(maxR)
        // 기울어질수록 위·아래로는 옆 폭, 옆으로는 키만큼 차지합니다.
        val tiltMix = abs(sin(spin))
        val extDown = below + (side - below) * tiltMix
        val extUp = above + (side - above) * tiltMix
        val extSide = side + (max(below, above) - side) * tiltMix

        bvy += GRAVITY_BODY * d * dt
        bvx *= (1f - 0.12f * dt)
        bx += bvx * dt
        by += bvy * dt
        spin += spinV * dt
        spinV *= (1f - 0.5f * dt)

        // 바닥
        val floorY = sy(floorDepth)
        if (by + extDown >= floorY && bvy > 0f) {
            by = floorY - extDown
            val impact = bvy
            sqPivX = 0f; sqPivY = below
            sqYv += (impact / (1000f * d)) * 3.0f
            if (impact > 1500f * d) hardHit = true
            if (impact > BOUNCE_MIN * d) {
                bvy = -impact * REST_FLOOR
                bvx *= 0.8f
                // 바닥에 닿으면 굴러가는 방향으로 회전이 바뀝니다
                spinV = spinV * 0.4f + bvx / max(below, 1f) * 0.5f
                bounces++
                bump(impact)
            } else {
                bvy = 0f
                grab = Grab.SETTLING
            }
        }
        // 천장 (위쪽 정보 막대 아래)
        val ceil = areaTop + 4f * d
        if (by - extUp <= ceil && bvy < 0f) {
            by = ceil + extUp
            val impact = -bvy
            bvy = impact * REST_WALL
            sqPivX = 0f; sqPivY = -above
            sqYv += (impact / (1000f * d)) * 2.4f
            spinV += bvx / max(above, 1f) * 0.3f
            bounces++
            bump(impact)
        }
        // 양쪽 벽
        if (bx - extSide <= 0f && bvx < 0f) {
            bx = extSide
            val impact = -bvx
            bvx = impact * REST_WALL
            sqPivX = -side; sqPivY = 0f
            sqXv += (impact / (1000f * d)) * 2.6f
            spinV -= bvy / max(side, 1f) * 0.3f
            bounces++
            bump(impact)
        } else if (bx + extSide >= width && bvx > 0f) {
            bx = width - extSide
            val impact = bvx
            bvx = -impact * REST_WALL
            sqPivX = side; sqPivY = 0f
            sqXv += (impact / (1000f * d)) * 2.6f
            spinV += bvy / max(side, 1f) * 0.3f
            bounces++
            bump(impact)
        }
        spinV = spinV.coerceIn(-24f, 24f)
    }

    /** 바닥에 붙은 채 미끄러지며 멈추고, 오뚝이처럼 똑바로 일어섭니다. */
    private fun stepSettling(dt: Float) {
        val maxR = maxRNow()
        val below = painter.centerAboveGround(maxR)
        val side = painter.halfWidth(maxR)
        val target = round(spin / TWO_PI) * TWO_PI
        val tiltMix = abs(sin(spin))
        val extDown = below + (side - below) * tiltMix
        by = sy(floorDepth) - extDown
        bvx *= (1f - 5f * dt)
        bx += bvx * dt
        if (bx - side < 0f) { bx = side; bvx = abs(bvx) * 0.4f }
        if (bx + side > width) { bx = width - side; bvx = -abs(bvx) * 0.4f }
        // 오뚝이 스프링: 살짝 지나쳤다가 돌아옵니다
        spinV += ((target - spin) * 70f - spinV * 7f) * dt
        spin += spinV * dt
        if (abs(bvx) < 12f * d && abs(spin - target) < 0.015f && abs(spinV) < 0.25f) land()
    }

    private fun land() {
        grab = Grab.NONE
        spin = 0f; spinV = 0f
        py = floorDepth
        px = ((bx / width.coerceAtLeast(1)) - 0.14f) / 0.72f
        bvx = 0f; bvy = 0f
        sqPivX = 0f; sqPivY = 0f
        walkPhase = Float.NaN
        if (bounces >= 2 || hardHit) dizzyUntil = now() + 1_400
        say(Lines.landed(painter.mood, bounces), 2_500)
        if (aiming) {
            state = St.APPROACH
            stateUntil = now() + 60_000
            pickApproachTarget()
        } else if (px !in 0f..1f) {
            // 벽 가까이 떨어지면 걸어 돌아옵니다
            tx = px.coerceIn(0.05f, 0.95f); ty = py
            setState(St.WALK, 3_000)
        } else {
            setState(St.IDLE, 1_600)
        }
    }

    private fun bump(impact: Float) {
        if (impact > 900f * d) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    /** 말랑한 찌그러짐: 살짝 지나쳤다 돌아오는 스프링 */
    private fun stepSquash(dt: Float) {
        var left = dt
        while (left > 0f) {
            val h = min(left, 1f / 240f)
            sqYv += (-SQ_K * sqY - SQ_C * sqYv) * h
            sqY = (sqY + sqYv * h).coerceIn(-0.3f, 0.36f)
            sqXv += (-SQ_K * sqX - SQ_C * sqXv) * h
            sqX = (sqX + sqXv * h).coerceIn(-0.3f, 0.36f)
            left -= h
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        reactStart = now()
        heartStart = reactStart
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
        foodTouched = false
        val k = 0.62f
        fvx = (vx * k).coerceIn(-900 * d, 900 * d)
        fvy = (vy * k).coerceIn(-2100 * d, -500 * d)
        // 꼭대기에서 140dp쯤 아래가 떨어질 자리입니다. 모찌가 주우러 갈 수 있게 걷는 바닥 안으로 맞춥니다.
        val apexRise = fvy * fvy / (2 * GRAVITY * d)
        landY = (fy - apexRise + 140 * d).coerceIn(walkTop + foodR, walkBottom)
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
        val r = maxRNow()
        pose.t = t
        pose.walk = walkPhase
        pose.look = look
        pose.jump = currentJump()
        pose.sit = sit
        pose.chew = (state == St.CATCH && n - catchStart > 150) || (state == St.PICKUP && food == Food.NONE)
        pose.gape = if (airborne) 0f else gape
        pose.blink = n < blinkUntil
        pose.shadow = true
        pose.held = grab == Grab.HELD
        // 말랑 찌그러짐: 세로(sqY)·가로(sqX)를 바깥 축 기준으로
        pose.scaleY = (1f - sqY) * (1f + sqX * 0.7f)
        pose.scaleX = (1f + sqY * 0.7f) * (1f - sqX)
        pose.pivotX = sqPivX
        pose.pivotY = sqPivY
        pose.expr = when {
            grab == Grab.HELD -> MochiPainter.Expr.SURPRISED
            grab == Grab.FLYING -> if (abs(spinV) > 7f || hypot(bvx, bvy) > 1800f * d) MochiPainter.Expr.SQUINT else MochiPainter.Expr.SURPRISED
            grab == Grab.SETTLING || n < dizzyUntil -> MochiPainter.Expr.SQUINT
            state == St.CATCH && n - catchStart > 120 -> MochiPainter.Expr.HAPPY
            else -> MochiPainter.Expr.NORMAL
        }
        if (airborne) {
            pose.tilt = if (grab == Grab.HELD) Math.toDegrees(theta.toDouble()).toFloat() else Math.toDegrees(spin.toDouble()).toFloat()
            pose.shadow = false
            if (grab == Grab.HELD) {
                // 손에 매달려 살짝 늘어난 몸 (손가락 쪽 기준)
                pose.scaleY *= 1.05f
                pose.scaleX *= 0.97f
                if (sqPivY == 0f && sqPivX == 0f) pose.pivotY = -painter.halfHeightTop(r)
            }
            val floorY = sy(floorDepth)
            val lift = floorY - (by + painter.centerAboveGround(r))
            painter.drawShadow(canvas, bx, floorY, r, lift.coerceAtLeast(0f))
            painter.drawCentered(canvas, bx, by, r, pose)
        } else {
            pose.tilt = 0f
            if (state == St.CATCH) {
                // 받아먹고 나서 좋아서 몸을 좌우로 살랑살랑
                val e = (n - catchStart) / 1000f - CATCH_HOP
                if (e > 0f) pose.tilt = sin(e * 13f) * 5f * (1f - e / 1.4f).coerceIn(0f, 1f)
            } else if (state == St.PICKUP) {
                val b = pickupBow((n - pickupStart) / 1000f)
                pose.scaleY *= 1f - b
                pose.scaleX *= 1f + b * 0.6f
            }
            if (sqPivX == 0f && sqPivY == 0f) pose.pivotY = painter.centerAboveGround(r)
            val groundY = sy(py) - if (sit > 0f) r * 0.12f * sit else 0f
            painter.draw(canvas, sx(px), groundY, r, pose)
        }

        // 하트
        val e = (n - heartStart) / 1000f
        if (e in 0f..1.4f && !airborne) {
            val alpha = ((1f - e / 1.4f) * 255).toInt().coerceIn(0, 255)
            val rise = e * r * 0.6f
            val cx = petCenterX()
            val y0 = petCenterY() - petR() * 1.4f
            painter.heart(canvas, cx - r * 0.45f, y0 + r * 0.3f - rise, r * 0.12f, alpha)
            painter.heart(canvas, cx, y0 - rise * 1.2f, r * 0.15f, alpha)
            painter.heart(canvas, cx + r * 0.45f, y0 + r * 0.2f - rise * 0.9f, r * 0.1f, alpha)
        }
        drawCelebrate(canvas, n)
        drawFood(canvas)
        drawCatchBurst(canvas, n)
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

        // 러그 (희미한 붉은색)
        fill.color = P.RUG
        val rugCy = sy(0.55f)
        rect.set(w * 0.16f, rugCy - (walkBottom - walkTop) * 0.26f, w * 0.84f, rugCy + (walkBottom - walkTop) * 0.26f)
        canvas.drawOval(rect, fill)
        line.color = P.RUG_LINE
        line.strokeWidth = 2f * d
        rect.inset(rect.width() * 0.06f, rect.height() * 0.12f)
        canvas.drawOval(rect, line)

        // 고양이 방석 (갈색 침대)
        val cx = sx(cushionX)
        val cy = sy(cushionY)
        val cr = baseR * depthScale(cushionY) * 1.25f
        fill.color = P.CUSHION
        rect.set(cx - cr, cy - cr * 0.42f, cx + cr, cy + cr * 0.36f)
        canvas.drawOval(rect, fill)
        line.color = P.CUSHION_LINE
        line.strokeWidth = 1.5f * d
        canvas.drawOval(rect, line)
        fill.color = P.CUSHION_IN
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
        val alpha = 255
        when (food) {
            Food.REST -> { x = foodRestX; y = foodRestY + sin(t * 2) * 2 * d }
            Food.DRAG -> { x = fx; y = fy }
            Food.FLY, Food.LANDED -> {
                x = fx; y = fy
                scale = flyScale(fy)
            }
            Food.NONE -> return
        }
        val r = foodR * scale
        if (food == Food.FLY && !foodTouched && fy < landY) {
            // 떨어질 자리의 그림자: 가까워질수록 진해집니다
            val k = 1f - ((landY - fy) / (360f * d)).coerceIn(0f, 0.85f)
            val sr = foodR * flyScale(landY) * (0.6f + 0.4f * k)
            fill.color = Color.argb((34 * k).toInt().coerceIn(6, 34), 28, 25, 23)
            rect.set(fx - sr, landY + sr * 0.7f, fx + sr, landY + sr * 1.1f)
            canvas.drawOval(rect, fill)
        }
        if (food == Food.REST || food == Food.LANDED) {
            fill.color = Color.argb(30, 28, 25, 23)
            rect.set(x - r, y + r * 0.7f, x + r, y + r * 1.1f)
            canvas.drawOval(rect, fill)
        }
        if (snack) {
            drawCandy(canvas, x, y, r, alpha)
        } else {
            fill.color = Color.argb(alpha, 141, 176, 106)
            rect.set(x - r * 1.15f, y - r * 0.85f, x + r * 1.15f, y + r * 0.85f)
            canvas.drawOval(rect, fill)
            line.color = Color.argb(alpha, 23, 25, 26)
            line.strokeWidth = 1.5f * d
            canvas.drawOval(rect, line)
            fill.color = Color.argb(alpha * 160 / 255, 255, 255, 255)
            canvas.drawCircle(x - r * 0.4f, y - r * 0.3f, r * 0.22f, fill)
        }
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

    /** 간식: 양쪽을 꼬아 둔 노란 포장지 + 분홍 사탕. 날아가는 동안 빙글빙글 돌아갑니다. */
    private fun drawCandy(canvas: Canvas, x: Float, y: Float, r: Float, alpha: Int) {
        canvas.save()
        if (food == Food.FLY) canvas.rotate(Math.toDegrees((t * 1.6f).toDouble()).toFloat(), x, y)
        line.color = Color.argb(alpha, 23, 25, 26)
        line.strokeWidth = 1.5f * d
        fill.color = Color.argb(alpha, 250, 214, 120)
        for (side in floatArrayOf(-1f, 1f)) {
            path.reset()
            path.moveTo(x + side * r * 0.7f, y)
            path.lineTo(x + side * r * 1.75f, y - r * 0.68f)
            path.lineTo(x + side * r * 1.75f, y + r * 0.68f)
            path.close()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, line)
        }
        fill.color = Color.argb(alpha, 242, 140, 168)
        canvas.drawCircle(x, y, r * 0.95f, fill)
        canvas.drawCircle(x, y, r * 0.95f, line)
        // 줄무늬
        line.color = Color.argb(alpha * 200 / 255, 255, 255, 255)
        line.strokeWidth = 2f * d
        rect.set(x - r * 0.55f, y - r * 0.55f, x + r * 0.55f, y + r * 0.55f)
        canvas.drawArc(rect, 200f, 120f, false, line)
        canvas.drawArc(rect, 20f, 120f, false, line)
        fill.color = Color.argb(alpha * 170 / 255, 255, 255, 255)
        canvas.drawCircle(x - r * 0.4f, y - r * 0.35f, r * 0.18f, fill)
        canvas.restore()
    }

    private fun drawCelebrate(canvas: Canvas, n: Long) {
        val e = (n - celebrateStart) / 1000f
        if (e !in 0f..1.8f) return
        val cx = petCenterX()
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

    /** 받아먹는 순간 입 주위로 터지는 짧은 빛줄 */
    private fun drawCatchBurst(canvas: Canvas, n: Long) {
        val e = (n - catchStart) / 1000f
        if (e !in 0f..0.35f) return
        val k = e / 0.35f
        line.color = Color.argb(((1f - k) * 255).toInt().coerceIn(0, 255), 240, 196, 60)
        line.strokeWidth = 2.5f * d
        val r0 = foodR * (0.8f + k * 1.6f)
        val r1 = r0 + 7f * d * (1f - k * 0.5f)
        for (i in 0 until 8) {
            val a = i * (TWO_PI / 8) - PI.toFloat() / 8
            canvas.drawLine(catchX + cos(a) * r0, catchY + sin(a) * r0, catchX + cos(a) * r1, catchY + sin(a) * r1, line)
        }
    }

    private fun drawFloats(canvas: Canvas, n: Long) {
        val it = floats.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val e = (n - f.start) / 1000f
            if (e > 1.4f) { it.remove(); continue }
            floatPaint.alpha = ((1f - e / 1.4f) * 255).toInt().coerceIn(0, 255)
            canvas.drawText(f.text, petCenterX() + f.side * petR() * 0.9f, petCenterY() - petR() - e * 40 * d, floatPaint)
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
        val petX = petCenterX()
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
        /** 모찌 몸 중력 (dp/s²): 먹이보다 조금 무거운 느낌 */
        const val GRAVITY_BODY = 2600f
        /** 매달렸을 때 흔들림이 잦아드는 정도 */
        const val SWING_DAMP = 3.2f
        /** 던지는 최대 속도 (dp/s) */
        const val MAX_THROW = 5200f
        /** 이보다 느리게 바닥에 닿으면 튕기지 않고 멈춥니다 (dp/s) */
        const val BOUNCE_MIN = 240f
        const val REST_FLOOR = 0.5f
        const val REST_WALL = 0.62f
        /** 찌그러짐 스프링: 단단함·잦아듦 */
        const val SQ_K = 380f
        const val SQ_C = 9f
        const val LONG_PRESS_MS = 260L
        /** 받아먹고 폴짝 뛰는 시간(초) */
        const val CATCH_HOP = 0.42f
        /** 주워 먹을 때 냠 무는 순간(초) */
        const val PICK_BITE = 0.3f
        /** 밥이 떨어지고 주우러 출발할 때까지 잠깐 바라보는 시간 */
        const val FETCH_DELAY_MS = 380L
        const val TWO_PI = (2 * PI).toFloat()
    }
}
