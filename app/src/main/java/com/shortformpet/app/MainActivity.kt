package com.shortformpet.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * 메인 화면. XML 레이아웃 없이 코드로 구성합니다.
 *
 * 아래 탭 4개(오늘 · 공부 · 기록 · 설정)로 나누고, 옆으로 밀어도 넘어갑니다.
 * - 오늘: 권한 안내, 캐릭터, 오늘 시청 시간, 할 일 바로가기
 * - 공부: 할 일, 집중 세션, 휴식 쿠폰, 보호권
 * - 기록: 최근 7일 그래프, 요약 숫자, 최근 정산
 * - 설정: 하루 목표, 안내 화면, 이름, 권한 상태, (디버그) 개발자 테스트
 *
 * 화면에 보이는 값은 모두 저장소(SharedPreferences)에서 읽어 오므로 앱을 껐다 켜도 같은 상태로 그려집니다.
 * 색·글자·버튼·아이콘·페이지 넘김은 Ui.kt에 있습니다.
 * 간격은 모두 4dp 단위입니다.
 */
class MainActivity : Activity() {

    private lateinit var repo: UsageRepository
    private lateinit var study: StudyManager
    private lateinit var pet: PetEngine
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var pager: Pager
    private val tabs = mutableListOf<Pair<LineIcon, TextView>>()

    // 모찌 (전체 화면)
    private lateinit var world: PetWorldView
    private lateinit var hud: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var petName: TextView
    private lateinit var petStage: TextView
    private lateinit var streakText: TextView
    private lateinit var streakIcon: LineIcon
    private lateinit var sceneIcon: LineIcon
    private lateinit var petNote: TextView
    private lateinit var petHint: TextView
    private lateinit var hpBar: ProgressBar
    private lateinit var hpText: TextView
    private lateinit var expBar: ProgressBar
    private lateinit var expText: TextView
    private lateinit var expNote: TextView
    private lateinit var feedButton: TextView
    private lateinit var snackButton: TextView
    /** 지금 던지는 것이 간식인지(false면 밥) */
    private var aimSnack = false

    // 오늘·할 일
    private lateinit var dateText: TextView
    private lateinit var permCard: LinearLayout
    private lateinit var permTitle: TextView
    private lateinit var accRow: View
    private lateinit var notiRow: View
    private lateinit var battRow: View
    private lateinit var setupHint: View
    private lateinit var setupHintValue: TextView
    /** 방금 완료한 할 일(되돌리기 버튼을 잠깐 보여 줌) */
    private var undoIndex = -1
    private var undoUntil = 0L
    private lateinit var todayTotal: TextView
    private lateinit var todayGoal: TextView
    private lateinit var todayBar: ProgressBar
    private lateinit var todayStatus: TextView
    private val appRows = mutableMapOf<ShortFormApp, TextView>()
    private val appBars = mutableMapOf<ShortFormApp, ProgressBar>()
    private lateinit var detectDot: View
    private lateinit var detectText: TextView
    private lateinit var resetText: TextView
    private lateinit var todoList: LinearLayout
    private lateinit var todoHint: TextView
    private lateinit var addTodoRow: View
    private lateinit var idleSession: LinearLayout
    private lateinit var lengthPicker: Segmented
    private lateinit var sessionButton: TextView
    private lateinit var activeSession: LinearLayout
    private lateinit var sessionClock: TextView
    private lateinit var couponSub: TextView
    private lateinit var couponButton: TextView
    private lateinit var shieldText: TextView
    private var todoSignature = ""

    // 기록
    private lateinit var weekChart: WeekChartView
    private lateinit var weekNote: TextView
    private lateinit var weekSummary: TextView
    private var bestStreak = 0
    private lateinit var reportText: TextView
    private lateinit var stampRow: LinearLayout
    private lateinit var stampNote: TextView

    // 설정
    private lateinit var goalPicker: Segmented
    private lateinit var intervalValue: TextView
    private lateinit var slotList: LinearLayout
    private var slotSignature = ""
    private lateinit var nameValue: TextView
    private lateinit var accStatus: TextView
    private lateinit var notiStatus: TextView
    private lateinit var battStatus: TextView

    // 개발자 테스트 (디버그 빌드 전용)
    private var devInfo: TextView? = null

    private var lastWidgetPush = 0L
    private var lastWeekRender = 0L

    private val uiPrefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stores = Stores.of(this)
        repo = UsageRepository(stores)
        study = StudyManager(stores)
        pet = PetEngine(stores)
        Notifier.createChannels(this)
        DailyResetScheduler.schedule(this)
        if (savedInstanceState == null) repo.increment(DayClock.logicalDay(), SyncManager.COUNT_LAUNCH)
        setContentView(buildUi())
        // 3버튼 내비게이션에서 아래 탭 바 밑에 회색 막이 덧씌워지지 않게 합니다.
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        selectTab(savedInstanceState?.getInt(KEY_TAB) ?: intent.getIntExtra(EXTRA_TAB, TAB_MOCHI), animate = false)
        when {
            !repo.consentAccepted -> showConsent()
            !repo.manualSeen -> handler.postDelayed({ showManual() }, 300)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 알림·위젯을 누르면 해당 탭으로
        if (intent.hasExtra(EXTRA_TAB)) selectTab(intent.getIntExtra(EXTRA_TAB, TAB_MOCHI))
    }

    override fun onStart() {
        super.onStart()
        inForeground = true
    }

    override fun onStop() {
        inForeground = false
        // 앱이 뒤로 내려간 뒤에만 런처 아이콘 표정을 바꿉니다(앞에 있을 때 바꾸면 런처가 깜빡일 수 있음).
        AppIconSwitcher.applyIfNeeded(this)
        MochiWidgetProvider.update(this, force = true)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, pager.current)
    }

    override fun onResume() {
        super.onResume()
        lastWeekRender = 0L
        todoSignature = ""
        handler.post(refresher)
        SyncScheduler.kick(this)
        world.active = pager.current == TAB_MOCHI
        // 정산이 끝난 뒤 그날 처음 열었을 때 한 번만 연속일수를 보여 주고, 성장했으면 성장 모션을 재생합니다.
        handler.postDelayed({ maybeShowStreak(); maybePlayEvolution() }, 350)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        world.active = false
        super.onPause()
    }

    @Deprecated("Activity 기본 뒤로 가기")
    override fun onBackPressed() {
        when {
            world.aiming -> setAiming(false)
            // 다른 탭에서 뒤로 가기를 누르면 먼저 '모찌'로 돌아옵니다.
            pager.current != TAB_MOCHI -> selectTab(TAB_MOCHI)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ 뼈대: 페이지 + 아래 탭

    private fun buildUi(): View {
        pager = Pager(this).apply {
            addView(buildMochiPage())
            addView(buildTodayPage())
            addView(buildRecordPage())
            addView(buildSettingsPage())
            onPageChanged = {
                highlightTab(it)
                world.active = it == TAB_MOCHI
                if (it != TAB_MOCHI && world.aiming) setAiming(false)
            }
        }

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(P.SURFACE)
        }
        listOf(
            Triple("모찌", LineIcon.Kind.HOME, TAB_MOCHI),
            Triple("오늘·할 일", LineIcon.Kind.STUDY, TAB_TODAY),
            Triple("기록", LineIcon.Kind.RECORD, TAB_RECORD),
            Triple("설정", LineIcon.Kind.SETTINGS, TAB_SETTINGS),
        ).forEach { (name, kind, index) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                contentDescription = "$name 탭"
                background = pressable(GradientDrawable().apply { setColor(Color.TRANSPARENT) }, 0f)
                setOnClickListener { selectTab(index) }
            }
            val icon = LineIcon(this, kind)
            val text = label(name, 12f, P.MUTED, W.MEDIUM)
            item.addView(icon, LinearLayout.LayoutParams(24.dp, 24.dp))
            item.addView(text, lp(WRAP, WRAP, top = 4))
            tabs += icon to text
            nav.addView(item, LinearLayout.LayoutParams(0, 64.dp, 1f))
        }

        val navWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(P.SURFACE)
            addView(View(this@MainActivity).apply { setBackgroundColor(P.LINE) }, LinearLayout.LayoutParams(MATCH, 1.dp))
            addView(nav)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(P.BG)
            addView(pager, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(navWrap, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        // Android 15부터 화면이 시스템 바 아래까지 그려지므로 위는 페이지, 아래는 탭 바가 그만큼 비웁니다.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                bars.top to bars.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            pager.setPadding(0, top, 0, 0)
            navWrap.setPadding(0, 0, 0, bottom)
            insets
        }
        return root
    }

    private fun selectTab(index: Int, animate: Boolean = true) {
        pager.show(index, animate)
        highlightTab(index)
    }

    private fun highlightTab(index: Int) {
        tabs.forEachIndexed { i, (icon, text) ->
            val on = i == index
            icon.color = if (on) P.INK else P.MUTED
            text.setTextColor(if (on) P.INK else P.MUTED)
            (icon.parent as? View)?.isSelected = on
        }
    }

    /** 한 페이지: 세로 스크롤 + 제목 줄. 제목은 화면 이름 그대로입니다. */
    private fun page(title: String, meta: TextView? = null, body: LinearLayout.() -> Unit): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 32.dp)
            // 대화상자가 닫힐 때 포커스가 아래쪽 컨트롤로 옮겨 가며 화면이 저절로 스크롤되지 않게 합니다.
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(4.dp, 8.dp, 4.dp, 20.dp)
        }
        header.addView(label(title, 28f, P.INK, W.MEDIUM), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (meta != null) header.addView(meta, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = 4.dp })
        content.addView(header)
        content.body()
        return ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content)
        }
    }

    // ------------------------------------------------------------------ 오늘

    /**
     * 모찌 탭: 카드 없이 화면 전체가 모찌의 방(또는 들판)입니다.
     * 위에 이름·연속일수·행복도·경험치 HUD, 아래에 밥 던지기·간식 도크를 얹습니다.
     */
    private fun buildMochiPage(): View {
        val frame = android.widget.FrameLayout(this)
        world = PetWorldView(this).apply {
            onPetTapped = { onPetTapped() }
            onFoodCaught = { inAir -> onFoodCaught(inAir) }
            onFoodDropped = { say(Lines.FEED_DROP, 2_000) }
        }
        frame.addView(world, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))

        // ---- 위 HUD
        hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 12.dp, 16.dp, 14.dp)
            background = roundRect(P.SURFACE, 16.dp.toFloat(), 1.dp, P.LINE)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            isClickable = true
            setOnClickListener { showRename() }
        }
        petName = label("", 20f, P.INK, W.SEMI)
        petStage = label("", 13f, P.SUB).apply { setPadding(8.dp, 0, 0, 2.dp) }
        names.addView(petName)
        names.addView(petStage)
        header.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f))
        val streakChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp, 6.dp, 10.dp, 6.dp)
            isClickable = true
            background = pressable(roundRect(P.SOFT, 10.dp.toFloat()), 10.dp.toFloat())
            setOnClickListener { showStreak() }
        }
        streakIcon = LineIcon(this, LineIcon.Kind.FLAME)
        streakChip.addView(streakIcon, LinearLayout.LayoutParams(18.dp, 18.dp).apply { marginEnd = 4.dp })
        streakText = label("", 14f, P.INK, W.SEMI, tabular = true)
        streakChip.addView(streakText)
        header.addView(streakChip)
        val sceneButton = android.widget.FrameLayout(this).apply {
            isClickable = true
            background = pressable(roundRect(P.SOFT, 10.dp.toFloat()), 10.dp.toFloat())
            setOnClickListener {
                pet.scene = 1 - pet.scene
                refresh()
            }
        }
        sceneIcon = LineIcon(this, LineIcon.Kind.FIELD).apply { color = P.INK }
        sceneButton.addView(sceneIcon, android.widget.FrameLayout.LayoutParams(20.dp, 20.dp, Gravity.CENTER))
        header.addView(sceneButton, LinearLayout.LayoutParams(36.dp, 32.dp).apply { marginStart = 8.dp })
        hud.addView(header)

        hpText = label("", 13f, P.INK, tabular = true)
        hpBar = bar(P.ACCENT)
        expText = label("", 13f, P.INK, tabular = true)
        expNote = label("", 12f, P.SUB, tabular = true)
        expBar = bar(P.INK)
        hud.addView(statRow(hpText, null), lp(MATCH, WRAP, top = 12))
        hud.addView(hpBar, lp(MATCH, 6.dp, top = 6))
        hud.addView(statRow(expText, expNote), lp(MATCH, WRAP, top = 10))
        hud.addView(expBar, lp(MATCH, 6.dp, top = 6))
        frame.addView(hud, android.widget.FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply {
            setMargins(12.dp, 8.dp, 12.dp, 0)
        })

        // ---- 아래 도크
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            background = roundRect(P.SURFACE, 16.dp.toFloat(), 1.dp, P.LINE)
        }
        petHint = label("", 12f, P.SUB)
        dock.addView(petHint, lp(MATCH, WRAP).apply { bottomMargin = 8.dp })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // 같은 버튼을 다시 누르면 그만 던지기, 다른 버튼을 누르면 밥 ↔ 간식으로 바꿉니다.
        feedButton = btn(Lines.THROW, BtnStyle.PRIMARY) {
            if (world.aiming && !aimSnack) setAiming(false) else setAiming(true, snack = false)
        }
        snackButton = btn(Lines.SNACK_EMPTY, BtnStyle.OUTLINE) {
            if (world.aiming && aimSnack) setAiming(false) else setAiming(true, snack = true)
        }
        buttons.addView(feedButton, LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 8.dp })
        buttons.addView(snackButton, LinearLayout.LayoutParams(0, 48.dp, 1f))
        dock.addView(buttons)
        // 버튼을 못 누르는 이유
        petNote = label("", 12f, P.SUB)
        dock.addView(petNote, lp(MATCH, WRAP, top = 8))
        frame.addView(dock, android.widget.FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            setMargins(12.dp, 0, 12.dp, 12.dp)
        })

        // HUD·도크 높이만큼은 모찌가 걷는 자리에서 뺍니다.
        hud.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> world.insetTop = (v.bottom + 4.dp).toFloat() }
        dock.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> world.insetBottom = (frame.height - v.top + 4.dp).toFloat() }
        return frame
    }

    /** 던지기 모드 켜기/끄기. snack = true면 밥 대신 간식을 던집니다. */
    private fun setAiming(on: Boolean, snack: Boolean = false) {
        if (on && !(if (snack) canSnack() else canFeed())) return
        // 밥 ↔ 간식을 바꿀 때는 들고 있던 것을 내려놓고 다시 집습니다.
        if (on && world.aiming) world.aiming = false
        aimSnack = on && snack
        world.snack = aimSnack
        world.aiming = on
        pager.locked = on
        if (on) world.say(if (snack) Lines.SNACK_AIM else Lines.FEED_AIM, 3_000)
        refresh()
    }

    private fun canFeed(): Boolean {
        val s = pet.state()
        return !s.isEgg && !s.fedToday
    }

    private fun canSnack(): Boolean {
        val s = pet.state()
        return !s.isEgg && s.snacksAvailable > 0
    }

    /** inAir = 날아오는 밥(간식)을 입으로 정확히 받아먹음, false = 바닥에 떨어진 것을 주워 먹음 */
    private fun onFoodCaught(inAir: Boolean) {
        val before = pet.state().happiness
        val reply: String
        val fed: Boolean
        val catchLine: String
        if (aimSnack) {
            reply = pet.giveSnack()
            fed = reply != Lines.SNACK_NONE && reply != Lines.SNACK_EGG
            catchLine = Lines.snackCatch()
        } else {
            reply = pet.feed()
            fed = reply != Lines.FEED_ALREADY && reply != Lines.FEED_EGG
            catchLine = Lines.feedCatch()
        }
        world.say(if (inAir && fed) catchLine else reply)
        val gain = pet.state().happiness - before
        if (gain > 0) world.floatText("+$gain")
        setAiming(false)
    }

    private fun buildTodayPage(): View {
        dateText = label("", 13f, P.SUB, tabular = true)
        return page("오늘·할 일", dateText) {
            addView(buildPermissionCard())
            setupHintValue = label("", 14f, P.OVER_TEXT, tabular = true)
            setupHint = card(padV = 4).apply { addView(linkRow("알림 · 배터리 설정", setupHintValue) { selectTab(TAB_SETTINGS) }) }
            addView(setupHint)
            addView(buildWatchCard())
            addView(buildTodoCard())
            addView(buildSessionCard())
            addView(buildRewardCard())
        }
    }

    private fun buildPermissionCard(): View {
        permCard = card()
        permTitle = label("", 17f, P.INK, W.MEDIUM)
        permCard.addView(permTitle)
        accRow = permissionRow("접근성 서비스", "설정 → 접근성 → 설치된 앱 → 숏폼펫 시청 시간 측정", "켜기") { openAccessibility() }
        notiRow = permissionRow("알림", "많이 봤을 때 알려 드려요", "허용") { requestNotifications() }
        battRow = permissionRow("배터리 최적화 제외", "일부 휴대폰은 백그라운드 측정을 멈춰요. '제한 없음'으로 바꿔 주세요", "설정") { openBattery() }
        permCard.addView(accRow)
        // 알림·배터리는 측정에 꼭 필요하지 않으므로 오늘 탭에서는 한 줄로만 알리고 설정 탭에서 켭니다.
        permCard.addView(notiRow)
        permCard.addView(battRow)
        notiRow.visibility = View.GONE
        battRow.visibility = View.GONE
        return permCard
    }

    private fun buildWatchCard(): View {
        val c = card()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(label("오늘 본 숏폼", 14f, P.SUB, W.MEDIUM), LinearLayout.LayoutParams(0, WRAP, 1f))
        todayStatus = label("", 13f, P.SUB, W.MEDIUM, tabular = true)
        top.addView(todayStatus)
        c.addView(top)

        val big = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
        }
        todayTotal = label("0분", 40f, P.INK, W.SEMI, tabular = true).apply { letterSpacing = -0.02f }
        todayGoal = label("", 15f, P.SUB, tabular = true).apply { setPadding(8.dp, 0, 0, 0) }
        big.addView(todayTotal)
        big.addView(todayGoal)
        c.addView(big, lp(MATCH, WRAP, top = 12))

        todayBar = bar(P.INK)
        c.addView(todayBar, lp(MATCH, 8.dp, top = 16))

        // 앱별 시간: 숫자를 상자에 나란히 넣는 대신, 오늘 본 시간 중 몫을 얇은 막대로 보여 주는 목록
        c.addView(divider(), lp(MATCH, 1.dp, top = 20))
        for (app in ShortFormApp.entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 36.dp
            }
            row.addView(label(shortAppName(app), 14f, P.SUB), LinearLayout.LayoutParams(48.dp, WRAP))
            val share = bar(P.BAR_PAST)
            row.addView(share, LinearLayout.LayoutParams(0, 4.dp, 1f).apply { marginEnd = 16.dp })
            val value = label("0분", 14f, P.INK, W.MEDIUM, tabular = true).apply { gravity = Gravity.END }
            row.addView(value, LinearLayout.LayoutParams(72.dp, WRAP))
            appRows[app] = value
            appBars[app] = share
            c.addView(row, lp(MATCH, WRAP, top = 4))
        }

        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        detectDot = dot(P.MUTED)
        status.addView(detectDot, LinearLayout.LayoutParams(8.dp, 8.dp).apply { marginEnd = 8.dp })
        detectText = label("", 13f, P.SUB)
        status.addView(detectText, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.addView(status, lp(MATCH, WRAP, top = 16))
        resetText = label("", 12f, P.MUTED, tabular = true).apply { setPadding(16.dp, 0, 0, 0) }
        c.addView(resetText, lp(MATCH, WRAP, top = 4))
        return c
    }

    private fun buildTodoCard(): View {
        val c = card(padV = 12)
        c.addView(label("오늘 할 일", 17f, P.INK, W.MEDIUM), lp(MATCH, WRAP, top = 8))
        todoList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(todoList, lp(MATCH, WRAP, top = 4))
        addTodoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            background = pressable(roundRect(Color.TRANSPARENT, 8.dp.toFloat()), 8.dp.toFloat())
            setOnClickListener { showAddTodo() }
            addView(label("+", 20f, P.SUB).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(24.dp, WRAP).apply { marginEnd = 12.dp })
            addView(label("할 일 추가", 15f, P.SUB))
        }
        c.addView(addTodoRow, lp(MATCH, 52.dp))
        todoHint = label("이름을 누르면 바로 집중 시작 · 길게 누르면 삭제", 12f, P.MUTED)
        c.addView(todoHint, lp(MATCH, WRAP, top = 0).apply { bottomMargin = 8.dp })
        return c
    }

    private fun buildSessionCard(): View {
        val c = card()
        c.addView(label("집중 세션", 17f, P.INK, W.MEDIUM))

        idleSession = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lengthPicker = Segmented(this, listOf("5분 착수", "25분", "50분")) { i ->
            repo.sessionMinutes = UsageRepository.SESSION_LENGTHS[i]
            refresh()
        }
        idleSession.addView(lengthPicker, lp(MATCH, 44.dp))
        sessionButton = btn("", BtnStyle.PRIMARY, 16f) { onSessionButton() }
        idleSession.addView(sessionButton, lp(MATCH, 52.dp, top = 8))
        idleSession.addView(label("집중하는 동안 인스타는 잠그고, 유튜브는 과제용일 때만 일반 영상을 열어 둬요. 쇼츠는 끝까지 막아요", 12f, P.SUB), lp(MATCH, WRAP, top = 12))
        c.addView(idleSession, lp(MATCH, WRAP, top = 16))

        activeSession = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 16.dp, 16.dp, 16.dp)
            background = roundRect(P.INK, 12.dp.toFloat())
        }
        val activeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val activeTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        activeTexts.addView(label("집중하는 중", 13f, Color.argb(180, 255, 255, 255)))
        sessionClock = label("", 36f, Color.WHITE, W.SEMI, tabular = true)
        activeTexts.addView(sessionClock, lp(WRAP, WRAP, top = 4))
        activeRow.addView(activeTexts, LinearLayout.LayoutParams(0, WRAP, 1f))
        activeRow.addView(btn("그만두기", BtnStyle.ON_DARK, 14f) { onSessionButton() }, LinearLayout.LayoutParams(WRAP, 40.dp))
        activeSession.addView(activeRow)
        activeSession.addView(label("인스타와 쇼츠를 막고 있어요", 12f, Color.argb(160, 255, 255, 255)), lp(MATCH, WRAP, top = 12))
        c.addView(activeSession, lp(MATCH, WRAP, top = 16))
        return c
    }

    private fun buildRewardCard(): View {
        val c = card(padV = 8)
        // 휴식 쿠폰
        val coupon = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 72.dp
        }
        val couponTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener { showCouponInfo() }
        }
        couponTexts.addView(titleWithInfo("휴식 쿠폰"))
        couponSub = label("", 13f, P.SUB, tabular = true)
        couponTexts.addView(couponSub, lp(WRAP, WRAP, top = 4))
        coupon.addView(couponTexts, LinearLayout.LayoutParams(0, WRAP, 1f))
        couponButton = btn("쓰기", BtnStyle.OUTLINE, 14f) { onCouponButton() }
        coupon.addView(couponButton, LinearLayout.LayoutParams(72.dp, 40.dp))
        c.addView(coupon)

        c.addView(divider(), lp(MATCH, 1.dp))

        // 보호권
        val shield = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 72.dp
            isClickable = true
            setOnClickListener { showShieldInfo() }
        }
        shield.addView(titleWithInfo("연속일수 보호권"))
        shieldText = label("", 13f, P.SUB, tabular = true)
        shield.addView(shieldText, lp(WRAP, WRAP, top = 4))
        c.addView(shield, lp(MATCH, WRAP))
        return c
    }

    // ------------------------------------------------------------------ 기록

    private fun buildRecordPage(): View = page("기록") {
        val chart = card()
        val head = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(label("최근 7일", 17f, P.INK, W.MEDIUM), LinearLayout.LayoutParams(0, WRAP, 1f))
        weekNote = label("", 12f, P.SUB, tabular = true)
        head.addView(weekNote)
        chart.addView(head)
        weekChart = WeekChartView(this@MainActivity)
        chart.addView(weekChart, lp(MATCH, 220.dp, top = 16))
        chart.addView(divider(), lp(MATCH, 1.dp, top = 16))
        weekSummary = label("", 14f, P.SUB, tabular = true).apply { setLineSpacing(0f, 1.4f) }
        chart.addView(weekSummary, lp(MATCH, WRAP, top = 16))
        addView(chart)

        // 연속일수 주간 달성표 + 최고 기록
        val streakCard = card()
        streakCard.isClickable = true
        streakCard.setOnClickListener { showStreak() }
        streakCard.addView(label("연속일수", 17f, P.INK, W.MEDIUM))
        stampRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        streakCard.addView(stampRow, lp(MATCH, WRAP, top = 16))
        stampNote = label("", 14f, P.SUB, tabular = true)
        streakCard.addView(stampNote, lp(MATCH, WRAP, top = 12))
        addView(streakCard)

        val report = card()
        report.addView(label("최근 정산", 17f, P.INK, W.MEDIUM))
        report.addView(label("매일 오전 5시에 전날 기록으로 정산해요", 12f, P.MUTED), lp(MATCH, WRAP, top = 4))
        reportText = label("", 14f, P.SUB)
        report.addView(reportText, lp(MATCH, WRAP, top = 12))
        addView(report)
    }

    // ------------------------------------------------------------------ 설정

    private fun buildSettingsPage(): View = page("설정") {
        val c = card()
        c.addView(label("하루 목표", 15f, P.INK, W.MEDIUM))
        c.addView(label("목표 이하로 본 날은 연속일수가 올라요", 12f, P.SUB), lp(MATCH, WRAP, top = 4))
        val goals = UsageRepository.GOAL_OPTIONS // 30, 40, 50, 60분
        goalPicker = Segmented(this@MainActivity, goals.map { formatMinutes(it) }) { i ->
            repo.dailyGoalMinutes = goals[i]
            lastWeekRender = 0L
            refresh()
        }
        goalPicker.select(goals.indexOf(repo.dailyGoalMinutes).coerceAtLeast(0))
        c.addView(goalPicker, lp(MATCH, 44.dp, top = 12))
        c.addView(divider(), lp(MATCH, 1.dp, top = 20))

        // 연속 시청 알림 주기 (1~45분)
        val intervalRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 72.dp
        }
        val intervalTexts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        intervalTexts.addView(label("연속 시청 알림", 15f, P.INK, W.MEDIUM))
        intervalTexts.addView(label("이어서 이만큼 보면 알려 줘요", 12f, P.SUB), lp(WRAP, WRAP, top = 4))
        intervalRow.addView(intervalTexts, LinearLayout.LayoutParams(0, WRAP, 1f))
        intervalValue = label("", 15f, P.INK, W.MEDIUM, tabular = true).apply { gravity = Gravity.CENTER }
        fun stepInterval(dir: Int) {
            val opts = UsageRepository.CONTINUOUS_OPTIONS
            val i = opts.indexOfFirst { it >= repo.continuousAlertMinutes }.coerceAtLeast(0)
            repo.continuousAlertMinutes = opts[(i + dir).coerceIn(0, opts.size - 1)]
            refresh()
        }
        intervalRow.addView(btn("−", BtnStyle.OUTLINE, 16f) { stepInterval(-1) }.apply { contentDescription = "주기 줄이기" }, LinearLayout.LayoutParams(40.dp, 40.dp))
        intervalRow.addView(intervalValue, LinearLayout.LayoutParams(56.dp, WRAP))
        intervalRow.addView(btn("+", BtnStyle.OUTLINE, 16f) { stepInterval(1) }.apply { contentDescription = "주기 늘리기" }, LinearLayout.LayoutParams(40.dp, 40.dp))
        c.addView(intervalRow, lp(MATCH, WRAP))
        addView(c)

        // 약속 시간표 (최대 3개)
        val slots = card(padV = 12)
        slots.addView(label("약속 시간표", 15f, P.INK, W.MEDIUM), lp(MATCH, WRAP, top = 8))
        slots.addView(label("이 시간에 숏폼을 켜면 3초 안내 화면이 떠요 · 최대 ${UsageRepository.MAX_SLOTS}개", 12f, P.SUB), lp(MATCH, WRAP, top = 4))
        slotList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        slots.addView(slotList, lp(MATCH, WRAP, top = 4))
        addView(slots)

        addView(label("안내", 13f, P.SUB, W.MEDIUM).apply { setPadding(4.dp, 12.dp, 4.dp, 8.dp) })
        val toggles = card(padV = 4)
        toggles.addView(switchRow("진입 안내 화면", "목표를 넘긴 뒤나 시간표 시간에 숏폼을 켜면 3초 동안 보여줘요", repo.entryGuideEnabled) { repo.entryGuideEnabled = it })
        toggles.addView(divider(), lp(MATCH, 1.dp))
        toggles.addView(switchRow("아침 집중 쪽지", "오전 9시에 오늘 할 일을 한 번 알려 줘요", repo.morningNoteEnabled) { repo.morningNoteEnabled = it })
        toggles.addView(divider(), lp(MATCH, 1.dp))
        toggles.addView(switchRow("빼꼼 모찌", "숏폼을 오래 볼 때 화면 끝에서 모찌가 시간을 알려 줘요", repo.peekEnabled) { repo.peekEnabled = it })
        toggles.addView(divider(), lp(MATCH, 1.dp))
        toggles.addView(switchRow("홈 화면 산책", "홈 화면을 모찌가 돌아다녀요 · 화면 끝으로 끌어 두면 눈만 빼꼼 내밀어요", repo.homeWalkEnabled) {
            repo.homeWalkEnabled = it
            // 산책 모찌는 접근성 서비스 안에서 덕니다. 꺼져 있으면 바로 켜러 갑니다.
            if (it && !isAccessibilityEnabled()) {
                Toast.makeText(this@MainActivity, Lines.HOME_WALK_NEED_ACC, Toast.LENGTH_LONG).show()
                openAccessibility()
            }
        })
        toggles.addView(divider(), lp(MATCH, 1.dp))
        toggles.addView(switchRow("연속일수 화면", "하루 처음 열 때 연속일수를 크게 보여줘요", repo.streakScreenEnabled) { repo.streakScreenEnabled = it })
        toggles.addView(divider(), lp(MATCH, 1.dp))
        toggles.addView(switchRow("행복도에 따라 앱 아이콘 바꾸기", "앱을 닫으면 홈 화면 아이콘이 모찌 표정으로 바뀌어요", repo.dynamicIconEnabled) { repo.dynamicIconEnabled = it })
        addView(toggles)

        addView(label("권한", 13f, P.SUB, W.MEDIUM).apply { setPadding(4.dp, 12.dp, 4.dp, 8.dp) })
        val perms = card(padV = 4)
        accStatus = label("", 14f, P.SUB)
        notiStatus = label("", 14f, P.SUB)
        battStatus = label("", 14f, P.SUB)
        perms.addView(linkRow("접근성 서비스", accStatus) { openAccessibility() })
        perms.addView(divider(), lp(MATCH, 1.dp))
        perms.addView(linkRow("알림", notiStatus) { requestNotifications() })
        perms.addView(divider(), lp(MATCH, 1.dp))
        perms.addView(linkRow("배터리 최적화 제외", battStatus) { openBattery() })
        addView(perms)

        addView(label("기타", 13f, P.SUB, W.MEDIUM).apply { setPadding(4.dp, 12.dp, 4.dp, 8.dp) })
        val etc = card(padV = 4)
        etc.addView(linkRow("홈 화면에 위젯 추가", label("2×2", 14f, P.SUB)) { addWidget() })
        etc.addView(divider(), lp(MATCH, 1.dp))
        etc.addView(linkRow("앱 사용 설명서 다시 보기", label("", 14f, P.SUB)) { showManual() })
        etc.addView(divider(), lp(MATCH, 1.dp))
        nameValue = label("", 15f, P.SUB)
        etc.addView(linkRow("캐릭터 이름", nameValue) { showRename() })
        addView(etc)

        if (DevTools.isDebugBuild(this@MainActivity)) {
            addView(label("개발자 테스트", 13f, P.SUB, W.MEDIUM).apply { setPadding(4.dp, 12.dp, 4.dp, 8.dp) })
            addView(buildDevCard())
        }
        addView(label("숏폼펫 ${appVersion()}", 12f, P.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, 16.dp, 0, 0)
        })
    }

    /** 설명 두 줄 + 오른쪽 스위치 (기존 진입 안내·연속일수 화면 행과 같은 모양) */
    private fun switchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 72.dp
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15f, P.INK, W.MEDIUM))
        texts.addView(label(desc, 12f, P.SUB), lp(WRAP, WRAP, top = 4))
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = 12.dp })
        row.addView(Switch(this).apply {
            isChecked = checked
            contentDescription = title
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(P.INK, Color.WHITE))
            trackTintList = ColorStateList(states, intArrayOf(P.INK, Color.rgb(170, 162, 154)))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        })
        return row
    }

    private fun renderSlots() {
        val list = repo.scheduleSlots()
        val sig = list.joinToString(",") { it.encode() }
        if (sig == slotSignature && slotList.childCount > 0) return
        slotSignature = sig
        slotList.removeAllViews()
        list.forEachIndexed { i, slot ->
            if (i > 0) slotList.addView(divider(), lp(MATCH, 1.dp))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 52.dp
            }
            row.addView(label(slot.label(), 15f, P.INK, tabular = true), LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(btn("삭제", BtnStyle.QUIET, 13f) {
                repo.removeScheduleSlot(i)
                refresh()
            }, LinearLayout.LayoutParams(WRAP, 36.dp))
            slotList.addView(row)
        }
        if (list.size < UsageRepository.MAX_SLOTS) {
            if (list.isNotEmpty()) slotList.addView(divider(), lp(MATCH, 1.dp))
            slotList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                background = pressable(roundRect(Color.TRANSPARENT, 8.dp.toFloat()), 8.dp.toFloat())
                setOnClickListener { showAddSlot() }
                addView(label("+", 20f, P.SUB).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(24.dp, WRAP).apply { marginEnd = 12.dp })
                addView(label("시간 추가", 15f, P.SUB))
            }, lp(MATCH, 52.dp))
        }
    }

    /** 시작·끝 시각을 30분 단위로 고르는 시트 */
    private fun showAddSlot() {
        var start = 21 * 60
        var end = 23 * 60
        Sheet.custom(
            this,
            title = "약속 시간 추가",
            primary = Sheet.Action("추가") {
                if (start != end) repo.addScheduleSlot(ScheduleSlot(start, end))
                refresh()
            },
        ) { _ ->
            addView(label("이 시간에 숏폼을 켜면 모찌가 3초 동안 알려 줘요", 14f, P.SUB), lp(MATCH, WRAP))
            fun stepper(name: String, get: () -> Int, set: (Int) -> Unit): View {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = 64.dp
                }
                row.addView(label(name, 15f, P.INK, W.MEDIUM), LinearLayout.LayoutParams(0, WRAP, 1f))
                val value = label("", 17f, P.INK, W.MEDIUM, tabular = true).apply { gravity = Gravity.CENTER }
                fun show() { value.text = "%d:%02d".format(get() / 60, get() % 60) }
                row.addView(btn("−", BtnStyle.OUTLINE, 16f) { set(Math.floorMod(get() - 30, 1440)); show() }, LinearLayout.LayoutParams(44.dp, 44.dp))
                row.addView(value, LinearLayout.LayoutParams(72.dp, WRAP))
                row.addView(btn("+", BtnStyle.OUTLINE, 16f) { set(Math.floorMod(get() + 30, 1440)); show() }, LinearLayout.LayoutParams(44.dp, 44.dp))
                show()
                return row
            }
            addView(stepper("시작", { start }, { start = it }), lp(MATCH, WRAP, top = 12))
            addView(divider(), lp(MATCH, 1.dp))
            addView(stepper("끝", { end }, { end = it }), lp(MATCH, WRAP))
        }
    }

    private fun addWidget() {
        val manager = getSystemService(android.appwidget.AppWidgetManager::class.java)
        val ok = try {
            manager != null && manager.isRequestPinAppWidgetSupported &&
                manager.requestPinAppWidget(ComponentName(this, MochiWidgetProvider::class.java), null, null)
        } catch (e: Exception) {
            false
        }
        if (!ok) Toast.makeText(this, Lines.TOAST_WIDGET_UNSUPPORTED, Toast.LENGTH_LONG).show()
    }

    private fun showManual() {
        OnboardingManualSheet.show(
            this,
            accessibilityOn = isAccessibilityEnabled(),
            onClose = { repo.manualSeen = true },
            onStart = {
                repo.manualSeen = true
                if (!isAccessibilityEnabled()) openAccessibility()
            },
        )
    }

    /** 오늘 성장했으면 그날 처음 열었을 때 한 번만 성장 모션 */
    private fun maybePlayEvolution() {
        if (isFinishing || !repo.consentAccepted) return
        val stage = pet.takePendingEvolution() ?: return
        selectTab(TAB_MOCHI)
        world.celebrate()
        world.say(if (stage == PetStage.BABY) Lines.HATCHED else Lines.evolved(stage.label), 5_000)
    }

    /** 디버그 빌드에서만 보이는 개발자 테스트 메뉴 */
    private fun buildDevCard(): View {
        val c = card(bg = P.SOFT)
        c.addView(label("디버그 빌드에서만 보여요", 12f, P.SUB))
        devInfo = label("", 12f, P.INK, tabular = true).apply {
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            background = roundRect(P.SURFACE, 8.dp.toFloat(), 1.dp, P.LINE)
        }
        c.addView(devInfo, lp(MATCH, WRAP, top = 12))

        c.addView(label("가짜 시청 시간 넣기", 12f, P.SUB, W.MEDIUM), lp(MATCH, WRAP, top = 16))
        val adds = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val apps = listOf(ShortFormApp.YOUTUBE_SHORTS to "쇼츠", ShortFormApp.INSTAGRAM_REELS to "릴스")
        apps.forEachIndexed { i, (app, name) ->
            adds.addView(
                btn("$name +10분", BtnStyle.OUTLINE, 14f) {
                    DevTools.addWatch(this, app, 10)
                    refresh()
                },
                LinearLayout.LayoutParams(0, 44.dp, 1f).apply { if (i < apps.size - 1) marginEnd = 8.dp },
            )
        }
        c.addView(adds, lp(MATCH, WRAP, top = 8))

        c.addView(label("시간 넘기기", 12f, P.SUB, W.MEDIUM), lp(MATCH, WRAP, top = 16))
        c.addView(
            btn("시간 +25분 (세션·쿠폰 확인)", BtnStyle.OUTLINE, 14f) {
                DevTools.shift(this, 25 * 60_000L)
                refresh()
            },
            lp(MATCH, 44.dp, top = 8),
        )
        c.addView(
            btn("하루 넘기기 (다음 날 새벽 5시 정산)", BtnStyle.PRIMARY, 14f) {
                val results = DevTools.skipToNextDay(this, tracked = true)
                world.say(results.lastOrNull()?.message ?: Lines.DEV_NOTHING_TO_SETTLE, 6_000)
                afterDevChange()
            },
            lp(MATCH, 44.dp, top = 8),
        )
        c.addView(
            btn("측정 안 된 날로 하루 넘기기", BtnStyle.OUTLINE, 14f) {
                val results = DevTools.skipToNextDay(this, tracked = false)
                world.say(results.lastOrNull()?.message ?: Lines.DEV_UNTRACKED_SKIPPED, 6_000)
                afterDevChange()
            },
            lp(MATCH, 44.dp, top = 8),
        )
        c.addView(
            btn("전체 초기화 (실제 시각으로 복귀)", BtnStyle.QUIET, 14f) {
                Sheet.confirm(
                    this,
                    title = "전체 초기화",
                    message = "시청 기록, 캐릭터, 공부 기록을 모두 지우고 실제 시각으로 돌아가요. 설정(목표, 동의)은 남아요.",
                    primary = Sheet.Action("초기화", destructive = true) {
                        DevTools.resetAll(this)
                        world.say(Lines.DEV_RESET)
                        afterDevChange()
                    },
                )
            },
            lp(MATCH, 44.dp, top = 8),
        )
        return c
    }

    /** 오늘(새벽 5시 기준) 아직 안 보여 줬고, 이어 가는 중이거나 기록이 있었던 사람에게만 */
    private fun maybeShowStreak() {
        if (isFinishing || !repo.consentAccepted) return
        if (!repo.streakScreenEnabled) return
        val today = DayClock.logicalDay().toString()
        if (uiPrefs.getString(PREF_STREAK_SHOWN_DAY, null) == today) return
        val state = pet.state()
        if (state.streak <= 0 && state.bestStreak <= 0) return
        uiPrefs.edit().putString(PREF_STREAK_SHOWN_DAY, today).apply()
        showStreak()
    }

    private fun showStreak() {
        val state = pet.state()
        val today = DayClock.logicalDay()
        val goal = state.goalMinutes
        val days = (6 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            val mark = when {
                back == 0 -> StreakScreen.Mark.TODAY
                !repo.isTracked(day) -> StreakScreen.Mark.NONE
                repo.countedMillis(day) / 60_000 <= goal -> StreakScreen.Mark.KEPT
                else -> StreakScreen.Mark.OVER
            }
            val label = if (back == 0) Lines.STREAK_TODAY else day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
            StreakScreen.Day(label, mark)
        }
        val milestone = listOf(7 to 1, 30 to 2, 100 to 3)
            .firstOrNull { (days, level) -> state.streak < days && state.accessoryLevel < level }
            ?.let { (days, level) -> (days - state.streak) to PetEngine.ACCESSORY_NAMES[level] }
        StreakScreen.show(
            this,
            StreakScreen.Data(
                streak = state.streak,
                best = state.bestStreak,
                shields = state.shields,
                goalText = formatMinutes(goal),
                days = days,
                nextMilestone = milestone,
            ),
        )
    }

    private fun afterDevChange() {
        lastWeekRender = 0L
        todoSignature = ""
        refresh()
        maybeShowStreak()
    }

    private fun refreshDevInfo(now: Long, state: PetState) {
        val info = devInfo ?: return
        val zoned = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val shiftedMin = Now.offsetMs / 60_000
        val shiftLabel = if (shiftedMin == 0L) "실제 시각" else "가상 시각 · 실제보다 ${shiftedMin / 1440}일 ${shiftedMin % 1440 / 60}시간 ${shiftedMin % 60}분 앞"
        val today = DayClock.logicalDay(now)
        val counted = repo.countedMinutes(today)
        info.text = listOf(
            "앱이 보는 시각: ${zoned.monthValue}/${zoned.dayOfMonth} %02d:%02d ($shiftLabel)".format(zoned.hour, zoned.minute),
            "기록 날짜(새벽 5시 기준): $today · 측정됨: ${if (repo.isTracked(today)) "예" else "아니오"}",
            "순수 시청 t %.1f분 · 목표 G %d분 → 지금 정산하면 경험치 +%d".format(counted, state.goalMinutes, state.expectedGain),
            "경험치 ${state.exp} (${state.stage.label}) · 행복 ${state.happiness} (${state.mood.label}) · 쓰다듬기 남음 ${state.petsLeft} · 아이콘 ${AppIconSwitcher.currentBucket(this)}단계",
            "연속 ${state.streak}일 · 보호권 ${state.shields}",
            "감지 원본: 앞 화면=${ShortFormAccessibilityService.lastForegroundPackage ?: "-"} · 숏폼=${ShortFormAccessibilityService.currentApp?.name ?: "-"}",
            "오늘 알림: 누적 ${repo.count(today, SyncManager.COUNT_ALERT_CUMULATIVE)}회(마지막 ${repo.lastAlertMinutes(today)}분 기준) · 연속 ${repo.count(today, SyncManager.COUNT_ALERT_CONTINUOUS)}회 · 알림 권한 ${if (Notifier.canNotify(this)) "있음" else "없음"}",
            "측정 서비스가 켜져 있던 시간: ${formatDuration(repo.trackedMillis(today))} (1분마다 저장)",
        ).joinToString("\n")
    }

    // ------------------------------------------------------------------ 갱신

    private fun refresh() {
        val now = Now.ms()
        val accOn = isAccessibilityEnabled()
        val notiOn = Notifier.canNotify(this)
        val battOn = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val optionalMissing = listOf(notiOn, battOn).count { !it }
        permCard.visibility = if (accOn) View.GONE else View.VISIBLE
        permTitle.text = "시청 시간을 재려면 접근성 서비스를 켜 주세요"
        setupHint.visibility = if (accOn && optionalMissing > 0) View.VISIBLE else View.GONE
        setupHintValue.text = "${optionalMissing}가지 남음"
        setStatus(accStatus, accOn)
        setStatus(notiStatus, notiOn)
        setStatus(battStatus, battOn)

        val zonedNow = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        dateText.text = "${zonedNow.monthValue}월 ${zonedNow.dayOfMonth}일 ${zonedNow.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}"

        // 공부 모드: 끝난 세션 완료 처리, 세션 중 접근성 서비스가 꺼졌으면 실패 처리
        for (event in study.sync(now)) {
            if (event is StudyEvent.SessionCompleted) {
                world.say(Lines.sessionDone(event.sessionMinutes, event.couponMinutes, event.shieldGranted))
                world.react()
            }
        }
        if (!accOn && study.sessionActive(now) && study.failSession(now)) {
            Toast.makeText(this, Lines.TOAST_SESSION_FAILED, Toast.LENGTH_LONG).show()
        }
        val sessionOn = study.sessionActive(now)
        val couponOn = study.couponActive(now)

        val state = pet.state()
        val today = DayClock.logicalDay(now)

        // 모찌 탭 HUD
        petName.text = state.name
        petName.contentDescription = "${state.name}, 눌러서 이름 바꾸기"
        petStage.text = "${state.stage.label} · ${state.mood.label}"
        streakText.text = Lines.streakChip(state.streak)
        streakText.setTextColor(if (state.streak > 0) P.FLAME_TEXT else P.MUTED)
        streakIcon.color = if (state.streak > 0) P.FLAME else P.MUTED
        (streakText.parent as View).contentDescription = "연속 ${state.streak}일, 눌러서 자세히"
        world.scene = state.scene
        // 지금이 방이면 들판 아이콘(누르면 들판으로), 들판이면 집 아이콘
        sceneIcon.kind = if (state.scene == 0) LineIcon.Kind.FIELD else LineIcon.Kind.HOUSE
        (sceneIcon.parent as View).contentDescription = if (state.scene == 0) "들판으로 나가기" else "방 안으로 들어가기"
        world.setPet(state)
        world.idleText = if (world.aiming) (if (aimSnack) Lines.SNACK_AIM else Lines.FEED_AIM) else Lines.idle(state.mood, state.stage, focusing = sessionOn)
        val uptime = SystemClock.uptimeMillis()
        if (state.isEgg) {
            hpText.text = Lines.HATCHING
            hpBar.progress = 1000
        } else {
            hpText.text = Lines.happiness(state.happiness)
            hpBar.progress = state.happiness * 10
        }
        setBarColor(hpBar, if (!state.isEgg && state.happiness < 20) P.OVER else P.ACCENT)
        val next = state.stage.next
        expText.text = if (next != null) Lines.expTotal(state.exp, next.minExp) else Lines.expDone(state.exp)
        expNote.text = if (next != null) Lines.expToday(state.expectedGain) else ""
        // 숫자(누적 경험치 / 다음 단계 기준)와 막대가 같은 비율
        expBar.progress = (state.expProgress * 1000).toInt()
        petHint.text = if (state.isEgg) Lines.NOTE_EGG else Lines.petHint(state.petsLeft)
        feedButton.text = when {
            world.aiming && !aimSnack -> Lines.THROW_CANCEL
            state.fedToday -> Lines.THROW_DONE
            else -> Lines.THROW
        }
        setUsable(feedButton, (world.aiming && !aimSnack) || (!state.fedToday && !state.isEgg))
        val snacks = state.snacksAvailable
        snackButton.text = when {
            world.aiming && aimSnack -> Lines.THROW_CANCEL
            snacks > 0 -> Lines.snack(snacks)
            else -> Lines.SNACK_EMPTY
        }
        setUsable(snackButton, (world.aiming && aimSnack) || (snacks > 0 && !state.isEgg))
        val notes = when {
            world.aiming -> listOf(if (aimSnack) Lines.NOTE_AIM_SNACK else Lines.NOTE_AIM)
            state.isEgg -> emptyList()
            else -> listOfNotNull(
                if (state.fedToday) Lines.NOTE_FED else null,
                if (snacks == 0) Lines.NOTE_NO_SNACK else null,
            )
        }
        petNote.text = notes.joinToString("\n")
        petNote.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        Notifier.updateFocus(this)

        // 오늘 시청
        val totalMs = repo.totalMillis(today)
        val countedMs = repo.countedMillis(today)
        val exemptMs = repo.exemptMillis(today)
        val goal = state.goalMinutes
        val countedMin = countedMs / 60_000
        val over = countedMin > goal
        todayTotal.text = formatDuration(totalMs)
        todayGoal.text = "/ 목표 ${formatMinutes(goal)}"
        todayStatus.text = if (over) "${formatMinutes((countedMin - goal).toInt())} 넘음" else "${formatMinutes((goal - countedMin).toInt())} 남음"
        todayStatus.setTextColor(if (over) P.OVER_TEXT else P.SUB)
        todayBar.progress = (countedMs * 1000 / (goal * 60_000L)).toInt().coerceAtMost(1000)
        setBarColor(todayBar, if (over) P.OVER else P.INK)
        for ((app, view) in appRows) {
            val ms = repo.millis(today, app)
            view.text = formatDuration(ms)
            appBars[app]?.progress = if (totalMs > 0) (ms * 1000 / totalMs).toInt() else 0
        }

        val watching = ShortFormAccessibilityService.currentApp
        val (dotColor, status) = when {
            !accOn -> P.MUTED to "측정 꺼짐 · 접근성 서비스를 켜 주세요"
            !ShortFormAccessibilityService.running -> P.WARN to "측정 서비스 연결 중"
            sessionOn -> P.INK to "집중 중이라 숏폼을 막고 있어요"
            watching != null && couponOn -> P.WARN to "휴식 쿠폰으로 ${shortAppName(watching)} 보는 중 · 목표에 안 넣어요"
            watching != null -> P.OVER to "지금 ${shortAppName(watching)} 시간을 재고 있어요"
            else -> {
                val fg = ShortFormApp.fromPackage(ShortFormAccessibilityService.lastForegroundPackage)
                if (fg != null) P.GOOD to "${fg.label.split(" ").first()} 사용 중 · 숏폼 화면이 아니라 안 재요"
                else P.GOOD to "측정 중 · 지금은 숏폼을 보고 있지 않아요"
            }
        }
        detectDot.setDotColor(dotColor)
        detectText.text = status
        val remain = DayClock.nextResetMillis(now) - now
        val exemptNote = if (exemptMs >= 60_000) "쿠폰으로 본 ${formatDuration(exemptMs)} 제외 · " else ""
        resetText.text = "${exemptNote}오전 5시 초기화까지 ${formatDuration(remain)}"

        // 공부
        val todos = study.todos(now)
        val doneCount = todos.count { it.done }
        renderTodos(todos, sessionOn)
        idleSession.visibility = if (sessionOn) View.GONE else View.VISIBLE
        activeSession.visibility = if (sessionOn) View.VISIBLE else View.GONE
        if (sessionOn) {
            sessionClock.text = formatClock(study.sessionRemainingMs(now))
        } else {
            lengthPicker.select(UsageRepository.SESSION_LENGTHS.indexOf(repo.sessionMinutes).coerceAtLeast(0))
            sessionButton.text = if (repo.sessionMinutes == 5) "5분만 시작하기" else "${repo.sessionMinutes}분 집중 시작"
        }
        val couponLeft = study.couponRemainingMs(now)
        couponSub.text = when {
            couponOn -> "쓰는 중 · ${formatClock(couponLeft)} 남음"
            couponLeft > 0 -> "${formatClock(couponLeft)} 남음 · 오늘까지"
            else -> "집중을 끝내면 받아요"
        }
        couponButton.text = if (couponOn) "끄기" else "쓰기"
        couponButton.visibility = if (couponOn || couponLeft > 0) View.VISIBLE else View.GONE
        setUsable(couponButton, couponOn || !sessionOn)
        shieldText.text = if (state.shields >= 1) {
            "1개 있어요 · 목표를 조금 넘긴 날 자동으로 써요"
        } else {
            "집중 ${StudyManager.SESSIONS_PER_SHIELD - state.shieldProgress}번 더 하면 생겨요"
        }

        // 기록 · 설정
        if (bestStreak != state.bestStreak) {
            bestStreak = state.bestStreak
            lastWeekRender = 0L
        }
        goalPicker.select(UsageRepository.GOAL_OPTIONS.indexOf(repo.dailyGoalMinutes).coerceAtLeast(0))
        intervalValue.text = "${repo.continuousAlertMinutes}분"
        renderSlots()
        nameValue.text = state.name
        if (uptime - lastWidgetPush > 30_000) {
            lastWidgetPush = uptime
            MochiWidgetProvider.update(this)
        }
        reportText.text = state.lastReport ?: "아직 정산 기록이 없어요. 내일 새벽 5시에 첫 정산을 해요."

        refreshDevInfo(now, state)

        if (uptime - lastWeekRender > 10_000) {
            lastWeekRender = uptime
            renderWeek(goal)
        }
    }

    /** 할 일 목록은 내용이 바뀌었을 때만 다시 그립니다. */
    private fun renderTodos(todos: List<Todo>, sessionOn: Boolean) {
        val undoOn = undoIndex >= 0 && SystemClock.uptimeMillis() < undoUntil
        if (!undoOn) undoIndex = -1
        val signature = "$todos|$sessionOn|$undoIndex"
        if (signature == todoSignature) return
        todoSignature = signature

        todoList.removeAllViews()
        if (todos.isEmpty()) {
            todoList.addView(label("오늘 꼭 끝낼 일을 적어 두세요. 끝낼 때마다 간식이 생겨요.", 14f, P.SUB), lp(MATCH, WRAP, top = 8).apply { bottomMargin = 8.dp })
        }
        todos.forEachIndexed { index, todo ->
            if (index > 0) todoList.addView(divider(), lp(MATCH, 1.dp))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 56.dp
            }
            val check = CheckBoxView(this).apply {
                checked = todo.done
                contentDescription = if (todo.done) "${todo.text} 완료됨" else "${todo.text} 완료하기"
                if (!todo.done) {
                    isClickable = true
                    setOnClickListener {
                        checked = true
                        if (study.completeTodo(index)) {
                            world.say(Lines.todoDone(todo.text))
                            undoIndex = index
                            undoUntil = SystemClock.uptimeMillis() + 5_000
                        }
                        refresh()
                    }
                }
            }
            // 체크박스 터치 영역은 44dp, 그림은 왼쪽 글줄에 맞춥니다.
            row.addView(check, LinearLayout.LayoutParams(44.dp, 56.dp).apply { marginStart = (-12).dp })
            val name = label(todo.text, 15f, if (todo.done) P.MUTED else P.INK).apply {
                if (todo.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                setPadding(0, 16.dp, 8.dp, 16.dp)
                if (!todo.done && !sessionOn) {
                    contentDescription = "${todo.text}, 눌러서 집중 시작"
                    setOnClickListener { confirmStartSession(todo.text) }
                }
                if (!todo.done) setOnLongClickListener { confirmRemoveTodo(index, todo.text); true }
            }
            row.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (todo.done && index == undoIndex) {
                // 잘못 눌렀을 때 5초 동안 되돌릴 수 있어요
                row.addView(btn(Lines.UNDO, BtnStyle.OUTLINE, 13f) {
                    study.undoCompleteTodo(index)
                    undoIndex = -1
                    refresh()
                }, LinearLayout.LayoutParams(WRAP, 36.dp))
            } else {
                row.addView(label(if (todo.done) "간식 받음" else "간식 +1", 12f, if (todo.done) P.MUTED else P.ACCENT_TEXT))
            }
            todoList.addView(row, lp(MATCH, WRAP))
        }
        val canAdd = todos.size < StudyManager.MAX_TODOS
        addTodoRow.visibility = if (canAdd) View.VISIBLE else View.GONE
        if (canAdd && todos.isNotEmpty()) todoList.addView(divider(), lp(MATCH, 1.dp))
        todoHint.visibility = if (todos.any { !it.done }) View.VISIBLE else View.GONE
    }

    private fun renderWeek(goal: Int) {
        val today = DayClock.logicalDay()
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val data = days.map { day ->
            WeekChartView.Day(
                label = if (day == today) "오늘" else day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN),
                minutes = repo.countedMillis(day) / 60_000,
                tracked = repo.isTracked(day),
                today = day == today,
            )
        }
        weekChart.setData(data, goal)
        weekNote.text = "점선 · 목표 ${formatMinutes(goal)}"
        val tracked = data.filter { it.tracked }
        weekSummary.text = if (tracked.isEmpty()) {
            "아직 기록이 없어요. 측정을 켜 두면 오늘부터 쌓여요."
        } else {
            val avg = formatMinutes((tracked.sumOf { it.minutes } / tracked.size).toInt())
            val kept = tracked.count { it.minutes <= goal }
            val best = if (bestStreak > 0) " 가장 길게 이어 간 건 ${bestStreak}일이에요." else ""
            "하루에 평균 $avg 봤고, 기록이 있는 ${tracked.size}일 중 ${kept}일은 목표를 지켰어요.$best"
        }

        // 연속일수 주간 스탬프 (연속일수 화면과 같은 표시)
        stampRow.removeAllViews()
        for (day in days) {
            val mark = when {
                day == today -> StreakScreen.Mark.TODAY
                !repo.isTracked(day) -> StreakScreen.Mark.NONE
                repo.countedMillis(day) / 60_000 <= repo.goalFor(day) -> StreakScreen.Mark.KEPT
                else -> StreakScreen.Mark.OVER
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            col.addView(StreakScreen.DayDot(this, mark), LinearLayout.LayoutParams(28.dp, 28.dp))
            col.addView(
                label(if (day == today) Lines.STREAK_TODAY else day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN), 12f, P.SUB),
                lp(WRAP, WRAP, top = 6),
            )
            stampRow.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        val s = pet.state()
        stampNote.text = if (s.streak > 0) "지금 ${s.streak}일 연속 · 최고 ${s.bestStreak}일" else if (s.bestStreak > 0) "최고 기록 ${s.bestStreak}일 · 오늘 목표 안으로 보면 다시 1일" else "목표 안으로 본 날이 이어지면 불꽃이 켜져요"
    }

    // ------------------------------------------------------------------ 동작

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, ShortFormAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun openAccessibility() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun openBattery() = startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(this)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    /** 쓰다듬기: 시간대마다 3번까지 행복 +5, 그 뒤에도 반응은 그대로 */
    private fun onPetTapped() {
        // 3초 안에 다시 누르면 연달아 누른 것으로 셉니다.
        val nowUp = SystemClock.uptimeMillis()
        petTaps = if (nowUp - lastPetTap < 3_000) petTaps + 1 else 1
        lastPetTap = nowUp
        val gain = pet.pet()
        val state = pet.state()
        world.say(Lines.petReply(state.mood, petTaps, state.stage))
        if (gain > 0) world.floatText("+$gain")
        refresh()
    }

    private var petTaps = 0
    private var lastPetTap = 0L

    private fun onSessionButton() {
        if (study.sessionActive()) {
            Sheet.confirm(
                this,
                title = "집중을 그만둘까요?",
                message = "그만두면 차단이 풀리고 이번 집중은 실패로 남아요. 휴식 쿠폰도 받지 못해요.",
                primary = Sheet.Action("그만두기", destructive = true) {
                    study.failSession()
                    world.say(Lines.SESSION_QUIT)
                    refresh()
                },
                secondaryLabel = "계속하기",
            )
        } else {
            confirmStartSession(null)
        }
    }

    private fun confirmStartSession(todoText: String?) {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, Lines.TOAST_NEED_ACCESSIBILITY, Toast.LENGTH_LONG).show()
            return
        }
        val minutes = repo.sessionMinutes
        Sheet.confirm(
            this,
            title = if (todoText != null) "'$todoText' ${minutes}분 집중" else "${minutes}분 집중",
            message = "${minutes}분 동안 인스타그램은 잠기고, 유튜브는 과제에 필요한 영상인지 물어본 뒤 일반 영상만 열어 둬요. 쇼츠는 끝까지 막아요.\n" +
                if (minutes == 5) "5분은 시작을 돕는 용도라 쿠폰은 없어요." else "끝까지 하면 휴식 쿠폰 ${StudyManager.couponFor(minutes)}분을 받아요.",
            primary = Sheet.Action("시작") {
                if (study.startSession(minutes)) world.say(Lines.sessionStart(minutes))
                refresh()
            },
        )
    }

    private fun confirmRemoveTodo(index: Int, text: String) {
        Sheet.confirm(
            this,
            title = "할 일을 지울까요?",
            message = "'$text'",
            primary = Sheet.Action("삭제", destructive = true) {
                study.removeTodo(index)
                refresh()
            },
        )
    }

    private fun onCouponButton() {
        if (study.couponActive()) {
            study.setCoupon(false)
        } else if (!study.setCoupon(true)) {
            Toast.makeText(this, Lines.TOAST_NO_COUPON_DURING_SESSION, Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun showCouponInfo() {
        Sheet.confirm(
            this,
            title = "휴식 쿠폰",
            message = "집중을 끝까지 하면 받아요. 25분은 5분, 50분은 10분이고 하루 최대 20분까지 모여요.\n\n" +
                "쿠폰을 켠 동안 본 숏폼은 기록에는 남지만 목표·기분·경험치에는 넣지 않아요. 켜 둔 만큼 남은 시간이 줄고, 오늘이 지나면 사라져요.",
            primary = Sheet.Action("확인") {},
            secondaryLabel = null,
        )
    }

    private fun showShieldInfo() {
        Sheet.confirm(
            this,
            title = "연속일수 보호권",
            message = "집중을 ${StudyManager.SESSIONS_PER_SHIELD}번 끝내면 1개 생겨요. 최대 1개까지 가질 수 있어요.\n\n" +
                "목표를 30분 이내로 넘긴 날 자동으로 쓰여서 연속일수가 끊기지 않게 지켜 줘요.",
            primary = Sheet.Action("확인") {},
            secondaryLabel = null,
        )
    }

    private fun showAddTodo() {
        val left = StudyManager.MAX_TODOS - study.todos().size
        Sheet.input(
            this,
            title = "할 일 추가",
            message = "끝내면 간식이 하나 생겨요 · ${left}개 더 적을 수 있어요",
            initial = "",
            hint = "예: 통계 과제 제출",
            maxLength = StudyManager.MAX_TODO_LENGTH,
            submitLabel = "추가",
        ) { text ->
            val ok = study.addTodo(text)
            if (!ok) Toast.makeText(this, Lines.toastTodoLimit(StudyManager.MAX_TODOS), Toast.LENGTH_SHORT).show()
            refresh()
            ok
        }
    }

    private fun showRename() {
        Sheet.input(
            this,
            title = "캐릭터 이름",
            message = null,
            initial = pet.name,
            hint = "이름",
            maxLength = 10,
            submitLabel = "저장",
        ) { text ->
            pet.name = text
            refresh()
            true
        }
    }

    private fun showConsent() {
        Sheet.confirm(
            this,
            title = "시작하기 전에",
            message = "숏폼펫은 접근성 서비스로 유튜브·인스타그램 화면이 쇼츠·릴스 화면인지 확인하고 그 시간을 기록합니다. " +
                "집중 세션을 직접 시작했을 때만 인스타그램을 닫고, 유튜브는 과제용인지 물어본 뒤 쇼츠를 막습니다.\n\n" +
                "기록은 이 기기에 먼저 저장하고, 서비스 개선을 위해 하루 1번 아래 항목을 서버(Firebase)로 보냅니다.\n" +
                "• 무작위 익명 ID, 날짜, 앱별 시청 시간, 측정 시간, 알림 횟수\n" +
                "• 앱·기기·안드로이드 버전, 유튜브·인스타그램 앱 버전\n" +
                "• 하루 목표, 캐릭터 단계·행복도, 앱 실행 횟수, 공부 모드 횟수 기록, 연속일수\n\n" +
                "보내지 않는 것: 화면 내용, 영상 제목, 계정, 캐릭터 이름, 할 일 내용, 위치·연락처.\n" +
                "언제든 접근성 설정에서 끄거나 앱을 삭제할 수 있습니다.",
            primary = Sheet.Action("동의하고 시작") {
                repo.consentAccepted = true
                // 동의 직후 사용 설명서
                if (!repo.manualSeen) handler.postDelayed({ showManual() }, 250)
            },
            secondaryLabel = "나가기",
            onSecondary = { finish() },
            cancelable = false,
            scrollMessage = true,
        )
    }

    // ------------------------------------------------------------------ 위젯 헬퍼

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int, top: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = top.dp }

    /** 카드: 모서리 16, 안쪽 여백 좌우 20. 흰 카드는 얇은 선으로 구분하고 그림자는 쓰지 않습니다. */
    private fun card(bg: Int = P.SURFACE, border: Boolean = bg == P.SURFACE, padV: Int = 20): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dp, padV.dp, 20.dp, padV.dp)
        background = roundRect(bg, 16.dp.toFloat(), if (border) 1.dp else 0, P.LINE)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 12.dp }
    }

    private fun divider() = View(this).apply { setBackgroundColor(P.LINE) }

    private fun titleWithInfo(value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(value, 15f, P.INK, W.MEDIUM))
        addView(LineIcon(this@MainActivity, LineIcon.Kind.INFO), LinearLayout.LayoutParams(16.dp, 16.dp).apply { marginStart = 8.dp })
    }

    /** 왼쪽 이름, 오른쪽 값과 꺾쇠. 누르면 해당 화면이나 대화상자를 엽니다. */
    private fun linkRow(name: String, value: TextView, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 56.dp
        isClickable = true
        background = pressable(roundRect(Color.TRANSPARENT, 8.dp.toFloat()), 8.dp.toFloat())
        setOnClickListener { onClick() }
        addView(label(name, 15f, P.INK, W.MEDIUM), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(value)
        addView(LineIcon(this@MainActivity, LineIcon.Kind.CHEVRON), LinearLayout.LayoutParams(20.dp, 20.dp).apply { marginStart = 4.dp })
    }

    private fun setStatus(view: TextView, on: Boolean) {
        view.text = if (on) "켜짐" else "꺼짐"
        view.setTextColor(if (on) P.SUB else P.OVER_TEXT)
    }

    /** 왼쪽 값 글자, 오른쪽 보조 글자 (모찌 HUD) */
    private fun statRow(left: TextView, right: TextView?) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (right != null) addView(right)
    }

    private fun statRow(name: String, value: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(label(name, 13f, P.SUB), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(value)
    }

    private fun shortAppName(app: ShortFormApp) = when (app) {
        ShortFormApp.YOUTUBE_SHORTS -> "쇼츠"
        ShortFormApp.INSTAGRAM_REELS -> "릴스"
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun bar(color: Int, track: Int = P.TRACK) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        val radius = 99.dp.toFloat()
        val fill = ClipDrawable(roundRect(color, radius), Gravity.START, ClipDrawable.HORIZONTAL)
        progressDrawable = LayerDrawable(arrayOf(roundRect(track, radius), fill)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun setBarColor(bar: ProgressBar, color: Int) {
        val layer = bar.progressDrawable as? LayerDrawable ?: return
        val clip = layer.findDrawableByLayerId(android.R.id.progress) as? ClipDrawable ?: return
        (clip.drawable as? GradientDrawable)?.setColor(color)
    }

    private fun permissionRow(name: String, desc: String, action: String, onClick: () -> Unit): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(divider(), lp(MATCH, 1.dp, top = 16))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16.dp, 0, 0)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(name, 15f, P.INK, W.MEDIUM))
        texts.addView(label(desc, 12f, P.SUB), lp(WRAP, WRAP, top = 4))
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = 12.dp })
        row.addView(btn(action, BtnStyle.PRIMARY, 14f, onClick), LinearLayout.LayoutParams(WRAP, 40.dp))
        wrap.addView(row)
        return wrap
    }

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val KEY_TAB = "tab"
        const val EXTRA_TAB = "tab"
        private const val PREF_STREAK_SHOWN_DAY = "streak_shown_day"
        const val TAB_MOCHI = 0
        const val TAB_TODAY = 1

        /** 앱 화면이 보이는 중인지 (아이콘 바꾸기는 뒤로 내려간 뒤에만) */
        @Volatile var inForeground = false
        private const val TAB_RECORD = 2
        private const val TAB_SETTINGS = 3
    }
}
