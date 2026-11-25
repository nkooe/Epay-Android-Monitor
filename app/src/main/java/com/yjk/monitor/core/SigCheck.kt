package com.yjk.monitor.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * 完整性自检：签名 / 调试器 / 注入痕迹。
 *
 * 设计要点：
 *  - 校验点分散在 Application、上报前、监听绑定后，只删一处绕不过去
 *  - 失败不弹"请勿破解"这种提示，静默停止工作，改包者拿不到可用产品
 *  - 期望签名分段拼接，避免 strings 一把搜出完整哈希
 */
object SigCheck {

    /** 正式 keystore 的证书 SHA-256，分段存储 */
    private fun expected(): String = StringBuilder(64)
        .append("b2181440").append("b7a474d6")
        .append("38e19e3e").append("7f9b2e49")
        .append("4921960f").append("c1592b07")
        .append("a607972c").append("fa0dfc7a")
        .toString()

    private const val UNKNOWN = 0
    private const val GOOD = 1
    private const val BAD = 2

    @Volatile
    private var sigState = UNKNOWN

    /** 签名是否可信；release 构建才校验，debug 直接放行 */
    fun signatureOk(ctx: Context): Boolean {
        if (com.yjk.monitor.BuildConfig.DEBUG) return true
        val s = sigState
        if (s == GOOD) return true
        if (s == BAD) return false

        val ok = try {
            val pm = ctx.packageManager
            val info = if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            }
            val certs = if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            if (certs.isNullOrEmpty()) {
                false
            } else {
                val md = MessageDigest.getInstance("SHA-256")
                var hit = false
                for (c in certs) {
                    val hex = md.digest(c.toByteArray())
                        .joinToString("") { String.format("%02x", it) }
                    if (hex == expected()) {
                        hit = true
                        break
                    }
                }
                hit
            }
        } catch (e: Throwable) {
            false
        }

        sigState = if (ok) GOOD else BAD
        return ok
    }

    @Volatile
    private var cleanState = UNKNOWN

    /** 后台预热：把扫描 /proc 的耗时挪出启动路径 */
    fun warmUp() {
        try {
            runtimeClean()
        } catch (e: Throwable) {
        }
    }

    /** 是否被调试或注入；release 构建才校验。结果缓存，避免每次上报都扫 /proc */
    fun runtimeClean(): Boolean {
        if (com.yjk.monitor.BuildConfig.DEBUG) return true
        val cached = cleanState
        if (cached == GOOD) return true
        if (cached == BAD) return false
        val ok = doRuntimeCheck()
        cleanState = if (ok) GOOD else BAD
        return ok
    }

    private fun doRuntimeCheck(): Boolean {
        return try {
            if (android.os.Debug.isDebuggerConnected()) return false
            // 被 ptrace 附加时 TracerPid 非 0
            val status = java.io.File("/proc/self/status")
            if (status.canRead()) {
                status.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("TracerPid:")) {
                            val pid = line.substringAfter(':').trim().toIntOrNull() ?: 0
                            if (pid != 0) return false
                        }
                    }
                }
            }
            // 常见注入框架的加载痕迹
            val maps = java.io.File("/proc/self/maps")
            if (maps.canRead()) {
                maps.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val n = line.lowercase()
                        if (n.contains("frida") || n.contains("xposed") || n.contains("substrate")) {
                            return false
                        }
                    }
                }
            }
            true
        } catch (e: Throwable) {
            // 检测本身失败时不判定为篡改，避免误伤
            true
        }
    }

    /** 关键路径统一入口 */
    fun trusted(ctx: Context): Boolean = signatureOk(ctx) && runtimeClean()
}
