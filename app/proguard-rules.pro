# 易监控 - R8 规则

# 系统会按类名反射创建组件；通知使用权授权记录里也存的是组件名，
# 改名会导致已授权失效，因此组件类名全部保留。
-keep class com.yjk.monitor.App { *; }
-keep class com.yjk.monitor.ui.MainActivity { *; }
-keep class com.yjk.monitor.ui.ScanActivity { *; }
-keep class com.yjk.monitor.ui.AppsActivity { *; }
-keep class com.yjk.monitor.service.PayListenerService { *; }
-keep class com.yjk.monitor.service.KeepAliveService { *; }
-keep class com.yjk.monitor.service.FloatingService { *; }
-keep class com.yjk.monitor.service.SendJobService { *; }
-keep class com.yjk.monitor.service.WatchdogJobService { *; }
-keep class com.yjk.monitor.service.KeepAliveAccessibilityService { *; }
-keep class com.yjk.monitor.service.BootReceiver { *; }

# 无障碍服务由系统按类名注册
-keepnames class * extends android.accessibilityservice.AccessibilityService
# JobService / NotificationListenerService 同理
-keepnames class * extends android.app.job.JobService
-keepnames class * extends android.service.notification.NotificationListenerService

# 注解与泛型签名
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# 不打印混淆映射警告
-dontwarn org.jetbrains.annotations.**

# 混淆增强：允许跨包重排，进一步打乱结构
-allowaccessmodification
-overloadaggressively

# 保留崩溃栈可读性所需的最小信息
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
