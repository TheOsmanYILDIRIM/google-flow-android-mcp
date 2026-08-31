package com.googleflow.mcp.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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

    private val flowUrl = "https://labs.google/fx/"
    private val desktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

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
            userAgentString = desktopUserAgent
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        view.addJavascriptInterface(bridge, "AndroidBridge")

        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                bridge.log("Page started: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                bridge.log("Page finished: $url")
                injectBridgeScript()
            }
        }

        view.webChromeClient = WebChromeClient()
        view.loadUrl(flowUrl)
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
