package com.yjk.monitor.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 保活型无障碍服务。
 *
 * 不做任何自动点击，只利用一个事实：系统会优先保持无障碍服务的进程，
 * 进程不死，通知监听被解绑的概率就低。
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        android.util.Log.d("YJK", "accessibility connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不监听任何事件，纯保活
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    companion object {
        @Volatile
        var connected: Boolean = false
            private set
    }
}
