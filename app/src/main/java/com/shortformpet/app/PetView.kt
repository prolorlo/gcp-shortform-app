package com.shortformpet.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 임시 캐릭터 "모찌"를 Canvas로 직접 그립니다. (이미지 리소스 불필요)
 * 단계(알→아기→어린이→청소년→어른)에 따라 크기와 모양이, 기분에 따라 색과 표정이 바뀝니다.
 * 정식 캐릭터 아트가 나오면 이 클래스만 교체하면 됩니다.
 */
class PetView(context: Context) : View(context) {

    var stage: PetStage = PetStage.EGG
        set(value) { field = value; invalidate() }
    var mood: Mood = Mood.HAPPY
        set(value) { field = value; invalidate() }
    /** 알 단계에서 부화까지 진행률 (금이 가는 정도) */
    var stageProgress: Float = 0f
        set(value) { field = value; invalidate() }
    /** 연속일수 기념 꾸밈: 0 없음, 1 리본(7일), 2 목도리(30일), 3 별 장식(100일) */
    var accessoryLevel: Int = 0
        set(value) { field = value; invalidate() }
    /** 밤 시간: 눈을 감고 천천히 숨쉬듯 움직입니다 */
    var sleepy: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    private var phase = 0f
    private var petStart = -10_000L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val oval = RectF()

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

    private fun bodyColor(): Int = when (mood) {
        Mood.HAPPY -> Color.rgb(255, 253, 248)
        Mood.OKAY -> Color.rgb(255, 243, 222)
        Mood.SULKY -> Color.rgb(233, 228, 240)
        Mood.SICK -> Color.rgb(226, 235, 213)
    }

    /** 알 무늬 색 (기분에 따라) */
    private fun patternColor(): Int = when (mood) {
        Mood.HAPPY -> Color.rgb(244, 164, 136)
        Mood.OKAY -> Color.rgb(236, 196, 128)
        Mood.SULKY -> Color.rgb(184, 170, 212)
        Mood.SICK -> Color.rgb(170, 196, 136)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val ground = h * 0.88f
        val maxR = min(w, h) * 0.36f
        val t = (phase * 2 * PI).toFloat()

        val petElapsed = (SystemClock.uptimeMillis() - petStart) / 1000f
        val jump = if (petElapsed in 0f..0.6f) sin(petElapsed / 0.6f * PI.toFloat()) * maxR * 0.35f else 0f

        // 그림자
        fill.color = Color.argb(28, 28, 25, 23)
        val shadowW = maxR * (0.9f - jump / maxR * 0.4f)
        oval.set(cx - shadowW, ground - maxR * 0.1f, cx + shadowW, ground + maxR * 0.1f)
        canvas.drawOval(oval, fill)

        if (stage == PetStage.EGG) drawEgg(canvas, cx, ground - jump, maxR, t)
        else drawCreature(canvas, cx, ground - jump, maxR, t)

        if (petElapsed in 0f..1.4f) drawHearts(canvas, cx, ground - maxR * 2.1f, maxR, petElapsed)
    }

    private fun drawEgg(canvas: Canvas, cx: Float, bottom: Float, maxR: Float, t: Float) {
        val rx = maxR * 0.62f
        val ry = maxR * 0.82f
        val wobble = when (mood) {
            Mood.HAPPY -> 7f
            Mood.OKAY -> 4f
            Mood.SULKY -> 1.5f
            Mood.SICK -> 0f
        }
        canvas.save()
        canvas.rotate(sin(t * 2) * wobble, cx, bottom)
        val cy = bottom - ry

        // 알 모양 (위가 좁은 타원)
        path.reset()
        path.moveTo(cx, cy - ry)
        path.cubicTo(cx + rx * 0.9f, cy - ry, cx + rx * 1.05f, cy + ry * 0.35f, cx + rx, cy + ry * 0.4f)
        path.cubicTo(cx + rx * 0.95f, cy + ry * 1.0f, cx - rx * 0.95f, cy + ry * 1.0f, cx - rx, cy + ry * 0.4f)
        path.cubicTo(cx - rx * 1.05f, cy + ry * 0.35f, cx - rx * 0.9f, cy - ry, cx, cy - ry)
        path.close()
        fill.color = Color.rgb(255, 251, 242)
        canvas.drawPath(path, fill)
        stroke.color = INK
        stroke.strokeWidth = maxOf(maxR * 0.035f, 2f)
        canvas.drawPath(path, stroke)

        // 무늬 (기분 색)
        fill.color = patternColor()
        canvas.drawCircle(cx - rx * 0.4f, cy - ry * 0.35f, rx * 0.16f, fill)
        canvas.drawCircle(cx + rx * 0.45f, cy - ry * 0.05f, rx * 0.12f, fill)
        canvas.drawCircle(cx - rx * 0.2f, cy + ry * 0.55f, rx * 0.1f, fill)

        // 지그재그 띠
        stroke.color = patternColor()
        stroke.strokeWidth = maxR * 0.06f
        path.reset()
        val bandY = cy + ry * 0.2f
        val steps = 6
        for (i in 0..steps) {
            val x = cx - rx * 0.92f + (rx * 1.84f) * i / steps
            val y = bandY + if (i % 2 == 0) -ry * 0.06f else ry * 0.06f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, stroke)

        // 부화가 가까우면 금이 감
        if (stageProgress > 0.5f) {
            stroke.color = Color.rgb(140, 110, 90)
            stroke.strokeWidth = maxR * 0.03f
            path.reset()
            val crackY = cy - ry * 0.55f
            path.moveTo(cx - rx * 0.3f, crackY)
            path.lineTo(cx - rx * 0.12f, crackY + ry * 0.12f)
            path.lineTo(cx + rx * 0.02f, crackY - ry * 0.02f)
            if (stageProgress > 0.8f) {
                path.lineTo(cx + rx * 0.18f, crackY + ry * 0.14f)
                path.lineTo(cx + rx * 0.34f, crackY + ry * 0.02f)
            }
            canvas.drawPath(path, stroke)
        }

        // 아픈 알은 땀방울
        if (mood == Mood.SICK || mood == Mood.SULKY) drawSweat(canvas, cx + rx * 0.85f, cy - ry * 0.55f, maxR * 0.12f)
        canvas.restore()
    }

    private fun drawCreature(canvas: Canvas, cx: Float, bottom: Float, maxR: Float, t: Float) {
        val scale = when (stage) {
            PetStage.BABY -> 0.6f
            PetStage.CHILD -> 0.72f
            PetStage.TEEN -> 0.86f
            else -> 1f
        }
        val r = maxR * scale
        val energy = if (mood == Mood.SICK || sleepy) 0.3f else 1f
        val squash = 1f + sin(t) * 0.035f * energy
        val bob = (sin(t) + 1f) * r * 0.04f * energy
        val bw = r * 1.05f / squash
        val bh = r * 0.95f * squash
        val cy = bottom - bh - bob
        val body = bodyColor()
        val ink = INK
        val line = maxOf(r * 0.045f, 2f)
        stroke.color = ink
        stroke.strokeWidth = line

        // 발
        fill.color = darker(body)
        oval.set(cx - r * 0.55f, bottom - r * 0.16f - bob * 0.3f, cx - r * 0.15f, bottom + r * 0.02f)
        canvas.drawOval(oval, fill)
        canvas.drawOval(oval, stroke)
        oval.set(cx + r * 0.15f, bottom - r * 0.16f - bob * 0.3f, cx + r * 0.55f, bottom + r * 0.02f)
        canvas.drawOval(oval, fill)
        canvas.drawOval(oval, stroke)

        // 귀 (어린이부터)
        if (stage.ordinal >= PetStage.CHILD.ordinal) {
            for (sx in floatArrayOf(-1f, 1f)) {
                val ex = cx + sx * r * 0.6f
                val ey = cy - bh * 0.74f
                fill.color = body
                canvas.drawCircle(ex, ey, r * 0.24f, fill)
                canvas.drawCircle(ex, ey, r * 0.24f, stroke)
                fill.color = Color.rgb(246, 196, 180)
                canvas.drawCircle(ex, ey, r * 0.11f, fill)
            }
        }

        // 몸
        fill.color = body
        oval.set(cx - bw, cy - bh, cx + bw, cy + bh)
        canvas.drawOval(oval, fill)
        stroke.color = ink
        stroke.strokeWidth = line
        canvas.drawOval(oval, stroke)

        // 배 (청소년부터)
        if (stage.ordinal >= PetStage.TEEN.ordinal) {
            fill.color = Color.argb(110, 255, 255, 255)
            oval.set(cx - bw * 0.55f, cy + bh * 0.05f, cx + bw * 0.55f, cy + bh * 0.85f)
            canvas.drawOval(oval, fill)
        }

        // 왕관 (어른)
        if (stage == PetStage.ADULT) {
            val top = cy - bh
            path.reset()
            path.moveTo(cx - r * 0.32f, top + r * 0.08f)
            path.lineTo(cx - r * 0.36f, top - r * 0.3f)
            path.lineTo(cx - r * 0.16f, top - r * 0.12f)
            path.lineTo(cx, top - r * 0.38f)
            path.lineTo(cx + r * 0.16f, top - r * 0.12f)
            path.lineTo(cx + r * 0.36f, top - r * 0.3f)
            path.lineTo(cx + r * 0.32f, top + r * 0.08f)
            path.close()
            fill.color = Color.rgb(255, 202, 40)
            canvas.drawPath(path, fill)
            fill.color = Color.rgb(239, 83, 80)
            canvas.drawCircle(cx, top - r * 0.08f, r * 0.06f, fill)
        }

        // 볼터치
        if (mood == Mood.HAPPY || mood == Mood.OKAY) {
            fill.color = Color.argb(170, 244, 152, 128)
            oval.set(cx - r * 0.72f, cy + r * 0.02f, cx - r * 0.42f, cy + r * 0.2f)
            canvas.drawOval(oval, fill)
            oval.set(cx + r * 0.42f, cy + r * 0.02f, cx + r * 0.72f, cy + r * 0.2f)
            canvas.drawOval(oval, fill)
        }

        // 눈
        val eyeY = cy - r * 0.14f
        val eyeDx = r * 0.34f
        val eyeR = r * (if (stage == PetStage.BABY) 0.14f else 0.11f)
        val blinking = sleepy || phase > 0.94f
        for (sx in floatArrayOf(-1f, 1f)) {
            val ex = cx + sx * eyeDx
            when {
                mood == Mood.SICK -> {
                    stroke.color = ink
                    stroke.strokeWidth = r * 0.05f
                    val d = eyeR * 0.8f
                    canvas.drawLine(ex - d, eyeY - d, ex + d, eyeY + d, stroke)
                    canvas.drawLine(ex - d, eyeY + d, ex + d, eyeY - d, stroke)
                }
                blinking -> {
                    stroke.color = ink
                    stroke.strokeWidth = r * 0.05f
                    canvas.drawLine(ex - eyeR, eyeY, ex + eyeR, eyeY, stroke)
                }
                else -> {
                    fill.color = ink
                    oval.set(ex - eyeR * 0.78f, eyeY - eyeR * 1.1f, ex + eyeR * 0.78f, eyeY + eyeR * 1.1f)
                    canvas.drawOval(oval, fill)
                    if (mood == Mood.SULKY) {
                        // 반쯤 감긴 눈꺼풀
                        fill.color = body
                        canvas.drawRect(ex - eyeR * 1.3f, eyeY - eyeR * 1.4f, ex + eyeR * 1.3f, eyeY - eyeR * 0.1f, fill)
                        stroke.color = ink
                        stroke.strokeWidth = r * 0.035f
                        canvas.drawLine(ex - eyeR * 1.1f, eyeY - eyeR * 0.1f, ex + eyeR * 1.1f, eyeY - eyeR * 0.1f, stroke)
                    }
                }
            }
        }

        // 입
        val mouthY = cy + r * 0.2f
        stroke.color = ink
        stroke.strokeWidth = r * 0.05f
        when (mood) {
            Mood.HAPPY -> {
                oval.set(cx - r * 0.14f, mouthY - r * 0.12f, cx + r * 0.14f, mouthY + r * 0.1f)
                canvas.drawArc(oval, 15f, 150f, false, stroke)
            }
            Mood.OKAY -> {
                oval.set(cx - r * 0.12f, mouthY - r * 0.1f, cx + r * 0.12f, mouthY + r * 0.06f)
                canvas.drawArc(oval, 20f, 140f, false, stroke)
            }
            Mood.SULKY -> {
                oval.set(cx - r * 0.12f, mouthY, cx + r * 0.12f, mouthY + r * 0.16f)
                canvas.drawArc(oval, 200f, 140f, false, stroke)
            }
            Mood.SICK -> {
                path.reset()
                path.moveTo(cx - r * 0.18f, mouthY + r * 0.04f)
                path.quadTo(cx - r * 0.09f, mouthY - r * 0.04f, cx, mouthY + r * 0.04f)
                path.quadTo(cx + r * 0.09f, mouthY + r * 0.12f, cx + r * 0.18f, mouthY + r * 0.04f)
                canvas.drawPath(path, stroke)
            }
        }

        if (mood == Mood.SICK || mood == Mood.SULKY) drawSweat(canvas, cx + bw * 0.8f, cy - bh * 0.5f, r * 0.13f)

        drawAccessories(canvas, cx, cy, r, bw, bh, t)
    }

    /** 연속일수 기념 꾸밈 (7일 리본, 30일 목도리, 100일 별) */
    private fun drawAccessories(canvas: Canvas, cx: Float, cy: Float, r: Float, bw: Float, bh: Float, t: Float) {
        if (accessoryLevel >= 2) {
            // 목도리: 몸 아래쪽을 두르는 띠와 늘어진 끝자락
            val scarf = Color.rgb(79, 195, 247)
            stroke.color = scarf
            stroke.strokeWidth = r * 0.15f
            oval.set(cx - bw * 0.9f, cy + bh * 0.16f, cx + bw * 0.9f, cy + bh * 0.8f)
            canvas.drawArc(oval, 12f, 156f, false, stroke)
            fill.color = Color.rgb(41, 182, 246)
            oval.set(cx + bw * 0.42f, cy + bh * 0.62f, cx + bw * 0.62f, cy + bh * 1.02f)
            canvas.drawRoundRect(oval, r * 0.05f, r * 0.05f, fill)
        }
        if (accessoryLevel >= 1) {
            // 리본: 머리 오른쪽 위
            val bx = cx + bw * 0.5f
            val by = cy - bh * 0.86f
            val s = r * 0.2f
            fill.color = Color.rgb(255, 92, 138)
            path.reset()
            path.moveTo(bx, by)
            path.lineTo(bx - s * 1.3f, by - s * 0.8f)
            path.lineTo(bx - s * 1.3f, by + s * 0.8f)
            path.close()
            path.moveTo(bx, by)
            path.lineTo(bx + s * 1.3f, by - s * 0.8f)
            path.lineTo(bx + s * 1.3f, by + s * 0.8f)
            path.close()
            canvas.drawPath(path, fill)
            fill.color = Color.rgb(216, 27, 96)
            canvas.drawCircle(bx, by, s * 0.38f, fill)
        }
        if (accessoryLevel >= 3) {
            // 별 장식: 머리 왼쪽 위에서 반짝임
            val sx = cx - bw * 1.0f
            val sy = cy - bh * 1.0f + sin(t * 2) * r * 0.04f
            val s = r * (0.2f + 0.03f * sin(t * 3))
            fill.color = Color.rgb(255, 202, 40)
            path.reset()
            path.moveTo(sx, sy - s)
            path.quadTo(sx + s * 0.18f, sy - s * 0.18f, sx + s, sy)
            path.quadTo(sx + s * 0.18f, sy + s * 0.18f, sx, sy + s)
            path.quadTo(sx - s * 0.18f, sy + s * 0.18f, sx - s, sy)
            path.quadTo(sx - s * 0.18f, sy - s * 0.18f, sx, sy - s)
            path.close()
            canvas.drawPath(path, fill)
        }
    }

    private fun drawSweat(canvas: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x, y - s)
        path.quadTo(x + s * 0.8f, y + s * 0.2f, x, y + s * 0.6f)
        path.quadTo(x - s * 0.8f, y + s * 0.2f, x, y - s)
        path.close()
        fill.color = Color.rgb(129, 212, 250)
        canvas.drawPath(path, fill)
    }

    private fun drawHearts(canvas: Canvas, cx: Float, y0: Float, maxR: Float, elapsed: Float) {
        val alpha = ((1f - elapsed / 1.4f) * 255).toInt().coerceIn(0, 255)
        fill.color = Color.argb(alpha, 255, 99, 132)
        val rise = elapsed * maxR * 0.6f
        heart(canvas, cx - maxR * 0.5f, y0 + maxR * 0.3f - rise, maxR * 0.14f)
        heart(canvas, cx, y0 - rise * 1.2f, maxR * 0.18f)
        heart(canvas, cx + maxR * 0.5f, y0 + maxR * 0.2f - rise * 0.9f, maxR * 0.12f)
    }

    private fun heart(canvas: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x, y + s * 0.6f)
        path.cubicTo(x - s * 1.4f, y - s * 0.3f, x - s * 0.6f, y - s * 1.3f, x, y - s * 0.5f)
        path.cubicTo(x + s * 0.6f, y - s * 1.3f, x + s * 1.4f, y - s * 0.3f, x, y + s * 0.6f)
        path.close()
        canvas.drawPath(path, fill)
    }

    private companion object {
        val INK = Color.rgb(28, 25, 23)
    }

    private fun darker(color: Int): Int = Color.rgb(
        (Color.red(color) * 0.85f).toInt(),
        (Color.green(color) * 0.85f).toInt(),
        (Color.blue(color) * 0.85f).toInt(),
    )
}
