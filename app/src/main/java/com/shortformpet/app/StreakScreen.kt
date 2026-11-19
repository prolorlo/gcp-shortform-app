package com.shortformpet.app

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.PI
import kotlin.math.sin

/**
 * 하루 첫 실행 때 한 번 보여주는 연속일수 화면.
 *
 * 스트릭 설계 원칙(Mobbin 859개 스트릭 분석, screensdesign 사례 참고):
 * - 하루 한 번만, 끄는 설정도 둡니다.
 * - 끊겼을 때 탓하지 않고 최고 기록이 남아 있다고 알려 줍니다.
 * - 무엇을 하면 이어지는지, 보호권이 어떻게 지켜 주는지 한 줄로 적습니다.
 * - 화려한 색은 불꽃 하나에만. 나머지는 앱의 무채색 그대로.
 */
object StreakScreen {

    enum class Mark { KEPT, OVER, NONE, TODAY }

    data class Day(val label: String, val mark: Mark)

    data class Data(
        val streak: Int,
        val best: Int,
        val shields: Int,
        val goalText: String,
        val days: List<Day>,
        /** 다음 꾸밈까지 남은 날과 꾸밈 이름. 없으면 null */
        val nextMilestone: Pair<Int, String>?,
    )

    fun show(context: Context, data: Data) {
        val lit = data.streak > 0
        val dialog = Dialog(context, android.R.style.Theme_Material_Light_NoActionBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
        val root = FrameLayout(context).apply { setBackgroundColor(P.SURFACE) }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(24))
        }
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        // 가운데 묶음: 불꽃, 숫자, 설명
        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val flame = FlameView(context).apply { this.lit = lit }
        center.addView(flame, LinearLayout.LayoutParams(context.dp(160), context.dp(184)))

        val number = context.label(
            if (lit) Lines.streakCount(0) else Lines.STREAK_RESTART_TITLE,
            if (lit) 64f else 32f,
            if (lit) P.FLAME_TEXT else P.INK,
            W.SEMI,
            tabular = true,
        ).apply {
            gravity = Gravity.CENTER
            if (lit) letterSpacing = -0.02f
        }
        center.addView(number, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(8) })
        if (lit) {
            center.addView(context.label(Lines.STREAK_UNIT, 20f, P.INK, W.MEDIUM), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(4) })
        }
        val body = if (lit) Lines.streakKeepBody(data.goalText, data.streak + 1) else Lines.streakRestartBody(data.best)
        center.addView(
            context.label(body, 15f, P.SUB).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(12) },
        )

        // 최근 7일
        val week = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(context.dp(12), context.dp(16), context.dp(12), context.dp(16))
            background = roundRect(P.SOFT, context.dpf(16f))
        }
        for (d in data.days) {
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            cell.addView(DayDot(context, d.mark), LinearLayout.LayoutParams(context.dp(32), context.dp(32)))
            cell.addView(
                context.label(d.label, 12f, if (d.mark == Mark.TODAY) P.INK else P.SUB, if (d.mark == Mark.TODAY) W.MEDIUM else W.REGULAR),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(8) },
            )
            week.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        center.addView(week, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(32) })

        // 최고 기록 · 보호권 · 다음 꾸밈
        val facts = listOfNotNull(
            Lines.streakBest(maxOf(data.best, data.streak)),
            Lines.streakShields(data.shields),
            data.nextMilestone?.let { Lines.streakNextMilestone(it.first, it.second) },
        ).joinToString("  ·  ")
        center.addView(
            context.label(facts, 13f, P.SUB, tabular = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(16) },
        )
        center.addView(
            context.label(Lines.STREAK_RULE, 12f, P.MUTED).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(8) },
        )

        column.addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f))
        column.addView(center, LinearLayout.LayoutParams(MATCH, WRAP))
        column.addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f))
        val button = context.btn(if (lit) Lines.STREAK_CONTINUE else Lines.STREAK_START, BtnStyle.PRIMARY, 16f) { dialog.dismiss() }
        column.addView(button, LinearLayout.LayoutParams(MATCH, context.dp(52)))

        dialog.setContentView(root)
        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(P.SURFACE))
        window.setLayout(MATCH, MATCH)
        window.setWindowAnimations(0)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        val basePad = context.dp(24)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                bars.top to bars.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            column.setPadding(basePad, basePad + top, basePad, basePad + bottom)
            insets
        }

        // 들어오는 움직임: 바탕은 그대로, 내용만 살짝 떠오르며 나타나고 숫자는 0부터 올라갑니다.
        val motion = motionEnabled(context)
        if (motion) {
            center.alpha = 0f
            center.translationY = context.dpf(12f)
            center.animate().alpha(1f).translationY(0f).setDuration(400).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        dialog.show()
        if (lit) {
            if (motion) {
                ValueAnimator.ofInt(0, data.streak).apply {
                    duration = (400L + data.streak * 60L).coerceAtMost(1200L)
                    startDelay = 200
                    interpolator = DecelerateInterpolator(2f)
                    addUpdateListener { number.text = Lines.streakCount(it.animatedValue as Int) }
                    start()
                }
            } else {
                number.text = Lines.streakCount(data.streak)
            }
        }
    }

    /** 시스템에서 애니메이션을 끈 사용자에게는 움직이지 않는 화면을 보여 줍니다. */
    private fun motionEnabled(context: Context): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (e: Exception) {
        true
    }

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /** 요일 칸: 이어 간 날은 불꽃색 원 + 체크, 넘긴 날은 회색, 기록 없는 날은 점선, 오늘은 불꽃색 테두리 */
    private class DayDot(context: Context, private val mark: Mark) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val s = minOf(width, height).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            val r = s / 2f - context.dpf(1.5f)
            when (mark) {
                Mark.KEPT -> {
                    paint.style = Paint.Style.FILL
                    paint.color = P.FLAME
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = context.dpf(2f)
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.strokeJoin = Paint.Join.ROUND
                    paint.color = Color.WHITE
                    path.reset()
                    path.moveTo(cx - r * 0.42f, cy + r * 0.02f)
                    path.lineTo(cx - r * 0.1f, cy + r * 0.34f)
                    path.lineTo(cx + r * 0.45f, cy - r * 0.3f)
                    canvas.drawPath(path, paint)
                }
                Mark.OVER -> {
                    paint.style = Paint.Style.FILL
                    paint.color = P.TRACK
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = context.dpf(2f)
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.color = P.MUTED
                    canvas.drawLine(cx - r * 0.35f, cy, cx + r * 0.35f, cy, paint)
                }
                Mark.NONE -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = context.dpf(1.5f)
                    paint.color = Color.rgb(196, 200, 192)
                    paint.pathEffect = DashPathEffect(floatArrayOf(context.dpf(3f), context.dpf(3f)), 0f)
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.pathEffect = null
                }
                Mark.TODAY -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = context.dpf(2.5f)
                    paint.color = P.FLAME
                    canvas.drawCircle(cx, cy, r - context.dpf(0.5f), paint)
                }
            }
        }
    }
}

/**
 * 타오르는 불꽃. 바깥 불꽃(주황→진한 주황), 안쪽 불꽃(노랑), 심지(연노랑) 세 겹이
 * 서로 다른 속도로 흔들리고, 작은 불티가 위로 올라가며 사라집니다.
 * lit=false면 꺼진 회색 불씨로, 거의 움직이지 않습니다.
 */
class FlameView(context: Context) : View(context) {
    var lit = true
        set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()
    private var t = 0f
    private val reducedMotion = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (e: Exception) {
        false
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 6000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = (it.animatedValue as Float) * 6f // 초 단위
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!reducedMotion) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val base = h * 0.94f
        val tau = (2 * PI).toFloat()
        val energy = if (lit) 1f else 0.25f

        // 바닥 그림자
        fill.shader = null
        fill.color = Color.argb(24, 23, 25, 26)
        oval.set(cx - w * 0.26f, base - h * 0.025f, cx + w * 0.26f, base + h * 0.025f)
        canvas.drawOval(oval, fill)

        // 바깥 불꽃
        val outerH = h * (0.86f + 0.025f * sin(t * tau * 0.9f) * energy)
        val outerR = w * (0.33f + 0.008f * sin(t * tau * 1.3f) * energy)
        val outerTip = sin(t * tau * 0.7f) * w * 0.05f * energy + sin(t * tau * 1.9f) * w * 0.015f * energy
        flamePath(cx, base, outerR, outerH, outerTip, lick = true)
        fill.color = Color.WHITE // 그림자에서 쓴 투명도가 불꽃 그라데이션에 섞이지 않게
        fill.shader = if (lit) {
            LinearGradient(0f, base, 0f, base - outerH, P.FLAME_DEEP, P.FLAME, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, base, 0f, base - outerH, Color.rgb(176, 180, 172), Color.rgb(214, 217, 210), Shader.TileMode.CLAMP)
        }
        canvas.drawPath(path, fill)

        // 안쪽 불꽃
        val innerH = outerH * (0.6f + 0.04f * sin(t * tau * 1.4f + 1f) * energy)
        val innerR = outerR * 0.58f
        val innerTip = outerTip * 0.6f + sin(t * tau * 2.3f) * w * 0.012f * energy
        flamePath(cx, base - outerR * 0.08f, innerR, innerH, innerTip, lick = false)
        fill.color = Color.WHITE
        fill.shader = if (lit) {
            LinearGradient(0f, base, 0f, base - innerH, P.FLAME_CORE, Color.rgb(255, 196, 64), Shader.TileMode.CLAMP)
        } else {
            null
        }
        if (!lit) fill.color = Color.rgb(232, 234, 229)
        canvas.drawPath(path, fill)

        // 심지 쪽 가장 밝은 부분
        if (lit) {
            fill.shader = null
            fill.color = Color.argb(200, 255, 246, 214)
            val coreR = innerR * 0.45f
            oval.set(cx - coreR, base - outerR * 0.08f - coreR * 2.2f, cx + coreR, base - outerR * 0.08f)
            canvas.drawOval(oval, fill)

            // 불티: 6개가 시차를 두고 위로 올라가며 사라집니다.
            for (i in 0 until 6) {
                val phase = ((t * 0.55f + i / 6f) % 1f)
                val x = cx + sin((i * 1.7f + phase * 3f)) * w * 0.18f
                val y = base - outerH * (0.45f + phase * 0.75f)
                val alpha = ((1f - phase) * 220).toInt().coerceIn(0, 255)
                fill.color = Color.argb(alpha, 255, 150 + i * 10, 60)
                canvas.drawCircle(x, y, w * (0.012f + 0.006f * (i % 3)), fill)
            }
        }
        fill.shader = null
    }

    /** 물방울을 뒤집은 불꽃 모양. lick=true면 오른쪽에 작은 불길이 하나 더 솟습니다. */
    private fun flamePath(cx: Float, base: Float, r: Float, height: Float, tipDx: Float, lick: Boolean) {
        val top = base - height
        val tipX = cx + tipDx
        path.reset()
        path.moveTo(tipX, top)
        if (lick) {
            path.cubicTo(cx + r * 0.25f, top + height * 0.22f, cx + r * 0.55f, top + height * 0.3f, cx + r * 0.62f, top + height * 0.36f)
            path.cubicTo(cx + r * 0.75f, top + height * 0.42f, cx + r * 1.05f, base - r * 1.4f, cx + r, base - r)
        } else {
            path.cubicTo(cx + r * 0.35f, top + height * 0.3f, cx + r * 1.05f, base - r * 1.6f, cx + r, base - r)
        }
        oval.set(cx - r, base - 2 * r, cx + r, base)
        path.arcTo(oval, 0f, 180f, false)
        path.cubicTo(cx - r * 1.05f, base - r * 1.7f, cx - r * 0.4f, top + height * 0.35f, tipX, top)
        path.close()
    }
}
