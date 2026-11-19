package com.shortformpet.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import kotlin.math.PI

/**
 * 처음 설치했을 때(동의 직후) 한 번, 그리고 설정에서 언제든 다시 여는 4장짜리 사용 설명서.
 * 시트 안에 옆으로 넘기는 페이지와 점 4개, 아래에 다음 / 시작 버튼이 있습니다.
 */
object OnboardingManualSheet {

    /**
     * [accessibilityOn] 접근성이 이미 켜져 있으면 마지막 버튼이 "시작하기"
     * [onStart] 마지막 버튼을 눌렀을 때 (접근성 설정 열기 등)
     */
    fun show(context: Context, accessibilityOn: Boolean, onClose: () -> Unit, onStart: () -> Unit) {
        val pages = Lines.MANUAL
        Sheet.custom(
            context,
            title = null,
            cancelable = true,
            maxHeightRatio = 0.8f,
            onDismiss = onClose,
        ) { dismiss ->
            val pager = Pager(context)
            pages.forEachIndexed { i, page ->
                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(context.dp(4), 0, context.dp(4), 0)
                }
                col.addView(ManualArt(context, i), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                col.addView(context.label("${i + 1} / ${pages.size}", 12f, P.MUTED, tabular = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(16) })
                col.addView(context.label(page.title, 20f, P.INK, W.SEMI), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
                col.addView(context.label(page.body, 14f, P.SUB).apply { setLineSpacing(0f, 1.45f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
                pager.addView(col)
            }
            addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))

            val dots = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val dotViews = pages.indices.map {
                View(context).also { v -> dots.addView(v, LinearLayout.LayoutParams(context.dp(6), context.dp(6)).apply { marginStart = context.dp(3); marginEnd = context.dp(3) }) }
            }
            addView(dots, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(20) })

            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val skip = context.btn(Lines.MANUAL_SKIP, BtnStyle.QUIET) { dismiss() }
            val next = context.btn(Lines.MANUAL_NEXT, BtnStyle.PRIMARY) {}
            row.addView(skip, LinearLayout.LayoutParams(0, context.dp(52), 1f).apply { marginEnd = context.dp(8) })
            row.addView(next, LinearLayout.LayoutParams(0, context.dp(52), 2f))
            addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(20) })

            fun render(page: Int) {
                dotViews.forEachIndexed { i, v ->
                    v.background = roundRect(if (i == page) P.INK else P.TRACK, context.dpf(3f))
                    v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply { width = context.dp(if (i == page) 18 else 6) }
                }
                val last = page == pages.size - 1
                next.text = when {
                    !last -> Lines.MANUAL_NEXT
                    accessibilityOn -> Lines.MANUAL_START
                    else -> Lines.MANUAL_START_WITH_ACC
                }
                skip.visibility = if (last) View.GONE else View.VISIBLE
            }
            pager.onPageChanged = { render(it) }
            next.setOnClickListener {
                if (pager.current < pages.size - 1) {
                    pager.show(pager.current + 1)
                } else {
                    dismiss()
                    onStart()
                }
            }
            render(0)
        }
    }

    /** 각 장의 작은 그림. 선 하나 굵기, 무채색 + 쑥색 한 곳 */
    private class ManualArt(context: Context, private val index: Int) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = context.dpf(2f)
            color = P.INK
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val mochi = MochiPainter().apply { stage = PetStage.CHILD; mood = Mood.JOYFUL }
        private val pose = MochiPainter.Pose().apply { t = (PI / 2).toFloat() }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val d = context.dpf(1f)
            fill.color = P.PET_BG
            r.set(0f, 0f, w, h)
            canvas.drawRoundRect(r, 16 * d, 16 * d, fill)
            val cx = w / 2
            val cy = h / 2
            val s = minOf(w, h)
            when (index) {
                0 -> {
                    // 휴대폰 + 세로 영상 + 초시계
                    r.set(cx - s * 0.18f, cy - s * 0.36f, cx + s * 0.18f, cy + s * 0.36f)
                    fill.color = P.SURFACE
                    canvas.drawRoundRect(r, 14 * d, 14 * d, fill)
                    canvas.drawRoundRect(r, 14 * d, 14 * d, stroke)
                    r.inset(s * 0.03f, s * 0.05f)
                    fill.color = P.TRACK
                    canvas.drawRoundRect(r, 6 * d, 6 * d, fill)
                    fill.color = P.OVER
                    canvas.drawCircle(cx + s * 0.1f, cy - s * 0.25f, 4 * d, fill)
                    // 재생 삼각형
                    fill.color = P.MUTED
                    val p = android.graphics.Path()
                    p.moveTo(cx - s * 0.04f, cy - s * 0.06f); p.lineTo(cx + s * 0.06f, cy); p.lineTo(cx - s * 0.04f, cy + s * 0.06f); p.close()
                    canvas.drawPath(p, fill)
                    // 초시계
                    val tx = cx + s * 0.3f
                    val ty = cy + s * 0.18f
                    fill.color = P.SURFACE
                    canvas.drawCircle(tx, ty, s * 0.11f, fill)
                    canvas.drawCircle(tx, ty, s * 0.11f, stroke)
                    stroke.color = P.ACCENT
                    canvas.drawLine(tx, ty, tx, ty - s * 0.07f, stroke)
                    canvas.drawLine(tx, ty, tx + s * 0.05f, ty, stroke)
                    stroke.color = P.INK
                }
                1 -> {
                    mochi.draw(canvas, cx, cy + s * 0.25f, s * 0.36f, pose)
                    // 행복 막대
                    fill.color = P.TRACK
                    r.set(cx - s * 0.3f, cy + s * 0.33f, cx + s * 0.3f, cy + s * 0.36f)
                    canvas.drawRoundRect(r, 4 * d, 4 * d, fill)
                    fill.color = P.ACCENT
                    r.right = cx + s * 0.12f
                    canvas.drawRoundRect(r, 4 * d, 4 * d, fill)
                }
                2 -> {
                    // 자물쇠 + 타이머 고리
                    stroke.color = P.TRACK
                    stroke.strokeWidth = 8 * d
                    canvas.drawCircle(cx, cy, s * 0.3f, stroke)
                    stroke.color = P.ACCENT
                    r.set(cx - s * 0.3f, cy - s * 0.3f, cx + s * 0.3f, cy + s * 0.3f)
                    canvas.drawArc(r, -90f, 250f, false, stroke)
                    stroke.color = P.INK
                    stroke.strokeWidth = 2 * d
                    r.set(cx - s * 0.1f, cy - s * 0.02f, cx + s * 0.1f, cy + s * 0.14f)
                    fill.color = P.SURFACE
                    canvas.drawRoundRect(r, 4 * d, 4 * d, fill)
                    canvas.drawRoundRect(r, 4 * d, 4 * d, stroke)
                    r.set(cx - s * 0.06f, cy - s * 0.12f, cx + s * 0.06f, cy + s * 0.02f)
                    canvas.drawArc(r, 180f, 180f, false, stroke)
                }
                else -> {
                    // 홈 화면 + 2×2 위젯 + 가장자리에서 내민 모찌
                    r.set(cx - s * 0.22f, cy - s * 0.38f, cx + s * 0.22f, cy + s * 0.38f)
                    fill.color = P.SURFACE
                    canvas.drawRoundRect(r, 14 * d, 14 * d, fill)
                    canvas.drawRoundRect(r, 14 * d, 14 * d, stroke)
                    val left = r.left
                    val top = r.top
                    val g = s * 0.1f
                    fill.color = P.TRACK
                    for (row in 0 until 2) for (col in 0 until 3) {
                        val x = left + s * 0.05f + col * (g + s * 0.035f)
                        val y = top + s * 0.42f + row * (g + s * 0.035f)
                        r.set(x, y, x + g, y + g)
                        canvas.drawRoundRect(r, 4 * d, 4 * d, fill)
                    }
                    fill.color = P.PET_BG
                    r.set(left + s * 0.05f, top + s * 0.06f, left + s * 0.05f + g * 2 + s * 0.035f, top + s * 0.06f + g * 2 + s * 0.035f)
                    canvas.drawRoundRect(r, 6 * d, 6 * d, fill)
                    mochi.draw(canvas, r.centerX(), r.bottom - s * 0.02f, g * 0.75f, pose.apply { shadow = false })
                    canvas.save()
                    canvas.clipRect(0f, 0f, cx + s * 0.22f, h)
                    mochi.draw(canvas, cx + s * 0.25f, cy + s * 0.2f, s * 0.11f, pose)
                    canvas.restore()
                    pose.shadow = true
                }
            }
        }
    }
}
