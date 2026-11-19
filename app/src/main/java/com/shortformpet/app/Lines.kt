package com.shortformpet.app

/**
 * 앱에 나오는 캐릭터 대사·알림·안내 문구를 한곳에 모았습니다.
 * 문구만 고칠 때는 이 파일만 바꾸면 됩니다. (로직은 다른 파일에 있습니다)
 *
 * 말투
 * - 캐릭터(모찌): 친구한테 하듯 짧은 반말. 한 번에 한 가지만 말하고, 설교하지 않습니다.
 * - 앱 안내·알림: 다른 앱처럼 짧은 존댓말. "~했어요", "~할 수 있어요". 숫자는 구체적으로.
 * - 느낌표·물결(~)·말줄임표 남발 없음. 짧은 문구 끝에는 마침표 없음.
 *
 * 조사 처리: Josa.iGa(이름) → "모찌가", Josa.euro(단계) → "어린이로", Josa.subject(할 일) → "이/가"
 */
object Lines {

    /** 같은 상황이면 한동안(20분) 같은 대사를 쓰다가 바꿉니다. 1초마다 새로 그려도 깜빡이지 않게. */
    private fun <T> pick(list: List<T>, salt: Int = 0): T {
        val slot = (Now.ms() / (20 * 60_000L)).toInt()
        return list[Math.floorMod(slot * 31 + salt, list.size)]
    }

    // ================================================================== A. 캐릭터 기본 말풍선
    // 모찌 탭 말풍선, 누적 알림 본문, 위젯에 쓰입니다.
    // 표정: 행복도 H 70↑ 신남, 40↑ 기분 좋음, 20↑ 배고픔, 10↑ 시무룩, 그 밑 아픔

    private val EGG = listOf("알이 톡톡 움직여요", "안에서 꼼지락거리는 소리가 나요", "알이 따뜻해요", "가끔 살짝 흔들려요")

    private val IDLE = mapOf(
        Mood.JOYFUL to listOf(
            "오늘 컨디션 좋다",
            "숏폼 거의 안 봤네. 멋있다",
            "이 페이스면 오늘 쉽게 간다",
            "할 일 하나만 끝내 볼까?",
            "산책하기 딱 좋은 날이야",
        ),
        Mood.SMILE to listOf(
            "아직 괜찮아. 여기서 멈추면 돼",
            "슬슬 할 일 하나 해 볼까?",
            "25분만 같이 집중해 보자",
            "나 쓰다듬어 주면 힘날 것 같아",
        ),
        Mood.HUNGRY to listOf(
            "배고파. 밥 던져 줄래?",
            "배에서 꼬르륵 소리 나",
            "간식 있으면 하나만",
            "조금 출출해",
        ),
        Mood.SULKY to listOf(
            "오늘 좀 많이 봤다",
            "나도 좀 봐 줘",
            "폰 잠깐 내려놓고 물 한 잔 어때?",
            "딱 25분만 같이 해 보자",
        ),
        Mood.SICK to listOf(
            "머리가 핑 돌아",
            "오늘은 여기까지만 보자",
            "밥 좀 줘. 기운이 없어",
            "나 잠깐 누워 있을게",
        ),
    )

    private val LATE_NIGHT = listOf(
        "졸려. 우리 이제 자자",
        "내일 일찍 일어나려면 지금 자야 해",
        "폰 덮고 자자. 나 먼저 잘게",
    )

    private val MORNING = listOf(
        "좋은 아침. 오늘 할 일부터 적어 볼까?",
        "잘 잤어? 오늘도 가볍게 가 보자",
    )

    private val FOCUSING = listOf(
        "집중하는 중이지? 나 조용히 있을게",
        "지금 잘하고 있어",
        "조금만 더 하면 쿠폰이야",
    )

    /** 방석에서 쉬는 중 */
    private val RESTING = listOf("방석 푹신하다", "잠깐 누워 있을게", "여기 내 자리야")

    /** 들판에서 노는 중 */
    private val FIELD = listOf("바람 시원하다", "나비다", "풀 냄새 좋아")

    /**
     * 평소 말풍선. 밤(23:30~05시)에는 졸린 말, 집중 중에는 응원, 아침(5~10시)에는 기분이 괜찮을 때 인사.
     * 나머지는 표정에 맞는 말 중 하나를 20분마다 바꿔 가며 씁니다.
     */
    fun idle(mood: Mood, stage: PetStage, focusing: Boolean = false): String {
        if (stage == PetStage.EGG) return pick(EGG)
        val hour = DayClock.hour()
        return when {
            DayClock.isLateNight() -> pick(LATE_NIGHT)
            focusing -> pick(FOCUSING)
            hour < 10 && (mood == Mood.JOYFUL || mood == Mood.SMILE) -> pick(MORNING)
            else -> pick(IDLE.getValue(mood), mood.ordinal)
        }
    }

    fun resting(): String = pick(RESTING, 7)
    fun field(): String = pick(FIELD, 3)

    // ================================================================== B. 캐릭터 반응 (몇 초 동안 말풍선)

    /** 쓰다듬기. 표정마다 반응이 다르고, 짧은 시간에 여러 번 누르면 말이 바뀝니다. taps = 연달아 누른 횟수 */
    fun petReply(mood: Mood, taps: Int, stage: PetStage): String {
        if (stage == PetStage.EGG) return listOf("알이 살짝 따뜻해졌어요", "알이 톡 움직였어요").random()
        if (taps >= 6) return listOf("손 아프겠다. 이제 공부하러 가자", "알았어 알았어, 기분 좋아졌어").random()
        return when (mood) {
            Mood.JOYFUL -> listOf("헤헤", "간지러워", "한 번만 더", "좋다")
            Mood.SMILE -> listOf("오, 좋아", "기분 좀 나아졌어", "헤헤")
            Mood.HUNGRY -> listOf("좋긴 한데 배고파", "밥도 주면 더 좋고")
            Mood.SULKY -> listOf("조금 풀렸어", "그래도 고마워")
            Mood.SICK -> listOf("고마워. 조금 낫다", "응")
        }.random()
    }

    fun feedOk() = listOf("맛있다", "잘 먹을게", "냠, 든든하다").random()
    const val FEED_ALREADY = "아까 먹었어. 내일 또 줘"
    const val FEED_EGG = "알에서 나오면 먹을게"
    const val FEED_MISS = "아깝다. 다시 던져 줘"
    const val FEED_AIM = "위로 밀어서 던져 줘"

    fun snackOk() = listOf("간식이다", "달다", "이거 좋아해").random()
    const val SNACK_NONE = "간식이 없어. 할 일 하나 끝내면 생겨"
    const val SNACK_EGG = "알에서 나오면 먹을게"

    fun todoDone(todo: String) = "'$todo' 끝냈네. 간식 하나 생겼어"

    fun sessionStart(minutes: Int) = if (minutes <= 5) "좋아, 5분만 해 보자" else "좋아, ${minutes}분 뒤에 보자"
    const val SESSION_QUIT = "괜찮아. 다음엔 조금만 더 해 보자"
    fun sessionDone(minutes: Int, couponMinutes: Int, shieldGranted: Boolean) = when {
        minutes <= 5 -> "시작이 반이야. 이어서 25분 가 볼까?"
        couponMinutes > 0 -> "수고했어. 휴식 쿠폰 ${couponMinutes}분 받았어" + if (shieldGranted) ". 보호권도 생겼어" else ""
        else -> "수고했어. 오늘 쿠폰은 다 받았어"
    }

    fun evolved(stage: String) = "나 ${Josa.euro(stage)} 자랐어"
    const val HATCHED = "안녕, 반가워"

    // ================================================================== C. 새벽 5시 정산 결과
    // 정산 알림 본문, 기록 탭 "최근 정산", 말풍선에 같은 문장이 쓰입니다.
    // 예: "11/18 숏폼 20분 · 경험치 +75 · 연속 3일 · 행복 +15. 모찌가 어린이로 자랐어요"

    fun settleHead(month: Int, day: Int, watched: String, gain: Int) = "$month/$day 숏폼 $watched · 경험치 +$gain"
    fun settleStreak(days: Int) = " · 연속 ${days}일"
    fun settleHappiness(bonus: Int) = " · 행복 +$bonus"
    fun settleEvolved(name: String, stage: String) = ". ${Josa.iGa(name)} ${Josa.euro(stage)} 자랐어요"
    /** 7·30·100일 달성. accessory: 리본, 목도리, 별 장식 */
    fun settleMilestone(days: Int, name: String, accessory: String) =
        ". ${days}일 연속이라 ${Josa.iGa(name)} ${Josa.eulReul(accessory)} 선물받았어요"
    fun settleShieldUsed(days: Int) = ". 보호권으로 연속 ${days}일을 지켰어요"
    const val SETTLE_STREAK_BROKEN = ". 연속 기록은 오늘부터 다시 시작해요"

    const val DEV_NOTHING_TO_SETTLE = "정산할 날이 없어요"
    const val DEV_UNTRACKED_SKIPPED = "측정 기록이 없어 정산을 건너뛰었어요"
    const val DEV_RESET = "처음부터 다시 시작해요"

    // ================================================================== D. 알림

    /** 23:30~04:59: 할 일 줄과 캐릭터 대사 대신 */
    const val SLEEP = "이제 자자. 내일 공부하려면 지금 자야 해"
    /** 캐릭터가 말하는 줄 앞에 이름을 붙이는 형식. 예: "모찌: 잠깐 쉬었다 볼까?" */
    fun says(name: String, line: String) = "$name: $line"
    fun nextTodo(todo: String) = "남은 할 일 · $todo"
    /** 잠금 화면(내용 가림)에 보이는 줄 */
    fun todosLeftPublic(count: Int) = "할 일 ${count}개 남음"

    // 누적 시청 (목표 전 30분마다, 넘긴 뒤 15분마다)
    fun watchTitle(total: String) = "오늘 숏폼 $total 봤어요"
    fun watchGoalLeft(left: String) = "목표까지 $left 남았어요"
    fun watchGoalReached(goal: String) = "오늘 목표 ${goal}을 다 썼어요"
    fun watchGoalOver(goal: String, over: String) = "목표 ${goal}보다 $over 더 봤어요"

    // 연속 시청 (설정한 주기마다)
    const val CONTINUOUS_TITLE = "잠깐 쉬었다 볼까?"
    fun continuousBody(minutes: Int) = "${minutes}분째 계속 보고 있어요"

    // 집중 완료
    fun sessionDoneTitle(minutes: Int) = if (minutes <= 5) "5분 착수 완료" else "${minutes}분 집중 완료"
    fun sessionDoneCoupon(couponMinutes: Int) = "휴식 쿠폰 ${couponMinutes}분을 받았어요"
    const val SESSION_DONE_COUPON_MAX = "오늘 받을 수 있는 휴식 쿠폰(20분)은 다 받았어요"
    const val SESSION_DONE_WARMUP = "시작했으니 25분도 할 수 있어요"
    const val SESSION_DONE_SHIELD = " · 보호권도 1개 생겼어요"

    // 새벽 5시 정산 (본문은 C의 정산 문장)
    const val DAILY_TITLE = "어제 기록을 정리했어요"

    // 오전 일일 집중 쪽지 (오전 9시)
    fun morningTitle(name: String) = "$name: 좋은 아침"
    fun morningBody(todoCount: Int, goal: String) =
        if (todoCount > 0) "할 일 ${todoCount}개 남았어요. 5분 착수부터 해 볼까요? · 오늘 목표 $goal"
        else "오늘 할 일을 적어 두면 끝낼 때마다 간식이 생겨요 · 오늘 목표 $goal"
    const val MORNING_PUBLIC = "오늘 계획을 세워 볼까요?"

    // 휴대폰 설정 > 알림에 보이는 채널 이름·설명
    const val CHANNEL_ALERT = "시청 알림"
    const val CHANNEL_ALERT_DESC = "숏폼을 오래 보거나 목표에 가까워지면 알려 드려요"
    const val CHANNEL_DAILY = "하루 정산"
    const val CHANNEL_DAILY_DESC = "매일 새벽 5시에 어제 기록과 캐릭터 변화를 알려 드려요"
    const val CHANNEL_STUDY = "공부"
    const val CHANNEL_STUDY_DESC = "집중 세션이 끝나면 알려 드려요"
    const val CHANNEL_MORNING = "아침 쪽지"
    const val CHANNEL_MORNING_DESC = "오전 9시에 오늘 할 일을 한 번 알려 드려요"

    // ================================================================== E. 숏폼 앱 위에 뜨는 것

    // 집중 중 인스타를 열었을 때 (토스트, 캐릭터가 말함)
    fun blockedWithTodo(name: String, todo: String) = "$name: 지금은 집중 시간이야. '$todo' 마저 하자"
    fun blockedNoTodo(name: String) = "$name: 지금은 집중 시간이야. 조금만 더 버티자"
    fun blockedShorts(name: String) = "$name: 쇼츠는 집중 끝나고 보자"

    // 집중 중 유튜브를 처음 열었을 때
    const val YT_QUESTION_TITLE = "지금 집중 시간인데, 과제나 공부에 필요한 영상이야?"
    const val YT_QUESTION_BODY = "과제용이면 이번 집중이 끝날 때까지 일반 영상과 검색은 열어 둘게. 쇼츠는 계속 막아"
    const val YT_YES = "응, 과제용이야"
    const val YT_NO = "아니, 다시 집중할게"

    // 목표를 넘긴 뒤 / 약속 시간표 시간에 숏폼을 켰을 때 (3초 안내 화면)
    fun guideTitle(name: String) = "$name: 오늘은 목표 시간을 넘었어"
    fun guideScheduleTitle(name: String) = "$name: 지금은 약속한 시간이야"
    fun guideUsage(watched: String, goal: String) = "오늘 숏폼 $watched · 목표 $goal"
    fun guideSchedule(slot: String) = "정해 둔 시간 $slot"
    fun guideTodo(todo: String) = "아직 '$todo'${Josa.subject(todo)} 남아 있어요"
    fun guideCountdown(seconds: Int) = "${seconds}초 뒤에 닫혀요"
    const val GUIDE_STOP = "그만 보자"
    const val GUIDE_DISMISS = "모찌 들어가 있어"

    // 휴식 쿠폰이 숏폼을 보다가 끝났을 때
    fun couponEndTitle(name: String) = "$name: 쿠폰 시간 끝났어"
    const val COUPON_END_BODY = "이제부터 보는 시간은 목표에 들어가요"
    const val COUPON_END_FOCUS = "바로 25분 집중 시작"

    // ================================================================== F. 빼꼼 모찌 (화면 가장자리)

    fun peekContinuous(minutes: Int) = "${minutes}분째야"
    fun peekGoal(left: String) = "목표까지 $left"
    fun peekCoupon(clock: String) = "쿠폰 $clock"
    val PEEK_PRAISE = listOf("스스로 껐네. 멋있다", "딱 좋을 때 멈췄어", "오, 잘 끊었다")
    val PEEK_HOME = listOf("나 여기 있어", "산책 중", "오늘도 같이 가자")

    // ================================================================== G. 집중 중 고정 알림

    const val CHANNEL_FOCUS = "집중 중"
    const val CHANNEL_FOCUS_DESC = "집중하는 동안 남은 시간을 보여 드려요"
    const val FOCUS_TITLE = "집중하는 중"
    /** 남은 시간은 알림 오른쪽에 초 단위로 줄어듭니다 */
    fun focusBody(todo: String?) = if (todo != null) "'$todo' 하는 중 · 인스타·쇼츠는 잠시 막아 둘게요" else "인스타·쇼츠는 잠시 막아 둘게요"
    const val FOCUS_PUBLIC = "집중하는 중"

    // ================================================================== H. 하루 첫 실행 때 연속일수 화면

    fun streakCount(days: Int) = "$days"
    const val STREAK_UNIT = "일 연속"
    fun streakKeepBody(goal: String, nextDays: Int) = "오늘도 $goal 안으로 보면 ${nextDays}일째가 돼요"
    const val STREAK_RESTART_TITLE = "다시 시작해요"
    fun streakRestartBody(best: Int) =
        if (best > 0) "최고 기록 ${best}일은 그대로예요. 오늘이 새로운 1일이에요" else "오늘 목표 안으로 보면 1일이 시작돼요"
    fun streakBest(days: Int) = "최고 ${days}일"
    fun streakShields(count: Int) = "보호권 ${count}개"
    fun streakNextMilestone(daysLeft: Int, accessory: String) = "${daysLeft}일 더 하면 $accessory"
    const val STREAK_RULE = "목표 시간 안으로 보면 하루가 이어져요\n조금 넘긴 날엔 보호권이 대신 지켜 줘요"
    const val STREAK_CONTINUE = "오늘도 이어 가기"
    const val STREAK_START = "시작하기"
    const val STREAK_TODAY = "오늘"

    // ================================================================== I. 모찌 탭 HUD·도크

    const val HATCHING = "부화 준비 중"
    fun happiness(h: Int) = "행복 $h / 100"
    fun expTotal(exp: Int, next: Int) = "누적 경험치 $exp / $next"
    fun expDone(exp: Int) = "누적 경험치 $exp · 다 자람"
    fun expToday(gain: Int) = "오늘 예상 +$gain"
    fun streakChip(days: Int) = "연속 ${days}일"

    const val THROW = "밥 던지기"
    const val THROW_CANCEL = "그만 던지기"
    const val THROW_DONE = "밥 먹었어요"
    fun snack(count: Int) = "간식 주기 · $count"
    const val SNACK_EMPTY = "간식 없음"
    // 버튼을 못 누를 때 아래에 적는 이유
    const val NOTE_FED = "오늘 밥은 이미 줬어요 · 내일 오전 5시에 초기화돼요"
    const val NOTE_EGG = "알에서 나오면 밥과 간식을 줄 수 있어요"
    const val NOTE_NO_SNACK = "할 일을 끝내면 간식이 생겨요"
    const val NOTE_AIM = "먹이를 위로 밀어 던져요 · 모찌에게 닿으면 행복 +20"
    fun petHint(left: Int) = if (left > 0) "모찌를 눌러 쓰다듬기 · 지금 시간대 행복 +5가 ${left}번 남았어요" else "쓰다듬기 행복 보너스는 다음 시간대에 다시 생겨요"
    const val SCENE_ROOM = "방 안"
    const val SCENE_FIELD = "들판"

    const val UNDO = "되돌리기"

    // ================================================================== J. 그 밖의 안내 (토스트)

    const val TOAST_SESSION_FAILED = "접근성 서비스가 꺼져서 집중이 중단됐어요"
    const val TOAST_NEED_ACCESSIBILITY = "집중 모드를 쓰려면 접근성 서비스를 켜 주세요"
    const val TOAST_NO_COUPON_DURING_SESSION = "집중하는 동안엔 쿠폰을 쓸 수 없어요"
    fun toastTodoLimit(max: Int) = "할 일은 하루에 ${max}개까지 적을 수 있어요"
    const val TOAST_WIDGET_UNSUPPORTED = "홈 화면을 길게 눌러 위젯 목록에서 '모찌'를 추가해 주세요"

    // ================================================================== K. 사용 설명서 (처음 설치 · 설정에서 다시 보기)

    class ManualPage(val title: String, val body: String)

    val MANUAL = listOf(
        ManualPage(
            "릴스와 쇼츠만 골라 재요",
            "유튜브 인강이나 인스타 DM·피드는 재지 않아요. 전체 화면으로 보는 쇼츠·릴스만 1초 단위로 기록해요.\n하루는 오전 5시에 새로 시작돼요.",
        ),
        ManualPage(
            "덜 볼수록 쑥쑥 자라요",
            "모찌는 시간이 지나면 배고파하고, 목표 시간을 넘기면 시무룩해져요.\n밥을 던져 주고 쓰다듬으며 어른 모찌까지 키워 보세요.",
        ),
        ManualPage(
            "공부할 땐 확실히 막고, 쉴 땐 맘 편히",
            "할 일과 집중 세션을 시작하면 인스타는 잠기고 유튜브는 과제용 일반 영상만 열려요.\n끝까지 하면 목표에 안 들어가는 휴식 쿠폰을 받아요.",
        ),
        ManualPage(
            "앱 밖에서도 모찌가 함께해요",
            "홈 화면에 2×2 위젯을 추가해 보세요.\n숏폼을 오래 볼 땐 화면 끝에서 모찌가 살짝 얼굴을 내밀어 시간을 알려 줘요.",
        ),
    )
    const val MANUAL_NEXT = "다음"
    const val MANUAL_START_WITH_ACC = "접근성 켜고 시작하기"
    const val MANUAL_START = "시작하기"
    const val MANUAL_SKIP = "건너뛰기"
}
