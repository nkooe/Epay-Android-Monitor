package com.yjk.monitor.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 极简进程内事件总线：记录变化、状态变化。
 * 所有回调都投递到主线程，页面/悬浮窗直接改 UI 即可。
 */
object Bus {

    interface Listener {
        fun onRecordAdded(record: PayRecord) {}
        fun onRecordsChanged() {}
        fun onStateChanged() {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())

    fun register(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun unregister(l: Listener) {
        listeners.remove(l)
    }

    fun fireRecordAdded(record: PayRecord) {
        main.post { listeners.forEach { it.onRecordAdded(record) } }
    }

    fun fireRecordsChanged() {
        main.post { listeners.forEach { it.onRecordsChanged() } }
    }

    fun fireStateChanged() {
        main.post { listeners.forEach { it.onStateChanged() } }
    }
}
