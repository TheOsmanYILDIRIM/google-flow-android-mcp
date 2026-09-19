package com.googleflow.mcp.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
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
import java.io.ByteArrayOutputStream
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

    // Exact official URLs
    val flowUrl = "https://labs.google/fx/tools/flow"
    val imageFxUrl = "https://labs.google/fx/tools/image-fx"
    val videoFxUrl = "https://labs.google/fx/tools/video-fx"
    val loginUrl = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Flabs.google%2Ffx%2Ftools%2Fflow"

    // Clean browser User Agent profiles (Firefox Desktop & Safari macOS bypass Google OAuth disallowed_useragent)
    val firefoxUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) Gecko/20100101 Firefox/130.0"
    val safariUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15"
    val pixelChromeUserAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.6668.70 Mobile Safari/537.36"
    val desktopChromeUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

    var currentUserAgent = safariUserAgent

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
            userAgentString = currentUserAgent
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            try {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.settings, emptySet())
                bridge.log("X-Requested-With header stripped.")
            } catch (e: Exception) {
                bridge.log("RequestedWith error: ${e.message}")
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
                val targetUrl = url ?: ""
                if (targetUrl.contains("accounts.google.com") || targetUrl.contains("accounts.youtube.com")) {
                    view?.settings?.userAgentString = firefoxUserAgent
                } else {
                    view?.settings?.userAgentString = currentUserAgent
                }
                bridge.log("Loading: $targetUrl")
                bridge.setPageInfo(targetUrl, view?.title ?: "")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                cookieManager.flush()
                bridge.log("Loaded: $url")
                bridge.setPageInfo(url ?: "", view?.title ?: "")
                injectBridgeScripts()

                try {
                    val labs = cookieManager.getCookie("https://labs.google") ?: ""
                    val google = cookieManager.getCookie("https://google.com") ?: ""
                    val accounts = cookieManager.getCookie("https://accounts.google.com") ?: ""
                    val all = listOf(labs, google, accounts).filter { it.isNotBlank() }.joinToString("; ")
                    if (all.contains("PSID") || all.contains("SSID") || all.contains("OTZ") || all.contains("SID")) {
                        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GoogleFlow")
                        if (!dir.exists()) dir.mkdirs()
                        File(dir, "cookies.txt").writeText(all)
                    }
                } catch (e: Exception) {}
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                bridge.setPageInfo(view?.url ?: "", title ?: "")
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val newWebView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.userAgentString = currentUserAgent
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

    fun navigate(url: String, customUserAgent: String? = null) {
        mainHandler.post {
            if (!customUserAgent.isNullOrBlank()) {
                currentUserAgent = customUserAgent
                webView?.settings?.userAgentString = customUserAgent
            }
            webView?.loadUrl(url)
            bridge.log("Navigating to: $url")
        }
    }

    fun reload() {
        mainHandler.post {
            webView?.reload()
            bridge.log("Reloading current page")
        }
    }

    fun goBack() {
        mainHandler.post {
            if (webView?.canGoBack() == true) {
                webView?.goBack()
                bridge.log("Navigated back")
            }
        }
    }

    fun goForward() {
        mainHandler.post {
            if (webView?.canGoForward() == true) {
                webView?.goForward()
                bridge.log("Navigated forward")
            }
        }
    }

    fun switchUserAgent(ua: String) {
        currentUserAgent = ua
        mainHandler.post {
            webView?.settings?.userAgentString = ua
            webView?.reload()
            bridge.log("Switched User-Agent to: $ua")
        }
    }

    fun importCookies(cookieString: String, targetDomain: String = "https://labs.google") {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieString.split(";")
        for (rawCookie in cookies) {
            val cookie = rawCookie.trim()
            if (cookie.isNotEmpty()) {
                cookieManager.setCookie(targetDomain, cookie)
                cookieManager.setCookie("https://google.com", cookie)
                cookieManager.setCookie("https://accounts.google.com", cookie)
            }
        }
        cookieManager.flush()
        mainHandler.post {
            bridge.log("Imported ${cookies.size} cookies for $targetDomain.")
        }
    }

    fun getCookiesForUrl(url: String): String {
        val cookieManager = CookieManager.getInstance()
        return cookieManager.getCookie(url) ?: ""
    }

    fun clearAllCookies(callback: () -> Unit) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies {
            cookieManager.flush()
            bridge.log("All cookies cleared.")
            callback()
        }
    }

    fun injectBridgeScripts() {
        val currentUrl = webView?.url ?: ""
        if (currentUrl.contains("accounts.google.com") || currentUrl.contains("accounts.youtube.com")) {
            bridge.log("Google Login page active: Skipping script injection to prevent bot detection.")
            return
        }

        try {
            val browserBridgeJs = context.assets.open("browser_bridge.js").bufferedReader().use { it.readText() }
            mainHandler.post {
                webView?.evaluateJavascript(browserBridgeJs) { _ ->
                    bridge.log("Universal BrowserBridge injected.")
                }
            }
        } catch (e: Exception) {
            bridge.log("Failed to inject BrowserBridge: ${e.message}")
        }

        try {
            val flowBridgeJs = context.assets.open("flow_bridge.js").bufferedReader().use { it.readText() }
            mainHandler.post {
                webView?.evaluateJavascript(flowBridgeJs) { _ ->
                    bridge.log("FlowBridge injected.")
                }
            }
        } catch (e: Exception) {}
    }

    fun evaluateJs(script: String, callback: (String) -> Unit) {
        mainHandler.post {
            webView?.evaluateJavascript(script) { result ->
                val cleaned = if (result != null && result.startsWith("\"") && result.endsWith("\"")) {
                    try {
                        gson.fromJson(result, String::class.java)
                    } catch (e: Exception) {
                        result
                    }
                } else result ?: "null"
                callback(cleaned)
            }
        }
    }

    fun getDom(format: String = "interactive", selector: String? = null, callback: (String) -> Unit) {
        val safeSel = if (selector != null) "\"${selector.replace("\"", "\\\"")}\"" else "null"
        val script = "window.__AGY_BROWSER__ ? JSON.stringify(window.__AGY_BROWSER__.getDom('$format', $safeSel)) : JSON.stringify({success: false, error: 'Bridge not loaded'});"
        mainHandler.post {
            webView?.evaluateJavascript(script) { result ->
                val unescaped = if (result != null && result.startsWith("\"") && result.endsWith("\"")) {
                    try {
                        gson.fromJson(result, String::class.java)
                    } catch (e: Exception) {
                        result
                    }
                } else result ?: "{}"
                callback(unescaped)
            }
        }
    }

    fun clickElement(selector: String?, textMatch: String?, callback: (String) -> Unit) {
        val safeSel = if (selector != null) "\"${selector.replace("\"", "\\\"")}\"" else "null"
        val safeText = if (textMatch != null) "\"${textMatch.replace("\"", "\\\"")}\"" else "null"
        val script = "window.__AGY_BROWSER__ ? JSON.stringify(window.__AGY_BROWSER__.click($safeSel, $safeText)) : JSON.stringify({success: false, error: 'Bridge not ready'});"
        mainHandler.post {
            webView?.evaluateJavascript(script) { result ->
                val unescaped = if (result != null && result.startsWith("\"") && result.endsWith("\"")) {
                    try {
                        gson.fromJson(result, String::class.java)
                    } catch (e: Exception) {
                        result
                    }
                } else result ?: "{}"
                callback(unescaped)
            }
        }
    }

    fun typeText(selector: String, text: String, clearFirst: Boolean = true, pressEnter: Boolean = false, callback: (String) -> Unit) {
        val safeSel = selector.replace("\"", "\\\"")
        val safeText = text.replace("\"", "\\\"").replace("\n", "\\n")
        val script = "window.__AGY_BROWSER__ ? JSON.stringify(window.__AGY_BROWSER__.type(\"$safeSel\", \"$safeText\", $clearFirst, $pressEnter)) : JSON.stringify({success: false, error: 'Bridge not ready'});"
        mainHandler.post {
            webView?.evaluateJavascript(script) { result ->
                val unescaped = if (result != null && result.startsWith("\"") && result.endsWith("\"")) {
                    try {
                        gson.fromJson(result, String::class.java)
                    } catch (e: Exception) {
                        result
                    }
                } else result ?: "{}"
                callback(unescaped)
            }
        }
    }

    fun captureScreenshot(callback: (path: String?, base64: String?, error: String?) -> Unit) {
        mainHandler.post {
            val v = webView
            if (v == null || v.width <= 0 || v.height <= 0) {
                callback(null, null, "WebView is not attached or has zero dimensions")
                return@post
            }

            try {
                val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                v.draw(canvas)

                // Save to Documents/BrowserBridge
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "BrowserBridge")
                if (!dir.exists()) dir.mkdirs()

                val filename = "screenshot_${System.currentTimeMillis()}.png"
                val file = File(dir, filename)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }

                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 85, baos)
                val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                bridge.log("Screenshot saved: ${file.absolutePath}")
                callback(file.absolutePath, base64, null)
            } catch (e: Exception) {
                bridge.log("Screenshot error: ${e.message}")
                callback(null, null, e.message)
            }
        }
    }

    fun dumpDom(callback: (String) -> Unit) {
        mainHandler.post {
            webView?.evaluateJavascript("window.__AGY_BROWSER__ ? JSON.stringify(window.__AGY_BROWSER__.getDom('html')) : window.FlowAutomation ? window.FlowAutomation.dumpFullDom() : '{}';") { result ->
                val unescaped = if (result != null && result.startsWith("\"") && result.endsWith("\"")) {
                    try {
                        gson.fromJson(result, String::class.java)
                    } catch (e: Exception) {
                        result
                    }
                } else result ?: "{}"
                callback(unescaped)
            }
        }
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

    fun loadImageFxUrl() {
        mainHandler.post {
            webView?.loadUrl(imageFxUrl)
        }
    }

    fun loadVideoFxUrl() {
        mainHandler.post {
            webView?.loadUrl(videoFxUrl)
        }
    }

    fun generateImage(
        prompt: String,
        model: String = "Nano Banana 2",
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
            val script = "window.FlowAutomation ? window.FlowAutomation.generateImage('$taskId', \"$safePrompt\", \"$optionsJson\") : null;"
            webView?.evaluateJavascript(script) { result ->
                bridge.log("Executed generateImage ($taskId): $result")
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
        model: String = "Nano Banana 2",
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
            val script = "window.FlowAutomation ? window.FlowAutomation.generateImage('$taskId', \"$safePrompt\", \"$optionsJson\") : null;"
            webView?.evaluateJavascript(script) { result ->
                bridge.log("Executed generateWithReference ($taskId): $result")
            }
        }
        callback(taskId)
        return taskId
    }

    fun generateVideo(
        prompt: String,
        model: String = "Veo 3.1 - Fast",
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
            val script = "window.FlowAutomation ? window.FlowAutomation.generateVideo('$taskId', \"$safePrompt\", \"$optionsJson\") : null;"
            webView?.evaluateJavascript(script) { result ->
                bridge.log("Executed generateVideo ($taskId): $result")
            }
        }
        callback(taskId)
        return taskId
    }

    fun listProjects(callback: (String) -> Unit) {
        mainHandler.post {
            webView?.evaluateJavascript("window.FlowAutomation && window.FlowAutomation.listProjects ? window.FlowAutomation.listProjects() : '[]';") { result ->
                callback(result ?: "[]")
            }
        }
    }

    fun createProject(projectName: String) {
        val safeName = projectName.replace("\"", "\\\"")
        mainHandler.post {
            webView?.evaluateJavascript("window.FlowAutomation && window.FlowAutomation.createProject ? window.FlowAutomation.createProject(\"$safeName\") : null;") { result ->
                bridge.log("Created project $safeName: $result")
            }
        }
    }

    fun checkStatus(callback: (String) -> Unit) {
        mainHandler.post {
            webView?.evaluateJavascript("window.FlowAutomation && window.FlowAutomation.getAccountInfo ? window.FlowAutomation.getAccountInfo() : '{}';") { result ->
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
