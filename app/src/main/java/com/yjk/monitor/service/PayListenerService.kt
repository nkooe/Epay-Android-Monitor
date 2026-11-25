package com.yjk.monitor.service

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.yjk.monitor.core.AppUtil
import com.yjk.monitor.core.Bus
import com.yjk.monitor.core.PayParser
import com.yjk.monitor.core.Prefs
import com.yjk.monitor.core.Quiet
import com.yjk.monitor.core.RecordStatus
import com.yjk.monitor.core.RecordStore
import com.yjk.monitor.push.Pusher

/**
 * 通知监听服务 —— 整个软件的入口。
 * 只读勾选应用的通知栏内容，解析出收款记录，然后：记账 → 本地提醒 → 推送。
 */
class PayListenerService : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        alive = true
        instance = this
        android.util.Log.d("YJK", "listener onCreate")
    }

    override fun onDestroy() {
        alive = false
        connected = false
        instance = null
        android.util.Log.d("YJK", "listener onDestroy")
        super.onDestroy()
    }

    override fun onListenerConnected() {
        // 完整性校验失败时不接受任何通知
        if (!com.yjk.monitor.core.SigCheck.trusted(this)) {
            android.util.Log.e("YJK", "integrity check failed, listener disabled")
            try {
                stopSelf()
            } catch (e: Exception) {
            }
            return
        }
        android.util.Log.d("YJK", "listener onListenerConnected")
        com.yjk.monitor.push.Heartbeat.poke()
        connected = true
        Bus.fireStateChanged()
        Notifier.updateKeepAlive(this, Prefs.floatingEnabled)
    }

    override fun onListenerDisconnected() {
        com.yjk.monitor.push.Heartbeat.poke()
        connected = false
        Bus.fireStateChanged()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            handle(sbn)
        } catch (e: Exception) {
            // 任何解析异常都不允许打断监听服务
        }
    }

    private fun handle(sbn: StatusBarNotification?) {
        val notif = sbn ?: return
        lastEventAt = System.currentTimeMillis()
        val pkg = notif.packageName ?: return
        // 「发送测试通知」由本应用自己发出，不走勾选检查，否则永远抓不到自己
        val isSelfTest = pkg == packageName && sbn.id == Notifier.ID_TEST
        // 自己的通知里只跳过内部用途（常驻 1001 / 悬浮窗 1002 / 断连告警 1003），
        // 「发送测试通知」用的 1900 要能被自己抓到，才能验证链路
        if (pkg == packageName && !isSelfTest && notif.id in INTERNAL_NOTIFICATION_IDS) return
        val selected = Prefs.selectedPackages().contains(pkg)
        android.util.Log.d("YJK", "notif pkg=$pkg monitor=${Prefs.monitorEnabled} selected=$selected selfTest=$isSelfTest")
        if (!Prefs.monitorEnabled) return
        if (!selected && !isSelfTest) return

        val n = notif.notification ?: return
        val extras = n.extras ?: return
        // 开发者日志：把通知的全部字段打出来，用于排查"某个 App 抓不到"
        if (Prefs.devLog) {
            android.util.Log.d("YJK", "===== 通知全量字段 pkg=$pkg id=${sbn.id} tag=${sbn.tag} key=${sbn.key} =====")
            for (k in extras.keySet()) {
                val v = extras.get(k)
                val str = when (v) {
                    null -> "null"
                    is CharSequence -> v.toString()
                    is Array<*> -> v.joinToString(" | ") { it?.toString() ?: "null" }
                    else -> v.toString()
                }
                android.util.Log.d("YJK", "  [$k] (${v?.javaClass?.simpleName}) = ${str.take(300)}")
            }
            android.util.Log.d("YJK", "  [tickerText] = ${n.tickerText}")
            android.util.Log.d("YJK", "===== 字段结束 =====")
        }

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = collectText(extras, n.tickerText)
        if (title.isEmpty() && text.isEmpty()) return

        // 通知获取规则：配了关键词就只转发命中的通知；留空表示全部转发
        val keywords = Prefs.keywordList()
        if (Prefs.keywordFilterOn && keywords.isNotEmpty()) {
            val body = title + "\n" + text
            if (keywords.none { matches(body, it) }) {
                android.util.Log.d("YJK", "skip no-keyword pkg=$pkg title=${title.take(20)}")
                return
            }
        }

        val appName = AppUtil.labelOf(this, pkg)

        val rec = PayParser.parse(pkg, appName, title, text, notif.postTime)
        if (rec == null) {
            android.util.Log.d("YJK", "skip empty pkg=$pkg")
            return
        }
        android.util.Log.d("YJK", "record ok pkg=$pkg amount=${rec.amount}")

        // 1) 落记录
        RecordStore.add(rec)
        Bus.fireRecordAdded(rec)

        // 3) 推送
        if (Prefs.pushEnabled && Pusher.isConfigured() && !Quiet.isQuietNow()) {
            Pusher.enqueue(rec)
        } else {
            RecordStore.updateStatus(rec.id, RecordStatus.LOCAL_ONLY)
        }

        // 4) 悬浮窗刷新（若在运行）
        FloatingService.notifyNewRecord(rec)

        // 5) 按设置清除通知栏
        if (Prefs.clearNotifyOn) {
            try {
                val mode = Prefs.clearNotifyMode
                android.util.Log.d("YJK", "clear notify mode=$mode key=${sbn.key}")
                if (mode == 1) {
                    cancelAllNotifications()
                    android.util.Log.d("YJK", "clear all done")
                } else if (pkg == packageName) {
                    // 自己发的通知（测试通知）用 NotificationManager 按 id 取消最稳
                    getSystemService(android.app.NotificationManager::class.java)
                        ?.cancel(notif.id)
                    android.util.Log.d("YJK", "clear self id=${notif.id}")
                } else {
                    cancelNotification(sbn.key)
                    android.util.Log.d("YJK", "clear one done key=${sbn.key}")
                }
            } catch (e: Exception) {
                android.util.Log.d("YJK", "clear failed: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** 关键词匹配：只做包含匹配（不区分大小写），不支持正则 */
    private fun matches(body: String, keyword: String): Boolean =
        body.contains(keyword.trim(), ignoreCase = true)

    /** 把一条通知里的所有可读文本拼起来（不同 App 放的字段不一样） */
    private fun collectText(extras: Bundle, ticker: CharSequence?): String {
        val parts = ArrayList<String>(6)
        fun add(s: CharSequence?) {
            val t = s?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && !parts.contains(t)) parts.add(t)
        }
        add(extras.getCharSequence(Notification.EXTRA_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it) }
        // 部分 App 只把内容放在 tickerText（脱壳对照：码支付用 tickerText 做最后兜底）
        add(ticker)
        return parts.joinToString("\n")
    }

    companion object {
        private val INTERNAL_NOTIFICATION_IDS = setOf(1001, 1002, 1003, 2001)

        /** 当前服务实例，用于「真实调用验证」 */
        @Volatile
        private var instance: PayListenerService? = null

        @Volatile
        var connected: Boolean = false
            private set

        /** 服务实例是否存活（系统绑定后 onCreate 就会置 true） */
        @Volatile
        private var alive: Boolean = false

        /** 最近一次收到通知的时间 */
        @Volatile
        private var lastEventAt: Long = 0L

        /** 上次强制重绑的时间，避免心跳每 5 秒重绑一次 */
        @Volatile
        private var lastRebindAt: Long = 0L

        /**
         * 监听是否在工作。
         * 不能只看 settings 里的权限，也不能只信 onListenerConnected：
         * 部分 ROM 重绑后只派发通知、不回调连接事件，会把"正常工作中"误判为未连接。
         */
        /**
         * 与码支付 NotificationCleanService.checkAndRecover 完全一致的三段判定：
         *  1. 系统名单里没有本包 → 放弃（无法自动恢复）
         *  2. 服务实例为空（进程刚被拉起）→ startService 触发创建
         *  3. 真实调用 getActiveNotifications()，抛异常 → disable→enable + requestRebind
         */
        fun checkAndRecover(ctx: android.content.Context) {
            val flat = try {
                android.provider.Settings.Secure.getString(
                    ctx.contentResolver, "enabled_notification_listeners"
                )
            } catch (e: Exception) {
                android.util.Log.e("YJK", "心跳检查异常: " + e.message)
                return
            }
            if (flat == null || !flat.contains(ctx.packageName)) {
                android.util.Log.w("YJK", "心跳：通知监听权限已关闭，无法自动恢复")
                return
            }
            val inst = instance
            if (inst == null) {
                android.util.Log.w("YJK", "心跳：Service 实例不存在（可能进程被杀过），启动服务触发重绑")
                try {
                    ctx.startService(android.content.Intent(ctx, PayListenerService::class.java))
                } catch (e: Exception) {
                    android.util.Log.w("YJK", "启动服务失败: " + e.message)
                }
                return
            }
            // 关键：权限在（settings 里有记录）不代表系统真的把服务绑上了。
            // 进程被杀后常见 connected=false 但权限仍显示已授权，此时必须主动重绑。
            if (!isConnected()) {
                val sinceStart = System.currentTimeMillis() - com.yjk.monitor.App.startedAt
                val sinceRebind = System.currentTimeMillis() - lastRebindAt
                // 刚启动留 20 秒给系统绑定；重绑间隔至少 60 秒
                if (sinceStart > 20_000L && sinceRebind > 60_000L) {
                    android.util.Log.w("YJK", "心跳：权限在但未连接，强制重绑")
                    lastRebindAt = System.currentTimeMillis()
                    forceRebindListener(inst)
                } else {
                    android.util.Log.d("YJK", "心跳：等待系统绑定（启动 ${sinceStart}ms）")
                }
                return
            }
            try {
                val alive = inst.activeNotifications
                android.util.Log.d("YJK", "心跳：监听正常，通知栏 ${alive?.size ?: 0} 条")
            } catch (e: Exception) {
                android.util.Log.w("YJK", "心跳：Service 绑定异常，强制重新绑定")
                lastRebindAt = System.currentTimeMillis()
                forceRebindListener(inst)
            }
        }

        /** disable → enable → requestRebind，与对方 forceRebindListener 相同 */
        private fun forceRebindListener(inst: PayListenerService) {
            if (android.os.Build.VERSION.SDK_INT < 24) return
            try {
                val cn = android.content.ComponentName(inst, PayListenerService::class.java)
                val pm = inst.packageManager
                pm.setComponentEnabledSetting(
                    cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
                requestRebind(cn)
                android.util.Log.d("YJK", "已执行 disable→enable + requestRebind 强制重绑")
            } catch (e: Exception) {
                android.util.Log.e("YJK", "forceRebindListener 失败: " + e.message)
            }
        }

        /** 当前通知栏里的通知条数；-1 表示读取失败（绑定已失效） */
        fun activeCount(): Int {
            val inst = instance ?: return -1
            return try {
                inst.activeNotifications?.size ?: 0
            } catch (e: Exception) {
                -1
            }
        }

        /** 按包名统计当前通知条数；null 表示读取失败 */
        fun activeSummary(): Map<String, Int>? {
            val inst = instance ?: return null
            return try {
                val arr = inst.activeNotifications ?: return mapOf()
                val map = HashMap<String, Int>()
                for (n in arr) {
                    val k = n.packageName ?: continue
                    map[k] = (map[k] ?: 0) + 1
                }
                map
            } catch (e: Exception) {
                null
            }
        }

        /** 真实调用一次系统接口，识别"标志还连着但 binder 已死"的僵尸绑定 */
        private fun probeWorking(): Boolean {
            val inst = instance ?: return false
            return try {
                inst.activeNotifications
                true
            } catch (e: Exception) {
                android.util.Log.w("YJK", "probe failed: ${e.javaClass.simpleName}")
                false
            }
        }

        fun isConnected(): Boolean {
            if (connected) {
                if (!probeWorking()) {
                    connected = false
                    return false
                }
                return true
            }
            // 部分 ROM 重绑后不回调 onListenerConnected，用"最近 2 分钟确实收到过通知"作为弱证据。
            // 不能用 alive（进程活着不等于服务被系统绑定），否则解绑后永远判定为已连接，
            // 巡检也不会去重绑。
            if (lastEventAt > 0 && System.currentTimeMillis() - lastEventAt < 2 * 60_000L) return true
            return false
        }

        /**
         * 系统在 force-stop / 后台清理后会把监听服务解绑（权限仍在，但不再派发通知）。
         * 这里主动请求重绑，保证重新打开 App 后马上恢复监听。
         */
        fun requestRebindIfNeeded(ctx: android.content.Context) {
            if (connected) return
            if (android.os.Build.VERSION.SDK_INT < 24) return

            // 统一节流：10 秒内只允许一次，避免 onCreate / watchdog / heartbeat
            // 三方同时重绑互相抵消
            val now = System.currentTimeMillis()
            if (now - lastRebindAt < 10_000L) return
            lastRebindAt = now

            // 服务实例不存在时先拉起来：requestRebind 只能"通知系统重绑"，
            // 服务没创建的话系统无处可绑
            if (instance == null) {
                try {
                    ctx.startService(android.content.Intent(ctx, PayListenerService::class.java))
                    android.util.Log.d("YJK", "服务实例不存在，先 startService")
                    // startService 是异步的，此刻 instance 仍为 null；
                    // 等 onCreate 跑完再做 disable→enable，否则会走轻量分支白白多等 7 秒
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val created = instance
                        if (created != null && !connected) {
                            forceRebindListener(created)
                            android.util.Log.d("YJK", "服务就绪后 forceRebind")
                        }
                    }, 200)
                    return
                } catch (e: Exception) {
                    android.util.Log.w("YJK", "startService 失败: " + e.message)
                }
            }

            val sinceStart = now - com.yjk.monitor.App.startedAt
            try {
                val inst = instance
                if (inst != null) {
                    // requestRebind 只是"请求系统重绑"，实测系统可能拖 7 秒以上才响应
                    // （服务 17.030 建好，系统 24.593 才 binding）。
                    // disable→enable 让 PMS 立即重新解析组件，系统马上绑定。
                    forceRebindListener(inst)
                    android.util.Log.d("YJK", "forceRebind（启动 ${sinceStart}ms）")
                } else {
                    requestRebind(
                        android.content.ComponentName(ctx, PayListenerService::class.java)
                    )
                    android.util.Log.d("YJK", "requestRebind listener（启动 ${sinceStart}ms）")
                }
            } catch (e: Exception) {
                android.util.Log.d("YJK", "rebind failed: " + (e.message ?: ""))
            }
        }
    }
}
