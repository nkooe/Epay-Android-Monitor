package com.yjk.monitor.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.yjk.monitor.R
import com.yjk.monitor.core.AppEntry
import com.yjk.monitor.core.AppUtil
import com.yjk.monitor.core.Prefs

/** 监听应用：勾选哪些 App 的通知需要被捕获 */
class AppsActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var adapter: AppAdapter
    private lateinit var countView: TextView
    private var chipHost: LinearLayout? = null
    @Volatile
    private var loading = false
    private lateinit var emptyTip: TextView

    private var all: List<AppEntry> = emptyList()
    private var query: String = ""
    private var onlySelected = false
    private var showSystem = false

    private val quickApps = listOf(
        "com.tencent.mm" to "微信",
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.unionpay" to "云闪付",
        "com.tencent.mobileqq" to "QQ"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.statusBarColor = getColor(R.color.wx_status_bar)
        } catch (e: Exception) {
            // ignore
        }
        setContentView(buildRoot())
        load()
    }

    override fun onResume() {
        super.onResume()
        // 系统刚弹过「读取应用列表」授权框：回到页面时列表若还是空的，自动重扫一次
        if (all.isEmpty()) load()
        // 本页面也属于"自家界面"，进前台同样隐藏悬浮球
        com.yjk.monitor.service.FloatingService.setAppForeground(true)
    }

    override fun onPause() {
        com.yjk.monitor.service.FloatingService.setAppForeground(false)
        super.onPause()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.colorBg(this@AppsActivity))
        }
        root.addView(topBar())
        root.addView(searchBar())
        root.addView(quickRow())
        root.addView(toolRow())

        listView = ListView(this).apply {
            divider = ColorDrawable(Ui.colorDivider(this@AppsActivity))
            dividerHeight = 1
            setBackgroundColor(Ui.colorCard(this@AppsActivity))
            isFastScrollEnabled = true
        }
        adapter = AppAdapter()
        listView.adapter = adapter
        root.addView(
            listView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return root
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.wx_green))
            setPadding(Ui.dp(this@AppsActivity, 12f), 0, Ui.dp(this@AppsActivity, 12f), 0)
        }
        bar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            Ui.dp(this@AppsActivity, 48f)
        )
        bar.addView(Ui.text(this, "‹", 26f, Color.WHITE).apply {
            setPadding(Ui.dp(this@AppsActivity, 4f), 0, Ui.dp(this@AppsActivity, 12f), Ui.dp(this@AppsActivity, 4f))
            setOnClickListener { finish() }
        })
        bar.addView(
            Ui.text(this, "监听应用", 17f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(Ui.text(this, "完成", 14f, Color.WHITE).apply {
            setPadding(Ui.dp(this@AppsActivity, 8f), Ui.dp(this@AppsActivity, 6f), 0, Ui.dp(this@AppsActivity, 6f))
            setOnClickListener { finish() }
        })
        return bar
    }

    private fun searchBar(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.colorCard(this@AppsActivity))
            setPadding(Ui.dp(this@AppsActivity, 10f), Ui.dp(this@AppsActivity, 8f), Ui.dp(this@AppsActivity, 12f), Ui.dp(this@AppsActivity, 8f))
        }
        val et = Ui.input(this, "搜索应用名 / 包名", "")
        et.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                query = s?.toString() ?: ""
                applyFilter()
            }

            override fun afterTextChanged(s: Editable?) {}
        })
        wrap.addView(et)
        countView = Ui.text(this, "已选 0", 11f, Ui.colorSub(this@AppsActivity)).apply {
            setPadding(Ui.dp(this@AppsActivity, 10f), 0, 0, 0)
        }
        wrap.addView(countView)
        return wrap
    }

    private fun quickRow(): View {
        val sc = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Ui.colorCard(this@AppsActivity))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(this@AppsActivity, 12f), 0, Ui.dp(this@AppsActivity, 12f), Ui.dp(this@AppsActivity, 8f))
        }
        chipHost = row
        fillChips(row)
        sc.addView(row)
        return sc
    }

    private fun fillChips(row: LinearLayout) {
        row.removeAllViews()
        for ((pkg, label) in quickApps) {
            if (!isInstalled(pkg)) continue
            val on = Prefs.isSelected(pkg)
            val chip = TextView(this).apply {
                text = label
                textSize = 12.5f
                setTextColor(if (on) Color.WHITE else Ui.colorText(this@AppsActivity))
                background = if (on) Ui.rounded(this@AppsActivity, Ui.colorGreen(this@AppsActivity), 8f)
                else Ui.rounded(this@AppsActivity, 0x14000000, 100f)
                setPadding(Ui.dp(this@AppsActivity, 12f), Ui.dp(this@AppsActivity, 6f), Ui.dp(this@AppsActivity, 12f), Ui.dp(this@AppsActivity, 6f))
                includeFontPadding = false
                setOnClickListener {
                    Prefs.setSelected(pkg, !Prefs.isSelected(pkg))
                    chipHost?.let { fillChips(it) }
                    applyFilter()
                }
            }
            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = Ui.dp(this@AppsActivity, 8f) }
            )
        }
    }

    private fun toolRow(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.colorCard(this@AppsActivity))
        }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@AppsActivity, 12f), 0, Ui.dp(this@AppsActivity, 8f), Ui.dp(this@AppsActivity, 6f))
        }
        emptyTip = Ui.text(this, "没读到应用列表？点这里重新扫描", 12.5f, Ui.colorRed(this@AppsActivity)).apply {
            setPadding(Ui.dp(this@AppsActivity, 14f), Ui.dp(this@AppsActivity, 14f), 0, Ui.dp(this@AppsActivity, 10f))
            visibility = View.GONE
            setOnClickListener { load() }
        }
        r.addView(smallBtn("全选") {
            var n = 0
            adapter.items.forEach { Prefs.setSelected(it.pkg, true); n++ }
            applyFilter()
            toast("已勾选 $n 个")
        })
        r.addView(smallBtn("清空") {
            Prefs.setSelectedPackages(emptySet())
            applyFilter()
            toast("已清空")
        })
        r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        r.addView(checkBox("仅看已选", onlySelected) { onlySelected = it; applyFilter() })
        r.addView(checkBox("系统应用", showSystem) { showSystem = it; applyFilter() })
        wrap.addView(r)
        if (::emptyTip.isInitialized) wrap.addView(emptyTip)
        wrap.addView(View(this).apply {
            setBackgroundColor(Ui.colorDivider(this@AppsActivity))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })
        return wrap
    }

    private fun smallBtn(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12.5f
        setTextColor(Ui.colorGreen(this@AppsActivity))
        background = Ui.rounded(this@AppsActivity, 0x1A07C160, 100f)
        setPadding(Ui.dp(this@AppsActivity, 11f), Ui.dp(this@AppsActivity, 5f), Ui.dp(this@AppsActivity, 11f), Ui.dp(this@AppsActivity, 5f))
        includeFontPadding = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = Ui.dp(this@AppsActivity, 8f) }
    }

    private fun checkBox(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox =
        CheckBox(this).apply {
            text = label
            textSize = 12f
            setTextColor(Ui.colorSub(this@AppsActivity))
            isChecked = checked
            setPadding(0, 0, 0, 0)
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }

    // ---------------- 数据 ----------------

    private fun load() {
        if (loading) return
        loading = true
        Thread {
            val list = try {
                AppUtil.installedApps(this, true)
            } catch (e: Throwable) {
                android.util.Log.e("YJK", "installedApps failed", e)
                emptyList()
            }
            android.util.Log.d("YJK", "installedApps -> ${list.size}")
            runOnUiThread {
                all = list
                loading = false
                applyFilter()
            }
        }.start()
    }

    private fun applyFilter() {
        if (!::adapter.isInitialized) return
        var list = all
        if (!showSystem) list = list.filter { !it.system }
        if (onlySelected) list = list.filter { Prefs.isSelected(it.pkg) }
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            list = list.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
        }
        adapter.submit(list)
        android.util.Log.d("YJK", "applyFilter items=${list.size} all=${all.size} showSystem=$showSystem")
        updateCount()
        if (::emptyTip.isInitialized) {
            emptyTip.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun updateCount() {
        if (!::countView.isInitialized) return
        val total = if (::adapter.isInitialized) adapter.items.size else 0
        countView.text = "共 $total · 已选 ${Prefs.selectedCount()}"
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private class Holder(
        val icon: ImageView,
        val title: TextView,
        val sub: TextView,
        val cb: CheckBox
    )

    private inner class AppAdapter : BaseAdapter() {

        var items: List<AppEntry> = emptyList()
            private set

        fun submit(list: List<AppEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): AppEntry = items[position]

        override fun getItemId(position: Int): Long = items[position].pkg.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: LinearLayout
            val holder: Holder

            if (convertView == null) {
                row = LinearLayout(this@AppsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Ui.colorCard(this@AppsActivity))
                    setPadding(Ui.dp(this@AppsActivity, 14f), Ui.dp(this@AppsActivity, 9f), Ui.dp(this@AppsActivity, 10f), Ui.dp(this@AppsActivity, 9f))
                }
                val icon = ImageView(this@AppsActivity)
                row.addView(icon, LinearLayout.LayoutParams(Ui.dp(this@AppsActivity, 38f), Ui.dp(this@AppsActivity, 38f)))
                val col = LinearLayout(this@AppsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(Ui.dp(this@AppsActivity, 10f), 0, 0, 0)
                }
                val titleView = Ui.text(this@AppsActivity, "", 15f, Ui.colorText(this@AppsActivity))
                val subView = Ui.text(this@AppsActivity, "", 11f, Ui.colorSub(this@AppsActivity)).apply {
                    setPadding(0, Ui.dp(this@AppsActivity, 3f), 0, 0)
                }
                col.addView(titleView)
                col.addView(subView)
                row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val cb = CheckBox(this@AppsActivity).apply {
                    isClickable = false
                    isFocusable = false
                }
                row.addView(cb, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                holder = Holder(icon, titleView, subView, cb)
                row.tag = holder
            } else {
                row = convertView as LinearLayout
                holder = row.tag as Holder
            }

            val item = items[position]
            holder.title.text = item.label
            holder.sub.text = item.pkg + if (item.system) "  · 系统" else ""
            val drawable = AppUtil.iconOf(this@AppsActivity, item.pkg)
            if (drawable != null) holder.icon.setImageDrawable(drawable)
            else holder.icon.setImageResource(R.drawable.ic_stat)
            holder.cb.isChecked = Prefs.isSelected(item.pkg)
            row.setOnClickListener {
                Prefs.setSelected(item.pkg, !Prefs.isSelected(item.pkg))
                holder.cb.isChecked = Prefs.isSelected(item.pkg)
                updateCount()
            }
            return row
        }
    }
}
