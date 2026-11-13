package cz.k4.salamanderdroid

import android.app.Service
import android.content.Intent
import android.os.IBinder

class OpService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
