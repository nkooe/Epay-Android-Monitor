package com.yjk.monitor.ui.pages

import android.view.View
import android.widget.LinearLayout
import com.yjk.monitor.BuildConfig
import com.yjk.monitor.core.AppUtil
import com.yjk.monitor.core.Prefs
import com.yjk.monitor.ui.MainActivity
import com.yjk.monitor.ui.ScrollPage
import com.yjk.monitor.ui.Ui

/** 关于页 */
class AboutPage(host: MainActivity) : ScrollPage(host) {

    override val title: String = "关于"

    // 勾选应用数、版本信息可能已变，切回本页时重建
    override fun onShow() = invalidate()

    override fun fill(parent: LinearLayout) {
        parent.addView(logoCard())
        parent.addView(infoCard())
        parent.addView(privacyCard())
    }

    private fun logoCard(): LinearLayout {
        val c = Ui.card(host, topMargin = 12)
        val col = Ui.col(host).apply {
            setPadding(0, Ui.dp(host, 22f), 0, Ui.dp(host, 22f))
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        col.addView(com.yjk.monitor.ui.Ui.icon(host, com.yjk.monitor.R.drawable.ic_launcher, 64, 0).apply {
            setColorFilter(null)
            setImageResource(com.yjk.monitor.R.drawable.ic_launcher)
        })
        col.addView(
            Ui.text(host, "易监控", 20f, Ui.colorText(host), true).apply {
                setPadding(0, Ui.dp(host, 10f), 0, 0)
                gravity = android.view.Gravity.CENTER
            }
        )
        col.addView(
            Ui.text(host, "通知栏消息监控 · 通用码支付转发", 12f, Ui.colorSub(host)).apply {
                setPadding(0, Ui.dp(host, 4f), 0, 0)
                gravity = android.view.Gravity.CENTER
            }
        )
        c.addView(col)
        return c
    }

    // 隐藏的开发者日志开关：未开启时连击 6 次开启，开启后连击 3 次关闭
    private var tapCount = 0
    private var lastTapAt = 0L

    private fun onVersionTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapAt > 1500L) tapCount = 0
        lastTapAt = now
        tapCount++

        if (!Prefs.devLog) {
            if (tapCount >= 6) {
                Prefs.devLog = true
                tapCount = 0
                toast("已开启开发者日志模式")
            }
        } else {
            if (tapCount >= 3) {
                Prefs.devLog = false
                tapCount = 0
                toast("已关闭开发者日志模式")
            }
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(host, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun infoCard(): LinearLayout {
        val c = Ui.card(host)
        c.addView(Ui.section(host, "信息"))
        c.addView(Ui.cell(host, "作者", "Coco"))
        c.addView(Ui.divider(host))
        c.addView(
            Ui.cell(host, "版本", BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）", null) {
                onVersionTap()
            }
        )
        c.addView(Ui.divider(host))
        c.addView(Ui.cell(host, "包名", BuildConfig.APPLICATION_ID))
        c.addView(Ui.divider(host))
        c.addView(Ui.cell(host, "设备", AppUtil.deviceName() + " / Android " + AppUtil.androidVersion()))
        c.addView(Ui.divider(host))
        c.addView(Ui.cell(host, "监听应用", "${Prefs.selectedCount()} 个"))
        return c
    }

    private fun privacyCard(): LinearLayout {
        val c = Ui.card(host)
        c.addView(Ui.section(host, "说明"))
        c.addView(
            Ui.text(
                host,
                "易监控只在本机读取通知栏文本，用于识别到账消息并转发到你配置的平台地址；" +
                    "不采集通讯录、短信、位置，日志默认只存在手机本地。\n\n" +
                    "请只在你自己拥有或已获授权的设备与应用上使用，并遵守微信 / 支付宝等平台的规则" +
                    "以及当地法律法规；不得用于跑分、洗钱、赌博、诈骗等违法用途。",
                12f, Ui.colorHint(host)
            ).apply {
                setPadding(Ui.dp(host, 16f), Ui.dp(host, 6f), Ui.dp(host, 16f), Ui.dp(host, 16f))
            }
        )
        c.addView(View(host).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Ui.dp(host, 4f)
            )
        })
        return c
    }
}
