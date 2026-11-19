package com.shortformpet.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 아래에서 올라오는 시트. 기본 AlertDialog 대신 앱의 색·글자·버튼을 그대로 씁니다.
 *
 * - 위 모서리 20, 손잡이, 제목 20sp, 설명 14sp
 * - 버튼은 아래에 나란히: 왼쪽 취소(테두리), 오른쪽 실행(채움). 지우는 동작은 실행 버튼이 붉은 계열
 * - 키보드가 올라오면 시트가 키보드 위로 같이 올라갑니다.
 */
object Sheet {

    class Action(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

    /** 안내·확인용 시트 */
    fun confirm(
        context: Context,
        title: String,
        message: String,
        primary: Action,
        secondaryLabel: String? = "취소",
        onSecondary: (() -> Unit)? = null,
        cancelable: Boolean = true,
        scrollMessage: Boolean = false,
    ) {
        val dialog = newDialog(context, cancelable)
        val body = panel(context)
        body.addView(titleView(context, title))
        val msg = context.label(message, 14f, P.SUB).apply { setLineSpacing(0f, 1.4f) }
        if (scrollMessage) {
            body.addView(
                ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    addView(msg)
                },
                LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = context.dp(8) },
            )
        } else {
            body.addView(msg, lp(context, top = 8))
        }
        body.addView(buttons(context, dialog, secondaryLabel, onSecondary, primary) { true }, lp(context, top = 24))
        show(dialog, body, maxHeightRatio = if (scrollMessage) 0.85f else null)
    }

    /** 한 줄 입력 시트. onSubmit이 false를 돌려주면 시트를 닫지 않습니다. */
    fun input(
        context: Context,
        title: String,
        message: String?,
        initial: String,
        hint: String,
        maxLength: Int,
        submitLabel: String,
        onSubmit: (String) -> Boolean,
    ) {
        val dialog = newDialog(context, true)
        val body = panel(context)
        body.addView(titleView(context, title))
        if (message != null) body.addView(context.label(message, 14f, P.SUB), lp(context, top = 8))

        val counter = context.label("", 12f, P.MUTED, tabular = true)
        val field = EditText(context).apply {
            setText(initial)
            this.hint = hint
            setHintTextColor(P.MUTED)
            setTextColor(P.INK)
            textSize = 16f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            setPadding(context.dp(16), 0, context.dp(16), 0)
            background = fieldBackground(context, focused = false)
            setOnFocusChangeListener { v, has -> v.background = fieldBackground(context, has) }
            setSelection(text.length)
        }
        fun updateCounter() { counter.text = "${field.text.length} / $maxLength" }
        updateCounter()

        val submitButton = arrayOfNulls<TextView>(1)
        fun refreshSubmit() {
            submitButton[0]?.let { setUsable(it, field.text.toString().isNotBlank()) }
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateCounter(); refreshSubmit() }
        })
        body.addView(field, LinearLayout.LayoutParams(MATCH, context.dp(52)).apply { topMargin = context.dp(20) })
        body.addView(counter.apply { gravity = Gravity.END }, lp(context, top = 8))

        val submit = Action(submitLabel) {}
        val row = buttons(context, dialog, "취소", null, submit) { onSubmit(field.text.toString()) }
        submitButton[0] = row.getChildAt(1) as TextView
        refreshSubmit()
        body.addView(row, lp(context, top = 16))

        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && field.text.isNotBlank()) {
                if (onSubmit(field.text.toString())) dialog.dismiss()
                true
            } else {
                false
            }
        }
        show(dialog, body, maxHeightRatio = null)
        field.requestFocus()
        field.postDelayed({
            context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    /**
     * 내용을 직접 넣는 시트. [build]는 제목 아래에 들어갈 내용을 채우고,
     * 닫을 때 쓸 함수(dismiss)를 받습니다. 버튼이 필요하면 [primary]를 줍니다.
     */
    fun custom(
        context: Context,
        title: String?,
        primary: Action? = null,
        secondaryLabel: String? = "취소",
        cancelable: Boolean = true,
        maxHeightRatio: Float? = null,
        onDismiss: (() -> Unit)? = null,
        build: LinearLayout.(dismiss: () -> Unit) -> Unit,
    ) {
        val dialog = newDialog(context, cancelable)
        val body = panel(context)
        if (title != null) body.addView(titleView(context, title))
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.build { dialog.dismiss() }
        if (maxHeightRatio != null) {
            body.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { if (title != null) topMargin = context.dp(8) })
        } else {
            body.addView(content, lp(context, top = if (title != null) 8 else 0))
        }
        if (primary != null) body.addView(buttons(context, dialog, secondaryLabel, null, primary) { true }, lp(context, top = 24))
        show(dialog, body, maxHeightRatio)
        if (onDismiss != null) {
            dialog.setOnDismissListener {
                dialogCancelable.remove(dialog)
                onDismiss()
            }
        }
    }

    // ------------------------------------------------------------------ 내부

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun lp(context: Context, top: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(top) }

    private fun newDialog(context: Context, cancelable: Boolean) =
        // 떠 있지 않은(전체 화면) 투명 창. 바깥 어두운 부분은 show()에서 직접 그립니다.
        Dialog(context, android.R.style.Theme_Material_Light_NoActionBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(cancelable)
            setCanceledOnTouchOutside(cancelable)
            dialogCancelable[this] = cancelable
        }

    private fun panel(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(24), context.dp(12), context.dp(24), context.dp(24))
        val r = context.dpf(20f)
        background = GradientDrawable().apply {
            setColor(P.SURFACE)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        // 손잡이
        addView(
            View(context).apply { background = roundRect(P.TRACK, context.dpf(2f)) },
            LinearLayout.LayoutParams(context.dp(36), context.dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = context.dp(20)
            },
        )
    }

    private fun titleView(context: Context, title: String) = context.label(title, 20f, P.INK, W.MEDIUM)

    private fun fieldBackground(context: Context, focused: Boolean) =
        roundRect(P.SOFT, context.dpf(10f), context.dp(if (focused) 2 else 1), if (focused) P.INK else P.LINE)

    private fun buttons(
        context: Context,
        dialog: Dialog,
        secondaryLabel: String?,
        onSecondary: (() -> Unit)?,
        primary: Action,
        beforePrimary: () -> Boolean,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        if (secondaryLabel != null) {
            addView(
                context.btn(secondaryLabel, BtnStyle.OUTLINE) {
                    dialog.dismiss()
                    onSecondary?.invoke()
                },
                LinearLayout.LayoutParams(0, context.dp(52), 1f).apply { marginEnd = context.dp(8) },
            )
        }
        val main = context.btn(primary.label, BtnStyle.PRIMARY) {
            if (beforePrimary()) {
                dialog.dismiss()
                primary.onClick()
            }
        }
        if (primary.destructive) {
            val r = context.dpf(10f)
            main.background = pressable(roundRect(P.OVER_TEXT, r), r, Color.argb(60, 255, 255, 255))
        }
        addView(main, LinearLayout.LayoutParams(0, context.dp(52), 1f))
    }

    /**
     * 시트 창은 화면 전체를 덮는 투명한 창으로 띄우고, 그 안에서 시트를 아래에 붙입니다.
     * 떠 있는 작은 창(기본 Dialog)은 기기마다 키보드 처리가 달라 키보드가 시트를 가리는 일이 있어서,
     * 키보드·내비게이션 바 높이를 직접 받아 시트 아래 여백으로 씁니다.
     */
    private fun show(dialog: Dialog, body: LinearLayout, maxHeightRatio: Float?) {
        val context = dialog.context
        val cancelable = dialogCancelable[dialog] ?: true
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(102, 0, 0, 0))
            isClickable = true
            setOnClickListener { if (cancelable) dialog.dismiss() }
        }
        body.isClickable = true // 시트 안을 눌러도 닫히지 않게
        val height = if (maxHeightRatio != null) (context.resources.displayMetrics.heightPixels * maxHeightRatio).toInt() else WRAP
        root.addView(body, FrameLayout.LayoutParams(MATCH, height, Gravity.BOTTOM))
        dialog.setContentView(root)

        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setLayout(MATCH, MATCH)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setWindowAnimations(0)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // 시트 아래쪽은 내비게이션 바와 키보드 중 더 높은 쪽만큼 비웁니다.
        val basePad = body.paddingBottom
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                bars.top to maxOf(bars.bottom, ime.bottom)
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            root.setPadding(0, top, 0, 0)
            body.setPadding(body.paddingLeft, body.paddingTop, body.paddingRight, basePad + bottom)
            insets
        }
        dialog.setOnDismissListener { dialogCancelable.remove(dialog) }

        // 바탕은 서서히, 시트는 아래에서 올라옵니다.
        root.alpha = 0f
        body.translationY = context.dpf(400f)
        dialog.show()
        root.animate().alpha(1f).setDuration(180).start()
        body.animate().translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private val dialogCancelable = HashMap<Dialog, Boolean>()
}
