package com.yjk.monitor.ui.pages

import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.yjk.monitor.core.Bus
import com.yjk.monitor.core.AppUtil
import com.yjk.monitor.core.Prefs
import com.yjk.monitor.service.KeepAliveService
import com.yjk.monitor.ui.AppsActivity
import com.yjk.monitor.ui.MainActivity
import com.yjk.monitor.ui.ScrollPage
import com.yjk.monitor.ui.Ui

/**
 * 配置页：域名 / PID / Token 三样分开填，上报地址由程序自动拼接。
 * 扫码只回填这三项，不再塞一整条地址。
 */
class ConfigPage(host: MainActivity) : ScrollPage(host) {

    override val title: String = "配置"

    private var siteField: EditText? = null
    private var pidField: EditText? = null
    private var tokenField: EditText? = null
    private var previewView: TextView? = null
    /** 接口模式下拉框：切换时只刷新文字，不重建页面（否则未保存的输入会丢） */
    private var apiDropdown: TextView? = null
    /** 上次构建时监控是否开启：状态变了要重建页面，好让心跳开关的可用状态跟着变 */
    private var lastMonitorState: Boolean = false
    /** 心跳最近一次结果（每 3 秒刷新文本） */
    private var hbStatusView: TextView? = null
    private var hbTicker: Runnable? = null
    private val hbHandler = Handler(Looper.getMainLooper())

    override fun onHide() {
        stopHbTicker()
    }

    /** 清除方式：只清本条 / 清全部 */
    private fun chooseClearMode() {
        val names = arrayOf("只清除被转发的那条通知", "清除所有应用的通知")
        AlertDialog.Builder(host)
            .setTitle("清除方式")
            .setSingleChoiceItems(names, Prefs.clearNotifyMode) { d, which ->
                Prefs.clearNotifyMode = which
                d.dismiss()
                toast(if (which == 1) "将清除所有应用的通知" else "只清除被转发的那条")
                invalidate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 通知获取规则：逗号分隔的关键词，命中才转发；留空=全部转发 */
    private fun showKeywordDialog() {
        val input = EditText(host).apply {
            setText(Prefs.keywords)
            hint = "用中文逗号分隔，例如：微信支付收款，收款到账"
            setTextSize(13f)
            setPadding(Ui.dp(host, 14f), Ui.dp(host, 10f), Ui.dp(host, 14f), Ui.dp(host, 10f))
        }
        AlertDialog.Builder(host)
            .setTitle("通知获取规则")
            .setMessage("只有包含下列任一关键词的通知才转发；留空表示全部通知都转发")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                Prefs.keywords = input.text.toString().trim()
                toast("已保存，立即生效")
                invalidate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun fill(parent: LinearLayout) {
        lastMonitorState = Prefs.monitorEnabled
        parent.addView(platformCard())
        parent.addView(systemCard())
        parent.addView(toolsCard())
    }

    // ---------------- 平台配置 ----------------

    private fun platformCard(): LinearLayout {
        val c = Ui.card(host, topMargin = 12)
        c.addView(apiModeRow())
        c.addView(Ui.divider(host))

        siteField = fieldRow(c, "平台域名", "https://pay.example.com（结尾不用加 /）", Prefs.site) {
            refreshPreview()
        }
        pidField = fieldRow(c, "PID", "平台分配的应用编号", Prefs.pid) {
            refreshPreview()
        }
        tokenField = fieldRow(c, "密钥", "平台分配的上报密钥", Prefs.token) {
            refreshPreview()
        }

        previewView = Ui.text(host, "", 11.5f, Ui.colorHint(host)).apply {
            setPadding(Ui.dp(host, 16f), Ui.dp(host, 4f), Ui.dp(host, 16f), 0)
        }
        c.addView(previewView!!)
        refreshPreview()

        // 扫一扫 + 保存配置（扫码在保存前面）
        val row = Ui.row(host).apply {
            gravity = Gravity.END
            setPadding(Ui.dp(host, 16f), Ui.dp(host, 10f), Ui.dp(host, 16f), Ui.dp(host, 16f))
        }
        row.addView(
            Ui.button(host, "扫一扫", filled = false) { startScan() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = Ui.dp(host, 8f) }
        )
        row.addView(Ui.button(host, "保存配置") { save() })
        c.addView(row)
        return c
    }

    private fun fieldRow(
        parent: LinearLayout,
        label: String,
        hint: String,
        value: String,
        onChange: (String) -> Unit
    ): EditText {
        val col = Ui.col(host).apply {
            setPadding(Ui.dp(host, 16f), Ui.dp(host, 10f), Ui.dp(host, 16f), Ui.dp(host, 4f))
        }
        col.addView(Ui.text(host, label, 13f, Ui.colorSub(host)))
        val et = Ui.input(host, hint, value)
        et.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(host, 5f) }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                // 程序回填时不要写回，避免覆盖刚导入的值
                if (et.tag == "loading") return
                onChange(s?.toString() ?: "")
            }

            override fun afterTextChanged(s: Editable?) {}
        })
        col.addView(et)
        parent.addView(col)
        return et
    }

    /** 预览按输入框当前内容算（不写 Prefs） */
    private fun refreshPreview() {
        val v = previewView ?: return
        val s = siteField?.text?.toString()?.trim()?.trimEnd('/') ?: ""
        val p = pidField?.text?.toString()?.trim() ?: ""
        val t = tokenField?.text?.toString()?.trim() ?: ""
        if (Prefs.apiMode == 1) {
            val err = Prefs.apiRouteError()
            val routes = Prefs.apiRoutes()
            v.text = when {
                err != null -> "上报地址：$err"
                s.isEmpty() -> "上报地址：填好域名后自动生成"
                routes == null -> "上报地址：PID 还没填"
                else -> routes.joinToString("\n") { "上报地址：$s/api/pay/${it.channelId}/notify（${it.from}）" }
            }
            return
        }
        // X-YPay 只认单个 PID；逗号是 V1 的多通道写法，混用会拼出平台不认的地址
        if (p.contains(",") || p.contains("，")) {
            v.text = "上报地址：请输入正确的PID编号"
            return
        }
        val url = when {
            s.isEmpty() -> ""
            p.isEmpty() -> s
            else -> s + "/api/report/" + p + if (t.isEmpty()) "" else "?token=" + t
        }
        v.text = if (url.isEmpty()) "上报地址：填好域名后自动生成" else "上报地址：$url"
    }

    private fun save() {
        val s = siteField?.text?.toString()?.trim()?.trimEnd('/') ?: ""
        if (s.isEmpty()) {
            toast("先填平台域名")
            return
        }
        Prefs.site = s
        Prefs.pid = pidField?.text?.toString()?.trim() ?: ""
        Prefs.token = tokenField?.text?.toString()?.trim() ?: ""
        android.util.Log.d("YJK", "save site=" + Prefs.site + " pid=" + Prefs.pid + " tokenLen=" + Prefs.token.length)
        Bus.fireStateChanged()
        toast("配置已保存")
    }

    // ---------------- 扫码 ----------------

    private fun startScan() {
        try {
            host.startActivityForResult(
                android.content.Intent(host, com.yjk.monitor.ui.ScanActivity::class.java), 1001
            )
        } catch (e: Exception) {
            toast("打不开扫码页：" + (e.message ?: ""))
        }
    }

    fun onScanResult(text: String) {
        if (com.yjk.monitor.core.QrConfig.apply(text)) {
            // 直接按 Prefs 重建页面，避免填到已被替换的旧输入框上
            invalidate()
            toast("配置已导入，记得点保存配置")
        } else {
            toast("二维码内容不认识：" + text.take(40))
        }
        invalidate()
    }

    private fun startHbTicker() {
        stopHbTicker()
        val r = object : Runnable {
            override fun run() {
                refreshHbStatus()
                hbHandler.postDelayed(this, 3000L)
            }
        }
        hbTicker = r
        hbHandler.post(r)
    }

    private fun stopHbTicker() {
        hbTicker?.let { hbHandler.removeCallbacks(it) }
        hbTicker = null
    }

    private fun refreshHbStatus() {
        val v = hbStatusView ?: return
        when {
            !Prefs.heartbeatOn -> {
                v.text = "心跳未开启"
                v.setTextColor(Ui.colorHint(host))
            }
            !Prefs.monitorEnabled -> {
                v.text = "未启动监控，心跳不上报"
                v.setTextColor(Ui.colorHint(host))
            }
            com.yjk.monitor.push.Heartbeat.lastTime == 0L -> {
                v.text = "等待首次上报…"
                v.setTextColor(Ui.colorHint(host))
            }
            else -> {
                val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                    .format(java.util.Date(com.yjk.monitor.push.Heartbeat.lastTime))
                if (com.yjk.monitor.push.Heartbeat.lastOk) {
                    v.text = "最近 $t 上报成功（在线）"
                    v.setTextColor(Ui.colorGreen(host))
                } else {
                    v.text = "最近 $t 失败 · " +
                        com.yjk.monitor.push.Heartbeat.lastText.take(40)
                    v.setTextColor(Ui.colorRed(host))
                }
            }
        }
    }

    override fun onShow() {
        // 整页重建：勾选应用可能在别的页面被改过，只刷输入框会导致「监听应用」显示旧值
        invalidate()
        fillFields()
        refreshPreview()
        refreshHbStatus()
        startHbTicker()
        // 在首页开了/关了监控后回来，心跳开关的可用状态要跟着刷新
        if (lastMonitorState != Prefs.monitorEnabled) {
            lastMonitorState = Prefs.monitorEnabled
            invalidate()
        }
    }

    private fun fillFields() {
        setField(siteField, Prefs.site)
        setField(pidField, Prefs.pid)
        setField(tokenField, Prefs.token)
    }

    private fun setField(et: EditText?, value: String) {
        et ?: return
        et.tag = "loading"
        et.setText(value)
        et.tag = null
    }

    // ---------------- 系统设置 ----------------

    // ---------------- 转发规则（原「上报说明」位置） ----------------

    private fun toolsCard(): LinearLayout {
        val c = Ui.card(host)
        c.addView(Ui.section(host, "转发规则"))

        // 发送测试通知
        c.addView(
            Ui.cell(
                host,
                "发送测试通知",
                "往通知栏发一条模拟收款，走完整上报流程，可在日志里看结果",
                null
            ) {
                com.yjk.monitor.service.Notifier.testNotification(host)
                toast("已发送测试通知，去日志看结果")
            }
        )
        c.addView(Ui.divider(host))

        // 关键词过滤开关：关掉 = 全部通知都转发
        val kwOn = Prefs.keywordFilterOn
        val kwCell = Ui.switchCell(
            host, "关键词过滤",
            if (kwOn) "只转发命中关键词的通知" else "已关闭，全部通知都转发",
            kwOn
        ) { on ->
            Prefs.keywordFilterOn = on
            toast(if (on) "已开启关键词过滤" else "已关闭，全部通知都会转发")
            invalidate()
        }
        c.addView(kwCell.row)

        // 过滤关闭时，关键词配置没有意义，整行不显示
        if (kwOn) {
            c.addView(Ui.divider(host))
            val kws = Prefs.keywordList()
            c.addView(
                Ui.cell(
                    host,
                    "关键词配置",
                    if (kws.isEmpty()) "未设置关键词，将转发全部通知"
                    else kws.take(4).joinToString("，") +
                        (if (kws.size > 4) " 等 ${kws.size} 个" else ""),
                    null
                ) {
                    showKeywordDialog()
                }
            )
        }

        // 清除通知：转发后把通知栏那条清掉，避免堆叠
        val clearOn = Prefs.clearNotifyOn
        val clearCell = Ui.switchCell(
            host, "转发后清除通知",
            if (clearOn) "已开启：转发成功会清掉通知栏对应消息" else "关闭：通知栏消息保留",
            clearOn
        ) { on ->
            Prefs.clearNotifyOn = on
            toast(if (on) "已开启，转发后会清除通知" else "已关闭，通知会保留")
            invalidate()
        }
        c.addView(clearCell.row)

        if (clearOn) {
            c.addView(Ui.divider(host))
            c.addView(
                Ui.cell(
                    host,
                    "清除方式",
                    if (Prefs.clearNotifyMode == 1) "清除所有应用的通知" else "只清除被转发的那条通知",
                    null
                ) {
                    chooseClearMode()
                }
            )
        }
        c.addView(Ui.divider(host))

        return c
    }

    private fun systemCard(): LinearLayout {
        val c = Ui.card(host)
        c.addView(Ui.section(host, "系统设置"))

        c.addView(Ui.cell(host, "主题设置", Prefs.themeModeName(), onClick = { chooseTheme() }))
        c.addView(Ui.divider(host))

        val boot = Ui.switchCell(
            host, "开机自启", "重启手机后自动恢复监听（系统还需允许本应用自启动）",
            AppUtil.isBootReceiverEnabled(host)
        ) { on ->
            Prefs.bootEnabled = on
            AppUtil.setBootReceiverEnabled(host, on)
        }
        c.addView(boot.row)
        c.addView(Ui.divider(host))

        // 心跳只能在监控运行中开启
        var hbRef: Ui.SwitchCell? = null
        val monitoring = Prefs.monitorEnabled
        val hb = Ui.switchCell(
            host, "上报心跳",
            if (monitoring) "每 5 秒上报一次在线状态（服务端 15 秒收不到判离线）"
            else "需要先在首页启动监控，才能开启心跳",
            Prefs.heartbeatOn && monitoring
        ) { on ->
            if (on && !Prefs.monitorEnabled) {
                toast("请先在首页启动监控")
                hbRef?.switch?.isChecked = false
            } else {
                Prefs.heartbeatOn = on
                if (on) com.yjk.monitor.push.Heartbeat.start()
                else com.yjk.monitor.push.Heartbeat.stop()
            }
        }
        hbRef = hb
        hb.switch.isEnabled = monitoring
        hb.switch.alpha = if (monitoring) 1f else 0.45f
        c.addView(hb.row)

        val hbStatus = Ui.text(host, "", 11.5f, Ui.colorHint(host)).apply {
            setPadding(Ui.dp(host, 14f), 0, Ui.dp(host, 14f), Ui.dp(host, 10f))
        }
        hbStatusView = hbStatus
        c.addView(hbStatus)
        c.addView(Ui.divider(host))

        c.addView(
            Ui.cell(
                host,
                "监听应用",
                if (Prefs.selectedCount() == 0) "还没有选择应用"
                else "已选 " + Prefs.selectedCount() + " 个",
                onClick = { host.startActivity(android.content.Intent(host, AppsActivity::class.java)) }
            )
        )
        return c
    }

    /** 一行：左边「接口模式」，右边下拉框，点击展开 */
    private fun apiModeRow(): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(host, 16f), Ui.dp(host, 10f), Ui.dp(host, 14f), Ui.dp(host, 10f))
        }
        val label = TextView(host).apply {
            text = "接口模式"
            textSize = 15.5f
            setTextColor(Ui.colorText(host))
        }
        row.addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val dd = Ui.dropdown(host, Prefs.apiModeName())
        apiDropdown = dd
        dd.setOnClickListener { anchor -> showApiModePopup(anchor) }
        val lp = LinearLayout.LayoutParams(0, Ui.dp(host, 32f), 1f).apply {
            marginStart = Ui.dp(host, 16f)
        }
        row.addView(dd, lp)
        return row
    }

    private fun showApiModePopup(anchor: View) {
        val names = arrayOf("X-YPay", "码支付V1")
        val green = Ui.colorGreen(host)
        val popup = android.widget.ListPopupWindow(host)
        popup.anchorView = anchor
        popup.width = anchor.width
        popup.height = android.widget.ListPopupWindow.WRAP_CONTENT
        popup.isModal = true
        popup.verticalOffset = Ui.dp(host, 4f)
        popup.setBackgroundDrawable(
            Ui.rounded(host, Ui.colorCard(host), 10f, Ui.colorDivider(host))
        )
        popup.setAdapter(object : android.widget.BaseAdapter() {
            override fun getCount(): Int = names.size
            override fun getItem(position: Int): Any = names[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                val selected = position == Prefs.apiMode
                val row = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(Ui.dp(host, 14f), Ui.dp(host, 12f), Ui.dp(host, 14f), Ui.dp(host, 12f))
                    if (selected) {
                        background = Ui.rounded(host, 0x1407C160, 8f)
                    }
                }
                val tv = TextView(host).apply {
                    text = names[position]
                    textSize = 14f
                    setTextColor(if (selected) green else Ui.colorText(host))
                }
                row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (selected) {
                    val tick = TextView(host).apply {
                        text = "✓"
                        textSize = 14f
                        setTextColor(green)
                    }
                    row.addView(tick)
                }
                return row
            }
        })
        popup.setOnItemClickListener { _, _, position, _ ->
            Prefs.apiMode = position
            popup.dismiss()
            // 只刷新下拉框文字和预览，不重建页面，保留用户已输入的域名/PID/密钥
            apiDropdown?.text = Prefs.apiModeName() + "  ▾"
            refreshPreview()
        }
        popup.show()
    }

    private fun chooseApiMode() {
        val names = arrayOf("X-YPay", "码支付V1")
        AlertDialog.Builder(host)
            .setTitle("接口模式")
            .setSingleChoiceItems(names, Prefs.apiMode) { d, which ->
                Prefs.apiMode = which
                d.dismiss()
                invalidate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun chooseTheme() {
        val names = arrayOf("跟随系统", "浅色", "深色")
        AlertDialog.Builder(host)
            .setTitle("主题设置")
            .setSingleChoiceItems(names, Prefs.themeMode) { d, which ->
                Prefs.themeMode = which
                d.dismiss()
                host.recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 说明 ----------------

    private fun helpCard(): LinearLayout {
        val c = Ui.card(host)
        c.addView(Ui.section(host, "上报说明"))
        c.addView(
            Ui.text(
                host,
                "上报地址由程序拼接：\n" +
                    "域名 + /api/report/ + PID + ?token= + Token\n\n" +
                    "请求体：\n" +
                    "{\"from\":\"android\",\"content\":\"{title,msg,package_name," +
                    "receive_time,device_name}\",\"timestamp\":毫秒,\"sign\":\"\"}\n\n" +
                    "content 里的 title 是发送者，msg 是通知正文；" +
                    "平台返回 code=0/200 视为成功，其余记为失败，可在日志里点开看请求与响应。",
                12f, Ui.colorSub(host)
            ).apply {
                setPadding(Ui.dp(host, 16f), Ui.dp(host, 4f), Ui.dp(host, 16f), Ui.dp(host, 16f))
            }
        )
        return c
    }

    private fun toast(msg: String) = Toast.makeText(host, msg, Toast.LENGTH_SHORT).show()
}
