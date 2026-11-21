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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 모찌 그리기 (Canvas). 모찌 탭(PetWorldView), 안내 화면(PetView), 위젯·알림 아이콘이 같은 그림을 씁니다.
 *
 * 디자인: 동글동글한 찹쌀떡 실루엣 + 따뜻한 다크 브라운 외곽선(#2B2320), 낮고 넓게 놓인 작은 눈과 입.
 * 어린이부터 귀와 짧은 팔, 청소년은 크림색 배, 어른은 작은 금색 왕관. 표정은 행복도 5단계를 따릅니다.
 *
 * 모든 길이는 "어른 반지름 = 37.5" 기준 단위(u)로 정해 두고, 그릴 때 실제 크기에 맞춰 늘립니다.
 * 그래서 작은 위젯에서도, 큰 모찌 탭에서도 같은 비율로 보입니다.
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

    /** 표정 덮어쓰기 (들렸을 때, 날아갈 때, 밥을 받아먹었을 때 ^^) */
    enum class Expr { NORMAL, SURPRISED, SQUINT, HAPPY }

    /** 그릴 때 바뀌는 움직임 */
    class Pose {
        /** 숨쉬기 위상(라디안). 2.4초에 한 바퀴 */
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
        /** 날아오는 밥을 받아먹으려고 입을 크게 벌린 정도 0~1 ("아~") */
        var gape = 0f
        /** 눈 깜빡임 */
        var blink = false
        /** 그림자 그리기 */
        var shadow = true

        /** 손에 들려 있음: 발이 아래로 늘어지고 팔을 들어 올립니다 */
        var held = false
        /** 몸 기울기(도). 몸 중심 기준 시계 방향 */
        var tilt = 0f
        /** 부딪힐 때 찌그러짐: 바깥(화면) 축 기준 배율 */
        var scaleX = 1f
        var scaleY = 1f
        /** 찌그러짐 기준점(몸 중심에서의 거리, px). 바닥에 부딪히면 발바닥 쪽 */
        var pivotX = 0f
        var pivotY = 0f
        var expr = Expr.NORMAL

        fun resetMotion() {
            held = false; tilt = 0f; scaleX = 1f; scaleY = 1f; pivotX = 0f; pivotY = 0f; expr = Expr.NORMAL
        }
    }

    // ------------------------------------------------------------------ 크기

    private class Spec(val baseR: Float, val wRatio: Float, val ears: Boolean, val arms: Boolean, val belly: Boolean, val crown: Boolean)

    private fun spec(): Spec = when (stage) {
        PetStage.EGG, PetStage.BABY -> Spec(23.5f, 1.04f, ears = false, arms = false, belly = false, crown = false)
        PetStage.CHILD -> Spec(28.5f, 1.06f, ears = true, arms = true, belly = false, crown = false)
        PetStage.TEEN -> Spec(33.5f, 1.08f, ears = true, arms = true, belly = true, crown = false)
        PetStage.ADULT -> Spec(37.5f, 1.10f, ears = true, arms = true, belly = false, crown = true)
    }

    /** 이 단계 모찌의 실제 반지름 (터치 판정용) */
    fun radius(maxR: Float): Float = maxR * when (stage) {
        PetStage.EGG -> 0.85f
        PetStage.BABY -> 23.5f / UNIT_R
        PetStage.CHILD -> 28.5f / UNIT_R
        PetStage.TEEN -> 33.5f / UNIT_R
        PetStage.ADULT -> 1f
    }

    /** 발바닥에서 몸 중심까지의 높이(px). 날아다닐 때 바닥 충돌에 씁니다. */
    fun centerAboveGround(maxR: Float): Float {
        val u = maxR / UNIT_R
        return if (stage == PetStage.EGG) (2f + EGG_RY) * u else (FOOT_H + spec().baseR * 0.94f) * u
    }

    /** 몸 중심에서 가장 먼 가장자리까지(가로, 세로) px. 벽·천장 충돌에 씁니다. */
    fun halfWidth(maxR: Float): Float {
        val u = maxR / UNIT_R
        return if (stage == PetStage.EGG) EGG_RX * u else spec().baseR * spec().wRatio * u * (if (spec().arms) 1.25f else 1f)
    }

    /** 몸 가로 반지름(팔 제외, px). 빼꼼 숨기에서 눈이 화면 안에 남도록 계산할 때 씁니다. */
    fun bodyHalfWidth(maxR: Float): Float {
        val u = maxR / UNIT_R
        return if (stage == PetStage.EGG) EGG_RX * u else spec().baseR * spec().wRatio * u
    }

    /** 몸 중심에서 입까지의 세로 거리(px). 밥 받아먹기 판정에 씁니다. */
    fun mouthOffsetY(maxR: Float): Float {
        val u = maxR / UNIT_R
        if (stage == PetStage.EGG) return 0f
        return spec().baseR * 0.94f * u * 0.1f + 4.6f * u
    }

    fun halfHeightTop(maxR: Float): Float {
        val u = maxR / UNIT_R
        if (stage == PetStage.EGG) return EGG_RY * u
        val s = spec()
        return s.baseR * 0.94f * u + (if (s.crown) 10f * u else if (s.ears) 4f * u else 0f)
    }

    // ------------------------------------------------------------------ 색

    private fun bodyColor(): Int = when (mood) {
        Mood.JOYFUL -> Color.rgb(0xFF, 0xFD, 0xF7)
        Mood.SMILE -> Color.rgb(0xFF, 0xF7, 0xE8)
        Mood.HUNGRY -> Color.rgb(0xFA, 0xF0, 0xE1)
        Mood.SULKY -> Color.rgb(0xEC, 0xE7, 0xF4)
        Mood.SICK -> Color.rgb(0xE6, 0xEF, 0xE0)
    }

    private fun shellColor(): Int = when (mood) {
        Mood.JOYFUL -> Color.rgb(0xFF, 0xF0, 0xD6)
        Mood.SMILE -> Color.rgb(0xFF, 0xF2, 0xDB)
        Mood.HUNGRY -> Color.rgb(0xF7, 0xE7, 0xCE)
        Mood.SULKY -> Color.rgb(0xE7, 0xE0, 0xF2)
        Mood.SICK -> Color.rgb(0xE0, 0xEC, 0xD7)
    }

    private fun spotColor(): Int = when (mood) {
        Mood.JOYFUL -> Color.rgb(0xF4, 0xA2, 0x92)
        Mood.SMILE -> Color.rgb(0xEF, 0xC2, 0x78)
        Mood.HUNGRY -> Color.rgb(0xDF, 0xBE, 0x92)
        Mood.SULKY -> Color.rgb(0xC2, 0xB3, 0xDC)
        Mood.SICK -> Color.rgb(0xB3, 0xCF, 0xA0)
    }

    // ------------------------------------------------------------------ 그리기

    /** (cx, ground) = 발이 닿는 바닥 중앙, maxR = 어른 모찌 반지름 */
    fun draw(canvas: Canvas, cx: Float, ground: Float, maxR: Float, pose: Pose) {
        val u = maxR / UNIT_R
        if (pose.shadow) drawShadow(canvas, cx, ground, maxR, pose.jump)
        val centerY = ground - centerAboveGround(maxR) - pose.jump
        drawAt(canvas, cx, centerY, maxR, pose, u)
    }

    /** 몸 중심(cx, cy)을 직접 정해서 그립니다. 손에 들렸거나 날아갈 때 씁니다(그림자는 따로). */
    fun drawCentered(canvas: Canvas, cx: Float, cy: Float, maxR: Float, pose: Pose) {
        drawAt(canvas, cx, cy, maxR, pose, maxR / UNIT_R)
    }

    /** 바닥 그림자. lift = 바닥에서 떠 있는 높이(px). 높이 뜰수록 작고 옅어집니다. */
    fun drawShadow(canvas: Canvas, cx: Float, ground: Float, maxR: Float, lift: Float) {
        val u = maxR / UNIT_R
        val w = if (stage == PetStage.EGG) EGG_RX * 0.88f * u else spec().baseR * spec().wRatio * 0.85f * u
        val k = (1f - (lift / (maxR * 4f)).coerceIn(0f, 0.65f))
        fill.color = Color.argb((28 * k).toInt().coerceIn(8, 28), 43, 35, 32)
        oval.set(cx - w * k, ground - 4.5f * u * k, cx + w * k, ground + 4.5f * u * k)
        canvas.drawOval(oval, fill)
    }

    private fun drawAt(canvas: Canvas, cx: Float, cy: Float, maxR: Float, pose: Pose, u: Float) {
        canvas.save()
        canvas.translate(cx, cy)
        if (pose.scaleX != 1f || pose.scaleY != 1f) {
            canvas.translate(pose.pivotX, pose.pivotY)
            canvas.scale(pose.scaleX, pose.scaleY)
            canvas.translate(-pose.pivotX, -pose.pivotY)
        }
        if (pose.tilt != 0f) canvas.rotate(pose.tilt)
        if (stage == PetStage.EGG) drawEgg(canvas, u, pose) else drawCreature(canvas, u, pose)
        canvas.restore()
    }

    /** 2.4초에 한 바퀴 도는 t를 초 단위로 */
    private fun sec(t: Float) = t * 2.4f / (2f * PI.toFloat())

    private fun drawEgg(canvas: Canvas, u: Float, pose: Pose) {
        val rx = EGG_RX * u
        val ry = EGG_RY * u
        val s = sec(pose.t)
        val wobbleDeg = when (mood) {
            Mood.JOYFUL -> 6.5f
            Mood.SMILE -> 3.5f
            Mood.HUNGRY -> 2.5f
            Mood.SULKY -> 1.2f
            Mood.SICK -> 0.4f
        }
        val speed = if (mood == Mood.JOYFUL) 4.4f else 2.6f
        val held = pose.held || pose.expr != Expr.NORMAL
        canvas.save()
        // 바닥(알 밑동)을 축으로 흔들흔들. 들렸거나 날 때는 흔들지 않습니다(몸 전체가 기울어짐).
        if (!held) canvas.rotate(sin(s * speed) * wobbleDeg, 0f, ry)

        path.reset()
        path.moveTo(0f, -ry)
        path.cubicTo(rx * 0.72f, -ry, rx * 1.04f, -ry * 0.12f, rx, ry * 0.35f)
        path.cubicTo(rx * 0.94f, ry * 0.88f, rx * 0.52f, ry, 0f, ry)
        path.cubicTo(-rx * 0.52f, ry, -rx * 0.94f, ry * 0.88f, -rx, ry * 0.35f)
        path.cubicTo(-rx * 1.04f, -ry * 0.12f, -rx * 0.72f, -ry, 0f, -ry)
        path.close()
        fill.color = shellColor()
        canvas.drawPath(path, fill)

        // 껍질 안쪽 점무늬
        canvas.save()
        canvas.clipPath(path)
        fill.color = spotColor()
        canvas.drawCircle(-rx * 0.42f, -ry * 0.22f, 5.2f * u, fill)
        canvas.drawCircle(rx * 0.44f, ry * 0.10f, 6.0f * u, fill)
        canvas.drawCircle(-rx * 0.20f, ry * 0.54f, 4.8f * u, fill)
        canvas.restore()

        stroke.color = INK
        stroke.strokeWidth = STROKE * u
        canvas.drawPath(path, stroke)

        // 부화가 가까우면 금이 갑니다
        if (hatch > 0.5f) {
            stroke.strokeWidth = 1.8f * u
            path.reset()
            val crackY = -ry * 0.48f
            path.moveTo(-rx * 0.34f, crackY)
            path.lineTo(-rx * 0.14f, crackY + ry * 0.12f)
            path.lineTo(rx * 0.02f, crackY - ry * 0.02f)
            if (hatch > 0.8f) {
                path.lineTo(rx * 0.18f, crackY + ry * 0.13f)
                path.lineTo(rx * 0.36f, crackY + ry * 0.02f)
            }
            canvas.drawPath(path, stroke)
        }
        canvas.restore()
    }

    private fun drawCreature(canvas: Canvas, u: Float, pose: Pose) {
        val sp = spec()
        val s = sec(pose.t)
        val walking = !pose.walk.isNaN()
        val energy = when {
            sleepy || mood == Mood.SICK -> 0.28f
            mood == Mood.SULKY -> 0.55f
            else -> 1f
        }
        val breathSpeed = if (mood == Mood.JOYFUL) 3.4f else 2.3f
        val breath = if (pose.held) 0f else sin(s * breathSpeed)
        val sitSquash = pose.sit * 0.12f
        val sqX = 1f + breath * 0.025f * energy + sitSquash * 0.6f
        val sqY = 1f - breath * 0.025f * energy - sitSquash
        val baseRx = sp.baseR * sp.wRatio * u
        val baseRy = sp.baseR * 0.94f * u
        val rx = baseRx * sqX
        val ry = baseRy * sqY
        // 몸 중심이 늘 (0,0)에 오도록 그립니다. 걸을 때는 통통 튀는 만큼만 위로.
        val bob = if (walking) abs(sin(pose.walk)) * baseRy * 0.08f else 0f
        val oy = -bob + (baseRy - ry)
        val body = bodyColor()
        val line = STROKE * u
        stroke.color = INK

        // 0. 발: 걸을 때 번갈아 들리고, 들렸을 때 아래로 늘어져 달랑달랑, 앉으면 숨김
        if (pose.sit < 0.5f) {
            for (side in floatArrayOf(-1f, 1f)) {
                var fy = oy + ry * 0.92f
                var fxo = 0f
                if (walking) fy -= max(0f, sin(pose.walk) * side) * baseRy * 0.14f
                if (pose.held) {
                    fy += baseRy * 0.16f
                    fxo = sin(s * 7f + side) * 1.6f * u
                }
                oval.set(
                    side * rx * 0.40f + fxo - max(5.5f * u, rx * 0.21f), fy - max(3.6f * u, ry * 0.14f),
                    side * rx * 0.40f + fxo + max(5.5f * u, rx * 0.21f), fy + max(3.6f * u, ry * 0.14f),
                )
                fill.color = FOOT
                canvas.drawOval(oval, fill)
                stroke.strokeWidth = line
                canvas.drawOval(oval, stroke)
            }
        }

        // 1. 귀 (어린이부터)
        if (sp.ears) {
            val earR = min(8.5f * u, baseRy * 0.26f)
            for (side in floatArrayOf(-1f, 1f)) {
                val ex = side * rx * 0.58f
                val ey = oy - ry * 0.74f
                fill.color = body
                canvas.drawCircle(ex, ey, earR, fill)
                stroke.strokeWidth = line
                canvas.drawCircle(ex, ey, earR, stroke)
                fill.color = EAR_IN
                canvas.drawCircle(ex, ey + 0.5f * u, earR * 0.48f, fill)
            }
        }

        // 2. 짧은 찹쌀떡 팔 (어린이부터). 신나면 흔들고, 들리면 번쩍 들어 올립니다.
        if (sp.arms) {
            val wave = when {
                pose.held -> 46f + sin(s * 9f) * 14f
                mood == Mood.JOYFUL && !sleepy -> sin(s * 5f) * 10f
                else -> 0f
            }
            for (side in floatArrayOf(-1f, 1f)) {
                canvas.save()
                canvas.translate(side * rx * 0.94f, oy + ry * 0.08f)
                canvas.rotate(side * (-18f - wave))
                val ax = max(5.5f * u, baseRx * 0.19f)
                val ay = max(4.0f * u, baseRy * 0.14f)
                oval.set(side * 4.5f * u - ax, -ay, side * 4.5f * u + ax, ay)
                fill.color = body
                canvas.drawOval(oval, fill)
                stroke.strokeWidth = line
                canvas.drawOval(oval, stroke)
                canvas.restore()
            }
        }

        // 3. 동글동글한 몸
        canvas.save()
        canvas.translate(0f, oy)
        tracePath(rx, ry)
        fill.color = body
        canvas.drawPath(path, fill)
        stroke.strokeWidth = line
        canvas.drawPath(path, stroke)

        // 4. 크림색 배 (청소년)
        if (sp.belly) {
            fill.color = Color.argb(217, 255, 245, 224)
            oval.set(-rx * 0.5f, ry * 0.42f - ry * 0.34f, rx * 0.5f, ry * 0.42f + ry * 0.34f)
            canvas.drawOval(oval, fill)
        }

        // 5. 작은 금색 왕관 (어른)
        if (sp.crown) {
            val cw = 12.5f * u
            val ch = 11.5f * u
            val base = -ry + 1.5f * u
            path.reset()
            path.moveTo(-cw, base)
            path.lineTo(-cw * 1.12f, base - ch * 0.82f)
            path.lineTo(-cw * 0.45f, base - ch * 0.36f)
            path.lineTo(0f, base - ch)
            path.lineTo(cw * 0.45f, base - ch * 0.36f)
            path.lineTo(cw * 1.12f, base - ch * 0.82f)
            path.lineTo(cw, base)
            path.close()
            fill.color = CROWN
            canvas.drawPath(path, fill)
            stroke.strokeWidth = line
            canvas.drawPath(path, stroke)
        }

        // 6. 표정
        drawFace(canvas, u, rx, ry, pose, body)
        drawAccessories(canvas, u, rx, ry, s)
        canvas.restore()
    }

    private fun tracePath(rx: Float, ry: Float) {
        path.reset()
        path.moveTo(0f, -ry)
        path.cubicTo(rx * 0.62f, -ry, rx * 1.03f, -ry * 0.34f, rx * 1.04f, ry * 0.22f)
        path.cubicTo(rx * 1.05f, ry * 0.82f, rx * 0.60f, ry, 0f, ry)
        path.cubicTo(-rx * 0.60f, ry, -rx * 1.05f, ry * 0.82f, -rx * 1.04f, ry * 0.22f)
        path.cubicTo(-rx * 1.03f, -ry * 0.34f, -rx * 0.62f, -ry, 0f, -ry)
        path.close()
    }

    /** 몸 중심이 (0,0)인 좌표에서 그립니다 */
    private fun drawFace(canvas: Canvas, u: Float, rx: Float, ry: Float, pose: Pose, body: Int) {
        val lookDx = pose.look.coerceIn(-1f, 1f) * rx * 0.08f
        val eyeY = ry * 0.1f
        val eyeOff = rx * 0.34f
        val eyeRx = 3.0f * u
        val eyeRy = 4.1f * u
        val mouthY = eyeY + 3.2f * u
        val mx = lookDx * 0.6f
        val closed = (sleepy && pose.expr == Expr.NORMAL) || pose.blink
        val squint = pose.expr == Expr.SQUINT || mood == Mood.SICK

        // 볼 홍조: 신날 때만
        if ((mood == Mood.JOYFUL && !squint) || pose.expr == Expr.HAPPY) {
            fill.color = BLUSH
            for (side in floatArrayOf(-1f, 1f)) {
                val bx = side * (eyeOff + 5.6f * u) + lookDx
                oval.set(bx - 4.8f * u, eyeY + 4f * u - 2.6f * u, bx + 4.8f * u, eyeY + 4f * u + 2.6f * u)
                canvas.drawOval(oval, fill)
            }
        }

        // 눈
        stroke.color = INK
        for (side in floatArrayOf(-1f, 1f)) {
            val ex = side * eyeOff + lookDx
            when {
                pose.expr == Expr.HAPPY -> {
                    // ^ ^ 행복하게 감은 눈
                    stroke.strokeWidth = STROKE * u
                    path.reset()
                    path.moveTo(ex - 3.4f * u, eyeY + 1.4f * u)
                    path.quadTo(ex, eyeY - 3.4f * u, ex + 3.4f * u, eyeY + 1.4f * u)
                    canvas.drawPath(path, stroke)
                }
                squint -> {
                    // > <
                    val w = 3.4f * u
                    val h = 2.7f * u
                    stroke.strokeWidth = STROKE * u
                    path.reset()
                    path.moveTo(ex - side * w, eyeY - h)
                    path.lineTo(ex + side * w * 0.65f, eyeY)
                    path.lineTo(ex - side * w, eyeY + h)
                    canvas.drawPath(path, stroke)
                }
                closed && pose.expr == Expr.NORMAL -> {
                    stroke.strokeWidth = STROKE * u
                    path.reset()
                    path.moveTo(ex - 3.4f * u, eyeY + 0.5f * u)
                    path.quadTo(ex, eyeY + 1.8f * u, ex + 3.4f * u, eyeY + 0.5f * u)
                    canvas.drawPath(path, stroke)
                }
                pose.expr == Expr.SURPRISED || mood == Mood.JOYFUL || mood == Mood.SMILE -> {
                    val grow = if (pose.expr == Expr.SURPRISED) 1.15f else 1f
                    fill.color = INK
                    oval.set(ex - eyeRx * grow, eyeY - eyeRy * grow, ex + eyeRx * grow, eyeY + eyeRy * grow)
                    canvas.drawOval(oval, fill)
                    fill.color = Color.WHITE
                    canvas.drawCircle(ex - 1.0f * u, eyeY - 1.5f * u, 1.25f * u, fill)
                }
                mood == Mood.HUNGRY -> {
                    canvas.save()
                    canvas.clipRect(ex - 6f * u, eyeY - 0.8f * u, ex + 6f * u, eyeY + 8f * u)
                    fill.color = INK
                    oval.set(ex - eyeRx * 0.95f, eyeY - eyeRy * 0.92f, ex + eyeRx * 0.95f, eyeY + eyeRy * 0.92f)
                    canvas.drawOval(oval, fill)
                    canvas.restore()
                    stroke.strokeWidth = 2.1f * u
                    path.reset()
                    path.moveTo(ex - 3.5f * u, eyeY - 0.8f * u)
                    path.quadTo(ex, eyeY - 0.2f * u, ex + 3.5f * u, eyeY - 0.8f * u)
                    canvas.drawPath(path, stroke)
                }
                else -> { // SULKY: 반쯤 감긴 눈
                    canvas.save()
                    canvas.clipRect(ex - 6f * u, eyeY - 0.2f * u, ex + 6f * u, eyeY + 8f * u)
                    fill.color = INK
                    oval.set(ex - eyeRx, eyeY - eyeRy, ex + eyeRx, eyeY + eyeRy)
                    canvas.drawOval(oval, fill)
                    canvas.restore()
                    stroke.strokeWidth = 2.2f * u
                    canvas.drawLine(ex - 3.8f * u, eyeY - 0.2f * u, ex + 3.8f * u, eyeY - 0.2f * u, stroke)
                }
            }
        }

        // 입
        stroke.color = INK
        stroke.strokeWidth = 2.2f * u
        path.reset()
        when {
            pose.gape > 0.05f -> {
                // 크게 벌린 입 + 혀 (받아먹기 직전)
                val g = pose.gape
                val hw = (2.4f + 1.8f * g) * u
                val top = mouthY - 0.8f * g * u
                val bottom = mouthY + (1.6f + 5.4f * g) * u
                fill.color = Color.rgb(140, 70, 66)
                oval.set(mx - hw, top, mx + hw, bottom)
                canvas.drawOval(oval, fill)
                fill.color = TONGUE
                oval.set(mx - hw * 0.62f, bottom - (bottom - top) * 0.42f, mx + hw * 0.62f, bottom - 0.4f * u)
                canvas.drawOval(oval, fill)
                oval.set(mx - hw, top, mx + hw, bottom)
                canvas.drawOval(oval, stroke)
            }
            pose.chew -> {
                val open = (sin(pose.t * 6f) + 1f) / 2f
                fill.color = Color.rgb(140, 70, 66)
                oval.set(mx - 2.6f * u, mouthY - 0.6f * u, mx + 2.6f * u, mouthY + (1.6f + 2.4f * open) * u)
                canvas.drawOval(oval, fill)
                canvas.drawOval(oval, stroke)
            }
            pose.expr == Expr.SURPRISED || pose.expr == Expr.SQUINT -> {
                // 작은 'o' 입
                fill.color = Color.rgb(140, 70, 66)
                oval.set(mx - 2.0f * u, mouthY - 0.2f * u, mx + 2.0f * u, mouthY + 3.4f * u)
                canvas.drawOval(oval, fill)
                canvas.drawOval(oval, stroke)
            }
            mood == Mood.JOYFUL || mood == Mood.SMILE || pose.expr == Expr.HAPPY -> {
                val w = 3.5f * u
                path.moveTo(mx - w, mouthY - 0.2f * u)
                path.quadTo(mx - w * 0.5f, mouthY + 2.3f * u, mx, mouthY + 0.3f * u)
                path.quadTo(mx + w * 0.5f, mouthY + 2.3f * u, mx + w, mouthY - 0.2f * u)
                canvas.drawPath(path, stroke)
            }
            mood == Mood.HUNGRY -> {
                path.moveTo(mx - 3.2f * u, mouthY + 0.8f * u)
                path.quadTo(mx, mouthY + 1.5f * u, mx + 3.2f * u, mouthY + 0.8f * u)
                canvas.drawPath(path, stroke)
            }
            mood == Mood.SULKY -> {
                path.moveTo(mx - 3.2f * u, mouthY + 2.0f * u)
                path.quadTo(mx, mouthY - 0.5f * u, mx + 3.2f * u, mouthY + 2.0f * u)
                canvas.drawPath(path, stroke)
            }
            else -> { // SICK: 힘없는 물결
                path.moveTo(mx - 4.0f * u, mouthY + 1.0f * u)
                path.quadTo(mx - 2.0f * u, mouthY - 0.8f * u, mx, mouthY + 1.0f * u)
                path.quadTo(mx + 2.0f * u, mouthY + 2.8f * u, mx + 4.0f * u, mouthY + 1.0f * u)
                canvas.drawPath(path, stroke)
            }
        }
        drawFaceMouthDone(canvas, u, rx, ry)
    }

    /** 시무룩·아픔일 때 땀방울 */
    private fun drawFaceMouthDone(canvas: Canvas, u: Float, rx: Float, ry: Float) {
        if (mood != Mood.SULKY && mood != Mood.SICK) return
        val x = rx * 0.72f
        val y = -ry + ry * 2f * 0.32f
        val s = 3.5f * u
        path.reset()
        path.moveTo(x, y - s)
        path.quadTo(x + s * 0.85f, y + s * 0.25f, x, y + s * 0.75f)
        path.quadTo(x - s * 0.85f, y + s * 0.25f, x, y - s)
        path.close()
        fill.color = SWEAT
        canvas.drawPath(path, fill)
        stroke.color = INK
        stroke.strokeWidth = 1.9f * u
        canvas.drawPath(path, stroke)
    }

    /** 연속일수 꾸밈 (몸 중심 기준 좌표) */
    private fun drawAccessories(canvas: Canvas, u: Float, rx: Float, ry: Float, s: Float) {
        if (accessory >= 2) {
            // 목도리: 몸 아래쪽을 두르는 띠
            stroke.color = Color.rgb(0x7C, 0xB8, 0xD8)
            stroke.strokeWidth = 4.6f * u
            oval.set(-rx * 0.86f, ry * 0.08f, rx * 0.86f, ry * 0.72f)
            canvas.drawArc(oval, 14f, 152f, false, stroke)
            fill.color = Color.rgb(0x5E, 0xA4, 0xC8)
            oval.set(rx * 0.36f, ry * 0.5f, rx * 0.56f, ry * 0.92f)
            canvas.drawRoundRect(oval, 2f * u, 2f * u, fill)
        }
        if (accessory >= 1) {
            // 리본: 머리 오른쪽 위
            val bx = rx * 0.42f
            val by = -ry * 0.86f
            val sz = 5.5f * u
            fill.color = Color.rgb(0xF2, 0x8C, 0xA8)
            path.reset()
            path.moveTo(bx, by)
            path.lineTo(bx - sz * 1.3f, by - sz * 0.8f)
            path.lineTo(bx - sz * 1.3f, by + sz * 0.8f)
            path.close()
            path.moveTo(bx, by)
            path.lineTo(bx + sz * 1.3f, by - sz * 0.8f)
            path.lineTo(bx + sz * 1.3f, by + sz * 0.8f)
            path.close()
            canvas.drawPath(path, fill)
            stroke.color = INK
            stroke.strokeWidth = 1.6f * u
            canvas.drawPath(path, stroke)
            fill.color = Color.rgb(0xD9, 0x5C, 0x80)
            canvas.drawCircle(bx, by, sz * 0.4f, fill)
        }
        if (accessory >= 3) {
            // 별 장식: 머리 왼쪽 위에서 반짝
            val sx = -rx * 1.0f
            val sy = -ry * 1.0f + sin(s * 2.6f) * 1.4f * u
            val sz = (5.5f + 0.8f * sin(s * 3.9f)) * u
            fill.color = CROWN
            path.reset()
            path.moveTo(sx, sy - sz)
            path.quadTo(sx + sz * 0.18f, sy - sz * 0.18f, sx + sz, sy)
            path.quadTo(sx + sz * 0.18f, sy + sz * 0.18f, sx, sy + sz)
            path.quadTo(sx - sz * 0.18f, sy + sz * 0.18f, sx - sz, sy)
            path.quadTo(sx - sz * 0.18f, sy - sz * 0.18f, sx, sy - sz)
            path.close()
            canvas.drawPath(path, fill)
        }
    }

    /** 하트 하나 (쓰다듬기·먹기 반응) */
    fun heart(canvas: Canvas, x: Float, y: Float, s: Float, alpha: Int) {
        fill.color = Color.argb(alpha, 0xF2, 0x7A, 0x8E)
        path.reset()
        path.moveTo(x, y + s * 0.6f)
        path.cubicTo(x - s * 1.4f, y - s * 0.3f, x - s * 0.6f, y - s * 1.3f, x, y - s * 0.5f)
        path.cubicTo(x + s * 0.6f, y - s * 1.3f, x + s * 1.4f, y - s * 0.3f, x, y + s * 0.6f)
        path.close()
        canvas.drawPath(path, fill)
    }

    companion object {
        /** 어른 몸 반지름(디자인 단위) */
        const val UNIT_R = 37.5f
        private const val STROKE = 2.5f
        private const val FOOT_H = 4.5f
        private const val EGG_RX = 28f
        private const val EGG_RY = 35f

        private val INK = Color.rgb(0x2B, 0x23, 0x20)
        private val FOOT = Color.rgb(0xF8, 0xE5, 0xD8)
        private val EAR_IN = Color.rgb(0xF7, 0xC2, 0xB6)
        private val BLUSH = Color.rgb(0xF5, 0x9E, 0x8B)
        private val CROWN = Color.rgb(0xFF, 0xD5, 0x4F)
        private val SWEAT = Color.rgb(0x90, 0xCA, 0xF9)
        private val TONGUE = Color.rgb(0xF2, 0x8C, 0x8C)

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
            val pose = Pose().apply { t = 0f; shadow = false }
            // 얼굴이 크게 보이도록 단계와 관계없이 화면의 약 74%를 채웁니다.
            val maxR = sizePx * 0.37f / painter.radius(1f)
            painter.draw(c, sizePx / 2f, sizePx * 0.9f, maxR, pose)
            return bmp
        }
    }
}
