package com.yjk.monitor.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.LinearLayout
import android.widget.TextView
import com.yjk.monitor.R
import com.yjk.monitor.core.AppUtil
import com.yjk.monitor.core.Bus
import com.yjk.monitor.core.PayRecord
import com.yjk.monitor.core.Prefs
import com.yjk.monitor.core.RecordStore
import com.yjk.monitor.ui.MainActivity
import kotlin.math.abs
import kotlin.math.max

/**
 * 悬浮窗服务。
 *
 * 一颗可拖动的绿色小球，点一下展开面板：今日金额 / 最近到账 / 暂停推送 / 回到应用。
 * 窗口类型用 TYPE_APPLICATION_OVERLAY（Android 8+），需要「显示在其他应用上层」权限。
 */
class FloatingService : Service() {

    private lateinit var wm: WindowManager
    private var ball: View? = null
    private var panel: View? = null
    private lateinit var ballParams: WindowManager.LayoutParams
    private var panelParams: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())

    private val busListener = object : Bus.Listener {
        override fun onRecordAdded(record: PayRecord) {
            onNewRecord(record)
        }

        override fun onRecordsChanged() {
            refresh()
        }

        override fun onStateChanged() {
            refresh()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Notifier.ensureChannels(this)
        Bus.register(busListener)
        showBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ball == null) showBall()
        return START_STICKY
    }

    override fun onDestroy() {
        Bus.unregister(busListener)
        removePanel()
        ball?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        ball = null
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- 小球 ----------------

    private fun showBall() {
        if (ball != null) return
        if (!AppUtil.canDrawOverlay(this)) return
        val size = dp(44)
        val tv = TextView(this).apply {
            text = ballText()
            setTextColor(Color.WHITE)
            textSize = 10.5f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_ball)
            includeFontPadding = false
        }
        ballParams = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = max(dp(8), screenWidth() - size - dp(4))
            y = (screenHeight() * 0.66f).toInt()
        }
        tv.setOnTouchListener(ballTouch())
        try {
            wm.addView(tv, ballParams)
            ball = tv
            if (appForeground) tv.visibility = View.GONE
        } catch (e: Exception) {
            ball = null
        }
    }

    private fun ballTouch(): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var downAt = 0L

        return View.OnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = ballParams.x
                    startY = ballParams.y
                    moved = false
                    downAt = System.currentTimeMillis()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    if (moved) {
                        ballParams.x = (startX + dx).coerceIn(0, max(0, screenWidth() - ballParams.width))
                        ballParams.y = (startY + dy).coerceIn(0, max(0, screenHeight() - ballParams.height))
                        try {
                            wm.updateViewLayout(v, ballParams)
                        } catch (ex: Exception) {
                            // ignore
                        }
                        movePanelWithBall()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved && System.currentTimeMillis() - downAt < 450) {
                        togglePanel()
                    }
                    true
                }

                else -> true
            }
        }
    }

    /** 隐藏 / 显示悬浮球（隐藏时顺便收起面板） */
    private fun setVisible(visible: Boolean) {
        val v = ball ?: return
        if (!visible) removePanel()
        v.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun ballText(): String {
        val s = RecordStore.todayStats()
        return if (s.count == 0) "易监控" else "¥${trimAmount(s.amount)}"
    }

    private fun trimAmount(v: Double): String {
        val s = String.format(java.util.Locale.CHINA, "%.2f", v)
        return if (s.endsWith(".00")) s.substring(0, s.length - 3) else s
    }

    // ---------------- 面板 ----------------

    private fun togglePanel() {
        if (panel != null) removePanel() else showPanel()
    }

    private fun showPanel() {
        if (panel != null) return
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
        }
        p.addView(buildHeader())
        p.addView(buildStats())
        p.addView(divider())
        p.addView(buildRecent())
        p.addView(divider())
        p.addView(buildFooter())

        val width = dp(268)
        panelParams = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val pos = panelPosition(width)
            x = pos[0]
            y = pos[1]
        }
        try {
            wm.addView(p, panelParams)
            panel = p
        } catch (e: Exception) {
            panel = null
        }
    }

    private fun removePanel() {
        val p = panel ?: return
        try {
            wm.removeView(p)
        } catch (e: Exception) {
            // ignore
        }
        panel = null
        panelParams = null
    }

    private fun panelPosition(width: Int): IntArray {
        val sw = screenWidth()
        val sh = screenHeight()
        var x = ballParams.x - width + ballParams.width
        x = x.coerceIn(dp(8), max(dp(8), sw - width - dp(8)))
        var y = ballParams.y
        if (y > sh / 2) y -= dp(170)
        y = y.coerceIn(dp(8), max(dp(8), sh - dp(230)))
        return intArrayOf(x, y)
    }

    private fun movePanelWithBall() {
        val p = panelParams ?: return
        val v = panel ?: return
        val pos = panelPosition(p.width)
        p.x = pos[0]
        p.y = pos[1]
        try {
            wm.updateViewLayout(v, p)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(4))
        }
        val listening = AppUtil.isNotificationListenerEnabled(this) && Prefs.monitorEnabled
        row.addView(TextView(this).apply {
            text = "易监控"
            textSize = 15f
            setTextColor(0xFF191919.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        row.addView(TextView(this).apply {
            text = if (listening) " ● 运行中" else " ● 已停用"
            textSize = 11f
            setTextColor(if (listening) 0xFF07C160.toInt() else 0xFFFF3B30.toInt())
            setPadding(dp(6), 0, 0, 0)
        })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(TextView(this).apply {
            text = "收起"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { removePanel() }
        })
        return row
    }

    private fun buildStats(): View {
        val s = RecordStore.todayStats()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(2), dp(14), dp(10))
        }
        col.addView(TextView(this).apply {
            text = "¥${PayRecord.formatAmount(s.amount)}"
            textSize = 26f
            setTextColor(0xFF07C160.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
        })
        col.addView(TextView(this).apply {
            val amt = String.format(java.util.Locale.CHINA, "%.2f", s.amount)
            text = "今日 ${s.count} 笔 / ¥${amt}元 · 失败 ${RecordStore.failedCount()}"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(3), 0, 0)
        })
        return col
    }

    private fun buildRecent(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val list = RecordStore.all().take(3)
        if (list.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "还没有收款记录，去「应用」里勾选收款 App 吧"
                textSize = 12f
                setTextColor(0xFF999999.toInt())
                setPadding(dp(14), dp(8), dp(14), dp(8))
            })
            return col
        }
        for (r in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(5), dp(14), dp(5))
            }
            row.addView(TextView(this).apply {
                text = r.channelName
                textSize = 12f
                setTextColor(0xFF191919.toInt())
            })
            if (r.hasAmount) {
                row.addView(TextView(this).apply {
                    text = " ¥" + r.amountText
                    textSize = 13f
                    setTextColor(0xFF07C160.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
            } else {
                val who = if (r.title.isNotBlank()) r.title else r.text
                row.addView(TextView(this).apply {
                    text = " " + who.replace("\n", " ").take(14)
                    textSize = 12f
                    setTextColor(0xFF888888.toInt())
                    maxLines = 1
                })
            }
            row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            row.addView(TextView(this).apply {
                text = android.text.format.DateFormat.format("HH:mm", r.time).toString()
                textSize = 11f
                setTextColor(0xFFAAAAAA.toInt())
            })
            col.addView(row)
        }
        return col
    }

    private fun buildFooter(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(8))
        }
        val pause = TextView(this).apply {
            text = if (Prefs.monitorEnabled) "暂停监听" else "恢复监听"
            textSize = 12f
            setTextColor(0xFF576B95.toInt())
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener {
                Prefs.monitorEnabled = !Prefs.monitorEnabled
                removePanel()
                showPanel()
                Bus.fireStateChanged()
            }
        }
        row.addView(pause)
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(TextView(this).apply {
            text = "打开易监控"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_btn_green)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                try {
                    val i = Intent(this@FloatingService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                } catch (e: Exception) {
                    // ignore
                }
            }
        })
        return row
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(0xFFEDEDED.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    // ---------------- 刷新 ----------------

    private fun onNewRecord(rec: PayRecord) {
        (ball as? TextView)?.text = ballText()
        flashBall()
        if (panel != null) {
            removePanel()
            showPanel()
        }
    }

    private fun refresh() {
        (ball as? TextView)?.text = ballText()
        if (panel != null) {
            removePanel()
            showPanel()
        }
    }

    private fun flashBall() {
        val v = ball ?: return
        val anim = ScaleAnimation(
            1f, 1.22f, 1f, 1.22f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 160
            repeatCount = 1
            repeatMode = Animation.REVERSE
        }
        try {
            v.startAnimation(anim)
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------------- 工具 ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    companion object {
        @Volatile
        private var instance: FloatingService? = null

        fun isRunning(): Boolean = instance != null

        fun start(ctx: Context) {
            if (!AppUtil.canDrawOverlay(ctx)) return
            try {
                ctx.startService(Intent(ctx, FloatingService::class.java))
            } catch (e: Exception) {
                // 后台启动受限时忽略，下次巡检会重试
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, FloatingService::class.java))
            } catch (e: Exception) {
                // ignore
            }
        }

        @Volatile
        private var appForeground = false

        /** App 自己在前台时把小球藏起来，别挡着自家界面 */
        fun setAppForeground(foreground: Boolean) {
            appForeground = foreground
            val s = instance ?: return
            s.main.post { s.setVisible(!foreground) }
        }

        fun notifyNewRecord(rec: PayRecord) {
            val s = instance ?: return
            s.main.post { s.onNewRecord(rec) }
        }
    }
}


