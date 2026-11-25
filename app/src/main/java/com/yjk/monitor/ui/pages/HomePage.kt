package com.yjk.monitor.ui.pages

import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import com.yjk.monitor.core.AppUtil
import com.yjk.monitor.core.Bus
import com.yjk.monitor.core.PayRecord
import com.yjk.monitor.core.Prefs
import com.yjk.monitor.core.RecordStore
import com.yjk.monitor.service.FloatingService
import com.yjk.monitor.service.KeepAliveService
import com.yjk.monitor.ui.AppsActivity
import com.yjk.monitor.ui.MainActivity
import com.yjk.monitor.ui.ScrollPage
import com.yjk.monitor.ui.Ui

/**
 * 首页：一个「启动监控 / 停止监控」主按钮 + 状态 + 权限自检。
 * 结构对齐同类产品（开关 → 监听 → 转发），视觉保持微信风格。
 */
class HomePage(host: MainActivity) : ScrollPage(host) {

    override val title: String = "易监控"

    override fun onShow() = invalidate()

    override fun fill(parent: LinearLayout) {
        parent.addView(mainCard())
        parent.addView(permCard())
        parent.addView(keepAliveCard())
        parent.addView(runCard())
        parent.addView(tipCard())
    }

    // ---------------- 主卡片：开关 + 状态 ----------------

    private fun mainCard(): LinearLayout {
        val c = Ui.card(host, topMargin = 12)
        val inner = Ui.col(host).apply {
            setPadding(Ui.dp(host, 16f), Ui.dp(host, 16f), Ui.dp(host, 16f), Ui.dp(host, 16f))
        }
        val running = Prefs.monitorEnabled
        val granted = AppUtil.isNotificationListenerEnabled(host)
        val listening = granted && com.yjk.monitor.service.PayListenerService.isConnected()
        // 刚启动时系统还没把监听接上，别急着报"未连接"
        val warmingUp = running && granted && !listening &&
            System.currentTimeMillis() - com.yjk.monitor.App.startedAt < 20_000L

        val head = Ui.row(host)
        head.addView(
            Ui.text(
                host,
                when {
                    !running -> "监控已停止"
                    warmingUp -> "监控已开启 · 正在连接监听"
                    !listening -> "监控已开启 · 监听未连接"
                    else -> "监控运行中"
                },
                18f,
                if (running) Ui.colorText(host) else Ui.colorSub(host),
                true
            )
        )
        head.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(
            Ui.pill(
                host,
                when {
                    running && listening -> "监听中"
                    warmingUp -> "连接中"
                    running && !granted -> "待授权"
                    running -> "未连接"
                    else -> "已停止"
                },
                running && listening
            )
        )
        inner.addView(head)

        inner.addView(
            Ui.text(
                host,
                when {
                    !running -> "点下面的按钮开始监听通知栏消息"
                    !granted -> "已开启，但还缺「通知访问」权限"
                    warmingUp -> "正在等待系统把通知监听接上…"
                    !listening -> "监听服务被系统解绑了，到上面「权限」里重新授权"
                    Prefs.selectedCount() == 0 -> "已开启，但还没选择要监听的应用"
                    else -> "正在监听 ${Prefs.selectedCount()} 个应用的通知栏消息"
                },
                12f, Ui.colorSub(host)
            ).apply { setPadding(0, Ui.dp(host, 7f), 0, 0) }
        )

        // 主按钮：满宽圆角绿按钮（微信风格）
        val btn = Ui.button(host, if (running) "停止监控" else "启动监控") { toggleMonitor() }
        btn.textSize = 16f
        btn.setPadding(0, Ui.dp(host, 12f), 0, Ui.dp(host, 12f))
        if (running) {
            btn.background = Ui.rounded(host, if (Ui.isDark(host)) 0x22FFFFFF else 0x14000000, 100f)
            btn.setTextColor(Ui.colorRed(host))
        }
        inner.addView(
            btn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(host, 14f) }
        )

        val stats = RecordStore.todayStats()
        val failed = RecordStore.failedCount()
        val statsRow = Ui.row(host).apply { setPadding(0, Ui.dp(host, 14f), 0, 0) }
        statsRow.addView(Ui.text(host, "今日已转发 ${stats.count} 条", 13f, Ui.colorText(host), true))
        if (failed > 0) {
            statsRow.addView(Ui.text(host, "  ·  失败 $failed", 13f, Ui.colorRed(host), true))
        }
        statsRow.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        statsRow.addView(Ui.text(host, "已选 ${Prefs.selectedCount()} 个应用", 12f, Ui.colorSub(host)))
        inner.addView(statsRow)

        c.addView(inner)
        return c
    }

    private fun toggleMonitor() {
        val on = !Prefs.monitorEnabled
        Prefs.monitorEnabled = on
        if (on) {
            KeepAliveService.start(host)
            if (Prefs.floatingEnabled && AppUtil.canDrawOverlay(host)) FloatingService.start(host)
            toast("监控已开启")
        } else {
            toast("监控已停止")
        }
        com.yjk.monitor.push.Heartbeat.poke()
        Bus.fireStateChanged()
        invalidate()
    }

    // ---------------- 权限自检 ----------------

    private fun permCard(): LinearLayout {
        val c = Ui.card(host)
        c.addView(Ui.section(host, "权限"))

        var missing = 0
        val granted = AppUtil.isNotificationListenerEnabled(host)
        val connected = granted && com.yjk.monitor.service.PayListenerService.isConnected()
        // 非系统安装器装的包，Android 13+ 会把通知使用权/悬浮窗上锁
        if (!AppUtil.installedBySystem(host)) {
            c.addView(
                needRow(
                    "安装来源受限",
                    "这个包不是系统安装器装的（adb/第三方安装器），通知使用权和悬浮窗会被系统上锁。" +
                        "点这里打开应用信息，右上角 ⋮ → 允许受限制的设置"
                ) { ctx -> AppUtil.openAppDetails(ctx) }
            )
            missing++
        }
        if (granted && !connected) {
            // 权限还在，但系统把监听服务解绑了（force-stop / 后台清理后常见）
            c.addView(
                needRow("通知监听已断开", "系统解绑了监听服务，点这里重新授权（进去关一次再开）") { ctx ->
                    AppUtil.openNotificationAccess(ctx)
                }
            )
            missing++
        }
        if (!granted) {
            c.addView(needRow("通知访问", "没有它读不到任何通知栏消息") { ctx ->
                AppUtil.openNotificationAccess(ctx)
            })
            missing++
            if (AppUtil.mayBeRestrictedSettings(host)) {
                c.addView(needRow("被系统拒绝授权？", "Android 13+ 会限制侧载应用，点这里看解决办法") { ctx ->
                    AppUtil.openAppSettings(ctx)
                })
            }
        }
        if (!AppUtil.canDrawOverlay(host)) {
            c.addView(needRow("悬浮窗", "桌面悬浮球需要「显示在其他应用上层」") { ctx ->
                AppUtil.openOverlaySettings(ctx)
            })
            missing++
        }

        if (missing == 0) {
            c.addView(Ui.cell(host, "权限已就绪", "通知访问、悬浮窗、后台保活都正常"))
        }
        return c
    }

    /** 后台保活：ColorOS 的自启动/速冻没有公开 API，只能引导用户手动确认 */
    private fun keepAliveCard(): LinearLayout {
        val c = Ui.card(host)
        c.addView(Ui.section(host, "后台保活"))

        val batOk = AppUtil.isIgnoringBatteryOptimizations(host)
        c.addView(
            Ui.cell(
                host, "电池优化",
                if (batOk) "已允许，锁屏后不容易被清理" else "未允许，锁屏后可能被系统清理",
                null
            ) {
                if (!batOk) AppUtil.requestIgnoreBattery(host) else AppUtil.openAppDetails(host)
            }
        )
        c.addView(Ui.divider(host))

        // 自启动 / 后台限制 / 应用速冻在 ColorOS 里是同一类限制，合成一项，避免重复
        val restricted = AppUtil.isBackgroundRestricted(host)
        val accOn = AppUtil.isAccessibilityEnabled(host)
        c.addView(
            Ui.cell(
                host, "无障碍服务",
                if (accOn) "已开启，进程更稳定" else "未开启，点击去系统设置里打开（可提升存活率）",
                null
            ) {
                AppUtil.openAccessibilitySettings(host)
            }
        )
        c.addView(Ui.divider(host))

        val cnt = com.yjk.monitor.service.PayListenerService.activeCount()
        c.addView(
            Ui.cell(
                host, "通知栏读取",
                when {
                    !AppUtil.hasListenerPermission(host) -> "未授权，读不到任何通知"
                    cnt < 0 -> "读取失败，绑定可能已失效，点这里重新授权"
                    else -> "正常，当前通知栏 $cnt 条"
                },
                null
            ) {
                if (cnt < 0) AppUtil.openNotificationAccess(host)
            }
        )
        c.addView(Ui.divider(host))

        c.addView(
            Ui.cell(
                host, "自启动与后台限制",
                AppUtil.autostartHint(),
                null
            ) {
                AppUtil.openAutostartSettings(host)
            }
        )
        return c
    }

    private fun needRow(title: String, sub: String, action: (android.content.Context) -> Unit): View {
        val row = Ui.cell(
            host, title, sub,
            right = Ui.text(host, "去开启", 13f, Ui.colorBlue(host)).apply {
                setPadding(Ui.dp(host, 8f), Ui.dp(host, 4f), Ui.dp(host, 2f), Ui.dp(host, 4f))
            },
            onClick = { action(host) }
        )
        val wrap = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(row)
        wrap.addView(Ui.divider(host))
        return wrap
    }

    // ---------------- 运行设置 ----------------

    private fun runCard(): LinearLayout {
        val c = Ui.card(host)
        c.addView(Ui.section(host, "监听设置"))

        val keep = Ui.switchCell(
            host, "通知栏常驻", "用前台服务把进程挂住，减少被系统清理",
            Prefs.keepAliveOn
        ) { on ->
            Prefs.keepAliveOn = on
            if (on) KeepAliveService.start(host) else KeepAliveService.stop(host)
        }
        c.addView(keep.row)
        c.addView(Ui.divider(host))

        val float = Ui.switchCell(
            host, "桌面悬浮窗", "可拖动的小球，点开看通知/到账详情",
            Prefs.floatingEnabled
        ) { on ->
            Prefs.floatingEnabled = on
            if (on) {
                if (!AppUtil.canDrawOverlay(host)) {
                    AppUtil.openOverlaySettings(host)
                    toast("请先允许「显示在其他应用上层」")
                } else {
                    FloatingService.start(host)
                }
            } else {
                FloatingService.stop(host)
            }
        }
        c.addView(float.row)
        c.addView(Ui.divider(host))

        c.addView(
            Ui.cell(
                host,
                "监听应用",
                if (Prefs.selectedCount() == 0) "还没选择，点进去勾选微信 / 支付宝等"
                else "已选 ${Prefs.selectedCount()} 个",
                onClick = {
                    host.startActivity(android.content.Intent(host, AppsActivity::class.java))
                }
            )
        )
        return c
    }

    private fun tipCard(): LinearLayout {
        val c = Ui.card(host)
        c.addView(Ui.section(host, "使用步骤"))
        listOf(
            "1. 先把「通知访问 / 悬浮窗 / 电池优化」都开好",
            "2. 进入「监听应用」，勾选会弹通知的 App",
            "3. 到「配置」页填平台地址、商户ID、商户密钥并保存",
            "4. 回本页点「启动监控」，之后看「日志」确认转发"
        ).forEach {
            c.addView(
                Ui.text(host, it, 13f, Ui.colorSub(host)).apply {
                    setPadding(Ui.dp(host, 16f), Ui.dp(host, 6f), Ui.dp(host, 16f), Ui.dp(host, 6f))
                }
            )
        }
        c.addView(View(host).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Ui.dp(host, 10f)
            )
        })
        return c
    }

    private fun toast(msg: String) = Toast.makeText(host, msg, Toast.LENGTH_SHORT).show()
}
