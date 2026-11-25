package com.yjk.monitor.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局配置。
 * 所有用户可改的设置都集中在这里，UI 只负责读写，业务层只负责读。
 */
object Prefs {

    private const val FILE = "yjk_config"
    private lateinit var sp: SharedPreferences

    const val K_SELECTED = "selected_apps"
    const val K_CHANNEL = "channel"
    const val K_ENDPOINT = "endpoint"
    const val K_SECRET = "secret"
    const val K_EXTRA = "extra"
    const val K_TEMPLATE = "template"

    private const val K_MONITOR = "monitor_on"
    private const val K_PUSH = "push_on"
    private const val K_FLOATING = "floating_on"
    private const val K_NOTIFY = "notify_on"
    private const val K_BOOT = "boot_on"
    private const val K_MIN_AMOUNT = "min_amount"
    private const val K_DEDUPE = "dedupe_sec"
    private const val K_ALLOW_NO_AMOUNT = "allow_no_amount"
    private const val K_CAPTURE_MODE = "capture_mode"
    private const val K_SITE = "site"
    private const val K_PID = "pid"
    private const val K_TOKEN = "token"
    private const val K_THEME = "theme_mode"
    private const val K_KEEPALIVE = "keepalive_on"
    private const val K_HEARTBEAT = "heartbeat_on"
    private const val K_KEYWORDS = "keywords"
    private const val K_KEYWORD_ON = "keyword_filter_on"
    private const val K_CLEAR_ON = "clear_notify_on"
    private const val K_CLEAR_MODE = "clear_notify_mode"
    private const val K_DEV_LOG = "dev_log"
    private const val K_API_MODE = "api_mode"
    private const val K_EXCLUDE = "exclude_keywords"
    private const val K_QUIET_ON = "quiet_on"
    private const val K_QUIET_START = "quiet_start"
    private const val K_QUIET_END = "quiet_end"
    private const val K_LIMIT = "record_limit"
    private const val K_RETRY = "retry"
    private const val K_TIMEOUT = "timeout_sec"

    const val MODE_ALL = 0
    const val MODE_KEYWORD = 1
    const val MODE_PAYMENT = 2

    const val DEFAULT_KEYWORDS = "微信收款助手,微信支付收款,二维码赞赏到账,店员消息,收款单到账,个人收款码到账,收款通知,微信收款商业版,对外收款,收钱码,店员通,已转入余额,你已成功收款,通过扫码向你付款,成功收款"
    const val DEFAULT_EXCLUDE = "退款,优惠,红包,提现,扣款,失败,已退还,验证码,账单,还款"

    fun init(ctx: Context) {
        if (!this::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    // ---------- 基础读写 ----------
    private fun edit(block: SharedPreferences.Editor.() -> Unit) = sp.edit().apply(block).apply()

    fun str(key: String, def: String = ""): String = sp.getString(key, def) ?: def
    fun putStr(key: String, value: String) = edit { putString(key, value) }

    fun bool(key: String, def: Boolean = false): Boolean = sp.getBoolean(key, def)
    fun putBool(key: String, value: Boolean) = edit { putBoolean(key, value) }

    fun int(key: String, def: Int = 0): Int = sp.getInt(key, def)
    fun putInt(key: String, value: Int) = edit { putInt(key, value) }

    fun float(key: String, def: Float = 0f): Float = sp.getFloat(key, def)
    fun putFloat(key: String, value: Float) = edit { putFloat(key, value) }

    // ---------- 勾选的应用 ----------
    fun selectedPackages(): MutableSet<String> =
        (sp.getStringSet(K_SELECTED, emptySet()) ?: emptySet()).toMutableSet()

    fun setSelectedPackages(pkgs: Set<String>) = edit { putStringSet(K_SELECTED, HashSet(pkgs)) }

    fun isSelected(pkg: String): Boolean = selectedPackages().contains(pkg)

    fun setSelected(pkg: String, on: Boolean) {
        val s = selectedPackages()
        if (on) s.add(pkg) else s.remove(pkg)
        setSelectedPackages(s)
    }

    fun selectedCount(): Int = selectedPackages().size

    // ---------- 开关 ----------
    var monitorEnabled: Boolean
        get() = bool(K_MONITOR, true)
        set(v) = putBool(K_MONITOR, v)

    var pushEnabled: Boolean
        get() = bool(K_PUSH, true)
        set(v) = putBool(K_PUSH, v)

    var floatingEnabled: Boolean
        get() = bool(K_FLOATING, true)
        set(v) = putBool(K_FLOATING, v)

    /** 到账时是否发一条本地通知提醒（和推送通道独立） */
    var notifyEnabled: Boolean
        get() = bool(K_NOTIFY, true)
        set(v) = putBool(K_NOTIFY, v)

    /** 主题：0 跟随系统 / 1 浅色 / 2 深色 */
    var themeMode: Int
        get() = int(K_THEME, 0)
        set(v) = putInt(K_THEME, v.coerceIn(0, 2))

    fun themeModeName(): String = when (themeMode) {
        1 -> "浅色"
        2 -> "深色"
        else -> "跟随系统"
    }

    /** 通知栏常驻（前台服务） */
    var keepAliveOn: Boolean
        get() = bool(K_KEEPALIVE, true)
        set(v) = putBool(K_KEEPALIVE, v)

    /** 上报心跳（每 5 秒一次，服务端 15 秒无心跳判离线） */
    var heartbeatOn: Boolean
        get() = bool(K_HEARTBEAT, false)
        set(v) = putBool(K_HEARTBEAT, v)

    /** 开机自启 */
    var bootEnabled: Boolean
        get() = bool(K_BOOT, true)
        set(v) = putBool(K_BOOT, v)

    // ---------- 推送通道 ----------
    var channel: String
        get() = str(K_CHANNEL, "webhook")
        set(v) = putStr(K_CHANNEL, v)

    /** 平台域名，例如 https://pay.example.com（不带结尾斜杠） */
    var site: String
        get() = str(K_SITE, "")
        set(v) = putStr(K_SITE, v.trim().trimEnd('/'))

    /** 平台给的应用 / 商户 PID */
    var pid: String
        get() = str(K_PID, "")
        set(v) = putStr(K_PID, v.trim())

    /** 上报 Token */
    var token: String
        get() = str(K_TOKEN, "")
        set(v) = putStr(K_TOKEN, v.trim())

    /** 上报地址由软件自动拼接：{site}/api/report/{pid}?token={token} */
    fun endpointUrl(): String {
        val s = site.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        if (pid.isBlank()) return s
        val baseUrl = s + "/api/report/" + pid
        return if (token.isBlank()) baseUrl else baseUrl + "?token=" + token
    }

    /** 老版本把整条地址存在 endpoint 里，升级后自动拆成 域名 / PID / Token */
    fun migrateEndpoint() {
        if (site.isNotBlank()) return
        val old = str("endpoint", "")
        if (old.isBlank()) return
        try {
            val u = java.net.URI(old)
            val host = u.host ?: return
            site = (if (u.scheme.isNullOrBlank()) "https" else u.scheme) + "://" + host +
                (if (u.port > 0) ":" + u.port else "")
            pid = (u.path ?: "").split('/').lastOrNull { it.isNotBlank() } ?: ""
            token = (u.query ?: "").split('&')
                .firstOrNull { it.startsWith("token=") }?.substringAfter("token=") ?: ""
        } catch (e: Exception) {
            // 解析失败就不迁移
        }
    }

    /** 签名密钥（自建 Webhook 的 HMAC 密钥、钉钉加签密钥等） */
    var secret: String
        get() = str(K_SECRET, "")
        set(v) = putStr(K_SECRET, v)

    /** 附加参数：Telegram 的 chat_id、自定义 Header、备注等 */
    var extra: String
        get() = str(K_EXTRA, "")
        set(v) = putStr(K_EXTRA, v)

    /** 自定义文本模板（推送正文），留空用内置模板 */
    var template: String
        get() = str(K_TEMPLATE, "")
        set(v) = putStr(K_TEMPLATE, v)

    // ---------- 解析规则 ----------
    /** 最小金额，低于此值忽略（0 = 不限） */
    var minAmount: Float
        get() = float(K_MIN_AMOUNT, 0f)
        set(v) = putFloat(K_MIN_AMOUNT, v)

    /** 去重窗口（秒）：同一 App 同金额同文本在该时间内只算一次 */
    var dedupeSeconds: Int
        get() = int(K_DEDUPE, 30)
        set(v) = putInt(K_DEDUPE, v)

    /** 是否允许推送解析不出金额的通知 */
    var allowNoAmount: Boolean
        get() = bool(K_ALLOW_NO_AMOUNT, false)
        set(v) = putBool(K_ALLOW_NO_AMOUNT, v)

    /**
     * 抓取模式：
     *  0 = 全部通知（勾选 App 的每条通知都捕获，不看关键词和金额）—— 默认
     *  1 = 仅关键词命中（走关键词表，不要求有金额）
     *  2 = 仅收款到账（关键词 + 必须能解析出金额）
     */
    var captureMode: Int
        get() = int(K_CAPTURE_MODE, MODE_ALL)
        set(v) = putInt(K_CAPTURE_MODE, v.coerceIn(0, 2))

    fun captureModeName(): String = when (captureMode) {
        MODE_KEYWORD -> "仅关键词命中"
        MODE_PAYMENT -> "仅收款到账"
        else -> "全部通知"
    }

    var keywords: String
        get() = str(K_KEYWORDS, DEFAULT_KEYWORDS)
        set(v) = putStr(K_KEYWORDS, v)

    var excludeKeywords: String
        get() = str(K_EXCLUDE, DEFAULT_EXCLUDE)
        set(v) = putStr(K_EXCLUDE, v)

    /** 关键词过滤总开关：关掉就是全部通知都转发 */
    var keywordFilterOn: Boolean
        get() = bool(K_KEYWORD_ON, true)
        set(v) = putBool(K_KEYWORD_ON, v)

    /** 接口模式：0 = X-YPay，1 = 码支付 V1 */
    var apiMode: Int
        get() = int(K_API_MODE, 0)
        set(v) = putInt(K_API_MODE, v)

    /** 一条路由：支付方式 + 通道号 */
    data class ApiRoute(val payType: String, val channelId: String) {
        val from: String
            get() = when (payType) {
                "alipay" -> "com.eg.android.AlipayGphone"
                "qqpay" -> "com.tencent.mobileqq"
                else -> "com.tencent.mm"
            }
    }

    private val ROUTE_RE = Regex("^(wxpay|alipay|qqpay)([0-9]{1,20})$")

    /**
     * 解析 PID 为路由表。支持逗号分隔多个，例如 wxpay1,alipay2。
     * 解析失败（格式不对 / 同一支付方式重复）返回 null。
     */
    fun apiRoutes(): List<ApiRoute>? {
        val raw = pid.trim()
        if (raw.isEmpty()) return null
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val out = ArrayList<ApiRoute>(parts.size)
        val seen = HashSet<String>()
        for (part in parts) {
            val m = ROUTE_RE.find(part) ?: return null
            val type = m.groupValues[1]
            if (!seen.add(type)) return null
            out.add(ApiRoute(type, m.groupValues[2]))
        }
        return out
    }

    /** 支付方式的中文名，用于提示 */
    fun payTypeName(type: String?): String = when (type) {
        "wxpay" -> "微信"
        "alipay" -> "支付宝"
        "qqpay" -> "QQ"
        else -> type ?: ""
    }

    /** 只写了方式没写通道号，例如 alipay */
    private val TYPE_ONLY_RE = Regex("^(wxpay|alipay|qqpay)$", RegexOption.IGNORE_CASE)

    /** 给用户看的具体错误；null 表示正常 */
    fun apiRouteError(): String? {
        val raw = pid.trim()
        if (raw.isEmpty()) return null
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val seen = HashSet<String>()
        for (part in parts) {
            // 只写了支付方式：用原始写法指明哪一项缺通道号，不用中文名
            TYPE_ONLY_RE.find(part)?.let { m ->
                val t = m.groupValues[1].lowercase()
                return "PID 缺少通道ID：$t 应写成 ${t}1"
            }
            val m = ROUTE_RE.find(part)
                ?: return "格式应为 wxpay1,alipay2（逗号分隔）"
            val type = m.groupValues[1]
            if (!seen.add(type)) {
                return "同一支付方式只能配一个通道：$part 重复"
            }
        }
        return null
    }

    /** 从通知包名推断支付方式；不认识的返回 null */
    fun payTypeOf(pkg: String): String? = when {
        // 本应用自己发的「测试通知」：没有真实包名，按第一条路由试发，
        // 否则测试功能在严格路由下永远失败。
        pkg == "com.yjk.monitor" -> apiRoutes()?.firstOrNull()?.payType
        pkg == "com.tencent.mm" -> "wxpay"
        pkg.contains("alipay", true) -> "alipay"
        pkg.contains("mobileqq", true) -> "qqpay"
        else -> null
    }

    /**
     * 按通知包名找路由。**找不到就是 null，不回落到第一条**，
     * 否则支付宝通知会被发到微信通道，造成串联。
     */
    fun apiRouteFor(pkg: String): ApiRoute? {
        val type = payTypeOf(pkg) ?: return null
        return apiRoutes()?.firstOrNull { it.payType == type }
    }

    /**
     * X-YPay 模式只接受单个 PID，逗号是 V1 的多通道路由写法。
     * 混用会拼出 /api/report/wxpay1,alipay2?token=... 这种平台不认的地址。
     */
    fun xyPayPidError(): String? {
        val p = pid.trim()
        if (p.isEmpty()) return null
        if (p.contains(",") || p.contains("，")) return "请输入正确的PID编号"
        return null
    }

    fun apiModeName(): String = when (apiMode) {
        1 -> "码支付V1"
        else -> "X-YPay"
    }

    /** 开发者日志：记录通知的全部 extras 字段，用于排查抓不到的通知 */
    var devLog: Boolean
        get() = bool(K_DEV_LOG, false)
        set(v) = putBool(K_DEV_LOG, v)

    /** 转发后是否自动清除通知栏里的那条通知 */
    var clearNotifyOn: Boolean
        get() = bool(K_CLEAR_ON, false)
        set(v) = putBool(K_CLEAR_ON, v)

    /** 0=只清被转发的那一条（默认） 1=清所有应用的通知 */
    var clearNotifyMode: Int
        get() = int(K_CLEAR_MODE, 0)
        set(v) = putInt(K_CLEAR_MODE, v)

    fun keywordList(): List<String> = splitList(keywords)
    fun excludeList(): List<String> = splitList(excludeKeywords)

    private fun splitList(s: String): List<String> =
        s.split(',', '，', '|', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    // ---------- 免打扰 ----------
    var quietEnabled: Boolean
        get() = bool(K_QUIET_ON, false)
        set(v) = putBool(K_QUIET_ON, v)

    var quietStart: String
        get() = str(K_QUIET_START, "23:00")
        set(v) = putStr(K_QUIET_START, v)

    var quietEnd: String
        get() = str(K_QUIET_END, "07:30")
        set(v) = putStr(K_QUIET_END, v)

    // ---------- 存储 / 网络 ----------
    var recordLimit: Int
        get() = int(K_LIMIT, 300)
        set(v) = putInt(K_LIMIT, v)

    var retryTimes: Int
        get() = int(K_RETRY, 2)
        set(v) = putInt(K_RETRY, v)

    var timeoutSeconds: Int
        get() = int(K_TIMEOUT, 8)
        set(v) = putInt(K_TIMEOUT, v)
}

