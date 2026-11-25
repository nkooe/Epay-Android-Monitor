package com.yjk.monitor.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.yjk.monitor.App
import com.yjk.monitor.R
import com.yjk.monitor.core.AppUtil
import com.yjk.monitor.core.Bus
import com.yjk.monitor.core.PayRecord
import com.yjk.monitor.core.Prefs
import com.yjk.monitor.service.FloatingService
import com.yjk.monitor.service.KeepAliveService
import com.yjk.monitor.ui.pages.AboutPage
import com.yjk.monitor.ui.pages.ConfigPage
import com.yjk.monitor.ui.pages.HomePage
import com.yjk.monitor.ui.pages.LogsPage

/**
 * 主界面：微信风格四页 + 底部导航。
 * 纯框架 View 实现（无 AndroidX），启动快、依赖少。
 *
 * 注意：targetSdk 35 在 Android 15+ 会强制 edge-to-edge（窗口延伸到状态栏/导航栏下面），
 * 所以顶栏和底部导航栏都必须按 window insets 自己留白，否则底部 tab 会被系统导航按钮盖住。
 */
class MainActivity : Activity(), Bus.Listener {

    private lateinit var content: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var statePill: TextView
    private lateinit var topBar: FrameLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var pages: Array<Page>

    private val tabIconViews = ArrayList<ImageView>(4)
    private val tabLabelViews = ArrayList<TextView>(4)
    private var current = 0

    private val tabTitles = arrayOf("首页", "日志", "配置", "关于")
    private val tabIcons = intArrayOf(
        R.drawable.ic_tab_monitor,
        R.drawable.ic_tab_records,
        R.drawable.ic_tab_settings,
        R.drawable.ic_tab_about
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Bus.register(this)
        setContentView(buildRoot())
        enableEdgeToEdge()
        applyStatusBarStyle()
        applyWindowInsets()
        requestNotificationPermission()

        pages = arrayOf(HomePage(this), LogsPage(this), ConfigPage(this), AboutPage(this))
        select(0)

        // 前台服务：让进程常驻
        if (Prefs.monitorEnabled && Prefs.keepAliveOn) KeepAliveService.start(this)
        if (Prefs.floatingEnabled && AppUtil.canDrawOverlay(this)) FloatingService.start(this)
    }

    override fun onResume() {
        super.onResume()
        FloatingService.setAppForeground(true)
        if (!::pages.isInitialized) return
        updatePill()
        pages[current].onShow()
    }

    override fun onPause() {
        FloatingService.setAppForeground(false)
        super.onPause()
    }

    override fun onDestroy() {
        Bus.unregister(this)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (current != 0) {
            select(0)
        } else {
            super.onBackPressed()
        }
    }

    // ---------------- 布局 ----------------

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.colorBg(this@MainActivity))
        }
        root.addView(buildTopBar())

        content = FrameLayout(this)
        root.addView(
            content,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        root.addView(buildBottomBar())
        return root
    }

    private fun buildTopBar(): View {
        // 三栏：左=页面名称，中=监听状态，右=刷新
        topBar = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.wx_green))
            setPadding(dp(12), 0, dp(12), 0)
        }
        topBar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48)
        )

        titleView = Ui.text(this, "易监控", 17f, Color.WHITE, true)
        topBar.addView(
            titleView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            )
        )

        statePill = TextView(this).apply {
            textSize = 10.5f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0x33FFFFFF)
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            includeFontPadding = false
        }
        topBar.addView(
            statePill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        val btn = Ui.text(this, "刷新", 13f, Color.WHITE)
        btn.setPadding(dp(8), dp(6), dp(2), dp(6))
        btn.setOnClickListener {
            updatePill()
            if (::pages.isInitialized) pages[current].onShow()
        }
        topBar.addView(
            btn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        )
        return topBar
    }

    private fun buildBottomBar(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(View(this).apply {
            setBackgroundColor(Ui.colorDivider(this@MainActivity))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.colorCard(this@MainActivity))
        }
        for (i in tabTitles.indices) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { select(i) }
            }
            val icon = ImageView(this).apply {
                setImageResource(tabIcons[i])
                setColorFilter(Ui.colorSub(this@MainActivity))
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            val label = Ui.text(this, tabTitles[i], 10f, Ui.colorSub(this@MainActivity))
            item.addView(
                label,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            )
            bottomBar.addView(item, LinearLayout.LayoutParams(0, dp(54), 1f))
            tabIconViews.add(icon)
            tabLabelViews.add(label)
        }

        bottomBar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(54)
        )
        wrap.addView(
            bottomBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        wrap.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return wrap
    }

    // ---------------- 系统栏留白 ----------------

    /**
     * Android 15+（targetSdk 35）强制 edge-to-edge：窗口内容会铺到状态栏/导航栏下面，
     * 必须自己按 insets 留白，否则底部 tab 会被三键导航 / 手势条压住。
     * 低于 Android 15 的机型系统已经帮我们避开系统栏，这里就不重复加 padding。
     */
    /**
     * 全量 edge-to-edge：窗口内容铺满整屏（包括状态栏/导航栏后面），
     * 再由我们自己按 insets 给顶栏和底部导航留白。
     *
     * 这样做的好处：状态栏区域被标题栏的绿色铺满（微信就是这种"绿到顶"），
     * 任意 ROM / 三键导航 / 手势条都不会压住底部 tab，行为完全一致。
     */
    private fun enableEdgeToEdge() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
            }
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun applyWindowInsets() {
        window.decorView.setOnApplyWindowInsetsListener { _, insets ->
            var top = 0
            var bottom = 0
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
                // 键盘弹出时，底部导航让到键盘上面，输入框不被挡
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                if (ime > bottom) bottom = ime
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            applyBarInsets(top, bottom)
            insets
        }
        window.decorView.requestApplyInsets()
    }

    private fun applyBarInsets(top: Int, bottom: Int) {
        if (!::topBar.isInitialized || !::bottomBar.isInitialized) return
        // 顶栏：状态栏让出位置，绿色继续往上铺满
        topBar.setPadding(dp(10), top, dp(10), 0)
        topBar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48) + top
        )
        // 底部导航：让出导航栏高度，白色继续往下铺满
        bottomBar.setPadding(0, 0, 0, bottom)
        bottomBar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(54) + bottom
        )
    }

    // ---------------- 交互 ----------------

    fun select(index: Int) {
        if (!::pages.isInitialized) return
        if (current in pages.indices && current != index) pages[current].onHide()
        current = index
        content.removeAllViews()
        content.addView(
            pages[index].view(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        titleView.text = pages[index].title
        for (i in tabIconViews.indices) {
            val on = i == index
            val c = if (on) Ui.colorGreen(this) else Ui.colorSub(this)
            tabIconViews[i].setColorFilter(c)
            tabLabelViews[i].setTextColor(c)
        }
        pages[index].onShow()
        updatePill()
    }

    fun gotoPage(index: Int) = select(index)

    fun updatePill() {
        if (!::statePill.isInitialized) return
        val granted = AppUtil.isNotificationListenerEnabled(this)
        val connectedNow = AppUtil.isListenerConnected()
        val listening = granted && connectedNow
        android.util.Log.d("YJK", "pill granted=$granted connected=$connectedNow monitor=${Prefs.monitorEnabled} -> ${if (listening) "监听中" else "非监听中"}")
        val warmingUp = granted && !connectedNow &&
            System.currentTimeMillis() - App.startedAt < 20_000L
        statePill.text = when {
            !Prefs.monitorEnabled -> "已暂停"
            listening -> "监听中"
            warmingUp -> "连接中"
            granted -> "未连接"
            else -> "待授权"
        }
        statePill.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(
                when {
                    !Prefs.monitorEnabled -> 0x33FFFFFF
                    listening -> 0x4DFFFFFF
                    else -> 0x66FF5252
                }
            )
        }
    }

    // ---------------- Bus ----------------

    override fun onRecordAdded(record: PayRecord) {
        if (!::pages.isInitialized) return
        pages[current].onShow()
    }

    override fun onRecordsChanged() {
        if (!::pages.isInitialized) return
        pages[current].onShow()
    }

    override fun onStateChanged() {
        if (!::pages.isInitialized) return
        updatePill()
        pages[current].onShow()
    }

    // ---------------- 系统 ----------------

    private fun applyStatusBarStyle() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        // targetSdk < 33 的应用由系统在创建通知渠道时自动处理，不需要手动申请
        if (applicationInfo.targetSdkVersion < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val text = data?.getStringExtra("text") ?: return
            (pages.getOrNull(2) as? ConfigPage)?.onScanResult(text)
        }
    }
}
