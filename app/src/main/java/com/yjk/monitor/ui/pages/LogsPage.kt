package com.yjk.monitor.ui.pages

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yjk.monitor.core.PayRecord
import com.yjk.monitor.core.RecordStatus
import com.yjk.monitor.core.RecordStore
import com.yjk.monitor.push.Pusher
import com.yjk.monitor.ui.MainActivity
import com.yjk.monitor.ui.Page
import com.yjk.monitor.ui.RefreshLayout
import com.yjk.monitor.ui.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志页：每条被捕获并上报的通知；点开能看到这次上报的请求地址/报文/响应，并可重试 */
class LogsPage(host: MainActivity) : Page(host) {

    override val title: String = "日志"

    private lateinit var listView: ListView
    private lateinit var adapter: LogAdapter
    private lateinit var summary: TextView
    private lateinit var emptyView: TextView

    override fun build(): View {
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.colorBg(host))
        }
        root.addView(summaryBar())

        listView = ListView(host).apply {
            divider = ColorDrawable(Ui.colorDivider(host))
            dividerHeight = 1
            setBackgroundColor(Ui.colorCard(host))
        }
        val refreshBox = RefreshLayout(host).apply { onRefresh = { refresh() } }
        refreshBox.setContentView(listView)
        root.addView(
            refreshBox,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        emptyView = Ui.text(host, "还没有日志\n勾选应用并开启监控后，通知栏消息会出现在这里", 13f, Ui.colorHint(host)).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(host, 30f), Ui.dp(host, 60f), Ui.dp(host, 30f), Ui.dp(host, 60f))
        }
        listView.emptyView = emptyView
        root.addView(
            emptyView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        adapter = LogAdapter()
        listView.adapter = adapter
        return root
    }

    override fun onShow() = refresh()

    private fun refresh() {
        if (!::adapter.isInitialized) return
        val all = RecordStore.all()
        adapter.submit(all)
        val s = RecordStore.todayStats()
        val failed = RecordStore.failedCount()
        summary.text = "今日已转发 ${s.count} 条" +
            (if (failed > 0) " · 失败 $failed" else "") + " · 点一条看请求详情"
        emptyView.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun summaryBar(): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.colorCard(host))
            setPadding(Ui.dp(host, 14f), Ui.dp(host, 10f), Ui.dp(host, 10f), Ui.dp(host, 10f))
        }
        summary = Ui.text(host, "", 12f, Ui.colorSub(host))
        row.addView(summary, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(clearPill("清空全部"))
        return row
    }

    private fun toast(msg: String) = Toast.makeText(host, msg, Toast.LENGTH_SHORT).show()

    /** 顶部胶囊「清空全部」 */
    private fun clearPill(label: String): TextView = TextView(host).apply {
        text = label
        textSize = 12f
        setTextColor(Ui.colorRed(host))
        background = Ui.rounded(host, 0x14FF3B30, 100f)
        setPadding(Ui.dp(host, 11f), Ui.dp(host, 5f), Ui.dp(host, 11f), Ui.dp(host, 5f))
        includeFontPadding = false
        setOnClickListener { confirmClear() }
    }

    /** 长按记录：只删本地这条，不影响已上报到平台的数据 */
    private fun confirmDelete(r: PayRecord) {
        val who = r.title.ifBlank { r.payer.ifBlank { r.appName } }
        android.app.AlertDialog.Builder(host)
            .setTitle("删除这条记录？")
            .setMessage("$who\n\n只删除本地日志，不影响平台已收到的数据。")
            .setPositiveButton("删除") { _, _ ->
                RecordStore.remove(r.id)
                refresh()
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClear() {
        if (RecordStore.size() == 0) {
            toast("没有日志可清空")
            return
        }
        AlertDialog.Builder(host)
            .setTitle("清空全部日志？")
            .setMessage("共 " + RecordStore.size() + " 条，只删本地记录，不影响已上报的内容")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                RecordStore.clear()
                refresh()
                toast("已清空")
            }
            .show()
    }

    // ---------------- 请求详情 ----------------

    private fun showDetail(r: PayRecord) {
        val col = Ui.col(host).apply {
            setPadding(Ui.dp(host, 16f), Ui.dp(host, 14f), Ui.dp(host, 16f), Ui.dp(host, 8f))
        }
        col.addView(
            TextView(host).apply {
                text = "请求详情"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.colorText(host))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, Ui.dp(host, 12f))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val statusRow = Ui.row(host)
        statusRow.addView(
            Ui.text(
                host, RecordStatus.label(r.status), 14f,
                when (r.status) {
                    RecordStatus.SENT -> Ui.colorGreen(host)
                    RecordStatus.FAILED -> Ui.colorRed(host)
                    RecordStatus.PENDING -> Ui.colorOrange(host)
                    else -> Ui.colorSub(host)
                }, true
            )
        )
        statusRow.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        statusRow.addView(Ui.text(host, Pusher.fmt(r.time), 12f, Ui.colorHint(host)))
        col.addView(statusRow)

        col.addView(
            Ui.text(host, r.appName + " · " + r.title.ifBlank { r.payer }, 13f, Ui.colorSub(host))
                .apply { setPadding(0, Ui.dp(host, 6f), 0, 0) }
        )
        if (r.text.isNotBlank()) {
            col.addView(
                Ui.text(host, r.text, 13f, Ui.colorText(host)).apply {
                    setPadding(0, Ui.dp(host, 4f), 0, 0)
                    setTextIsSelectable(true)
                }
            )
        }

        val waiting = r.status == RecordStatus.PENDING
        val emptyTip = if (waiting) "（正在上报…）" else "（还没上报）"
        block(col, "请求地址", r.requestUrl.ifBlank {
            when {
                waiting -> "（正在上报…）"
                r.response.isNotBlank() -> "（未发送）"
                else -> "（还没上报）"
            }
        })
        block(col, "请求内容", prettyBody(r.requestBody).ifBlank { emptyTip })
        block(col, "响应结果", r.response.ifBlank { emptyTip })

        val sv = ScrollView(host).apply {
            addView(col)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(host, 430f)
            )
        }

        AlertDialog.Builder(host)
            .setView(sv)
            .setPositiveButton("重试") { _, _ -> retry(r) }
            .setNeutralButton("复制") { _, _ -> copyDetail(r) }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 请求内容美化：
     *  - JSON 直接缩进
     *  - 表单编码（V1 的 from=x&content=%7B...%7D&sign=...）逐项解码，content 里的 JSON 再缩进
     * 只影响显示/复制，真正发送仍用原始报文。
     */
    private fun prettyBody(body: String): String {
        val t = body.trim()
        if (t.isEmpty()) return ""
        if (t.startsWith("{")) {
            return try {
                org.json.JSONObject(t).toString(2)
            } catch (e: Exception) {
                t
            }
        }
        if (!t.contains("=") || !t.contains("&")) return t
        return try {
            t.split("&").joinToString("\n") { pair ->
                val i = pair.indexOf('=')
                if (i <= 0) pair
                else {
                    val k = pair.substring(0, i)
                    val raw = java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                    val v = raw.trim()
                    if (v.startsWith("{")) {
                        val pretty = try {
                            org.json.JSONObject(v).toString(2)
                        } catch (e: Exception) {
                            raw
                        }
                        "$k:\n$pretty"
                    } else {
                        "$k: $raw"
                    }
                }
            }
        } catch (e: Exception) {
            t
        }
    }

    private fun block(parent: LinearLayout, label: String, content: String) {
        parent.addView(
            Ui.text(host, label + "：", 12.5f, Ui.colorSub(host))
                .apply { setPadding(0, Ui.dp(host, 12f), 0, Ui.dp(host, 5f)) }
        )
        parent.addView(
            TextView(host).apply {
                text = content
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                setTextColor(Ui.colorText(host))
                setTextIsSelectable(true)
                background = Ui.rounded(host, Ui.colorInputBg(host), 8f, Ui.colorDivider(host))
                setPadding(Ui.dp(host, 10f), Ui.dp(host, 8f), Ui.dp(host, 10f), Ui.dp(host, 8f))
            }
        )
    }

    private fun retry(r: PayRecord) {
        RecordStore.markSending(r.id)
        refresh()
        Pusher.retry(r) { ok, msg ->
            toast(if (ok) "上报成功" else "上报失败：" + msg.take(80))
            refresh()
        }
        toast("正在重新上报…")
    }

    private fun copyDetail(r: PayRecord) {
        val text = buildString {
            append("时间：").append(Pusher.fmt(r.time)).append('\n')
            append("来源：").append(r.appName).append(" / ").append(r.pkg).append('\n')
            if (r.title.isNotBlank()) append("标题：").append(r.title).append('\n')
            if (r.text.isNotBlank()) append("内容：").append(r.text).append('\n')
            if (r.requestUrl.isNotBlank()) append("请求地址：").append(r.requestUrl).append('\n')
            if (r.requestBody.isNotBlank()) append("请求内容：").append(prettyBody(r.requestBody)).append('\n')
            if (r.response.isNotBlank()) append("响应结果：").append(r.response)
        }
        try {
            val cm = host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("易监控", text))
            toast("已复制")
        } catch (e: Exception) {
            toast("复制失败")
        }
    }

    private class Holder(
        val title: TextView,
        val body: TextView,
        val time: TextView,
        val status: TextView,
        val amount: TextView
    )

    private inner class LogAdapter : BaseAdapter() {

        private var items: List<PayRecord> = emptyList()
        private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
        private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

        fun submit(list: List<PayRecord>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): PayRecord = items[position]

        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: LinearLayout
            val holder: Holder

            if (convertView == null) {
                row = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Ui.colorCard(host))
                    setPadding(Ui.dp(host, 12f), Ui.dp(host, 11f), Ui.dp(host, 12f), Ui.dp(host, 11f))
                }

                val col = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, Ui.dp(host, 8f), 0)
                }
                val titleView = Ui.text(host, "", 14.5f, Ui.colorText(host), true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val bodyView = Ui.text(host, "", 12.5f, Ui.colorSub(host)).apply {
                    setPadding(0, Ui.dp(host, 5f), 0, 0)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                col.addView(titleView)
                col.addView(bodyView)
                row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                val right = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                }
                val timeView = Ui.text(host, "", 11f, Ui.colorHint(host))
                val statusView = Ui.text(host, "", 10.5f, Ui.colorSub(host)).apply {
                    setPadding(0, Ui.dp(host, 4f), 0, 0)
                }
                val amountView = Ui.text(host, "", 11.5f, Ui.colorGreen(host), true).apply {
                    setPadding(0, Ui.dp(host, 3f), 0, 0)
                    visibility = View.GONE
                }
                right.addView(timeView)
                right.addView(statusView)
                right.addView(amountView)
                row.addView(right)

                holder = Holder(titleView, bodyView, timeView, statusView, amountView)
                row.tag = holder
            } else {
                row = convertView as LinearLayout
                holder = row.tag as Holder
            }

            val r = items[position]

            // 记录条 = 谁发的 + 说了什么（对齐同类产品，不展示金额块）
            val who = when {
                r.title.isNotBlank() && r.title != r.appName -> r.title
                r.payer.isNotBlank() -> r.payer
                else -> r.appName
            }
            holder.title.text = if (who == r.appName) r.appName else r.appName + "  ·  " + who
            holder.body.text = r.text.replace("\n", "  ").ifBlank { r.title }

            val now = System.currentTimeMillis()
            holder.time.text = if (now - r.time < 24L * 3600 * 1000) timeFmt.format(Date(r.time))
            else dateFmt.format(Date(r.time))

            if (r.hasAmount) {
                holder.amount.visibility = View.VISIBLE
                holder.amount.text = "¥" + r.amountText
            } else {
                holder.amount.visibility = View.GONE
            }

            holder.status.text = RecordStatus.label(r.status)
            holder.status.setTextColor(
                when (r.status) {
                    RecordStatus.SENT -> Ui.colorGreen(host)
                    RecordStatus.FAILED -> Ui.colorRed(host)
                    RecordStatus.PENDING -> Ui.colorOrange(host)
                    else -> Ui.colorHint(host)
                }
            )

            row.setOnClickListener { showDetail(r) }
            row.setOnLongClickListener {
                confirmDelete(r)
                true
            }
            return row
        }
    }
}
