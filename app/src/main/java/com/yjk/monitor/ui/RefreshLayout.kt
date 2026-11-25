package com.yjk.monitor.ui

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * 自研下拉刷新容器（零依赖，替代 SwipeRefreshLayout）。
 *
 * 用法：
 *   val r = RefreshLayout(ctx)
 *   r.onRefresh = { ... }           // 下拉触发
 *   r.setContentView(scrollOrListView)
 *
 * 规则与系统一致：只有当内容已经在顶部（不能再往上滚）时才接管下拉手势，
 * 下拉超过阈值松手即触发；异步刷新可设 autoFinish = false，刷完手动 finishRefresh()。
 */
class RefreshLayout(ctx: Context) : FrameLayout(ctx) {

    var onRefresh: (() -> Unit)? = null

    /** true：回调后自动结束动画；false：由调用方 finishRefresh() */
    var autoFinish: Boolean = true

    private val indicator: TextView = TextView(ctx).apply {
        text = "下拉刷新"
        textSize = 12f
        setTextColor(0xFF888888.toInt())
        gravity = Gravity.CENTER
        alpha = 0f
        includeFontPadding = false
    }

    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val maxPull = dp(64f).toFloat()
    private val trigger = dp(34f).toFloat()

    private var downY = 0f
    private var lastY = 0f
    private var pull = 0f
    private var dragging = false
    private var refreshing = false

    init {
        addView(indicator, LayoutParams(LayoutParams.MATCH_PARENT, dp(64f), Gravity.TOP))
    }

    fun setContentView(v: View) {
        if (childCount > 1) removeViewAt(1)
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun content(): View? = if (childCount > 1) getChildAt(1) else null

    private fun canScrollUp(): Boolean = content()?.canScrollVertically(-1) ?: false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (refreshing || onRefresh == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                lastY = ev.y
                pull = 0f
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && ev.y - downY > touchSlop && !canScrollUp()) {
                    dragging = true
                    lastY = ev.y
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (onRefresh == null || refreshing) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val dy = ev.y - lastY
                lastY = ev.y
                pull = max(0f, min(maxPull, pull + dy * 0.55f))
                applyPull()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                if (pull >= trigger) startRefresh() else endPull()
                return true
            }
        }
        return dragging
    }

    private fun applyPull() {
        content()?.translationY = pull
        indicator.alpha = (pull / trigger).coerceIn(0f, 1f)
        indicator.text = if (pull >= trigger) "松开立即刷新" else "下拉刷新"
    }

    private fun endPull() {
        pull = 0f
        content()?.animate()?.translationY(0f)?.setDuration(180)?.start()
        indicator.animate()?.alpha(0f)?.setDuration(180)?.start()
    }

    private fun startRefresh() {
        refreshing = true
        indicator.text = "正在刷新…"
        indicator.animate()?.alpha(1f)?.setDuration(120)?.start()
        content()?.animate()?.translationY(dp(44f).toFloat())?.setDuration(160)?.start()
        postDelayed({
            try {
                onRefresh?.invoke()
            } catch (e: Exception) {
                // 刷新回调异常不影响手势状态
            }
            if (autoFinish) finishRefresh()
        }, 200L)
    }

    /** 结束刷新（幂等）。异步场景由页面在数据回填后调用。 */
    fun finishRefresh() {
        if (!refreshing) {
            content()?.translationY = 0f
            indicator.alpha = 0f
            return
        }
        refreshing = false
        pull = 0f
        content()?.animate()?.translationY(0f)?.setDuration(200)?.start()
        indicator.animate()?.alpha(0f)?.setDuration(200)?.start()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
