package com.yjk.monitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yjk.monitor.core.AppUtil
import com.yjk.monitor.core.Prefs

/**
 * 开机 / 解锁 / 应用被覆盖安装后自动拉起监听与悬浮窗。
 * （国产 ROM 还需要在"自启动管理"里放行，否则收不到这个广播）
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        val isUnlock = action == Intent.ACTION_USER_PRESENT
        val isUpdate = action == Intent.ACTION_MY_PACKAGE_REPLACED

        // 注意：ACTION_POWER_CONNECTED / DISCONNECTED 从 Android 8.0 起
        // 不在隐式广播豁免列表里，静态注册收不到，所以不监听充电事件。
        if (!isBoot && !isUnlock && !isUpdate) return

        // 覆盖安装一定会杀掉旧进程。这时必须无条件恢复，
        // 否则要等用户下次手动打开 App 才能重新收到通知。
        if (isUpdate) {
            restoreAfterUpdate(context)
            return
        }

        if (!Prefs.bootEnabled) return

        // 开机/解锁后立刻尝试一次
        PayListenerService.requestRebindIfNeeded(context)

        if (isBoot) {
            try {
                KeepAliveService.start(context)
            } catch (e: Throwable) {
            }
            if (Prefs.floatingEnabled && AppUtil.canDrawOverlay(context)) {
                try {
                    FloatingService.start(context)
                } catch (e: Throwable) {
                }
            }
        } else if (isUnlock) {
            // 解锁时系统才真正开始绑定通知监听，这时再拉一次保活服务
            try {
                KeepAliveService.start(context)
            } catch (e: Throwable) {
            }
        }

        // 开机早期系统未必就绪：用 goAsync 在后台多试几次，覆盖绑定延迟
        if (isBoot) {
            val pending = goAsync()
            Thread {
                for (delay in longArrayOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L)) {
                    try {
                        Thread.sleep(delay)
                        if (PayListenerService.isConnected()) break
                        PayListenerService.requestRebindIfNeeded(context)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
                pending.finish()
            }.start()
        }
    }

    /**
     * 覆盖安装后的恢复。
     *
     * startService 会拉起进程并执行 App.onCreate，那里已经有 forceRebind 逻辑；
     * 这里再补几次重试，覆盖"服务建出来但系统还没绑定"的窗口。
     *
     * 注意：Android 12+ 禁止后台启动前台服务，KeepAliveService 用 try 包住，
     * 失败也不影响通知监听（监听由系统绑定，不依赖前台服务）。
     */
    private fun restoreAfterUpdate(context: Context) {
        val pending = goAsync()
        Thread({
            try {
                try {
                    context.startService(Intent(context, PayListenerService::class.java))
                } catch (e: Throwable) {
                }
                try {
                    KeepAliveService.start(context)
                } catch (e: Throwable) {
                }
                for (delay in longArrayOf(800L, 2_000L, 4_000L, 7_000L)) {
                    Thread.sleep(delay)
                    if (PayListenerService.isConnected()) break
                    PayListenerService.requestRebindIfNeeded(context)
                }
            } catch (e: Throwable) {
            }
            pending.finish()
        }, "yjk-restore").start()
    }
}
