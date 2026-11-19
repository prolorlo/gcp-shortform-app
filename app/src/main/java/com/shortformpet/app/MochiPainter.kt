package com.shortformpet.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 모찌 그리기 (Canvas). 모찌 탭(PetWorldView), 안내 화면(PetView), 위젯·알림 아이콘이 같은 그림을 씁니다.
 * 흰 찹쌀떡에 검은 1선 외곽, 표정은 행복도 5단계를 따릅니다. 정식 아트가 나오면 이 클래스만 바꾸면 됩니다.
 */
class MochiPainter {

    var stage: PetStage = PetStage.EGG
    var mood: Mood = Mood.JOYFUL
    /** 알 단계에서 부화까지 진행률 (금이 가는 정도) */
    var hatch: Float = 0f
    /** 0 없음, 1 리본, 2 목도리, 3 별 */
    var accessory: Int = 0
    /** 밤 · 방석에서 졸 때: 눈 감고 천천히 */
    var sleepy: Boolean = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val oval = RectF()

    /** 그릴 때 바뀌는 움직임 */
    class Pose {
        /** 숨쉬기 위상(라디안) */
        var t = 0f
        /** 걷는 중이면 발 위상(라디안), 아니면 NaN */
        var walk = Float.NaN
        /** 시선 -1(왼쪽) ~ 1(오른쪽) */
        var look = 0f
        /** 폴짝 높이(px) */
        var jump = 0f
        /** 방석에 앉아 납작해진 정도 0~1 */
        var sit = 0f
        /** 먹는 중(입 오물오물) */
        var chew = false
        /** 눈 깜빡임 */
        var blink = false
        /** 그림자 그리기 */
        var shadow = true
    }

    private fun bodyColor(): Int = when (mood) {
        Mood.JOYFUL -> Color.rgb(255, 253, 248)
        Mood.SMILE -> Color.rgb(255, 243, 222)
        Mood.HUNGRY -> Color.rgb(250, 240, 226)
        Mood.SULKY -> Color.rgb(233, 228, 240)
        Mood.SICK -> Color.rgb(226, 235, 213)
    }

    private fun patternColor(): Int = when (mood) {
        Mood.JOYFUL -> Color.rgb(244, 164, 136)
        Mood.SMILE, Mood.HUNGRY -> Color.rgb(236, 196, 128)
        Mood.SULKY -> Color.rgb(184, 170, 212)
        Mood.SICK -> Color.rgb(170, 196, 136)
    }

    /** (cx, ground) = 발이 닿는 바닥 중앙, maxR = 어른 모찌 반지름 */
    fun draw(canvas: Canvas, cx: Float, ground: Float, maxR: Float, pose: Pose) {
        if (pose.shadow) {
            fill.color = Color.argb(28, 28, 25, 23)
            val shadowW = maxR * 0.8f * (1f - (pose.jump / maxR).coerceIn(0f, 1f) * 0.35f)
            oval.set(cx - shadowW, ground - maxR * 0.09f, cx + shadowW, ground + maxR * 0.09f)
            canvas.drawOval(oval, fill)
        }
        if (stage == PetStage.EGG) drawEgg(canvas, cx, ground - pose.jump, maxR, pose.t)
        else drawCreature(canvas, cx, ground - pose.jump, maxR, pose)
    }

    /** 이 단계 모찌의 실제 반지름 (터치 판정용) */
    fun radius(maxR: Float): Float = maxR * when (stage) {
        PetStage.EGG -> 0.72f
        PetStage.BABY -> 0.6f
        PetStage.CHILD -> 0.72f
        PetStage.TEEN -> 0.86f
        PetStage.ADULT -> 1f
    }

    private fun drawEgg(canvas: Canvas, cx: Float, bottom: Float, maxR: Float, t: Float) {
        val rx = maxR * 0.62f
        val ry = maxR * 0.82f
        canvas.save()
        val wobble = when (mood) {
            Mood.JOYFUL -> 7f
            Mood.SMILE, Mood.HUNGRY -> 4f
            Mood.SULKY -> 1.5f
            Mood.SICK -> 0f
        }
        canvas.rotate(sin(t * 2) * wobble, cx, bottom)
        val cy = bottom - ry
        path.reset()
        path.moveTo(cx, cy - ry)
        path.cubicTo(cx + rx * 0.9f, cy - ry, cx + rx * 1.05f, cy + ry * 0.35f, cx + rx, cy + ry * 0.4f)
        path.cubicTo(cx + rx * 0.95f, cy + ry * 1.0f, cx - rx * 0.95f, cy + ry * 1.0f, cx - rx, cy + ry * 0.4f)
        path.cubicTo(cx - rx * 1.05f, cy + ry * 0.35f, cx - rx * 0.9f, cy - ry, cx, cy - ry)
        path.close()
        fill.color = Color.rgb(255, 251, 242)
        canvas.drawPath(path, fill)
        stroke.color = P.INK
        stroke.strokeWidth = maxOf(maxR * 0.035f, 2f)
        canvas.drawPath(path, stroke)

        fill.color = patternColor()
        canvas.drawCircle(cx - rx * 0.4f, cy - ry * 0.35f, rx * 0.16f, fill)
        canvas.drawCircle(cx + rx * 0.45f, cy - ry * 0.05f, rx * 0.12f, fill)
        canvas.drawCircle(cx - rx * 0.2f, cy + ry * 0.55f, rx * 0.1f, fill)

        stroke.color = patternColor()
        stroke.strokeWidth = maxR * 0.06f
        path.reset()
        val bandY = cy + ry * 0.2f
        for (i in 0..6) {
            val x = cx - rx * 0.92f + (rx * 1.84f) * i / 6
            val y = bandY + if (i % 2 == 0) -ry * 0.06f else ry * 0.06f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, stroke)

        if (hatch > 0.5f) {
            stroke.color = Color.rgb(140, 110, 90)
            stroke.strokeWidth = maxR * 0.03f
            path.reset()
            val crackY = cy - ry * 0.55f
            path.moveTo(cx - rx * 0.3f, crackY)
            path.lineTo(cx - rx * 0.12f, crackY + ry * 0.12f)
            path.lineTo(cx + rx * 0.02f, crackY - ry * 0.02f)
            if (hatch > 0.8f) {
                path.lineTo(cx + rx * 0.18f, crackY + ry * 0.14f)
                path.lineTo(cx + rx * 0.34f, crackY + ry * 0.02f)
            }
            canvas.drawPath(path, stroke)
        }
        canvas.restore()
    }

    private fun drawCreature(canvas: Canvas, cx: Float, bottom: Float, maxR: Float, pose: Pose) {
        val r = radius(maxR)
        val t = pose.t
        val walking = !pose.walk.isNaN()
        val energy = when {
            sleepy || mood == Mood.SICK -> 0.3f
            walking -> 1.6f
            else -> 1f
        }
        val sitSquash = 1f - pose.sit * 0.12f
        val squash = (1f + sin(t) * 0.035f * energy) * sitSquash
        val bob = if (walking) abs(sin(pose.walk)) * r * 0.08f else (sin(t) + 1f) * r * 0.04f * energy
        val bw = r * 1.05f / squash
        val bh = r * 0.95f * squash
        val cy = bottom - bh - bob
        val body = bodyColor()
        val ink = P.INK
        val line = maxOf(r * 0.045f, 2f)
        stroke.color = ink
        stroke.strokeWidth = line

        // 발 (걸을 때 번갈아 들림, 앉으면 숨김)
        if (pose.sit < 0.5f) {
            val liftL = if (walking) (sin(pose.walk).coerceAtLeast(0f)) * r * 0.12f else 0f
            val liftR = if (walking) ((-sin(pose.walk)).coerceAtLeast(0f)) * r * 0.12f else 0f
            fill.color = darker(body)
            oval.set(cx - r * 0.55f, bottom - r * 0.16f - bob * 0.3f - liftL, cx - r * 0.15f, bottom + r * 0.02f - liftL)
            canvas.drawOval(oval, fill)
            canvas.drawOval(oval, stroke)
            oval.set(cx + r * 0.15f, bottom - r * 0.16f - bob * 0.3f - liftR, cx + r * 0.55f, bottom + r * 0.02f - liftR)
            canvas.drawOval(oval, fill)
            canvas.drawOval(oval, stroke)
        }

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
        if (mood == Mood.JOYFUL || mood == Mood.SMILE) {
            fill.color = Color.argb(170, 244, 152, 128)
            oval.set(cx - r * 0.72f, cy + r * 0.02f, cx - r * 0.42f, cy + r * 0.2f)
            canvas.drawOval(oval, fill)
            oval.set(cx + r * 0.42f, cy + r * 0.02f, cx + r * 0.72f, cy + r * 0.2f)
            canvas.drawOval(oval, fill)
        }

        // 눈
        val lookDx = pose.look.coerceIn(-1f, 1f) * r * 0.1f
        val eyeY = cy - r * 0.14f
        val eyeDx = r * 0.34f
        val eyeR = r * (if (stage == PetStage.BABY) 0.14f else 0.11f)
        val closed = sleepy || pose.blink
        for (sx in floatArrayOf(-1f, 1f)) {
            val ex = cx + sx * eyeDx + lookDx
            stroke.color = ink
            stroke.strokeWidth = r * 0.05f
            when {
                mood == Mood.SICK -> {
                    val d = eyeR * 0.8f
                    canvas.drawLine(ex - d, eyeY - d, ex + d, eyeY + d, stroke)
                    canvas.drawLine(ex - d, eyeY + d, ex + d, eyeY - d, stroke)
                }
                closed -> {
                    canvas.drawLine(ex - eyeR, eyeY, ex + eyeR, eyeY, stroke)
                }
                mood == Mood.JOYFUL -> {
                    // 활짝 웃는 눈 ^^
                    oval.set(ex - eyeR, eyeY - eyeR * 0.6f, ex + eyeR, eyeY + eyeR * 1.2f)
                    canvas.drawArc(oval, 200f, 140f, false, stroke)
                }
                else -> {
                    fill.color = ink
                    oval.set(ex - eyeR * 0.78f, eyeY - eyeR * 1.1f, ex + eyeR * 0.78f, eyeY + eyeR * 1.1f)
                    canvas.drawOval(oval, fill)
                    if (mood == Mood.SULKY) {
                        fill.color = body
                        canvas.drawRect(ex - eyeR * 1.3f, eyeY - eyeR * 1.4f, ex + eyeR * 1.3f, eyeY - eyeR * 0.1f, fill)
                        stroke.strokeWidth = r * 0.035f
                        canvas.drawLine(ex - eyeR * 1.1f, eyeY - eyeR * 0.1f, ex + eyeR * 1.1f, eyeY - eyeR * 0.1f, stroke)
                    }
                }
            }
        }

        // 입
        val mouthY = cy + r * 0.2f
        val mx = cx + lookDx * 0.6f
        stroke.color = ink
        stroke.strokeWidth = r * 0.05f
        if (pose.chew) {
            val open = (sin(t * 6) + 1f) / 2f
            fill.color = Color.rgb(120, 60, 60)
            oval.set(mx - r * 0.09f, mouthY - r * 0.04f, mx + r * 0.09f, mouthY + r * (0.04f + 0.1f * open))
            canvas.drawOval(oval, fill)
            canvas.drawOval(oval, stroke)
        } else when (mood) {
            Mood.JOYFUL -> {
                oval.set(mx - r * 0.14f, mouthY - r * 0.12f, mx + r * 0.14f, mouthY + r * 0.1f)
                canvas.drawArc(oval, 15f, 150f, false, stroke)
            }
            Mood.SMILE -> {
                oval.set(mx - r * 0.12f, mouthY - r * 0.1f, mx + r * 0.12f, mouthY + r * 0.06f)
                canvas.drawArc(oval, 20f, 140f, false, stroke)
            }
            Mood.HUNGRY -> {
                canvas.drawLine(mx - r * 0.1f, mouthY + r * 0.02f, mx + r * 0.1f, mouthY + r * 0.02f, stroke)
            }
            Mood.SULKY -> {
                oval.set(mx - r * 0.12f, mouthY, mx + r * 0.12f, mouthY + r * 0.16f)
                canvas.drawArc(oval, 200f, 140f, false, stroke)
            }
            Mood.SICK -> {
                path.reset()
                path.moveTo(mx - r * 0.18f, mouthY + r * 0.04f)
                path.quadTo(mx - r * 0.09f, mouthY - r * 0.04f, mx, mouthY + r * 0.04f)
                path.quadTo(mx + r * 0.09f, mouthY + r * 0.12f, mx + r * 0.18f, mouthY + r * 0.04f)
                canvas.drawPath(path, stroke)
            }
        }

        if (mood == Mood.SICK || mood == Mood.SULKY) drawSweat(canvas, cx + bw * 0.8f, cy - bh * 0.5f, r * 0.13f)

        drawAccessories(canvas, cx, cy, r, bw, bh, t)
    }

    private fun drawAccessories(canvas: Canvas, cx: Float, cy: Float, r: Float, bw: Float, bh: Float, t: Float) {
        if (accessory >= 2) {
            val scarf = Color.rgb(79, 195, 247)
            stroke.color = scarf
            stroke.strokeWidth = r * 0.15f
            oval.set(cx - bw * 0.9f, cy + bh * 0.16f, cx + bw * 0.9f, cy + bh * 0.8f)
            canvas.drawArc(oval, 12f, 156f, false, stroke)
            fill.color = Color.rgb(41, 182, 246)
            oval.set(cx + bw * 0.42f, cy + bh * 0.62f, cx + bw * 0.62f, cy + bh * 1.02f)
            canvas.drawRoundRect(oval, r * 0.05f, r * 0.05f, fill)
        }
        if (accessory >= 1) {
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
        if (accessory >= 3) {
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

    /** 하트 하나 (쓰다듬기·먹기 반응) */
    fun heart(canvas: Canvas, x: Float, y: Float, s: Float, alpha: Int) {
        fill.color = Color.argb(alpha, 255, 99, 132)
        path.reset()
        path.moveTo(x, y + s * 0.6f)
        path.cubicTo(x - s * 1.4f, y - s * 0.3f, x - s * 0.6f, y - s * 1.3f, x, y - s * 0.5f)
        path.cubicTo(x + s * 0.6f, y - s * 1.3f, x + s * 1.4f, y - s * 0.3f, x, y + s * 0.6f)
        path.close()
        canvas.drawPath(path, fill)
    }

    private fun darker(color: Int): Int = Color.rgb(
        (Color.red(color) * 0.85f).toInt(),
        (Color.green(color) * 0.85f).toInt(),
        (Color.blue(color) * 0.85f).toInt(),
    )

    companion object {
        /** 위젯·알림 아이콘용 정지 그림 */
        fun bitmap(context: Context, sizePx: Int, state: PetState, background: Int = Color.TRANSPARENT): Bitmap {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            if (background != Color.TRANSPARENT) c.drawColor(background)
            val painter = MochiPainter().apply {
                stage = state.stage
                mood = state.mood
                hatch = state.hatchProgress
                accessory = state.accessoryLevel
                sleepy = DayClock.isLateNight()
            }
            val pose = Pose().apply { t = (PI / 2).toFloat(); shadow = false }
            // 얼굴이 크게 보이도록 단계와 관계없이 어른 크기로 맞춥니다.
            val maxR = sizePx * 0.37f / painter.radius(1f)
            painter.draw(c, sizePx / 2f, sizePx * 0.92f, maxR, pose)
            return bmp
        }
    }
}
