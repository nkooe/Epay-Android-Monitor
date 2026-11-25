package com.yjk.monitor.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.yjk.monitor.R
import com.yjk.monitor.core.Prefs

/**
 * 微信风格 UI 原子组件（支持浅色 / 深色两种主题）。
 * 所有颜色与背景都在这里按主题计算，页面里不写死颜色。
 */
object Ui {

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    // ---------------- 主题 ----------------

    fun isDark(ctx: Context): Boolean {
        val systemDark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return when (Prefs.themeMode) {
            1 -> false
            2 -> true
            else -> systemDark
        }
    }

    fun colorBg(ctx: Context): Int = if (isDark(ctx)) 0xFF111111.toInt() else ctx.getColor(R.color.wx_bg)
    fun colorCard(ctx: Context): Int = if (isDark(ctx)) 0xFF1E1E1E.toInt() else ctx.getColor(R.color.wx_card)
    fun colorText(ctx: Context): Int = if (isDark(ctx)) 0xFFE6E6E6.toInt() else ctx.getColor(R.color.wx_text)
    fun colorSub(ctx: Context): Int = if (isDark(ctx)) 0xFF9A9A9A.toInt() else ctx.getColor(R.color.wx_text_sub)
    fun colorHint(ctx: Context): Int = if (isDark(ctx)) 0xFF6E6E6E.toInt() else ctx.getColor(R.color.wx_text_hint)
    fun colorDivider(ctx: Context): Int = if (isDark(ctx)) 0xFF2C2C2C.toInt() else ctx.getColor(R.color.wx_divider)
    fun colorInputBg(ctx: Context): Int = if (isDark(ctx)) 0xFF262626.toInt() else ctx.getColor(R.color.wx_input_bg)

    fun colorGreen(ctx: Context): Int = ctx.getColor(R.color.wx_green)
    fun colorRed(ctx: Context): Int = ctx.getColor(R.color.wx_red)
    fun colorBlue(ctx: Context): Int =
        if (isDark(ctx)) 0xFF8FA8D8.toInt() else ctx.getColor(R.color.wx_blue)
    fun colorOrange(ctx: Context): Int = ctx.getColor(R.color.wx_orange)

    // ---------------- 基础控件 ----------------

    fun text(
        ctx: Context,
        s: CharSequence,
        size: Float = 14f,
        color: Int = colorText(ctx),
        bold: Boolean = false
    ): TextView = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun col(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun rounded(ctx: Context, color: Int, radiusDp: Float, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(color)
            if (strokeColor != 0) setStroke(dp(ctx, 1f), strokeColor)
        }

    fun oval(ctx: Context, color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun card(ctx: Context, topMargin: Int = 10, sideMargin: Int = 10, padV: Int = 0): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ctx, colorCard(ctx), 12f)
            setPadding(0, dp(ctx, padV.toFloat()), 0, dp(ctx, padV.toFloat()))
        }
        c.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(ctx, sideMargin.toFloat()), dp(ctx, topMargin.toFloat()), dp(ctx, sideMargin.toFloat()), 0)
        }
        return c
    }

    fun section(ctx: Context, title: String): TextView = TextView(ctx).apply {
        text = title
        textSize = 13f
        setTextColor(colorSub(ctx))
        setPadding(dp(ctx, 22f), dp(ctx, 16f), dp(ctx, 16f), dp(ctx, 6f))
        includeFontPadding = false
    }

    fun chevron(ctx: Context): TextView = TextView(ctx).apply {
        text = "›"
        textSize = 20f
        setTextColor(colorHint(ctx))
        setPadding(dp(ctx, 6f), 0, 0, 0)
    }

    fun divider(ctx: Context, insetLeft: Int = 16): View = View(ctx).apply {
        setBackgroundColor(colorDivider(ctx))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(ctx, insetLeft.toFloat())
        }
    }

    fun vSpace(ctx: Context, h: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, h.toFloat()))
    }

    /** 右侧下拉框：灰底圆角 + 当前值 + ▾，点击由调用方展开列表 */
fun dropdown(ctx: Context, value: String): TextView {
    val tv = TextView(ctx)
    tv.text = value + "  ▾"
    tv.textSize = 13.5f
    tv.setTextColor(colorText(ctx))
    tv.maxLines = 1
    tv.ellipsize = android.text.TextUtils.TruncateAt.END
    tv.gravity = android.view.Gravity.CENTER
    tv.setPadding(dp(ctx, 12f), 0, dp(ctx, 10f), 0)
    tv.background = rounded(ctx, colorInputBg(ctx), 6f, colorDivider(ctx))
    tv.isClickable = true
    tv.minWidth = dp(ctx, 104f)
    return tv
}

fun cell(
        ctx: Context,
        title: String,
        sub: String? = null,
        right: View? = null,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val r = row(ctx).apply {
            setPadding(dp(ctx, 16f), dp(ctx, 13f), dp(ctx, 14f), dp(ctx, 13f))
            if (onClick != null) setOnClickListener { onClick() }
        }
        val c = col(ctx)
        c.addView(text(ctx, title, 15.5f, colorText(ctx)))
        if (!sub.isNullOrEmpty()) {
            c.addView(text(ctx, sub, 12f, colorSub(ctx)).apply {
                setPadding(0, dp(ctx, 4f), 0, 0)
            })
        }
        r.addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        when {
            right != null -> r.addView(right)
            onClick != null -> r.addView(chevron(ctx))
        }
        return r
    }

    class SwitchCell(val row: LinearLayout, val switch: Switch, val subView: TextView?)

    fun switchCell(
        ctx: Context,
        title: String,
        sub: String? = null,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): SwitchCell {
        val r = row(ctx).apply {
            setPadding(dp(ctx, 16f), dp(ctx, 10f), dp(ctx, 16f), dp(ctx, 10f))
        }
        val c = col(ctx)
        c.addView(text(ctx, title, 15.5f, colorText(ctx)))
        var subView: TextView? = null
        if (!sub.isNullOrEmpty()) {
            subView = text(ctx, sub, 12f, colorSub(ctx)).apply { setPadding(0, dp(ctx, 4f), 0, 0) }
            c.addView(subView)
        }
        r.addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        r.addView(sw, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return SwitchCell(r, sw, subView)
    }

    fun input(
        ctx: Context,
        hint: String,
        value: String,
        numeric: Boolean = false,
        multiline: Boolean = false
    ): EditText = EditText(ctx).apply {
        this.hint = hint
        setText(value)
        textSize = 14f
        setTextColor(colorText(ctx))
        setHintTextColor(colorHint(ctx))
        background = rounded(ctx, colorInputBg(ctx), 8f, colorDivider(ctx))
        setPadding(dp(ctx, 10f), dp(ctx, 9f), dp(ctx, 10f), dp(ctx, 9f))
        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) {
            isSingleLine = false
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        } else {
            isSingleLine = true
        }
        includeFontPadding = false
    }

    fun button(ctx: Context, label: String, filled: Boolean = true, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (filled) Color.WHITE else colorGreen(ctx))
            background = if (filled) rounded(ctx, colorGreen(ctx), 8f)
            else rounded(ctx, 0x1A07C160, 100f)
            setPadding(dp(ctx, 16f), dp(ctx, 9f), dp(ctx, 16f), dp(ctx, 9f))
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun pill(ctx: Context, label: String, green: Boolean): TextView = TextView(ctx).apply {
        text = label
        textSize = 11f
        setTextColor(if (green) colorGreen(ctx) else colorSub(ctx))
        background = if (green) rounded(ctx, 0x1A07C160, 100f) else rounded(ctx, 0x14000000, 100f)
        setPadding(dp(ctx, 8f), dp(ctx, 3f), dp(ctx, 8f), dp(ctx, 3f))
        includeFontPadding = false
    }

    fun icon(ctx: Context, resId: Int, size: Int, tint: Int): ImageView = ImageView(ctx).apply {
        setImageResource(resId)
        setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(ctx, size.toFloat()), dp(ctx, size.toFloat()))
    }

    fun empty(ctx: Context, msg: String): View = col(ctx).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(ctx, 56f), 0, dp(ctx, 56f))
        addView(text(ctx, msg, 14f, colorHint(ctx)))
    }
}
