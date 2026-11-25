package com.yjk.monitor.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** 页面基类：懒加载 + 可刷新 */
abstract class Page(val host: MainActivity) {

    abstract val title: String

    private var root: View? = null

    fun view(): View {
        val v = root ?: build()
        root = v
        return v
    }

    protected abstract fun build(): View

    /** 每次切到该页 / 回到前台 / 数据变化时调用，页面自行决定刷新粒度 */
    open fun onShow() {}

    /** 离开该页时调用（用于停掉页面内的定时刷新） */
    open fun onHide() {}

    /** 重建整页（结构变化时用） */
    fun invalidate() = onInvalidate()

    protected open fun onInvalidate() {}
}

/** 可滚动 + 支持下拉刷新的页面 */
abstract class ScrollPage(host: MainActivity) : Page(host) {

    protected lateinit var container: LinearLayout
    private var scroll: ScrollView? = null
    private var refresh: RefreshLayout? = null

    override fun build(): View {
        container = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.colorBg(host))
        }
        val sv = ScrollView(host).apply {
            isFillViewport = true
            setBackgroundColor(Ui.colorBg(host))
            clipToPadding = false
            addView(
                container,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        scroll = sv
        fill(container)
        container.addView(bottomSpacer())

        val r = RefreshLayout(host).apply {
            onRefresh = { onPullRefresh() }
            setContentView(sv)
        }
        refresh = r
        return r
    }

    override fun onInvalidate() {
        if (!::container.isInitialized) return
        val y = scroll?.scrollY ?: 0
        container.removeAllViews()
        fill(container)
        container.addView(bottomSpacer())
        scroll?.post { scroll?.scrollTo(0, y) }
    }

    /** 下拉刷新的默认行为：整页重建。子类可覆盖成更轻量的刷新。 */
    protected open fun onPullRefresh() {
        onInvalidate()
    }

    /** 子类填充页面内容 */
    protected abstract fun fill(parent: LinearLayout)

    /** 让页面可以结束刷新动画（异步刷新时用） */
    protected fun finishPullRefresh() {
        refresh?.finishRefresh()
    }

    private fun bottomSpacer(): View = View(host).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            Ui.dp(host, 28f)
        )
    }
}
