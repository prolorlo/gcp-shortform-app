package com.shortformpet.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 제자리에 서 있는 모찌 한 마리 (진입 안내 화면, 빼꼼 모찌 등).
 * 돌아다니는 모찌는 PetWorldView, 그림 자체는 MochiPainter에 있습니다.
 */
class PetView(context: Context) : View(context) {

    private val painter = MochiPainter()
    private val pose = MochiPainter.Pose()

    var stage: PetStage
        get() = painter.stage
        set(value) { painter.stage = value; invalidate() }
    var mood: Mood
        get() = painter.mood
        set(value) { painter.mood = value; invalidate() }
    var stageProgress: Float
        get() = painter.hatch
        set(value) { painter.hatch = value; invalidate() }
    var accessoryLevel: Int
        get() = painter.accessory
        set(value) { painter.accessory = value; invalidate() }
    var sleepy: Boolean
        get() = painter.sleepy
        set(value) { if (painter.sleepy != value) { painter.sleepy = value; invalidate() } }
    /** 그림자를 그릴지 (빼꼼 모찌는 끔) */
    var shadow: Boolean = true
    /** 시선 -1(왼쪽) ~ 1(오른쪽). 빼꼼 모찌는 화면 안쪽(왼쪽)을 봅니다. */
    var look: Float = 0f
        set(value) { field = value; invalidate() }

    fun setState(state: PetState) {
        stage = state.stage
        mood = state.mood
        stageProgress = state.hatchProgress
        accessoryLevel = state.accessoryLevel
    }

    private var phase = 0f
    private var petStart = -10_000L

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    /** 쓰다듬기: 폴짝 뛰고 하트가 올라갑니다 */
    fun pet() {
        petStart = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
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
        val ground = h * 0.9f
        val maxR = min(w, h) * 0.38f
        val petElapsed = (SystemClock.uptimeMillis() - petStart) / 1000f
        pose.t = (phase * 2 * PI).toFloat()
        pose.jump = if (petElapsed in 0f..0.6f) sin(petElapsed / 0.6f * PI.toFloat()) * maxR * 0.35f else 0f
        pose.blink = phase > 0.94f
        pose.shadow = shadow
        pose.look = look
        painter.draw(canvas, cx, ground, maxR, pose)
        if (petElapsed in 0f..1.4f) {
            val alpha = ((1f - petElapsed / 1.4f) * 255).toInt().coerceIn(0, 255)
            val rise = petElapsed * maxR * 0.6f
            val y0 = ground - maxR * 2.1f
            painter.heart(canvas, cx - maxR * 0.5f, y0 + maxR * 0.3f - rise, maxR * 0.14f, alpha)
            painter.heart(canvas, cx, y0 - rise * 1.2f, maxR * 0.18f, alpha)
            painter.heart(canvas, cx + maxR * 0.5f, y0 + maxR * 0.2f - rise * 0.9f, maxR * 0.12f, alpha)
        }
    }
}
