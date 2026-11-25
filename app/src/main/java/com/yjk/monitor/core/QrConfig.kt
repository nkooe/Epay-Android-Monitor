package com.yjk.monitor.core

import android.util.Base64
import org.json.JSONObject

/**
 * 扫码配置串解析，尽量容错：
 *  - base64（标准 / URL-safe / 缺省填充）里套 JSON
 *  - 直接是 JSON
 *  - 直接是上报地址（从路径取 PID，从 query 取 token/key）
 *
 * 常见字段：site/domain/host/url、pid/appid/app_id/merchant/id、token/key/secret
 */
object QrConfig {

    data class Conf(val site: String, val pid: String, val token: String)

    fun parse(raw: String): Conf? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // 1) 直接是网址
        if (text.startsWith("http://") || text.startsWith("https://")) {
            return fromUrl(text)
        }

        // 2) 直接是 JSON
        if (text.startsWith("{")) {
            return parseJson(text) ?: fromUrl(text)
        }

        // 3) base64（标准 / URL-safe / 无填充）
        val decoded = tryDecodeBase64(text) ?: return null
        val t = decoded.trim()
        if (t.startsWith("{")) return parseJson(t) ?: fromUrl(t)
        if (t.startsWith("http")) return fromUrl(t)
        return null
    }

    private fun tryDecodeBase64(text: String): String? {
        val candidates = listOf(text, text.replace('-', '+').replace('_', '/'))
        for (c in candidates) {
            val padded = when (c.length % 4) {
                2 -> c + "=="
                3 -> c + "="
                else -> c
            }
            for (flags in intArrayOf(Base64.DEFAULT, Base64.NO_WRAP, Base64.URL_SAFE or Base64.NO_WRAP)) {
                try {
                    val bytes = Base64.decode(padded, flags)
                    if (bytes.isNotEmpty()) {
                        val s = String(bytes, Charsets.UTF_8)
                        if (s.contains("site") || s.contains("token") || s.contains("pid") || s.startsWith("http")) {
                            return s
                        }
                    }
                } catch (e: Exception) {
                    // 换下一种
                }
            }
        }
        return null
    }

    private fun parseJson(json: String): Conf? {
        val t = json.trim()
        if (!t.startsWith("{")) return null
        return try {
            val o = JSONObject(t)
            val site = pick(o, "site", "domain", "host", "url", "base_url").trim().trimEnd('/')
            val pid = pick(o, "pid", "appid", "app_id", "merchant", "merchant_id", "id")
            val token = pick(o, "token", "key", "secret", "token_key", "sign_key")
            if (site.isEmpty()) {
                // site 可能被写成完整地址
                val u = pick(o, "url", "endpoint")
                if (u.startsWith("http")) return fromUrl(u)
                return null
            }
            Conf(site, pid, token)
        } catch (e: Exception) {
            null
        }
    }

    private fun pick(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            if (o.has(k) && !o.isNull(k)) {
                val v = o.opt(k)
                val s = if (v is Number) v.toLong().toString() else v.toString()
                if (s.isNotBlank()) return s
            }
        }
        return ""
    }

    private fun fromUrl(url: String): Conf? = try {
        val u = java.net.URI(url)
        val host = u.host ?: return null
        val site = (if (u.scheme.isNullOrBlank()) "https" else u.scheme) + "://" + host +
            (if (u.port > 0) ":" + u.port else "")
        val pid = (u.path ?: "").split('/').lastOrNull { it.isNotBlank() } ?: ""
        val token = (u.query ?: "").split('&').map { it.split('=') }
            .firstOrNull { it.size >= 2 && (it[0] == "token" || it[0] == "key" || it[0] == "secret") }
            ?.let { it[1] } ?: ""
        Conf(site, pid, token)
    } catch (e: Exception) {
        null
    }

    /** 写进配置，返回是否成功 */
    fun apply(raw: String): Boolean {
        val c = parse(raw) ?: return false
        android.util.Log.d("YJK", "qr -> site=" + c.site + " pid=" + c.pid + " keyLen=" + c.token.length)
        Prefs.site = c.site
        Prefs.pid = c.pid
        Prefs.token = c.token
        return true
    }
}
