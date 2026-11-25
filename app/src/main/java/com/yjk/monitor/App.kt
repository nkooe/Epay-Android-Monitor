package com.yjk.monitor

import android.app.Application
import com.yjk.monitor.core.Prefs
import com.yjk.monitor.core.RecordStore
import com.yjk.monitor.service.Notifier

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        startedAt = System.currentTimeMillis()
        // 改包重签后签名不符，直接拒绝运行
        if (!com.yjk.monitor.core.SigCheck.signatureOk(this)) {
            android.util.Log.e("YJK", "signature mismatch, refusing to run")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    android.widget.Toast.makeText(
                        this, "应用完整性校验失败，无法启动", android.widget.Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                }
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 800)
            return
        }

        Prefs.init(this)
        Prefs.migrateEndpoint()
        RecordStore.init(this)
        Notifier.ensureChannels(this)
        Notifier.cancelAlert(this)
        com.yjk.monitor.push.Heartbeat.start()

        // 以下都有 IO / 系统调用 / 可能的网络请求，放后台线程，避免拖慢首屏
        Thread({
            try {
                com.yjk.monitor.core.SigCheck.warmUp()
                com.yjk.monitor.service.WatchdogJobService.schedule(this)
                com.yjk.monitor.push.Pusher.resumePending()
                // 前台服务是进程活过锁屏的关键。以前只在 MainActivity / 开机广播里启动，
                // 覆盖安装后不打开界面就没有前台服务，锁屏一会儿就被系统清掉。
                if (Prefs.keepAliveOn) {
                    try {
                        com.yjk.monitor.service.KeepAliveService.start(this)
                    } catch (e: Throwable) {
                        android.util.Log.w("YJK", "KeepAliveService start failed: " + e.message)
                    }
                }
                com.yjk.monitor.core.AppUtil.setBootReceiverEnabled(this, Prefs.bootEnabled)
            } catch (e: Throwable) {
                android.util.Log.w("YJK", "startup background task failed: " + e.message)
            }
        }, "yjk-startup").start()

        // 网络恢复时补发积压：锁屏 Doze 期间断网失败的记录状态是 PENDING，
        // 等 JobScheduler 的维护窗口可能拖十几分钟，网络一回来立刻重试。
        try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Thread({
                        try {
                            com.yjk.monitor.push.Pusher.resumePending()
                        } catch (e: Throwable) {
                        }
                    }, "yjk-net-resume").start()
                }
            }
            if (cm != null) {
                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    cm.registerDefaultNetworkCallback(cb)
                } else {
                    val req = android.net.NetworkRequest.Builder()
                        .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    cm.registerNetworkCallback(req, cb)
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w("YJK", "network callback failed: " + e.message)
        }

        // 被 force-stop / 后台清理后，系统会解绑通知监听：启动时请求重绑
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                com.yjk.monitor.service.PayListenerService.requestRebindIfNeeded(this)
            } catch (e: Exception) {
                // ignore
            }
        }, 300)

        // 不再自动补发：「默认只推送一次，想重试自己手动点」

    }

    companion object {
        /** 进程启动时间：用于区分"还在等系统绑定"和"真的断了" */
        @Volatile
        var startedAt: Long = 0L
            private set

        lateinit var instance: App
            private set
    }
}
