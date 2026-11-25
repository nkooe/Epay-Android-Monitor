package com.yjk.monitor.core

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * 记录仓库：内存 + JSON 文件双写，重启不丢。
 * 上限由「设置 - 记录保留条数」控制（默认 300 条），滚动淘汰。
 */
object RecordStore {

    private const val FILE_NAME = "records.json"
    private lateinit var file: File
    private val list = ArrayList<PayRecord>()
    private val io = Executors.newSingleThreadExecutor()

    /** 今日统计：count=上报成功条数，amount=其中识别到的金额合计 */
    class Stats(val count: Int, val amount: Double)

    @Volatile
    private var inited = false

    fun init(ctx: Context) {
        if (inited) return
        file = File(ctx.applicationContext.filesDir, FILE_NAME)
        load()
        inited = true
    }

    private fun load() {
        synchronized(list) {
            list.clear()
            try {
                if (file.exists()) {
                    val arr = JSONArray(file.readText())
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        PayRecord.fromJson(o)?.let { list.add(it) }
                    }
                }
            } catch (e: Exception) {
                // 文件损坏：忽略并继续（下次写入会覆盖）
            }
        }
    }

    fun all(): List<PayRecord> = synchronized(list) { ArrayList(list) }

    fun size(): Int = synchronized(list) { list.size }

    fun add(r: PayRecord) {
        synchronized(list) {
            list.add(0, r)
            val limit = Prefs.recordLimit.coerceIn(50, 5000)
            while (list.size > limit) list.removeAt(list.size - 1)
        }
        persist()
        Bus.fireRecordsChanged()
    }

    fun updateStatus(id: Long, status: Int) {
        var changed = false
        synchronized(list) {
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0 && list[i].status != status) {
                list[i].status = status
                changed = true
            }
        }
        if (changed) {
            persist()
            Bus.fireRecordsChanged()
        }
    }

    /**
     * 重新上报前：只清空上一次的响应，**保留原始请求地址与请求体**。
     * 重试 = 把存下来的请求原封不动重发一次。
     */
    fun markSending(id: Long) {
        synchronized(list) {
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) {
                list[i].status = RecordStatus.PENDING
                list[i].response = ""
            }
        }
        persist()
        Bus.fireRecordsChanged()
    }

    /** 写回上报结果（状态 + 请求地址 / 请求体 / 响应体） */
    fun updateResult(id: Long, status: Int, url: String, body: String, response: String) {
        var changed = false
        synchronized(list) {
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) {
                val r = list[i]
                r.status = status
                r.requestUrl = url
                r.requestBody = body
                r.response = response
                changed = true
            }
        }
        if (changed) {
            persist()
            Bus.fireRecordsChanged()
        }
    }

    /** 还没上报成功的记录（进程被杀时留下的队列），用于启动补发 */
    fun pending(): List<PayRecord> = synchronized(list) {
        list.filter { it.status == RecordStatus.PENDING }
    }

    fun statusOf(id: Long): Int = synchronized(list) { list.firstOrNull { it.id == id }?.status ?: -1 }

    fun remove(id: Long) {
        val removed = synchronized(list) { list.removeAll { it.id == id } }
        if (removed) {
            persist()
            Bus.fireRecordsChanged()
        }
    }

    fun clear() {
        synchronized(list) { list.clear() }
        persist()
        Bus.fireRecordsChanged()
    }

    /** 今日累计 */
    /**
     * 今日统计：**只统计上报成功（SENT）的记录**。
     * 抓到但没转发成功的不算，失败数单独看 failedCount()。
     */
    fun todayStats(): Stats {
        val start = todayStart()
        var count = 0
        var amount = 0.0
        synchronized(list) {
            for (r in list) {
                if (r.time < start) continue
                if (r.status != RecordStatus.SENT) continue
                count++
                if (r.hasAmount) amount += r.amount
            }
        }
        return Stats(count, amount)
    }

    /** 今日抓到的总条数（含失败/未转发），用于展示"抓到多少" */
    fun todayCaptured(): Int {
        val start = todayStart()
        return synchronized(list) { list.count { it.time >= start } }
    }

    private fun todayStart(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun pendingCount(): Int = synchronized(list) { list.count { it.status == RecordStatus.PENDING } }

    fun failedCount(): Int = synchronized(list) { list.count { it.status == RecordStatus.FAILED } }

    fun sentTodayCount(): Int {
        val start = dayStart()
        return synchronized(list) { list.count { it.time >= start && it.status == RecordStatus.SENT } }
    }

    private fun dayStart(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun persist() {
        if (!inited) return
        val snapshot = synchronized(list) {
            JSONArray().apply { list.forEach { put(it.toJson()) } }
        }
        io.execute {
            try {
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(snapshot.toString())
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            } catch (e: Exception) {
                // 磁盘写入失败不影响内存中的记录
            }
        }
    }
}
