package com.shortformpet.app

import java.time.Instant
import java.time.ZoneId

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

    private fun hourNow(): Int = Instant.ofEpochMilli(Now.ms()).atZone(ZoneId.systemDefault()).hour

    // ================================================================== A. 캐릭터 기본 말풍선
    // 오늘 탭 말풍선, 누적 알림 본문에 함께 쓰입니다.
    // 기분: 점수 = 100 × 60 ÷ (시청분 + 60). 70↑ 행복, 45↑ 보통, 25↑ 시무룩, 그 밑 아픔

    private val EGG = mapOf(
        Mood.HAPPY to listOf("알이 톡톡 움직여요", "안에서 꼼지락거리는 소리가 나요", "알이 따뜻해요"),
        Mood.OKAY to listOf("알이 가만히 있어요", "가끔 살짝 흔들려요"),
        Mood.SULKY to listOf("알이 조용해요", "알이 조금 식었어요"),
        Mood.SICK to listOf("알이 차가워졌어요", "알이 기운이 없어 보여요"),
    )

    private val IDLE = mapOf(
        Mood.HAPPY to listOf(
            "오늘 컨디션 좋다",
            "숏폼 거의 안 봤네. 멋있다",
            "이 페이스면 오늘 쉽게 간다",
            "기분 좋아서 뭐든 할 수 있을 것 같아",
            "할 일 하나만 끝내 볼까?",
        ),
        Mood.OKAY to listOf(
            "아직 괜찮아. 여기서 멈추면 돼",
            "슬슬 할 일 하나 해 볼까?",
            "25분만 같이 집중해 보자",
            "나 쓰다듬어 주면 힘날 것 같아",
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
            "눈 좀 쉬게 해 줘",
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

    /**
     * 평소 말풍선. 밤(0~5시)에는 졸린 말, 집중 중에는 응원, 아침(5~10시)에는 기분이 괜찮을 때 인사.
     * 나머지는 기분에 맞는 말 중 하나를 20분마다 바꿔 가며 씁니다.
     */
    fun idle(mood: Mood, stage: PetStage, focusing: Boolean = false): String {
        if (stage == PetStage.EGG) return pick(EGG.getValue(mood), mood.ordinal)
        val hour = hourNow()
        return when {
            hour < DayClock.RESET_HOUR -> pick(LATE_NIGHT)
            focusing -> pick(FOCUSING)
            hour < 10 && (mood == Mood.HAPPY || mood == Mood.OKAY) -> pick(MORNING)
            else -> pick(IDLE.getValue(mood), mood.ordinal)
        }
    }

    // ================================================================== B. 캐릭터 반응 (4초 동안 말풍선)

    /** 쓰다듬기. 기분마다 반응이 다르고, 짧은 시간에 여러 번 누르면 말이 바뀝니다. taps = 연달아 누른 횟수 */
    fun petReply(mood: Mood, taps: Int): String {
        if (taps >= 6) return listOf("손 아프겠다. 이제 공부하러 가자", "알았어 알았어, 기분 좋아졌어").random()
        return when (mood) {
            Mood.HAPPY -> listOf("헤헤", "간지러워", "한 번만 더", "좋다")
            Mood.OKAY -> listOf("오, 좋아", "기분 좀 나아졌어", "헤헤")
            Mood.SULKY -> listOf("조금 풀렸어", "그래도 고마워")
            Mood.SICK -> listOf("고마워. 조금 낫다", "응…")
        }.random()
    }

    fun feedOk(exp: Int) = listOf("맛있다. 경험치 +$exp", "잘 먹을게. 경험치 +$exp", "냠, 든든하다. 경험치 +$exp").random()
    const val FEED_ALREADY = "아까 먹었어. 내일 또 줘"
    /** 오늘 시청이 목표를 넘겨서 밥을 못 먹을 때. watched 예: "1시간 20분" */
    fun feedFull(watched: String) = "오늘은 입맛이 없어. 숏폼을 $watched 봤거든"

    fun snackOk(exp: Int) = listOf("간식이다. 경험치 +$exp", "달다. 경험치 +$exp").random()
    const val SNACK_NONE = "간식이 없어. 할 일 하나 끝내면 생겨"

    fun todoDone(todo: String) = "'$todo' 끝냈네. 간식 하나 생겼어"

    fun sessionStart(minutes: Int) = "좋아, ${minutes}분 뒤에 보자"
    const val SESSION_QUIT = "괜찮아. 다음엔 조금만 더 해 보자"
    fun sessionDone(couponMinutes: Int, shieldGranted: Boolean) =
        "수고했어. 휴식 쿠폰 ${couponMinutes}분 받았어" + if (shieldGranted) ". 보호권도 생겼어" else ""

    // ================================================================== C. 새벽 5시 정산 결과
    // 정산 알림 본문, 기록 탭 "최근 정산", 말풍선에 같은 문장이 쓰입니다. 아래 조각을 순서대로 이어 붙입니다.
    // 예: "11/18 숏폼 20분 · 경험치 +75 · 체력 +10 · 연속 3일. 모찌가 어린이로 자랐어요"

    fun settleHead(month: Int, day: Int, watched: String, gain: Int) = "$month/$day 숏폼 $watched · 경험치 +$gain · "
    fun settleHealth(delta: Int) = if (delta >= 0) "체력 +$delta" else "체력 $delta"
    fun settleStreak(days: Int) = " · 연속 ${days}일"
    fun settleEvolved(name: String, stage: String) = ". ${Josa.iGa(name)} ${Josa.euro(stage)} 자랐어요"
    fun settleDevolved(name: String, stage: String) = ". ${Josa.iGa(name)} 지쳐서 ${Josa.euro(stage)} 돌아갔어요"
    /** 7·30·100일 달성. accessory: 리본, 목도리, 별 장식 */
    fun settleMilestone(days: Int, name: String, accessory: String) =
        ". ${days}일 연속이라 ${Josa.iGa(name)} ${Josa.eulReul(accessory)} 선물받았어요"
    fun settleShieldUsed(days: Int) = ". 보호권으로 연속 ${days}일을 지켰어요"
    const val SETTLE_STREAK_BROKEN = ". 연속 기록은 오늘부터 다시 시작해요"

    // 개발자 테스트에서 정산할 게 없을 때
    const val DEV_NOTHING_TO_SETTLE = "정산할 날이 없어요"
    const val DEV_UNTRACKED_SKIPPED = "측정 기록이 없어 정산을 건너뛰었어요"
    const val DEV_RESET = "처음부터 다시 시작해요"

    // ================================================================== D. 알림

    /** 밤 12시~새벽 5시: 할 일 줄과 캐릭터 대사 대신 */
    const val SLEEP = "이제 자자. 내일 공부하려면 지금 자야 해"
    /** 캐릭터가 말하는 줄 앞에 이름을 붙이는 형식. 예: "모찌: 잠깐 쉬었다 볼까?" */
    fun says(name: String, line: String) = "$name: $line"
    fun nextTodo(todo: String) = "남은 할 일 · $todo"
    /** 잠금 화면(내용 가림)에 보이는 줄 */
    fun todosLeftPublic(count: Int) = "할 일 ${count}개 남았어요"

    // 누적 시청 (목표 전 30분마다, 넘긴 뒤 15분마다)
    fun watchTitle(total: String) = "오늘 숏폼 $total 봤어요"
    fun watchGoalLeft(left: String) = "목표까지 $left 남았어요"
    fun watchGoalReached(goal: String) = "오늘 목표 ${goal}을 다 썼어요"
    fun watchGoalOver(goal: String, over: String) = "목표 ${goal}보다 $over 더 봤어요"

    // 20분째 이어서 시청 (40분, 60분…)
    const val CONTINUOUS_TITLE = "잠깐 쉬었다 볼까?"
    fun continuousBody(minutes: Int) = "${minutes}분째 계속 보고 있어요"

    // 집중 완료
    fun sessionDoneTitle(minutes: Int) = "${minutes}분 집중 완료"
    fun sessionDoneCoupon(couponMinutes: Int) = "휴식 쿠폰 ${couponMinutes}분을 받았어요"
    const val SESSION_DONE_COUPON_MAX = "오늘 받을 수 있는 휴식 쿠폰(20분)은 다 받았어요"
    const val SESSION_DONE_SHIELD = " · 보호권도 1개 생겼어요"

    // 새벽 5시 정산 (본문은 C의 정산 문장)
    const val DAILY_TITLE = "어제 기록을 정리했어요"

    // 휴대폰 설정 > 알림에 보이는 채널 이름·설명
    const val CHANNEL_ALERT = "시청 알림"
    const val CHANNEL_ALERT_DESC = "숏폼을 오래 보거나 목표에 가까워지면 알려 드려요"
    const val CHANNEL_DAILY = "하루 정산"
    const val CHANNEL_DAILY_DESC = "매일 새벽 5시에 어제 기록과 캐릭터 변화를 알려 드려요"
    const val CHANNEL_STUDY = "공부"
    const val CHANNEL_STUDY_DESC = "집중 세션이 끝나면 알려 드려요"

    // ================================================================== E. 숏폼 앱 위에 뜨는 것

    // 집중 중에 숏폼을 열어서 막혔을 때 (토스트, 캐릭터가 말함)
    fun blockedWithTodo(name: String, todo: String) = "$name: 지금은 집중 시간이야. '$todo' 마저 하자"
    fun blockedNoTodo(name: String) = "$name: 지금은 집중 시간이야. 조금만 더 버티자"

    // 목표를 넘긴 뒤 숏폼을 켰을 때 (3초 안내 화면)
    fun guideTitle(name: String) = "$name: 오늘은 목표 시간을 넘었어"
    fun guideUsage(watched: String, goal: String) = "오늘 숏폼 $watched · 목표 $goal"
    fun guideTodo(todo: String) = "아직 '$todo'${Josa.subject(todo)} 남아 있어요"
    fun guideCountdown(seconds: Int) = "${seconds}초 뒤에 닫혀요"
    const val GUIDE_CONTINUE = "그래도 볼게요"

    // ================================================================== G. 집중 중 고정 알림 (다른 앱을 쓰는 동안에도 보임)

    const val CHANNEL_FOCUS = "집중 중"
    const val CHANNEL_FOCUS_DESC = "집중하는 동안 남은 시간을 보여 드려요"
    const val FOCUS_TITLE = "집중하는 중"
    /** 남은 시간은 알림 오른쪽에 초 단위로 줄어듭니다 */
    fun focusBody(todo: String?) = if (todo != null) "'$todo' 하는 중 · 숏폼은 잠시 막아 둘게요" else "숏폼은 잠시 막아 둘게요"
    const val FOCUS_PUBLIC = "집중하는 중"

    // ================================================================== H. 하루 첫 실행 때 연속일수 화면

    fun streakCount(days: Int) = "$days"
    const val STREAK_UNIT = "일 연속"
    /** 이어 가는 중 */
    fun streakKeepBody(goal: String, nextDays: Int) = "오늘도 $goal 안으로 보면 ${nextDays}일째가 돼요"
    /** 어제 끊겼을 때. 탓하지 않고 최고 기록이 남아 있다는 걸 알려 줍니다 */
    const val STREAK_RESTART_TITLE = "다시 시작해요"
    fun streakRestartBody(best: Int) =
        if (best > 0) "최고 기록 ${best}일은 그대로예요. 오늘이 새로운 1일이에요" else "오늘 목표 안으로 보면 1일이 시작돼요"
    fun streakBest(days: Int) = "최고 ${days}일"
    fun streakShields(count: Int) = "보호권 ${count}개"
    /** accessory: 리본, 목도리, 별 장식 */
    fun streakNextMilestone(daysLeft: Int, accessory: String) = "${daysLeft}일 더 하면 $accessory"
    const val STREAK_RULE = "목표 시간 안으로 보면 하루가 이어져요\n조금 넘긴 날엔 보호권이 대신 지켜 줘요"
    const val STREAK_CONTINUE = "오늘도 이어 가기"
    const val STREAK_START = "시작하기"
    const val STREAK_TODAY = "오늘"

    // ================================================================== I. 캐릭터 카드 버튼 상태

    const val FEED = "밥 주기"
    const val FEED_DONE = "밥 먹었어요"
    const val FEED_BLOCKED = "밥 주기"
    fun snack(count: Int) = "간식 주기 · $count"
    const val SNACK_EMPTY = "간식 없음"
    // 버튼을 못 누를 때 아래에 적는 이유
    const val NOTE_FED = "밥은 하루에 한 번 줄 수 있어요"
    const val NOTE_OVER_GOAL = "목표를 넘긴 날엔 밥을 줄 수 없어요"
    const val NOTE_NO_SNACK = "할 일을 끝내면 간식이 생겨요"

    // 할 일 완료를 잘못 눌렀을 때
    const val UNDO = "되돌리기"

    // ================================================================== F. 그 밖의 안내 (토스트)

    const val TOAST_SESSION_FAILED = "접근성 서비스가 꺼져서 집중이 중단됐어요"
    const val TOAST_NEED_ACCESSIBILITY = "집중 모드를 쓰려면 접근성 서비스를 켜 주세요"
    const val TOAST_NO_COUPON_DURING_SESSION = "집중하는 동안엔 쿠폰을 쓸 수 없어요"
    fun toastTodoLimit(max: Int) = "할 일은 하루에 ${max}개까지 적을 수 있어요"
}
