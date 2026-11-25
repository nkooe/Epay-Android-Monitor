package com.yjk.monitor.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.yjk.monitor.push.Pusher

/**
 * 发送队列的持久化载体。
 *
 * 码支付用 WorkManager 的 OneTimeWorkRequest 保证"进程被杀也不丢"，
 * 我们没有 AndroidX，改用系统 JobScheduler 达到同样语义：
 * App 里有待发记录时登记一个持久化 job，进程被杀后由系统重新拉起并补发。
 */
class SendJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            try {
                Pusher.resumePending()
            } catch (e: Exception) {
                android.util.Log.e("YJK", "send job failed: " + e.message)
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 1002

        /** 有记录待发时登记；重复调用只会替换同一个 job */
        fun schedule(ctx: Context) {
            try {
                val js = ctx.getSystemService(JobScheduler::class.java) ?: return
                val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, SendJobService::class.java))
                    .setPersisted(true)
                    .setMinimumLatency(1_000L)
                    .setOverrideDeadline(30_000L)
                    .build()
                js.schedule(job)
            } catch (e: Exception) {
                android.util.Log.w("YJK", "send job schedule failed: " + e.message)
            }
        }

        fun cancel(ctx: Context) {
            try {
                ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
            } catch (e: Exception) {
            }
        }
    }
}
