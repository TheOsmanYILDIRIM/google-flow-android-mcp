package com.googleflow.mcp.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class FlowScraperEngine(private val context: Context) {

    val bridge = FlowJsBridge()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val okHttpClient = OkHttpClient()
    private val gson = Gson()

    var webView: WebView? = null
        private set

    val flowUrl = "https://labs.google/fx/"
    val loginUrl = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Flabs.google%2Ffx%2F"
    
    // Clean Chrome Desktop User Agent without 'wv' or 'Version/4.0'
    private val cleanDesktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun attachWebView(view: WebView) {
        this.webView = view

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(view, true)

        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = cleanDesktopUserAgent
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = true
            allowContentAccess = true
        }

        // Strip X-Requested-With header to prevent Google from blocking embedded login
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            try {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.settings, emptySet())
                bridge.log("Successfully stripped X-Requested-With header.")
            } catch (e: Exception) {
                bridge.log("Could not set RequestedWithHeader allow list: ${e.message}")
            }
        }

        view.addJavascriptInterface(bridge, "AndroidBridge")

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                view?.loadUrl(url)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                bridge.log("Page started: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                cookieManager.flush()
                bridge.log("Page finished: $url")
                injectBridgeScript()
            }
        }

        // Handle Google Login popups and redirects cleanly inside the same WebView
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val newWebView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.userAgentString = cleanDesktopUserAgent
                    settings.domStorageEnabled = true
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)
                    
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                            val targetUrl = req?.url?.toString() ?: return false
                            this@FlowScraperEngine.webView?.loadUrl(targetUrl)
                            return true
                        }
                    }
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = newWebView
                resultMsg?.sendToTarget()
                return true
            }
        }

        view.loadUrl(flowUrl)
    }

    fun loadLoginUrl() {
        mainHandler.post {
            webView?.loadUrl(loginUrl)
        }
    }

    fun loadFlowUrl() {
        mainHandler.post {
            webView?.loadUrl(flowUrl)
        }
    }

    private fun injectBridgeScript() {
        try {
            val jsCode = context.assets.open("flow_bridge.js").bufferedReader().use { it.readText() }
            mainHandler.post {
                webView?.evaluateJavascript(jsCode) { result ->
                    bridge.log("FlowBridge v2.0 injected: $result")
                }
            }
        } catch (e: Exception) {
            bridge.log("Failed to inject FlowBridge: ${e.message}")
        }
    }

    fun generateImage(
        prompt: String,
        model: String = "nano-banana-2",
        aspectRatio: String = "1:1",
        count: Int = 1,
        callback: (String) -> Unit
    ): String {
        val taskId = UUID.randomUUID().toString()
        val safePrompt = prompt.replace("\"", "\\\"").replace("\n", " ")
        
        val options = JsonObject().apply {
            addProperty("model", model)
            addProperty("aspectRatio", aspectRatio)
            addProperty("count", count.coerceIn(1, 4))
        }
        val optionsJson = gson.toJson(options).replace("\"", "\\\"")

        mainHandler.post {
            val script = "window.FlowAutomation.generateImage('$taskId', \"$safePrompt\", \"$optionsJson\");"
            webView?.evaluateJavascript(script) { result ->
                bridge.log("Executed generateImage ($taskId) [model=$model, ratio=$aspectRatio, count=$count]: $result")
            }
        }
        callback(taskId)
        return taskId
    }

    fun generateWithReference(
        prompt: String,
        base64Image: String,
        mimeType: String,
        filename: String,
        model: String = "nano-banana-2",
        aspectRatio: String = "1:1",
        count: Int = 1,
        callback: (String) -> Unit
    ): String {
        val taskId = UUID.randomUUID().toString()
        val safePrompt = prompt.replace("\"", "\\\"").replace("\n", " ")

        val options = JsonObject().apply {
            addProperty("model", model)
            addProperty("aspectRatio", aspectRatio)
            addProperty("count", count.coerceIn(1, 4))
        }
        val optionsJson = gson.toJson(options).replace("\"", "\\\"")

        mainHandler.post {
            val script = "window.FlowAutomation.generateWithReference('$taskId', \"$safePrompt\", '$base64Image', '$mimeType', '$filename', \"$optionsJson\");"
            webView?.evaluateJavascript(script) { result ->
                bridge.log("Executed generateWithReference ($taskId): $result")
            }
        }
        callback(taskId)
        return taskId
    }

    fun generateVideo(
        prompt: String,
        model: String = "veo-3.1",
        aspectRatio: String = "16:9",
        callback: (String) -> Unit
    ): String {
        val taskId = UUID.randomUUID().toString()
        val safePrompt = prompt.replace("\"", "\\\"").replace("\n", " ")

        val options = JsonObject().apply {
            addProperty("model", model)
            addProperty("aspectRatio", aspectRatio)
        }
        val optionsJson = gson.toJson(options).replace("\"", "\\\"")

        mainHandler.post {
            val script = "window.FlowAutomation.generateVideo('$taskId', \"$safePrompt\", \"$optionsJson\");"
            webView?.evaluateJavascript(script) { result ->
                bridge.log("Executed generateVideo ($taskId) [model=$model, ratio=$aspectRatio]: $result")
            }
        }
        callback(taskId)
        return taskId
    }

    fun listProjects(callback: (String) -> Unit) {
        mainHandler.post {
            webView?.evaluateJavascript("window.FlowAutomation.listProjects();") { result ->
                callback(result ?: "[]")
            }
        }
    }

    fun createProject(projectName: String) {
        val safeName = projectName.replace("\"", "\\\"")
        mainHandler.post {
            webView?.evaluateJavascript("window.FlowAutomation.createProject(\"$safeName\");") { result ->
                bridge.log("Created project $safeName: $result")
            }
        }
    }

    fun checkStatus(callback: (String) -> Unit) {
        mainHandler.post {
            webView?.evaluateJavascript("window.FlowAutomation.getAccountInfo();") { result ->
                callback(result ?: "{}")
            }
        }
    }

    fun discoverUi(callback: (String) -> Unit) {
        mainHandler.post {
            webView?.evaluateJavascript("window.FlowAutomation.discoverUi();") { result ->
                callback(result ?: "{}")
            }
        }
    }

    suspend fun downloadMedia(mediaUrl: String, customPath: String? = null): File = withContext(Dispatchers.IO) {
        val outputDir = if (customPath != null) {
            File(customPath).parentFile ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GoogleFlow")
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GoogleFlow")
        }
        if (!outputDir.exists()) outputDir.mkdirs()

        val fileName = if (customPath != null) {
            File(customPath).name
        } else {
            val ext = if (mediaUrl.contains(".mp4") || mediaUrl.contains("video")) "mp4" else "png"
            "flow_${System.currentTimeMillis()}.$ext"
        }

        val destination = File(outputDir, fileName)

        val request = Request.Builder().url(mediaUrl).build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("Failed to download media: HTTP ${response.code}")

        val inputStream = response.body?.byteStream() ?: throw Exception("Empty response body")
        FileOutputStream(destination).use { out ->
            inputStream.copyTo(out)
        }
        destination
    }
}
