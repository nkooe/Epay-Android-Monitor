package com.yjk.monitor.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.yjk.monitor.core.AppUtil
import com.yjk.monitor.core.Bus
import com.yjk.monitor.core.Prefs

/**
 * 后台常驻服务（前台服务）。
 *
 * 职责：
 *  1. 用常驻通知把进程优先级抬到前台，避免被系统随手回收；
 *  2. 每分钟巡检一次：刷新通知内容、必要时把悬浮窗拉起来；
 *  3. 开机自启的落点。
 */
class KeepAliveService : Service(), Bus.Listener {

    private val handler = Handler(Looper.getMainLooper())
    /** 连续几次巡检都发现监听断开，才发告警，避免抖动误报 */
    private var listenerDownTicks = 0
    private var lastAlertAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            refreshNotification()
            ensureFloating()
            checkListener()
            handler.postDelayed(this, 15_000L)
        }
    }

    /**
     * 监听服务被系统解绑时：先尝试自动重绑；连续两分钟仍断开就发一条告警通知，
     * 让用户知道"它现在其实没在工作"。
     */
    private fun checkListener() {
        if (!Prefs.monitorEnabled) {
            listenerDownTicks = 0
            Notifier.clearListenerAlert(this)
            return
        }
        if (!AppUtil.hasListenerPermission(this)) {
            listenerDownTicks = 0
            Notifier.clearListenerAlert(this)
            return
        }
        if (PayListenerService.isConnected()) {
            if (listenerDownTicks > 0) Notifier.clearListenerAlert(this)
            listenerDownTicks = 0
            return
        }

        listenerDownTicks++
        PayListenerService.checkAndRecover(this)

        val now = System.currentTimeMillis()
        if (listenerDownTicks >= 3 && now - lastAlertAt > 5 * 60_000L) {
            lastAlertAt = now
            Notifier.listenerBroken(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        Notifier.ensureChannels(this)
        goForeground()
        Bus.register(this)
        handler.postDelayed(tick, 3_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PayListenerService.requestRebindIfNeeded(this)
        goForeground()
        ensureFloating()
        return START_STICKY
    }

    private fun goForeground() {
        val n = Notifier.keepAlive(this, FloatingService.isRunning())
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    Notifier.ID_KEEPALIVE, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(Notifier.ID_KEEPALIVE, n)
            }
        } catch (e: Exception) {
            // 前台服务被系统限制时不要崩，后台逻辑仍尽量工作
        }
    }

    private fun refreshNotification() {
        try {
            Notifier.updateKeepAlive(this, FloatingService.isRunning())
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun ensureFloating() {
        if (!Prefs.floatingEnabled) return
        if (!AppUtil.canDrawOverlay(this)) return
        if (!FloatingService.isRunning()) FloatingService.start(this)
    }

    override fun onStateChanged() {
        refreshNotification()
    }

    override fun onRecordsChanged() {
        refreshNotification()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        Bus.unregister(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile
        var running: Boolean = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                running = true
            } catch (e: Exception) {
                running = false
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, KeepAliveService::class.java))
            } catch (e: Exception) {
                // ignore
            }
            running = false
        }
    }
}
