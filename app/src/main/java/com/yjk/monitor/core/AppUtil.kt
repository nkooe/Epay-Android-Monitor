package com.yjk.monitor.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.text.Collator
import java.util.Locale

data class AppEntry(val pkg: String, val label: String, val system: Boolean)

/**
 * 应用列表 / 权限状态 / 系统设置跳转。
 */
object AppUtil {

    /**
     * 已安装应用列表。
     *
     * 注意：国产 ROM（ColorOS / HyperOS 等）会对 targetSdk>=30 的应用限制
     * getInstalledApplications（即使声明了 QUERY_ALL_PACKAGES，也可能返回空），
     * 所以这里先用「桌面启动器查询」拿全部可见应用，再用 getInstalledApplications 补漏，
     * 两条路合并去重，最大限度保证列表不是空的。
     */
    fun installedApps(ctx: Context, includeSystem: Boolean): List<AppEntry> {
        val pm = ctx.packageManager
        val map = LinkedHashMap<String, AppEntry>()

        fun put(ai: ApplicationInfo) {
            if (ai.packageName == ctx.packageName) return
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val label = try {
                ai.loadLabel(pm).toString()
            } catch (e: Exception) {
                ai.packageName
            }
            map[ai.packageName] = AppEntry(ai.packageName, label, isSystem)
        }

        // 1) 桌面可启动的应用（国产 ROM 普遍放行这条路）
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val list = pm.queryIntentActivities(intent, 0)
            for (ri in list) {
                val ai = ri.activityInfo?.applicationInfo ?: continue
                put(ai)
            }
        } catch (e: Exception) {
            // ignore
        }

        // 2) 兜底：部分 ROM 上能直接拿到全部应用（含系统应用）
        try {
            @Suppress("DEPRECATION")
            val list = pm.getInstalledApplications(0)
            for (ai in list) put(ai)
        } catch (e: Exception) {
            // ignore
        }

        val out = map.values.filter { includeSystem || !it.system }
        val coll = Collator.getInstance(Locale.CHINA)
        return out.sortedWith(Comparator { a, b -> coll.compare(a.label, b.label) })
    }

    fun labelOf(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    fun iconOf(ctx: Context, pkg: String): Drawable? = try {
        ctx.packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) {
        null
    }

    // ---------- 权限状态 ----------

    /** 通知使用权是否已授予（监听服务的前提） */
    /** 通知监听是否真的连上了（权限在但被系统解绑时为 false） */
    fun isListenerConnected(): Boolean = com.yjk.monitor.service.PayListenerService.isConnected()

    /** 纯系统设置判断：通知使用权是否在系统名单里（不看服务是否连上） */
    fun hasListenerPermission(ctx: Context): Boolean {
        val flat = try {
            Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        } catch (e: Exception) {
            null
        } ?: return false
        return flat.split(':').any { it.contains(ctx.packageName) }
    }

    fun isNotificationListenerEnabled(ctx: Context): Boolean {
        // 有些 ROM（如 Android 15 的 ColorOS）会限制应用读取该 secure setting，
        // 返回 null 时会误判成"没授权"。这里以"服务是否真的连上"作为兜底。
        val connected = com.yjk.monitor.service.PayListenerService.isConnected()
        val flat = try {
            Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        } catch (e: Exception) {
            null
        }
        if (flat != null) {
            val mine = ComponentName(ctx.packageName, "com.yjk.monitor.service.PayListenerService")
            if (flat.split(':').any { it.contains(ctx.packageName) || it == mine.flattenToString() }) {
                return true
            }
        }
        return connected
    }

    /** 悬浮窗权限 */
    fun canDrawOverlay(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (e: Exception) {
        false
    }

    /** 系统是否限制了本应用的后台运行（Android 9+） */
    fun isBackgroundRestricted(ctx: Context): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                .isBackgroundRestricted
        } else false
    } catch (e: Exception) {
        false
    }

    /** 是否已允许后台运行（忽略电池优化） */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) {
        true
    }

    fun notificationsEnabled(ctx: Context): Boolean = try {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        // areNotificationsEnabled 从 API 24 才有
        if (Build.VERSION.SDK_INT >= 24) nm.areNotificationsEnabled() else true
    } catch (e: Exception) {
        true
    }

    /**
     * Android 13+ 会把「非应用商店安装」的 APK 标记为受限制（Restricted Settings）：
     * 通知使用权、悬浮窗这类敏感权限的开关会被系统直接拒绝，必须先到
     * 设置 → 应用 → 易监控 → 右上角「⋮」→ 允许受限制的设置 里解锁。
     * 这里只是"很可能受限"的启发式判断，用于给用户提示，不参与功能决策。
     */
    fun mayBeRestrictedSettings(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val installer = try {
            if (Build.VERSION.SDK_INT >= 30) {
                ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getInstallerPackageName(ctx.packageName)
            }
        } catch (e: Exception) {
            null
        }
        // 装不了来源信息 -> 大概率是侧载；adb 安装的通常不受限，商店安装的不受限
        if (installer == null) return true
        val low = installer.lowercase(Locale.ROOT)
        if (low.contains("shell") || low.contains("adb")) return false
        return STORES.none { low.contains(it) }
    }

    private val STORES = listOf(
        "vending", "market", "appmarket", "appstore", "galaxyapps",
        "qqdownloader", "huawei", "xiaomi", "oppo", "vivo", "samsung", "amazon", "gpp"
    )

    // ---------- 系统设置跳转 ----------

    fun openNotificationAccess(ctx: Context) {
        var i = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        if (startSafe(ctx, i)) return
        i = Intent(Settings.ACTION_SECURITY_SETTINGS)
        if (startSafe(ctx, i)) return
        openAppDetails(ctx)
    }

    fun openOverlaySettings(ctx: Context) {
        val i = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${ctx.packageName}")
        )
        if (startSafe(ctx, i)) return
        startSafe(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
    }

    fun requestIgnoreBattery(ctx: Context) {
        val i = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${ctx.packageName}")
        )
        if (startSafe(ctx, i)) return
        openBatterySettings(ctx)
    }

    fun openBatterySettings(ctx: Context) {
        if (startSafe(ctx, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))) return
        startSafe(ctx, Intent(Settings.ACTION_SETTINGS))
    }

    /** 安装来源包名（null/空 = adb、MT 管理器等非系统安装器） */
    fun installerOf(ctx: Context): String = try {
        ctx.packageManager.getInstallerPackageName(ctx.packageName) ?: ""
    } catch (e: Exception) {
        ""
    }

    /** 是否由系统安装器安装（决定会不会被 Android 13+ 的「受限设置」上锁） */
    fun installedBySystem(ctx: Context): Boolean {
        val i = installerOf(ctx)
        if (i.isEmpty()) return false
        return i.contains("packageinstaller") || i.contains("package.installer") ||
            i == "com.google.android.packageinstaller" || i == "com.android.vending"
    }

    /**
     * 打开「自启动 / 后台限制」页面。
     * 各 ROM 没有统一 API，这里按常见实现依次尝试，全部不可用再退回应用详情页。
     */
    /**
     * 自启动管理页跳转表，按 Build.MANUFACTURER 分组。
     * 结构照抄码支付脱壳后的 BootReceiverManager.autoStartActivities：
     * 每组最后可以放一个「包名」，表示组件都打不开时，直接打开该 App 首页兜底。
     */
    private val AUTOSTART_PAGES: Map<String, List<String>> = mapOf(
        "xiaomi" to listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.securitycenter"
        ),
        "redmi" to listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.securitycenter"
        ),
        "huawei" to listOf(
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager/.optimize.process.ProtectActivity",
            "com.huawei.systemmanager/.optimize.bootstart.BootStartActivity",
            "com.huawei.systemmanager"
        ),
        "honor" to listOf(
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager"
        ),
        "oppo" to listOf(
            "com.coloros.oppoguardelf/com.coloros.oppoguardelf.MainActivity",
            "com.coloros.oppoguardelf/com.oppo.oppoguardelf.MainActivity",
            "com.coloros.safecenter",
            "com.oppo.safe"
        ),
        "oneplus" to listOf(
            "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity",
            "com.oneplus.security"
        ),
        "realme" to listOf(
            "com.coloros.oppoguardelf/com.coloros.oppoguardelf.MainActivity",
            "com.coloros.safecenter"
        ),
        "vivo" to listOf(
            "com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure/.safeguard.PurviewTabActivity",
            "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
            "com.iqoo.secure",
            "com.vivo.permissionmanager"
        ),
        "iqoo" to listOf(
            "com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure/.safeguard.PurviewTabActivity",
            "com.iqoo.secure"
        ),
        "meizu" to listOf(
            "com.meizu.safe/.security.SeeAllAppActivity",
            "com.meizu.safe"
        ),
        "samsung" to listOf(
            "com.samsung.android.sm_cn/.ui.rampmode.RamModeSettingsActivity",
            "com.samsung.android.sm_cn/.ui.battery.BatteryActivity",
            "com.samsung.android.sm_cn"
        ),
        "letv" to listOf(
            "com.letv.android.letvsafe/.AutobootManageActivity",
            "com.letv.android.letvsafe/.BackgroundAppManageActivity",
            "com.letv.android.letvsafe"
        ),
        "zte" to listOf(
            "com.zte.heartyservice/.autorun.AppAutoRunManager",
            "com.zte.heartyservice"
        ),
        "smartisan" to listOf(
            "com.smartisanos.security/.invoke.InvokeActivity",
            "com.smartisanos.security"
        ),
        "lenovo" to listOf("com.lenovo.security/.autorun.AutoRunListActivity", "com.lenovo.security"),
        "360" to listOf("com.qihoo360.mobilesafe/.ui.autorun.AutoRunActivity", "com.qihoo360.mobilesafe")
    )

    /**
     * 真正启用/禁用开机广播接收器。
     * 只改 Prefs 的话广播照样送达，只是我们不处理 —— 这里直接把组件禁用掉。
     */
    fun setBootReceiverEnabled(ctx: Context, enabled: Boolean) {
        try {
            ctx.packageManager.setComponentEnabledSetting(
                android.content.ComponentName(ctx, com.yjk.monitor.service.BootReceiver::class.java),
                if (enabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            android.util.Log.d("YJK", "boot receiver enabled=$enabled")
        } catch (e: Exception) {
            android.util.Log.w("YJK", "setBootReceiverEnabled failed: " + e.message)
        }
    }

    /** 开机接收器当前是否处于启用状态 */
    fun isBootReceiverEnabled(ctx: Context): Boolean {
        return try {
            val st = ctx.packageManager.getComponentEnabledSetting(
                android.content.ComponentName(ctx, com.yjk.monitor.service.BootReceiver::class.java)
            )
            st != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                st != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        } catch (e: Exception) {
            true
        }
    }

    /** 无障碍服务是否已启用 */
    fun isAccessibilityEnabled(ctx: Context): Boolean {
        return try {
            val flat = Settings.Secure.getString(
                ctx.contentResolver, "enabled_accessibility_services"
            ) ?: return false
            flat.split(':').any { it.contains(ctx.packageName) }
        } catch (e: Exception) {
            false
        }
    }

    /** 打开系统「无障碍」设置页 */
    fun openAccessibilitySettings(ctx: Context) {
        try {
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            openAppDetails(ctx)
        }
    }

    /**
     * 自启动管理页跳转表：按厂商查表 → 逐个试 → 都不行则打开应用详情页 */
    fun openAutostartSettings(ctx: Context) {
        val key = (android.os.Build.MANUFACTURER ?: "").lowercase(java.util.Locale.ROOT)
        android.util.Log.d("YJK", "autostart: manufacturer=$key")
        val list = AUTOSTART_PAGES[key]
        if (list == null) {
            android.util.Log.w("YJK", "autostart: 未适配厂商 $key，跳应用详情")
            openAppDetails(ctx)
            return
        }
        for (item in list) {
            try {
                val intent: android.content.Intent? = if (item.contains("/")) {
                    android.content.ComponentName.unflattenFromString(item)?.let { cn ->
                        android.content.Intent().apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            component = cn
                        }
                    }
                } else {
                    ctx.packageManager.getLaunchIntentForPackage(item)?.apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                if (intent != null) {
                    ctx.startActivity(intent)
                    android.util.Log.d("YJK", "autostart -> $item")
                    return
                }
            } catch (e: Exception) {
                android.util.Log.w("YJK", "autostart failed: $item, ${e.message}")
            }
        }
        android.util.Log.w("YJK", "autostart: 全部失败，跳应用详情")
        openAppDetails(ctx)
    }

    /** 各家 ROM 的自启动设置路径不同，按厂商给对应文案（用户照着找） */
    fun autostartHint(): String {
        val m = (android.os.Build.MANUFACTURER ?: "").lowercase()
        val b = (android.os.Build.BRAND ?: "").lowercase()
        return when {
            m.contains("huawei") || b.contains("honor") ->
                "设置 → 应用 → 应用启动管理 → 关闭「自动管理」→ 打开「允许自启动」"
            m.contains("xiaomi") || b.contains("redmi") ->
                "设置 → 应用设置 → 授权管理 → 自启动管理 → 允许自启动"
            m.contains("oppo") || m.contains("oneplus") || m.contains("realme") || b.contains("oplus") ->
                "设置 → 应用 → 应用管理 → 本应用 → 自启动 / 允许后台活动（并关闭「应用速冻」）"
            m.contains("vivo") || b.contains("iqoo") ->
                "设置 → 应用与权限 → 权限管理 → 自启动 → 允许自启动"
            m.contains("samsung") ->
                "设置 → 电池 → 后台使用限制 → 从「休眠应用」里移除本应用"
            m.contains("meizu") ->
                "手机管家 → 权限管理 → 自启动 → 允许自启动"
            m.contains("lenovo") || b.contains("zuk") ->
                "乐安全 → 自启管理 → 允许自启动"
            m.contains("zte") || b.contains("nubia") ->
                "设置 → 权限管理 → 自启动 → 允许自启动"
            m.contains("smartisan") ->
                "设置 → 权限管理 → 自启动 → 允许自启动"
            else ->
                "设置 → 应用 → 本应用 → 自启动 / 允许后台活动"
        }
    }

    fun openAppDetails(ctx: Context) {
        startSafe(
            ctx,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        )
    }

    fun openAppSettings(ctx: Context) = openAppDetails(ctx)

    fun startSafe(ctx: Context, intent: Intent): Boolean = try {
        if (ctx !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    fun deviceName(): String = "${Build.BRAND} ${Build.MODEL}".trim()

    fun androidVersion(): String = Build.VERSION.RELEASE ?: ""
}
