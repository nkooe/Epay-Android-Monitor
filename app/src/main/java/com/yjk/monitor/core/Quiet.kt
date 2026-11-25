package com.yjk.monitor.core

import java.util.Calendar

/** 免打扰时段判断（支持跨零点，如 23:00 - 07:30） */
object Quiet {

    fun isQuietNow(): Boolean {
        if (!Prefs.quietEnabled) return false
        val s = parse(Prefs.quietStart)
        val e = parse(Prefs.quietEnd)
        if (s < 0 || e < 0) return false
        val c = Calendar.getInstance()
        val cur = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (s <= e) cur in s until e else (cur >= s || cur < e)
    }

    private fun parse(t: String): Int {
        val s = t.trim().replace('：', ':')
        val parts = s.split(':')
        if (parts.size != 2) return -1
        val h = parts[0].trim().toIntOrNull() ?: return -1
        val m = parts[1].trim().toIntOrNull() ?: return -1
        if (h !in 0..23 || m !in 0..59) return -1
        return h * 60 + m
    }
}
