package com.googleflow.mcp.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import com.googleflow.mcp.FlowApplication
import com.googleflow.mcp.MainActivity
import com.googleflow.mcp.R
import com.googleflow.mcp.engine.FlowScraperEngine
import com.googleflow.mcp.server.FlowMcpServer

class FlowOverlayService : Service() {

    private val binder = LocalBinder()
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    lateinit var engine: FlowScraperEngine
        private set
    lateinit var server: FlowMcpServer
        private set

    private var isOverlayAttached = false

    inner class LocalBinder : Binder() {
        fun getService(): FlowOverlayService = this@FlowOverlayService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // Acquire CPU WakeLock so Android never throttles background CPU
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoogleFlowMCP::ServiceWakeLock").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) // 1 hour max safety
        }

        engine = FlowScraperEngine(this)
        server = FlowMcpServer(engine)
        
        startForeground(1001, createNotification("MCP Background Engine Active"))
        server.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1001, createNotification("MCP Server running on http://127.0.0.1:8765"))
        return START_STICKY
    }

    private fun createNotification(statusText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, FlowApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Transparent Full-Screen Pass-Through Window
     * Makes Chromium see a full 1080x1920 active layout so JS never freezes,
     * while touch events pass 100% through to apps below!
     */
    @SuppressLint("InflateParams")
    fun attachTo1x1Overlay() {
        if (isOverlayAttached) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            engine.bridge.log("Overlay permission not granted!")
            return
        }

        val webView = engine.webView ?: WebView(this).also { engine.attachWebView(it) }
        
        // Remove from previous parent if exists
        (webView.parent as? ViewGroup)?.removeView(webView)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 0.01f // Invisible to eye, touch passes through, but active to Chromium!
        }

        try {
            windowManager?.addView(webView, params)
            overlayView = webView
            isOverlayAttached = true
            webView.onResume()
            webView.resumeTimers()
            engine.bridge.log("Attached to transparent pass-through background window. Chromium running at full speed!")
        } catch (e: Exception) {
            engine.bridge.log("Failed to attach background overlay: ${e.message}")
        }
    }

    fun detachFromOverlay(): WebView? {
        if (!isOverlayAttached || overlayView == null) return engine.webView
        try {
            windowManager?.removeView(overlayView)
            isOverlayAttached = false
            engine.bridge.log("Detached from background overlay for full UI interaction.")
        } catch (e: Exception) {
            engine.bridge.log("Error detaching overlay: ${e.message}")
        }
        return engine.webView
    }

    override fun onDestroy() {
        super.onDestroy()
        server.stop()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        if (isOverlayAttached && overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {}
        }
    }
}
