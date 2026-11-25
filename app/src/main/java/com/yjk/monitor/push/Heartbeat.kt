package com.yjk.monitor.push

import android.util.Log
import com.yjk.monitor.App
import com.yjk.monitor.core.AppUtil
import com.yjk.monitor.core.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 心跳：每 5 秒上报一次"收款监听是否可用"。
 *
 * 请求体（固定字段）：
 * {
 *   "client_name": "XMPAY助手",
 *   "ext_data": "{\"online\":1}",
 *   "timestamp": 1792570048,
 *   "sign": "MD5(...)"
 * }
 *
 * 规则：
 *  - 监控开启 + 通知监听已连接 + 收款推送已配置 → online=1，否则 online=0
 *  - 启动/恢复监听后立即发一次，之后每 5 秒一次
 *  - 停止监控/关闭推送/监听断开 → 立即发 online=0
 *  - 失败不积压，等下一次
 *  - 成功判定：HTTP 2xx 且响应 code == 200
 */
object Heartbeat {

    private const val INTERVAL_MS = 5000L
    /** 心跳里的客户端名称，跟随本应用的显示名（易监控） */
    private val CLIENT_NAME: String
        get() = try {
            App.instance.getString(com.yjk.monitor.R.string.app_name)
        } catch (e: Exception) {
            "易监控"
        }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yjk-heartbeat").apply { isDaemon = true }
    }

    /** 用来打断等待，让 poke() 能立刻发一次（而不是等满 5 秒） */
    private val lock = Object()

    @Volatile
    private var running = false

    @Volatile
    var lastOk: Boolean = false
        private set

    @Volatile
    var lastText: String = "尚未上报"
        private set

    @Volatile
    var lastTime: Long = 0L
        private set

    /** 启动循环（幂等），会立刻发一次 */
    fun start() {
        if (running) {
            poke()
            return
        }
        running = true
        worker.execute {
            var delay = INTERVAL_MS
            while (running) {
                val ok = tick()
                // 成功保持 5 秒；失败退避到 10/20/30 秒，避免平台没就绪时每 5 秒刷一次
                delay = if (ok) INTERVAL_MS else Math.min(delay * 2, 30000L)
                try {
                    // 用 wait 代替 sleep：poke() 可以立即唤醒，做到"状态一变马上上报"
                    synchronized(lock) { lock.wait(delay) }
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    /** 状态变化时立即发一次：唤醒等待中的循环，马上跑下一轮 tick */
    fun poke() {
        if (!running) {
            start()
            return
        }
        synchronized(lock) { lock.notifyAll() }
    }

    fun stop() {
        running = false
    }

    private fun online(): Int {
        val monitoring = Prefs.monitorEnabled
        // 关键：必须是"监听真的连上了"，而不是"权限还在"。
        // 系统解绑后 hasPermission 仍为 true，用它会让平台显示在线但实际收不到钱。
        val listening = AppUtil.isListenerConnected()
        val pushReady = Pusher.isConfigured()
        return if (monitoring && listening && pushReady) 1 else 0
    }

    fun buildBody(online: Int, ts: Long): String {
        val ext = JSONObject().put("online", online).toString()   // {"online":1}
        val body = JSONObject().apply {
            put("client_name", CLIENT_NAME)
            put("ext_data", ext)
            put("timestamp", ts)
        }
        body.put("sign", Pusher.calcSign(body, Prefs.token.trim()))
        return body.toString()
    }

    /** 心跳地址：只拼 PID，不带任何查询参数 */
    fun endpoint(): String {
        val site = Prefs.site.trim().trimEnd('/')
        val pid = Prefs.pid.trim()
        if (site.isEmpty() || pid.isEmpty()) return ""
        return site + "/api/report/" + pid + "/heart"
    }

    private fun tick(): Boolean {
        if (!Prefs.heartbeatOn) return false
        val url = endpoint()
        if (url.isEmpty()) return false

        val online = online()
        val body = buildBody(online, System.currentTimeMillis())
        var conn: HttpURLConnection? = null
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 8000
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
            val bizOk = try {
                val o = JSONObject(text)
                o.optInt("code", 0) == 200
            } catch (e: Exception) {
                false
            }
            lastOk = code in 200..299 && bizOk
            lastText = "HTTP $code " + text.take(120)
            lastTime = System.currentTimeMillis()
            Log.d("YJK", "heartbeat online=$online http=$code body=$text")
            return lastOk
        } catch (e: Exception) {
            lastOk = false
            lastText = e.message ?: e.javaClass.simpleName
            lastTime = System.currentTimeMillis()
            Log.d("YJK", "heartbeat failed: " + lastText)
            return false
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
