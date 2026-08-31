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
        engine = FlowScraperEngine(this)
        server = FlowMcpServer(engine)
        
        startForeground(1001, createNotification("Service Initialized"))
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
            1, // 1 pixel width
            1, // 1 pixel height
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager?.addView(webView, params)
            overlayView = webView
            isOverlayAttached = true
            engine.bridge.log("Attached to 1x1 background overlay successfully.")
        } catch (e: Exception) {
            engine.bridge.log("Failed to attach 1x1 overlay: ${e.message}")
        }
    }

    fun detachFromOverlay(): WebView? {
        if (!isOverlayAttached || overlayView == null) return engine.webView
        try {
            windowManager?.removeView(overlayView)
            isOverlayAttached = false
            engine.bridge.log("Detached from 1x1 overlay for full UI interaction.")
        } catch (e: Exception) {
            engine.bridge.log("Error detaching overlay: ${e.message}")
        }
        return engine.webView
    }

    override fun onDestroy() {
        super.onDestroy()
        server.stop()
        if (isOverlayAttached && overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {}
        }
    }
}
