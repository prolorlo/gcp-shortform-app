package com.shortformpet.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 화면 공통 디자인 값.
 *
 * 방향: "덜 볼수록 쑥쑥". 모찌(떡) 캐릭터와 쑥떡에서 색을 가져옵니다.
 * - 바탕·카드·글자는 거의 회색인 무채색. 크림색 바탕과 테라코타 주황은 흔한 AI 화면 조합이라 쓰지 않습니다.
 * - 포인트는 쑥색 하나: 잘하고 있다는 표시(체력, 할 일 완료, 간식, 측정 중)에만 씁니다.
 * - 목표를 넘긴 시간처럼 경고만 붉은색. 보라·파랑 그라데이션, 빛 번짐, 큰 그림자, 이모지는 쓰지 않습니다.
 * 숫자는 고정폭(tnum)으로 그려 1초마다 바뀌어도 흔들리지 않게 합니다.
 */
object P {
    val BG = Color.rgb(243, 244, 241)
    val SURFACE = Color.WHITE
    val SOFT = Color.rgb(238, 240, 236)
    val LINE = Color.rgb(227, 229, 224)
    val TRACK = Color.rgb(232, 234, 229)
    val INK = Color.rgb(23, 25, 26)
    val SUB = Color.rgb(91, 96, 90)
    val MUTED = Color.rgb(138, 143, 136)
    /** 쑥색 */
    val ACCENT = Color.rgb(91, 127, 58)
    /** 흰 바탕 위 글자용 쑥색 (명도 대비 4.5 이상) */
    val ACCENT_TEXT = Color.rgb(74, 107, 46)
    /** 목표 초과·삭제처럼 주의가 필요한 것 */
    val OVER = Color.rgb(209, 67, 67)
    val OVER_TEXT = Color.rgb(180, 35, 24)
    /** 캐릭터 카드만 옅은 쑥색 */
    val PET_BG = Color.rgb(233, 239, 223)
    val GOOD = ACCENT
    val WARN = Color.rgb(183, 121, 31)
    val BAR_PAST = Color.rgb(205, 209, 202)
    /** 불꽃은 연속일수에만 씁니다(화면에서 색이 진한 유일한 곳) */
    val FLAME = Color.rgb(245, 128, 32)
    val FLAME_DEEP = Color.rgb(226, 74, 30)
    val FLAME_CORE = Color.rgb(255, 232, 140)
    /** 흰 바탕 위 불꽃색 글자 */
    val FLAME_TEXT = Color.rgb(201, 76, 10)

    /** 모찌 탭 배경: 방 안 */
    val ROOM_WALL = Color.rgb(237, 241, 232)
    val ROOM_FLOOR = Color.rgb(226, 222, 201)
    /** 고양이 방석(침대): 갈색 바깥 · 밝은 갈색 안쪽 · 진한 갈색 테두리 */
    val CUSHION = Color.rgb(170, 120, 84)
    val CUSHION_IN = Color.rgb(204, 160, 120)
    val CUSHION_LINE = Color.rgb(124, 86, 58)
    /** 방 러그: 희미한 붉은색 */
    val RUG = Color.rgb(231, 211, 200)
    val RUG_LINE = Color.rgb(225, 200, 190)
    /** 모찌 탭 배경: 들판 */
    val FIELD_SKY = Color.rgb(235, 241, 236)
    val FIELD_GRASS = Color.rgb(220, 231, 208)

    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    /** 600. 700 이상은 쓰지 않습니다. 위계는 굵기보다 크기와 색으로 만듭니다. */
    val SEMI: Typeface = if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 600, false) else MEDIUM
}

enum class W { REGULAR, MEDIUM, SEMI }

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

fun roundRect(color: Int, radiusPx: Float, strokePx: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx
    if (strokePx > 0) setStroke(strokePx, strokeColor)
}

/** 누르면 살짝 어두워지는 배경 */
fun pressable(content: Drawable, radiusPx: Float, pressColor: Int = Color.argb(28, 28, 25, 23)): Drawable =
    RippleDrawable(ColorStateList.valueOf(pressColor), content, roundRect(Color.BLACK, radiusPx))

fun Context.label(value: String, sizeSp: Float, color: Int, weight: W = W.REGULAR, tabular: Boolean = false) = TextView(this).apply {
    text = value
    textSize = sizeSp
    setTextColor(color)
    typeface = when (weight) {
        W.REGULAR -> Typeface.DEFAULT
        W.MEDIUM -> P.MEDIUM
        W.SEMI -> P.SEMI
    }
    includeFontPadding = false
    setLineSpacing(0f, 1.25f)
    if (tabular) fontFeatureSettings = "tnum"
}

enum class BtnStyle { PRIMARY, OUTLINE, QUIET, ON_DARK }

/** 텍스트 버튼. 기본 Button의 그림자·대문자·최소 높이 없이 디자인 값 그대로 그립니다. */
fun Context.btn(value: String, style: BtnStyle, sizeSp: Float = 15f, onClick: () -> Unit) = TextView(this).apply {
    text = value
    textSize = sizeSp
    typeface = P.MEDIUM
    gravity = Gravity.CENTER
    includeFontPadding = false
    isClickable = true
    isFocusable = true
    setPadding(dp(14), 0, dp(14), 0)
    tag = style
    styleBtn(this, style)
    setOnClickListener { onClick() }
}

fun styleBtn(view: TextView, style: BtnStyle) {
    val ctx = view.context
    val r = ctx.dpf(10f)
    when (style) {
        BtnStyle.PRIMARY -> {
            view.setTextColor(Color.WHITE)
            view.background = pressable(roundRect(P.INK, r), r, Color.argb(60, 255, 255, 255))
        }
        BtnStyle.OUTLINE -> {
            view.setTextColor(P.INK)
            view.background = pressable(roundRect(P.SURFACE, r, ctx.dp(1), P.LINE), r)
        }
        BtnStyle.QUIET -> {
            view.setTextColor(P.SUB)
            view.background = pressable(roundRect(Color.TRANSPARENT, r), r)
        }
        BtnStyle.ON_DARK -> {
            view.setTextColor(Color.WHITE)
            view.background = pressable(roundRect(Color.TRANSPARENT, r, ctx.dp(1), Color.argb(90, 255, 255, 255)), r, Color.argb(50, 255, 255, 255))
        }
    }
}

/**
 * 누를 수 없는 버튼은 흐리게만 하지 않고 "지금은 안 됨"이 보이게 바꿉니다:
 * 채움을 빼고 점선 테두리 + 옅은 글자. 이유는 버튼 옆이나 아래에 글로 적습니다.
 */
fun setUsable(view: View, usable: Boolean) {
    view.isEnabled = usable
    val style = view.tag as? BtnStyle
    if (view is TextView && style != null) {
        view.alpha = 1f
        if (usable) styleBtn(view, style) else styleUnavailable(view)
    } else {
        view.alpha = if (usable) 1f else 0.4f
    }
}

fun styleUnavailable(view: TextView) {
    val ctx = view.context
    val r = ctx.dpf(10f)
    view.setTextColor(P.MUTED)
    view.background = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = r
        setStroke(ctx.dp(1), Color.rgb(196, 200, 192), ctx.dpf(4f), ctx.dpf(3f))
    }
}

/** 둥근 점 (상태 표시) */
fun Context.dot(color: Int) = View(this).apply {
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
}

fun View.setDotColor(color: Int) {
    (background as? GradientDrawable)?.setColor(color)
}

/** 체크박스: 눌러서 완료. 완료되면 포인트 색으로 채워지고 체크가 그려집니다. */
class CheckBoxView(context: Context) : View(context) {
    var checked = false
        set(value) { field = value; invalidate() }

    private val box = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val size = context.dpf(22f).coerceAtMost(s)
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        box.set(left, top, left + size, top + size)
        val r = context.dpf(6f)
        if (checked) {
            fill.style = Paint.Style.FILL
            fill.color = P.ACCENT
            canvas.drawRoundRect(box, r, r, fill)
            line.color = Color.WHITE
            line.strokeWidth = context.dpf(2f)
            path.reset()
            path.moveTo(left + size * 0.27f, top + size * 0.52f)
            path.lineTo(left + size * 0.44f, top + size * 0.68f)
            path.lineTo(left + size * 0.74f, top + size * 0.36f)
            canvas.drawPath(path, line)
        } else {
            line.color = Color.rgb(184, 189, 181)
            line.strokeWidth = context.dpf(1.5f)
            box.inset(line.strokeWidth / 2, line.strokeWidth / 2)
            canvas.drawRoundRect(box, r, r, line)
        }
    }
}

/** 2~4개 중 하나를 고르는 분할 버튼 */
class Segmented(context: Context, options: List<String>, private val onSelect: (Int) -> Unit) : LinearLayout(context) {
    private val items = mutableListOf<TextView>()
    var selected = -1
        private set

    init {
        orientation = HORIZONTAL
        setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
        background = roundRect(P.TRACK, context.dpf(12f))
        options.forEachIndexed { i, opt ->
            val item = context.label(opt, 14f, P.SUB, W.MEDIUM, tabular = true).apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { if (selected != i) { select(i); onSelect(i) } }
            }
            items += item
            addView(item, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    fun select(index: Int) {
        if (index == selected) return
        selected = index
        items.forEachIndexed { i, v ->
            val on = i == index
            v.setTextColor(if (on) P.INK else P.SUB)
            v.background = if (on) roundRect(P.SURFACE, context.dpf(8f)) else null
            v.isSelected = on
        }
    }
}

/** 최근 7일 막대 그래프. 점선은 하루 목표, 목표를 넘긴 날은 포인트 색. */
class WeekChartView(context: Context) : View(context) {
    data class Day(val label: String, val minutes: Long, val tracked: Boolean, val today: Boolean)

    private var days: List<Day> = emptyList()
    private var goal = 60

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = P.MUTED
        strokeWidth = context.dpf(1.2f)
        pathEffect = DashPathEffect(floatArrayOf(context.dpf(4f), context.dpf(4f)), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    private val rect = RectF()
    private val dashPath = Path()

    fun setData(days: List<Day>, goal: Int) {
        this.days = days
        this.goal = goal
        contentDescription = days.joinToString(", ") { "${it.label} ${if (it.tracked) formatMinutes(it.minutes.toInt()) else "기록 없음"}" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val topPad = context.dpf(22f)
        val bottomPad = context.dpf(26f)
        val chartH = h - topPad - bottomPad
        val baseY = topPad + chartH
        val maxV = maxOf(days.maxOf { it.minutes }, (goal * 1.25f).toLong(), 1L).toFloat()
        val slot = w / days.size
        val barW = minOf(slot * 0.5f, context.dpf(34f))
        val r = context.dpf(7f)

        // 바닥선
        bar.color = P.LINE
        canvas.drawRect(0f, baseY, w, baseY + context.dpf(1f), bar)

        days.forEachIndexed { i, d ->
            val cx = slot * i + slot / 2f
            val over = d.minutes > goal
            if (d.tracked) {
                val bh = (chartH * d.minutes / maxV).coerceAtLeast(context.dpf(4f))
                bar.color = when {
                    over -> P.OVER
                    d.today -> P.INK
                    else -> P.BAR_PAST
                }
                rect.set(cx - barW / 2, baseY - bh, cx + barW / 2, baseY)
                canvas.drawRoundRect(rect, r, r, bar)
                // 바닥 쪽 모서리는 각지게
                canvas.drawRect(cx - barW / 2, baseY - minOf(bh, r), cx + barW / 2, baseY, bar)

                text.textSize = context.sp(11f)
                text.typeface = if (d.today) P.MEDIUM else null
                text.color = if (over) P.OVER_TEXT else if (d.today) P.INK else P.SUB
                canvas.drawText(d.minutes.toString(), cx, baseY - bh - context.dpf(6f), text)
            } else {
                text.textSize = context.sp(11f)
                text.typeface = null
                text.color = P.MUTED
                canvas.drawText("–", cx, baseY - context.dpf(6f), text)
            }
            text.textSize = context.sp(12f)
            text.typeface = if (d.today) P.MEDIUM else null
            text.color = if (d.today) P.INK else P.SUB
            canvas.drawText(d.label, cx, h - context.dpf(6f), text)
        }

        // 목표 점선 (막대 위에)
        val gy = baseY - chartH * goal / maxV
        dashPath.reset()
        dashPath.moveTo(0f, gy)
        dashPath.lineTo(w, gy)
        canvas.drawPath(dashPath, dash)
    }
}


/**
 * 선 아이콘. 한 벌, 같은 상자 크기, 같은 굵기(1.5dp 이상), 글자 색을 따릅니다.
 * 이모지·특수 기호 대신 씁니다.
 */
class LineIcon(context: Context, kind: Kind) : View(context) {
    var kind: Kind = kind
        set(value) { if (field != value) { field = value; invalidate() } }
    enum class Kind { HOME, STUDY, RECORD, SETTINGS, INFO, CHEVRON, FLAME, HOUSE, FIELD, PLUS }

    var color: Int = P.MUTED
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        val u = s / 24f
        fun x(v: Float) = ox + u * v
        fun y(v: Float) = oy + u * v
        paint.color = color
        paint.strokeWidth = maxOf(u * 1.6f, context.dpf(1.5f))
        paint.style = Paint.Style.STROKE
        path.reset()
        when (kind) {
            Kind.HOME -> {
                // 모찌 얼굴: 둥근 몸 + 두 귀 + 두 눈
                r.set(x(5.5f), y(2.5f), x(10f), y(7f)); canvas.drawArc(r, 150f, 210f, false, paint)
                r.set(x(14f), y(2.5f), x(18.5f), y(7f)); canvas.drawArc(r, 180f, 210f, false, paint)
                r.set(x(3.5f), y(5.5f), x(20.5f), y(20.5f)); canvas.drawOval(r, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x(9.5f), y(13f), u * 1.2f, paint)
                canvas.drawCircle(x(14.5f), y(13f), u * 1.2f, paint)
            }
            Kind.STUDY -> {
                r.set(x(4f), y(4f), x(20f), y(20f)); canvas.drawRoundRect(r, u * 4f, u * 4f, paint)
                path.moveTo(x(8.5f), y(12.2f)); path.lineTo(x(11f), y(14.7f)); path.lineTo(x(15.8f), y(9.6f))
                canvas.drawPath(path, paint)
            }
            Kind.RECORD -> {
                canvas.drawLine(x(6f), y(19f), x(6f), y(13f), paint)
                canvas.drawLine(x(12f), y(19f), x(12f), y(5f), paint)
                canvas.drawLine(x(18f), y(19f), x(18f), y(10f), paint)
            }
            Kind.SETTINGS -> {
                canvas.drawLine(x(4f), y(8f), x(11.5f), y(8f), paint)
                canvas.drawLine(x(18.5f), y(8f), x(20f), y(8f), paint)
                canvas.drawLine(x(4f), y(16f), x(5.5f), y(16f), paint)
                canvas.drawLine(x(12.5f), y(16f), x(20f), y(16f), paint)
                canvas.drawCircle(x(15f), y(8f), u * 2.8f, paint)
                canvas.drawCircle(x(9f), y(16f), u * 2.8f, paint)
            }
            Kind.INFO -> {
                canvas.drawCircle(x(12f), y(12f), u * 9f, paint)
                canvas.drawLine(x(12f), y(11f), x(12f), y(16.5f), paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x(12f), y(7.8f), u * 1.2f, paint)
            }
            Kind.CHEVRON -> {
                path.moveTo(x(9.5f), y(6f)); path.lineTo(x(15.5f), y(12f)); path.lineTo(x(9.5f), y(18f))
                canvas.drawPath(path, paint)
            }
            Kind.FLAME -> {
                // 채운 불꽃 (연속일수 옆)
                paint.style = Paint.Style.FILL
                path.moveTo(x(12f), y(2.5f))
                path.cubicTo(x(14f), y(7f), x(19f), y(9f), x(19f), y(14.5f))
                path.cubicTo(x(19f), y(18.6f), x(15.9f), y(21.5f), x(12f), y(21.5f))
                path.cubicTo(x(8.1f), y(21.5f), x(5f), y(18.6f), x(5f), y(14.8f))
                path.cubicTo(x(5f), y(11.5f), x(7f), y(9.5f), x(8.5f), y(8f))
                path.cubicTo(x(8.6f), y(10.5f), x(9.6f), y(11.6f), x(10.6f), y(12f))
                path.cubicTo(x(10.4f), y(8.5f), x(11f), y(5f), x(12f), y(2.5f))
                path.close()
                canvas.drawPath(path, paint)
            }
            Kind.HOUSE -> {
                // 방 안: 지붕 + 집 + 창
                path.moveTo(x(3.5f), y(11f)); path.lineTo(x(12f), y(4f)); path.lineTo(x(20.5f), y(11f))
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(x(5.5f), y(9.5f)); path.lineTo(x(5.5f), y(20f)); path.lineTo(x(18.5f), y(20f)); path.lineTo(x(18.5f), y(9.5f))
                canvas.drawPath(path, paint)
                r.set(x(10f), y(13f), x(14f), y(17f)); canvas.drawRect(r, paint)
            }
            Kind.FIELD -> {
                // 들판: 해 + 언덕
                canvas.drawCircle(x(16.5f), y(7.5f), u * 3f, paint)
                path.moveTo(x(2.5f), y(19.5f))
                path.quadTo(x(8f), y(11f), x(13f), y(16f))
                path.quadTo(x(17f), y(13f), x(21.5f), y(17.5f))
                canvas.drawPath(path, paint)
            }
            Kind.PLUS -> {
                canvas.drawLine(x(12f), y(5f), x(12f), y(19f), paint)
                canvas.drawLine(x(5f), y(12f), x(19f), y(12f), paint)
            }
        }
    }
}

/**
 * 옆으로 밀어서 넘기는 페이지 묶음 (androidx ViewPager 없이 직접 구현).
 * 가로로 확실히 민 경우에만 가로채므로, 각 페이지 안의 세로 스크롤과 버튼은 그대로 동작합니다.
 */
class Pager(context: Context) : ViewGroup(context) {
    var onPageChanged: ((Int) -> Unit)? = null
    var current = 0
        private set
    /** 밥 던지기 조준 중에는 옆으로 넘기지 않습니다. */
    var locked = false

    private val scroller = OverScroller(context, DecelerateInterpolator(2.2f))
    private val config = ViewConfiguration.get(context)
    private val slop = config.scaledTouchSlop
    private val minFling = config.scaledMinimumFlingVelocity * 4
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        // 위·아래 여백(상태 바 높이 등)을 뺀 만큼만 페이지에 줍니다.
        val ch = MeasureSpec.makeMeasureSpec((h - paddingTop - paddingBottom).coerceAtLeast(0), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, paddingTop, (i + 1) * w, b - t - paddingBottom)
        if (changed && scroller.isFinished) scrollTo(current * w, 0)
    }

    fun show(page: Int, animate: Boolean = true) {
        val target = page.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        val changed = target != current
        current = target
        if (width == 0 || !animate) {
            scroller.forceFinished(true)
            scrollTo(target * width, 0)
        } else {
            scroller.startScroll(scrollX, 0, target * width - scrollX, 0, 320)
            postInvalidateOnAnimation()
        }
        if (changed) onPageChanged?.invoke(target)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (locked) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x
                startTracking(ev)
                // 넘어가는 중에 다시 잡으면 그 자리에서 이어서 끕니다.
                dragging = !scroller.isFinished
                if (dragging) scroller.forceFinished(true)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && abs(dx) > slop && abs(dx) > abs(dy) * 1.4f) {
                    dragging = true
                    lastX = ev.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) stopTracking()
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (velocity == null) startTracking(ev) else velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; lastX = ev.x }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.x - downX) > slop) dragging = true
                if (dragging) {
                    var delta = lastX - ev.x
                    val maxX = (childCount - 1) * width
                    // 첫 장·마지막 장 바깥으로는 덜 움직이게 합니다.
                    if (scrollX + delta < 0 || scrollX + delta > maxX) delta /= 3f
                    scrollBy(delta.toInt(), 0)
                }
                lastX = ev.x
            }
            MotionEvent.ACTION_UP -> {
                val v = velocity?.let { it.computeCurrentVelocity(1000); it.xVelocity } ?: 0f
                val base = scrollX.toFloat() / width.coerceAtLeast(1)
                val target = when {
                    v < -minFling -> floor(base).toInt() + 1
                    v > minFling -> ceil(base).toInt() - 1
                    else -> round(base).toInt()
                }
                show(target)
                stopTracking()
            }
            MotionEvent.ACTION_CANCEL -> { show(current); stopTracking() }
        }
        return true
    }

    private fun startTracking(ev: MotionEvent) {
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    private fun stopTracking() {
        dragging = false
        velocity?.recycle()
        velocity = null
    }
}
