package com.yjk.monitor.core

/**
 * 通知级去重：以通知栏为准，同一条通知只转发一次。
 *
 * 系统会对同一个通知多次回调 onNotificationPosted（刷新、更新、重新排序），
 * 这里按「通知 key + 标题 + 正文」判重，窗口内重复回调直接丢弃。
 * 内容变了（例如同一个会话来了新消息）算新通知，照常转发。
 *
 * 这不是业务判定，只是避免同一条通知被重复搬运。
 */
object NotifDeduper {

    private const val WINDOW_MS = 60_000L
    private const val MAX_KEYS = 500

    private val lastSeen = HashMap<String, Long>()

    fun isDuplicate(key: String, title: String, text: String): Boolean = synchronized(lastSeen) {
        val now = System.currentTimeMillis()
        val k = key + "|" + title.hashCode() + "|" + text.hashCode()

        if (lastSeen.size > MAX_KEYS) {
            lastSeen.entries.removeAll { now - it.value > WINDOW_MS }
            if (lastSeen.size > MAX_KEYS) lastSeen.clear()
        }

        val last = lastSeen[k]
        lastSeen[k] = now
        last != null && now - last < WINDOW_MS
    }
}
