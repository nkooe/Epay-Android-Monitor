package com.yjk.monitor.core

import org.json.JSONObject
import java.util.Locale

/** 推送状态 */
object RecordStatus {
    const val PENDING = 0     // 待推送
    const val SENT = 1        // 已推送
    const val FAILED = 2      // 推送失败
    const val LOCAL_ONLY = 3  // 仅本地记录（推送关闭/免打扰/通道未配置）

    fun label(s: Int): String = when (s) {
        SENT -> "已转发"
        FAILED -> "转发失败"
        LOCAL_ONLY -> "未转发"
        else -> "待转发"
    }
}

/**
 * 一条「收款到账」记录。
 * 由 PayParser 从原始通知解析出来，之后所有页面与推送都只认这个结构。
 */
data class PayRecord(
    val id: Long,
    val time: Long,
    val pkg: String,
    val appName: String,
    val channelName: String,
    val title: String,
    val text: String,
    val amount: Double,
    val payer: String,
    val orderNo: String,
    var status: Int = RecordStatus.PENDING,
    /** 最近一次上报的请求地址 / 请求体 / 响应体，供日志详情查看 */
    var requestUrl: String = "",
    var requestBody: String = "",
    var response: String = ""
) {
    val hasAmount: Boolean get() = amount > 0

    val amountText: String
        get() = if (amount > 0) formatAmount(amount) else "未识别"

    /** 本地通知 / 悬浮窗用的一行摘要 */
    fun summary(): String {
        val who = if (payer.isNotEmpty()) " · $payer" else ""
        return if (hasAmount) "$channelName ${amountText} 元$who" else "$appName$who"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("time", time)
        put("pkg", pkg)
        put("app", appName)
        put("channel", channelName)
        put("title", title)
        put("text", text)
        put("amount", amount)
        put("payer", payer)
        put("orderNo", orderNo)
        put("status", status)
        put("requestUrl", requestUrl)
        put("requestBody", requestBody)
        put("response", response)
    }

    companion object {
        fun formatAmount(v: Double): String = String.format(Locale.CHINA, "%.2f", v)

        fun fromJson(o: JSONObject): PayRecord? = try {
            PayRecord(
                id = o.optLong("id"),
                time = o.optLong("time"),
                pkg = o.optString("pkg"),
                appName = o.optString("app"),
                channelName = o.optString("channel"),
                title = o.optString("title"),
                text = o.optString("text"),
                amount = o.optDouble("amount", -1.0),
                payer = o.optString("payer"),
                orderNo = o.optString("orderNo"),
                status = o.optInt("status", RecordStatus.PENDING),
                requestUrl = o.optString("requestUrl"),
                requestBody = o.optString("requestBody"),
                response = o.optString("response")
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** 今日统计 */
data class Stats(val count: Int, val amount: Double) {
    val amountText: String get() = PayRecord.formatAmount(amount)
}
