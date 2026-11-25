package com.yjk.monitor.push

import android.util.Log
import com.yjk.monitor.core.AppUtil
import com.yjk.monitor.core.Bus
import com.yjk.monitor.core.PayRecord
import com.yjk.monitor.core.Prefs
import com.yjk.monitor.core.RecordStatus
import com.yjk.monitor.core.RecordStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 上报引擎。报文格式对齐通用「监控上报」平台：
 *
 * POST 平台地址（地址里带 ?token=xxx）
 * {
 *   "from": "android",
 *   "content": "{\"title\":\"发送者\",\"msg\":\"消息内容\",\"package_name\":\"com.tencent.mm\",
 *                \"receive_time\":\"2026-10-21 13:37:36.933615\",\"device_name\":\"OnePlus MT2110\"}",
 *   "timestamp": 1792561056954,
 *   "sign": ""
 * }
 *
 * 每次上报的 url / body / response 都会写回日志，日志详情里可直接查看并重试。
 */
object Pusher {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yjk-pusher").apply { isDaemon = true }
    }

    /** 同一条记录同时只允许一个任务，避免补发时重复上报 */
    private val inFlight = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
    )

    @Volatile
    var lastOk: Boolean = false
        private set

    @Volatile
    var lastMessage: String = "尚未上报"
        private set

    @Volatile
    var lastTime: Long = 0L
        private set

    /** 是否已配置（地址非空即可） */
    fun isConfigured(): Boolean = when (Prefs.apiMode) {
        1 -> Prefs.site.isNotBlank() && Prefs.apiRoutes() != null && Prefs.token.isNotBlank()
        else -> Prefs.site.isNotBlank()
    }

    class Outcome(val ok: Boolean, val url: String, val body: String, val response: String)

    /** 入队上报：带重试，结束后把状态与请求/响应写回日志 */
    fun enqueue(
        record: PayRecord,
        manual: Boolean = false,
        onDone: ((Boolean, String) -> Unit)? = null
    ) {
        if (!inFlight.add(record.id)) return
        armJob()   // 交给系统托管：进程被杀后仍会被恢复补发
        worker.execute {
            // 默认只推一次：失败就记失败，要重试由用户手动点
            val attempts = 1
            var outcome = Outcome(false, Prefs.endpointUrl(), "", "未发送")
            var i = 1
            while (i <= attempts) {
                outcome = send(record)
                if (outcome.ok) break
                if (i < attempts) {
                    try {
                        Thread.sleep(1000L * i)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
                i++
            }

            lastOk = outcome.ok
            lastMessage = outcome.response
            lastTime = System.currentTimeMillis()

            // 网络类失败（锁屏 Doze 下常见 Socket closed / timeout）保持待发状态，
            // 交给 JobScheduler 重试；业务类失败（平台返回 400）才记为最终失败。
            val netFail = !outcome.ok && isNetworkFailure(outcome.response)
            val status = when {
                outcome.ok -> RecordStatus.SENT
                netFail -> RecordStatus.PENDING
                else -> RecordStatus.FAILED
            }
            try {
                RecordStore.updateResult(
                    record.id,
                    status,
                    outcome.url,
                    outcome.body,
                    outcome.response
                )
                if (status == RecordStatus.PENDING) armJob()
                Bus.fireStateChanged()
            } finally {
                inFlight.remove(record.id)
            }
            if (onDone != null) {
                android.os.Handler(android.os.Looper.getMainLooper())
                    .post { onDone(outcome.ok, outcome.response) }
            }
        }
    }

    /**
     * 手动重试：与同类产品一致 —— 用这条记录的**原始通知内容重新组包**再发一次，
     * timestamp / sign 重新生成（记录里存的 request_body 只用于展示）。
     */
    /**
     * 进程上次被杀时还没发出去的记录（状态仍是 PENDING），启动后补发一次。
     * 只补一次，失败就标记失败，不会无限重试 —— 与「默认只推一次」一致。
     */
    /** 判断是否网络层面的失败（这类失败值得重试，业务拒绝不值得） */
    private fun isNetworkFailure(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("socket") || m.contains("timeout") || m.contains("timed out") ||
            m.contains("unable to resolve") || m.contains("connection") ||
            m.contains("network") || m.contains("broken pipe") ||
            m.contains("econnreset") || m.contains("econnrefused") ||
            m.contains("未发送") || m.contains("ssl")
    }

    /** 有待发记录时，交给系统 JobScheduler 托管（进程被杀也会被恢复执行） */
    private fun armJob() {
        try {
            com.yjk.monitor.service.SendJobService.schedule(
                com.yjk.monitor.App.instance
            )
        } catch (e: Exception) {
        }
    }

    fun resumePending() {
        val list = try { RecordStore.pending() } catch (e: Exception) { emptyList() }
        if (list.isEmpty()) {
            com.yjk.monitor.service.SendJobService.cancel(com.yjk.monitor.App.instance)
            return
        }
        android.util.Log.d("YJK", "resume pending: ${list.size}")
        for (rec in list) send(rec)
    }

    fun retry(record: PayRecord, onDone: ((Boolean, String) -> Unit)? = null) {
        inFlight.remove(record.id)
        enqueue(record, manual = true, onDone = onDone)
    }

    private fun send(rec: PayRecord): Outcome {
        // 完整性校验：改包或调试环境下静默不上报
        val ctx = com.yjk.monitor.App.instance
        if (!com.yjk.monitor.core.SigCheck.trusted(ctx)) {
            return Outcome(false, "", "", "设备环境异常，已暂停上报")
        }

        // 锁屏后 CPU 降频、网络可能被挂起：持 PARTIAL_WAKE_LOCK 保证请求跑完。
        // 实测无唤醒锁时，22:12 付款要到 22:13 解锁后才发到平台。
        val wl = try {
            (ctx.getSystemService(android.content.Context.POWER_SERVICE)
                as? android.os.PowerManager)
                ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "yjk:push")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(30_000L)
                }
        } catch (e: Throwable) {
            null
        }

        // WiFi 在锁屏后会进入省电模式（即使有 CPU 唤醒锁），一并锁住
        val wfl = try {
            (ctx.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager)
                ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "yjk:push")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (e: Throwable) {
            null
        }

        return try {
            doSend(rec)
        } finally {
            try {
                if (wl?.isHeld == true) wl.release()
            } catch (e: Throwable) {
            }
            try {
                if (wfl?.isHeld == true) wfl.release()
            } catch (e: Throwable) {
            }
        }
    }

    private fun doSend(rec: PayRecord): Outcome {
        if (Prefs.apiMode == 1) return sendV1(rec)
        Prefs.xyPayPidError()?.let {
            return Outcome(false, "", "", it)
        }
        val url = Prefs.endpointUrl().trim()
        if (url.isEmpty()) return Outcome(false, "", "", "还没填平台域名")

        val body = buildReport(rec)
        return try {
            val r = http(url, body)
            Outcome(r.ok, url, body, r.text)
        } catch (e: Exception) {
            Log.e("YJK", "push failed", e)
            Outcome(false, url, body, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 码支付 V1 上报。
     * URL:  {域名}/api/pay/{通道ID}/notify     通道ID 取自 PID 后缀
     * 表单: from / content(JSON 字符串) / timestamp(13 位毫秒) / sign
     * 签名: base64(hmac_sha256(key=密钥, msg=timestamp + "\n" + 密钥))
     * 成功: 响应正文为 "200"
     */
    private fun sendV1(rec: PayRecord): Outcome {
        val site = Prefs.site.trim().trimEnd('/')
        val secret = Prefs.token.trim()
        if (site.isEmpty()) return Outcome(false, "", "", "还没填平台域名")

        // PID 配置本身有错：这里只给通用提示，具体哪一项错在配置页看，
        // 免得每条通知都弹出"请配置某支付方式通道"造成误判。
        if (Prefs.apiRouteError() != null) {
            return Outcome(false, "", "", "PID 配置有误，请到配置页按 wxpay1 的格式补齐")
        }

        val payType = Prefs.payTypeOf(rec.pkg)
        if (payType == null) {
            return Outcome(
                false, "", "",
                "无法识别的支付来源（${rec.pkg}），未上报"
            )
        }
        val route = Prefs.apiRouteFor(rec.pkg)
        if (route == null) {
            return Outcome(
                false, "", "",
                "PID 里没有 ${payType} 通道，请补上 ${payType}N（N 为通道号）"
            )
        }
        if (secret.isEmpty()) return Outcome(false, "", "", "还没填密钥")

        val url = "$site/api/pay/${route.channelId}/notify"
        val ts = System.currentTimeMillis().toString()
        val inner = JSONObject().apply {
            put("title", rec.title.ifBlank { rec.payer.ifBlank { rec.appName } })
            put("msg", rec.text.ifBlank { rec.title })
            put("package_name", rec.pkg)
            put("receive_time", microTime(rec.time))
            put("device_name", AppUtil.deviceName())
        }.toString()

        val form = buildString {
            append("from=").append(urlEncode(route.from))
            append("&content=").append(urlEncode(inner))
            append("&timestamp=").append(ts)
            append("&sign=").append(urlEncode(hmacSign(ts, secret)))
        }

        return try {
            val r = httpForm(url, form)
            var resp = r.text.trim()
            // 平台侧通道不存在 / 插件不匹配时，追加一句"检查通道 ID"的引导
            if (!r.ok && (resp == "error" || resp.contains("不属于允许的来源"))) {
                val name = Prefs.payTypeName(route.payType)
                resp = resp + "\n（请提供正确的${name}通道ID，当前配置 ${route.payType}${route.channelId}）"
            }
            Outcome(r.ok, url, form, resp)
        } catch (e: Exception) {
            Log.e("YJK", "push v1 failed", e)
            Outcome(false, url, form, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private fun hmacSign(ts: String, secret: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal((ts + "\n" + secret).toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
    }

    /** V1 用表单提交；必须带 UA，否则会被 Cloudflare 拦成 403 */
    private fun httpForm(urlStr: String, form: String): HttpResult {
        var conn: java.net.HttpURLConnection? = null
        try {
            val c = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("User-Agent", "okhttp/4.12.0")
            val bytes = form.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }

            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = try {
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e: Exception) {
                ""
            }
            // V1 的成功判定看正文，不看 HTTP 码（失败也是 200 + "400：原因"）
            val ok = code in 200..299 && text.trim() == "200"
            return HttpResult(ok, code, text.ifBlank { "(空响应)" })
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    /** 报文：from / content(内嵌 JSON 字符串) / timestamp(秒) / sign */
    fun buildReport(rec: PayRecord): String {
        val inner = JSONObject().apply {
            put("title", rec.title.ifBlank { rec.payer.ifBlank { rec.appName } })
            put("msg", rec.text.ifBlank { rec.title })
            put("package_name", rec.pkg)
            put("receive_time", microTime(rec.time))
            put("device_name", AppUtil.deviceName())
        }.toString()

        val body = JSONObject().apply {
            put("from", "android")
            put("content", inner)
            // 与同类产品一致：timestamp = 本次上报的发送时刻（毫秒）。
            // 服务端做时间窗判断时记得 /1000。
            put("timestamp", System.currentTimeMillis())
        }
        body.put("sign", calcSign(body, Prefs.token.trim()))
        return body.toString()
    }

    /**
     * 平台签名规则：
     *  - 取 POST 正文字段，排除 sign / sign_type / 对象 / 数组 / null / 空串
     *  - 按字段名升序，用 & 拼 key=value，末尾不留 &
     *  - 直接追加商户密钥（无分隔符、无 key=）
     *  - UTF-8 算 MD5，32 位小写
     */
    internal fun calcSign(body: JSONObject, key: String): String {
        if (key.isEmpty()) return ""
        val names = body.keys().asSequence()
            .filter { it != "sign" && it != "sign_type" }
            .sorted()
            .toList()
        val sb = StringBuilder()
        for (n in names) {
            val v = body.opt(n) ?: continue
            if (v == JSONObject.NULL) continue
            if (v is JSONObject || v is org.json.JSONArray) continue
            val str = v.toString()
            if (str.isBlank()) continue
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(n).append('=').append(str)
        }
        sb.append(key)
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            md.digest(sb.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { String.format("%02x", it) }
        } catch (e: Exception) {
            ""
        }
    }

    /** yyyy-MM-dd HH:mm:ss.SSSSSS（微秒位用毫秒补齐） */
    private fun microTime(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(time)) +
            String.format(Locale.CHINA, ".%06d", (time % 1000) * 1000)

    class HttpResult(val ok: Boolean, val code: Int, val text: String)

    private fun http(urlStr: String, body: String): HttpResult {
        var conn: HttpURLConnection? = null
        try {
            val c = URL(urlStr).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("User-Agent", "YiJianKong")
            val bytes = body.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }

            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = try {
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e: Exception) {
                ""
            }
            val ok = code in 200..299 && !bizError(text)
            return HttpResult(ok, code, text.ifBlank { "(空响应)" })
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /** code/message/data 这类业务错误码（如 {"code":403,...}）也算失败 */
    private fun bizError(resp: String): Boolean {
        val t = resp.trim()
        if (!t.startsWith("{")) return false
        return try {
            val o = JSONObject(t)
            if (o.has("code")) {
                val c = o.optInt("code", 0)
                return !(c == 0 || c == 200)
            }
            if (o.has("errcode")) return o.optInt("errcode", 0) != 0
            if (o.has("ok")) return !o.optBoolean("ok", true)
            false
        } catch (e: Exception) {
            false
        }
    }

    fun fmt(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(time))
}
