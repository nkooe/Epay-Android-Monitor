package com.yjk.monitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import com.yjk.monitor.R
import com.yjk.monitor.core.PayRecord
import com.yjk.monitor.core.Prefs
import com.yjk.monitor.core.RecordStore
import com.yjk.monitor.ui.MainActivity

/**
 * 通知栏：常驻通知（前台服务） + 到账提醒。
 */
object Notifier {

    const val CH_KEEPALIVE = "yjk_keepalive"
    const val CH_ALERT = "yjk_alert"

    const val ID_KEEPALIVE = 1001
    const val ID_FLOATING = 1002
    private const val ID_ALERT = 2001
    private const val ID_LISTENER = 1003
    const val ID_TEST = 1900

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        val keep = NotificationChannel(
            CH_KEEPALIVE,
            ctx.getString(R.string.channel_keepalive),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = ctx.getString(R.string.channel_keepalive_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }

        val alert = NotificationChannel(
            CH_ALERT,
            ctx.getString(R.string.channel_alert),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "收到勾选应用的收款通知时提醒"
            enableLights(true)
            lightColor = Color.GREEN
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 120, 80, 120)
        }

        nm.createNotificationChannel(keep)
        nm.createNotificationChannel(alert)
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            ctx, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun builder(ctx: Context, channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(ctx, channel)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx)
        }

    /** 常驻通知：显示今日战绩与运行状态 */
    fun keepAlive(ctx: Context, floating: Boolean = false): Notification {
        val stats = RecordStore.todayStats()
        val listening = com.yjk.monitor.core.AppUtil.isNotificationListenerEnabled(ctx)
        val state = if (listening) "监听中" else "未授权监听"
        val title = if (floating) "易监控 · 悬浮窗已开启" else "易监控正在后台运行"
        val amount = String.format(java.util.Locale.CHINA, "%.2f", stats.amount)
        val text = "${state} · 今日 ${stats.count} 笔 / ¥${amount}元" +
            if (Prefs.pushEnabled && com.yjk.monitor.push.Pusher.isConfigured()) " · 推送已开启" else " · 推送未开启"

        val b = builder(ctx, CH_KEEPALIVE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp(ctx))
        if (Build.VERSION.SDK_INT >= 21) b.setColor(0xFF07C160.toInt())
        return b.build()
    }

    fun floatingNote(ctx: Context): Notification =
        builder(ctx, CH_KEEPALIVE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("易监控悬浮窗运行中")
            .setContentText("点击可回到应用，长按可关闭")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp(ctx))
            .build()

    /** 到账提醒（本地通知，和远端推送独立） */
    /** 「发送测试通知」：往通知栏丢一条模拟收款，用来验证 抓取→上报 这条链路 */
    fun testNotification(ctx: Context) {
        val b = builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("微信支付")
            .setContentText("微信支付收款 0.01 元（测试通知）")
            .setStyle(
                Notification.BigTextStyle().bigText("微信支付收款 0.01 元（测试通知）")
            )
            .setAutoCancel(true)
            .setContentIntent(openListenerSettings(ctx))
        try {
            if (Build.VERSION.SDK_INT >= 26) b.setChannelId(CH_ALERT)
        } catch (e: Exception) {
            // ignore
        }
        try {
            ctx.getSystemService(NotificationManager::class.java)?.notify(ID_TEST, b.build())
        } catch (e: Exception) {
            // ignore
        }
    }

    /** 监听被系统解绑时的告警（可点进去修复） */
    /** 点通知直接进系统「通知使用权」页面，少一步操作 */
    private fun openListenerSettings(ctx: Context): android.app.PendingIntent {
        val i = android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return android.app.PendingIntent.getActivity(
            ctx, 1003, i,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (android.os.Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        )
    }

    fun listenerBroken(ctx: Context) {
        val b = builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("易监控：通知监听已断开")
            .setContentText("系统解绑了监听服务，收不到任何通知，点此重新授权")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "系统解绑了通知监听服务，现在收不到任何通知。\n" +
                        "点这条通知打开易监控，在「权限」里把通知使用权关一次再打开即可恢复。"
                )
            )
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
        try {
            if (Build.VERSION.SDK_INT >= 26) b.setChannelId(CH_ALERT)
        } catch (e: Exception) {
            // ignore
        }
        try {
            ctx.getSystemService(NotificationManager::class.java)?.notify(ID_LISTENER, b.build())
        } catch (e: Exception) {
            // ignore
        }
    }

    fun clearListenerAlert(ctx: Context) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(ID_LISTENER)
        } catch (e: Exception) {
            // ignore
        }
    }

    fun alert(ctx: Context, rec: PayRecord) {
        val title = if (rec.hasAmount) "收款到账 ¥${rec.amountText}" else "${rec.appName} · 新通知"
        val body = buildString {
            if (rec.title.isNotBlank()) append(rec.title)
            if (rec.text.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(rec.text.replace("\n", " ").take(120))
            }
            if (rec.hasAmount) {
                if (isNotEmpty()) append('\n')
                append("金额 ¥").append(rec.amountText)
                if (rec.payer.isNotBlank()) append("  ·  ").append(rec.payer)
            }
        }.ifBlank { "收到一条通知" }
        val b = builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .setStyle(Notification.BigTextStyle().bigText(body))
        if (Build.VERSION.SDK_INT >= 21) b.setColor(0xFF07C160.toInt())
        if (Prefs.notifyEnabled) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                // 固定 id：到账提醒只保留最新一条，不堆叠
                nm.notify(ID_ALERT, b.build())
            } catch (e: Exception) {
                // 没有通知权限时静默失败
            }
        }
    }

    fun cancelAlert(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancelAll()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun updateKeepAlive(ctx: Context, floating: Boolean = false) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(ID_KEEPALIVE, keepAlive(ctx, floating))
        } catch (e: Exception) {
            // ignore
        }
    }
}
