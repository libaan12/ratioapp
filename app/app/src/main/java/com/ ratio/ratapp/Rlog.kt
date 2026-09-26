package com.ratio.ratapp

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

object Rlog {
    private const val TAG = "RATAPP"
    private var deviceKey: String? = null

    fun init(ctx: Context) {
        try {
            val aid = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
            deviceKey = aid
        } catch (_: Throwable) { deviceKey = "unknown" }
    }

    fun d(msg: String) = emit("debug", msg, null)
    fun i(msg: String) = emit("info", msg, null)
    fun w(msg: String, t: Throwable? = null) = emit("warn", msg, t)
    fun e(msg: String, t: Throwable? = null) = emit("error", msg, t)

    private fun emit(level: String, msg: String, t: Throwable?) {
        val line = "[$level] $msg" + (t?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        when (level) {
            "debug" -> Log.d(TAG, line)
            "info"  -> Log.i(TAG, line)
            "warn"  -> Log.w(TAG, line, t)
            "error" -> Log.e(TAG, line, t)
        }
        push(level, msg, t)
    }

    private fun push(level: String, msg: String, t: Throwable?) {
        val key = deviceKey ?: return
        try {
            val ref = FirebaseDatabase.getInstance()
                .getReference("ratio").child("debug").child(key).child("logs").push()
            val stack = t?.stackTraceToString()?.take(4000)
            ref.setValue(mapOf(
                "level" to level,
                "msg" to msg,
                "stack" to stack,
                "ts" to System.currentTimeMillis()
            ))
        } catch (_: Throwable) {
            // silent — logging should never crash the app
        }
    }
}