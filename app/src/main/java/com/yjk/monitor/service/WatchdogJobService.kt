package com.yjk.monitor.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.yjk.monitor.core.Prefs

/**
 * 看门狗：进程被 ColorOS 杀掉后，靠系统的 JobScheduler 周期拉起。
 * ColorOS 对普通后台限制很严，但 JobScheduler 是系统级调度，存活率明显高于普通服务。
 * 每 15 分钟一次（Android 允许的最小周期），拉起后检查监听并尝试重绑。
 */
class WatchdogJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        try {
            if (Prefs.monitorEnabled) {
                if (!PayListenerService.isConnected()) {
                    android.util.Log.d("YJK", "watchdog: listener down, rebind")
                    PayListenerService.requestRebindIfNeeded(this)
                }
                KeepAliveService.start(this)
            }
        } catch (e: Exception) {
            android.util.Log.d("YJK", "watchdog failed: " + (e.message ?: ""))
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 1001

        fun schedule(ctx: Context) {
            try {
                val js = ctx.getSystemService(JobScheduler::class.java) ?: return
                val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, WatchdogJobService::class.java))
                    .setPeriodic(15 * 60_000L)
                    .setPersisted(true)
                    .build()
                js.schedule(job)
            } catch (e: Exception) {
                android.util.Log.d("YJK", "watchdog schedule failed: " + (e.message ?: ""))
            }
        }
    }
}
