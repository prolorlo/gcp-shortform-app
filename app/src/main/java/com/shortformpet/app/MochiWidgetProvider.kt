package com.shortformpet.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.widget.RemoteViews
import kotlin.math.PI

/**
 * 홈 화면 2×2 위젯 (DESIGN 5.4).
 * 방석 위 모찌 · 연속일수 · 행복도 막대 · 남은 목표를 비트맵 한 장으로 그려 넣습니다.
 * 왼쪽(모찌)을 누르면 모찌 탭, 오른쪽(할 일)을 누르면 오늘·할 일 탭이 열립니다.
 */
class MochiWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        @Volatile private var lastUpdate = 0L
        @Volatile private var lastSignature = ""

        /** 화면·서비스에서 부릅니다. 내용이 같으면 다시 그리지 않고, 30초에 한 번까지만. */
        fun update(context: Context, force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastUpdate < 30_000) return
            lastUpdate = now
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = try {
                manager.getAppWidgetIds(ComponentName(context, MochiWidgetProvider::class.java))
            } catch (e: Exception) {
                return
            }
            if (ids.isEmpty()) return
            render(context, manager, ids, force)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, force: Boolean = true) {
            val stores = Stores.of(context)
            val state = PetEngine(stores).state()
            val study = StudyManager(stores)
            val todosLeft = study.remainingTodoCount()
            val sessionOn = study.sessionActive()
            val signature = "${state.stage}|${state.mood}|${state.happiness}|${state.streak}|${state.todayMinutes.toInt()}|${state.goalMinutes}|$todosLeft|$sessionOn|${state.accessoryLevel}|${DayClock.isLateNight()}"
            if (!force && signature == lastSignature) return
            lastSignature = signature

            val bmp = draw(context, state, todosLeft, sessionOn)
            val views = RemoteViews(context.packageName, R.layout.widget_mochi)
            views.setImageViewBitmap(R.id.widget_image, bmp)
            views.setOnClickPendingIntent(R.id.widget_left, open(context, MainActivity.TAB_MOCHI))
            views.setOnClickPendingIntent(R.id.widget_right, open(context, MainActivity.TAB_TODAY))
            try {
                manager.updateAppWidget(ids, views)
            } catch (e: Exception) {
                // 위젯이 사라진 경우
            }
        }

        private fun open(context: Context, tab: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_TAB, tab)
            return PendingIntent.getActivity(context, 2000 + tab, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun draw(context: Context, state: PetState, todosLeft: Int, sessionOn: Boolean): Bitmap {
            val density = context.resources.displayMetrics.density
            val sizeDp = 168
            val size = (sizeDp * density).toInt().coerceAtMost(520)
            val u = size / sizeDp.toFloat()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }
            val r = RectF(0f, 0f, size.toFloat(), size.toFloat())

            // 카드
            fill.color = P.ROOM_WALL
            c.drawRoundRect(r, 22 * u, 22 * u, fill)
            // 바닥
            c.save()
            val clip = android.graphics.Path().apply { addRoundRect(r, 22 * u, 22 * u, android.graphics.Path.Direction.CW) }
            c.clipPath(clip)
            fill.color = P.ROOM_FLOOR
            c.drawRect(0f, size * 0.62f, size.toFloat(), size.toFloat(), fill)
            c.restore()

            // 연속일수 (왼쪽 위)
            text.textSize = 13 * u
            text.typeface = P.SEMI
            text.color = if (state.streak > 0) P.FLAME_TEXT else P.SUB
            val flame = android.graphics.Path()
            val fx = 14 * u
            val fy = 14 * u
            val fs = 14 * u / 24f
            flame.moveTo(fx + 12 * fs, fy + 2.5f * fs)
            flame.cubicTo(fx + 14 * fs, fy + 7 * fs, fx + 19 * fs, fy + 9 * fs, fx + 19 * fs, fy + 14.5f * fs)
            flame.cubicTo(fx + 19 * fs, fy + 18.6f * fs, fx + 15.9f * fs, fy + 21.5f * fs, fx + 12 * fs, fy + 21.5f * fs)
            flame.cubicTo(fx + 8.1f * fs, fy + 21.5f * fs, fx + 5 * fs, fy + 18.6f * fs, fx + 5 * fs, fy + 14.8f * fs)
            flame.cubicTo(fx + 5 * fs, fy + 11.5f * fs, fx + 8.5f * fs, fy + 8 * fs, fx + 12 * fs, fy + 2.5f * fs)
            flame.close()
            fill.color = if (state.streak > 0) P.FLAME else P.MUTED
            c.drawPath(flame, fill)
            c.drawText(Lines.streakChip(state.streak), fx + 18 * u, fy + 12 * u, text)

            // 오른쪽: 남은 할 일 / 집중 중
            text.textSize = 11 * u
            text.typeface = P.MEDIUM
            text.color = P.SUB
            text.textAlign = Paint.Align.RIGHT
            val rightX = size - 14 * u
            c.drawText(if (sessionOn) "집중 중" else "할 일 $todosLeft", rightX, fy + 12 * u, text)
            text.textAlign = Paint.Align.LEFT

            // 방석 + 모찌
            val cushionCx = size * 0.42f
            val ground = size * 0.72f
            fill.color = P.CUSHION
            r.set(cushionCx - 46 * u, ground - 14 * u, cushionCx + 46 * u, ground + 12 * u)
            c.drawOval(r, fill)
            val painter = MochiPainter().apply {
                stage = state.stage
                mood = state.mood
                hatch = state.hatchProgress
                accessory = state.accessoryLevel
                sleepy = DayClock.isLateNight()
            }
            val pose = MochiPainter.Pose().apply { t = (PI / 2).toFloat() }
            painter.draw(c, cushionCx, ground, 44 * u, pose)

            // 오른쪽 "집중" 버튼 모양
            fill.color = P.SURFACE
            r.set(size * 0.7f, size * 0.38f, size - 12 * u, size * 0.38f + 28 * u)
            c.drawRoundRect(r, 8 * u, 8 * u, fill)
            text.color = P.INK
            text.textSize = 11 * u
            text.textAlign = Paint.Align.CENTER
            c.drawText(if (sessionOn) "보기" else "집중", r.centerX(), r.centerY() + 4 * u, text)
            text.textAlign = Paint.Align.LEFT

            // 아래: 행복도 막대 + 남은 목표
            val barTop = size - 30 * u
            if (state.isEgg) {
                text.color = P.SUB
                text.textSize = 11 * u
                c.drawText(Lines.HATCHING, 14 * u, barTop + 4 * u, text)
            } else {
                fill.color = Color.argb(160, 255, 255, 255)
                r.set(14 * u, barTop - 4 * u, size - 14 * u, barTop + 2 * u)
                c.drawRoundRect(r, 3 * u, 3 * u, fill)
                fill.color = if (state.happiness < 20) P.OVER else P.ACCENT
                r.right = r.left + (size - 28 * u) * (state.happiness / 100f)
                c.drawRoundRect(r, 3 * u, 3 * u, fill)
            }
            val counted = state.todayMinutes.toInt()
            val goal = state.goalMinutes
            text.textSize = 11 * u
            text.color = if (counted > goal) P.OVER_TEXT else P.SUB
            val goalLine = if (counted > goal) "목표보다 ${formatMinutes(counted - goal)} 더" else "목표까지 ${formatMinutes(goal - counted)}"
            c.drawText(goalLine, 14 * u, size - 12 * u, text)
            return bmp
        }
    }
}
