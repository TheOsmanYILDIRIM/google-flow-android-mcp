package com.googleflow.mcp.server

import android.util.Base64
import android.webkit.CookieManager
import com.googleflow.mcp.engine.FlowScraperEngine
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class FlowMcpServer(
    private val engine: FlowScraperEngine,
    private val port: Int = 8765
) {
    private var server: ApplicationEngine? = null
    private val gson = Gson()

    fun start() {
        if (server != null) return

        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {
                get("/") {
                    call.respondText(
                        "Google Flow Android MCP Server v3.3 (Full Python Crawler & API Control Active)",
                        ContentType.Text.Plain
                    )
                }

                get("/api/status") {
                    val deferred = CompletableDeferred<String>()
                    engine.checkStatus { jsonStr ->
                        deferred.complete(jsonStr)
                    }
                    val result = deferred.await()
                    call.respondText(result, ContentType.Application.Json)
                }

                // Export current authenticated cookies directly to Python
                get("/api/cookies") {
                    val cookieManager = CookieManager.getInstance()
                    val labsCookies = cookieManager.getCookie("https://labs.google") ?: ""
                    val googleCookies = cookieManager.getCookie("https://google.com") ?: ""
                    val accountsCookies = cookieManager.getCookie("https://accounts.google.com") ?: ""
                    
                    val combined = listOf(labsCookies, googleCookies, accountsCookies)
                        .filter { it.isNotBlank() }
                        .joinToString("; ")

                    val res = mapOf(
                        "success" to true,
                        "cookieHeader" to combined,
                        "labsCookies" to labsCookies,
                        "googleCookies" to googleCookies,
                        "accountsCookies" to accountsCookies
                    )
                    call.respondText(gson.toJson(res), ContentType.Application.Json)
                }

                // Inject cookies into Android App WebView
                post("/api/cookies") {
                    val body = call.receiveText()
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val cookies = json.get("cookies")?.asString
                    if (!cookies.isNullOrBlank()) {
                        engine.importCookies(cookies)
                        val res = mapOf("success" to true, "message" to "Cookies imported")
                        call.respondText(gson.toJson(res), ContentType.Application.Json)
                    } else {
                        val res = mapOf("error" to "No cookies provided")
                        call.respondText(gson.toJson(res), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    }
                }

                // Get complete page HTML source for BeautifulSoup scraping
                get("/api/page-source") {
                    val deferred = CompletableDeferred<String>()
                    engine.webView?.post {
                        engine.webView?.evaluateJavascript("document.documentElement.outerHTML") { htmlResult ->
                            val unescaped = if (htmlResult != null && htmlResult.startsWith("\"") && htmlResult.endsWith("\"")) {
                                try {
                                    gson.fromJson(htmlResult, String::class.java)
                                } catch (e: Exception) {
                                    htmlResult
                                }
                            } else htmlResult ?: ""
                            deferred.complete(unescaped)
                        }
                    } ?: deferred.complete("")
                    
                    val html = deferred.await()
                    call.respondText(html, ContentType.Text.Html)
                }

                // Execute arbitrary JavaScript in the WebView and return result to Python
                post("/api/eval") {
                    val body = call.receiveText()
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val script = json.get("script")?.asString ?: ""
                    
                    val deferred = CompletableDeferred<String>()
                    engine.webView?.post {
                        engine.webView?.evaluateJavascript(script) { result ->
                            deferred.complete(result ?: "null")
                        }
                    } ?: deferred.complete("null")

                    val evalResult = deferred.await()
                    call.respondText(evalResult, ContentType.Application.Json)
                }

                // Navigate WebView to specific URL
                post("/api/navigate") {
                    val body = call.receiveText()
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val url = json.get("url")?.asString ?: ""
                    if (url.isNotBlank()) {
                        engine.webView?.post {
                            engine.webView?.loadUrl(url)
                        }
                        val res = mapOf("success" to true, "navigatedTo" to url)
                        call.respondText(gson.toJson(res), ContentType.Application.Json)
                    } else {
                        val res = mapOf("error" to "URL is required")
                        call.respondText(gson.toJson(res), ContentType.Application.Json, HttpStatusCode.BadRequest)
                    }
                }

                get("/api/dom-dump") {
                    val deferred = CompletableDeferred<String>()
                    engine.dumpDom { jsonStr ->
                        deferred.complete(jsonStr)
                    }
                    val result = deferred.await()
                    call.respondText(result, ContentType.Application.Json)
                }

                get("/api/projects") {
                    val deferred = CompletableDeferred<String>()
                    engine.listProjects { jsonStr ->
                        deferred.complete(jsonStr)
                    }
                    val result = deferred.await()
                    call.respondText(result, ContentType.Application.Json)
                }

                post("/api/projects") {
                    val body = call.receiveText()
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val name = json.get("name")?.asString ?: "New Project"
                    engine.createProject(name)
                    val res = mapOf("status" to "created", "name" to name)
                    call.respondText(gson.toJson(res), ContentType.Application.Json)
                }

                post("/api/generate") {
                    val body = call.receiveText()
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val prompt = json.get("prompt")?.asString ?: ""
                    val model = json.get("model")?.asString ?: "Nano Banana 2"
                    val aspectRatio = json.get("aspectRatio")?.asString ?: "1:1"
                    val count = json.get("count")?.asInt ?: 1
                    val outputPath = json.get("outputPath")?.asString

                    if (prompt.isBlank()) {
                        val res = mapOf("error" to "Prompt is required")
                        call.respondText(gson.toJson(res), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }

                    val deferred = CompletableDeferred<String>()
                    val taskId = engine.generateImage(prompt, model, aspectRatio, count) { tId ->
                        deferred.complete(tId)
                    }

                    val genResult = withTimeoutOrNull(240000) {
                        engine.bridge.generationEvents.first { it.taskId == taskId }
                    }

                    if (genResult != null && genResult.success) {
                        val mediaUrl = genResult.mediaUrl
                        try {
                            val localFile = engine.downloadMedia(mediaUrl, outputPath)
                            val res = mapOf(
                                "success" to true,
                                "taskId" to taskId,
                                "mediaUrl" to mediaUrl,
                                "localPath" to localFile.absolutePath,
                                "model" to model,
                                "aspectRatio" to aspectRatio
                            )
                            call.respondText(gson.toJson(res), ContentType.Application.Json)
                        } catch (e: Exception) {
                            val res = mapOf(
                                "success" to true,
                                "taskId" to taskId,
                                "mediaUrl" to mediaUrl,
                                "downloadError" to e.message
                            )
                            call.respondText(gson.toJson(res), ContentType.Application.Json)
                        }
                    } else {
                        val res = mapOf("error" to (genResult?.errorMessage ?: "Generation timed out or failed on device"))
                        call.respondText(gson.toJson(res), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                post("/api/generate-with-reference") {
                    val body = call.receiveText()
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val prompt = json.get("prompt")?.asString ?: ""
                    val imagePath = json.get("imagePath")?.asString ?: ""
                    val model = json.get("model")?.asString ?: "Nano Banana 2"
                    val aspectRatio = json.get("aspectRatio")?.asString ?: "1:1"
                    val count = json.get("count")?.asInt ?: 1
                    val outputPath = json.get("outputPath")?.asString

                    val refFile = File(imagePath)
                    if (!refFile.exists()) {
                        val res = mapOf("error" to "Reference image not found: $imagePath")
                        call.respondText(gson.toJson(res), ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }

                    val base64Image = Base64.encodeToString(refFile.readBytes(), Base64.NO_WRAP)
                    val mimeType = if (refFile.name.endsWith(".png", true)) "image/png" else "image/jpeg"

                    val deferred = CompletableDeferred<String>()
                    val taskId = engine.generateWithReference(prompt, base64Image, mimeType, refFile.name, model, aspectRatio, count) { tId ->
                        deferred.complete(tId)
                    }

                    val genResult = withTimeoutOrNull(260000) {
                        engine.bridge.generationEvents.first { it.taskId == taskId }
                    }

                    if (genResult != null && genResult.success) {
                        val mediaUrl = genResult.mediaUrl
                        try {
                            val localFile = engine.downloadMedia(mediaUrl, outputPath)
                            val res = mapOf(
                                "success" to true,
                                "taskId" to taskId,
                                "mediaUrl" to mediaUrl,
                                "localPath" to localFile.absolutePath,
                                "model" to model,
                                "aspectRatio" to aspectRatio
                            )
                            call.respondText(gson.toJson(res), ContentType.Application.Json)
                        } catch (e: Exception) {
                            val res = mapOf(
                                "success" to true,
                                "taskId" to taskId,
                                "mediaUrl" to mediaUrl,
                                "downloadError" to e.message
                            )
                            call.respondText(gson.toJson(res), ContentType.Application.Json)
                        }
                    } else {
                        val res = mapOf("error" to (genResult?.errorMessage ?: "Reference image generation failed or timed out"))
                        call.respondText(gson.toJson(res), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                post("/api/video") {
                    val body = call.receiveText()
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val prompt = json.get("prompt")?.asString ?: ""
                    val model = json.get("model")?.asString ?: "Veo 3.1 - Fast"
                    val aspectRatio = json.get("aspectRatio")?.asString ?: "16:9"
                    val outputPath = json.get("outputPath")?.asString

                    val deferred = CompletableDeferred<String>()
                    val taskId = engine.generateVideo(prompt, model, aspectRatio) { tId ->
                        deferred.complete(tId)
                    }

                    val genResult = withTimeoutOrNull(400000) {
                        engine.bridge.generationEvents.first { it.taskId == taskId }
                    }

                    if (genResult != null && genResult.success) {
                        val mediaUrl = genResult.mediaUrl
                        try {
                            val localFile = engine.downloadMedia(mediaUrl, outputPath)
                            val res = mapOf(
                                "success" to true,
                                "taskId" to taskId,
                                "mediaUrl" to mediaUrl,
                                "localPath" to localFile.absolutePath,
                                "model" to model,
                                "aspectRatio" to aspectRatio
                            )
                            call.respondText(gson.toJson(res), ContentType.Application.Json)
                        } catch (e: Exception) {
                            val res = mapOf(
                                "success" to true,
                                "taskId" to taskId,
                                "mediaUrl" to mediaUrl,
                                "downloadError" to e.message
                            )
                            call.respondText(gson.toJson(res), ContentType.Application.Json)
                        }
                    } else {
                        val res = mapOf("error" to (genResult?.errorMessage ?: "Video generation timed out or failed"))
                        call.respondText(gson.toJson(res), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                post("/mcp") {
                    val body = call.receiveText()
                    val rpcReq = gson.fromJson(body, JsonObject::class.java)
                    val id = rpcReq.get("id")?.asInt ?: 1
                    val method = rpcReq.get("method")?.asString ?: ""

                    when (method) {
                        "initialize" -> {
                            val res = JsonObject().apply {
                                addProperty("jsonrpc", "2.0")
                                addProperty("id", id)
                                add("result", JsonObject().apply {
                                    addProperty("protocolVersion", "2024-11-05")
                                    add("capabilities", JsonObject().apply {
                                        add("tools", JsonObject())
                                    })
                                    add("serverInfo", JsonObject().apply {
                                        addProperty("name", "google-flow-android-mcp")
                                        addProperty("version", "3.3.0")
                                    })
                                })
                            }
                            call.respondText(gson.toJson(res), ContentType.Application.Json)
                        }
                        "tools/list" -> {
                            val toolsJson = """
                            {
                                "jsonrpc": "2.0",
                                "id": $id,
                                "result": {
                                    "tools": [
                                        {
                                            "name": "flow_status",
                                            "description": "Check Google Flow status, active credits, and supported models (Veo 3.1 & Nano Banana 2)",
                                            "inputSchema": { "type": "object", "properties": {} }
                                        },
                                        {
                                            "name": "flow_dump_dom",
                                            "description": "Inspect and dump full real-time DOM structure of the current Flow page",
                                            "inputSchema": { "type": "object", "properties": {} }
                                        },
                                        {
                                            "name": "flow_get_cookies",
                                            "description": "Extract active session cookies from Android app for Python scraping",
                                            "inputSchema": { "type": "object", "properties": {} }
                                        },
                                        {
                                            "name": "flow_generate_image",
                                            "description": "Generate an image using Google Flow / Nano Banana 2",
                                            "inputSchema": {
                                                "type": "object",
                                                "properties": {
                                                    "prompt": { "type": "string" },
                                                    "model": { "type": "string", "enum": ["Nano Banana 2", "Nano Banana"], "default": "Nano Banana 2" },
                                                    "aspect_ratio": { "type": "string", "enum": ["1:1", "16:9", "9:16", "4:3", "3:4", "2:3", "3:2"], "default": "1:1" },
                                                    "count": { "type": "integer", "enum": [1, 2, 3, 4], "default": 1 },
                                                    "output_path": { "type": "string" }
                                                },
                                                "required": ["prompt"]
                                            }
                                        },
                                        {
                                            "name": "flow_generate_video",
                                            "description": "Generate video using Google Veo 3.1",
                                            "inputSchema": {
                                                "type": "object",
                                                "properties": {
                                                    "prompt": { "type": "string" },
                                                    "aspect_ratio": { "type": "string", "enum": ["16:9", "9:16"], "default": "16:9" },
                                                    "output_path": { "type": "string" }
                                                },
                                                "required": ["prompt"]
                                            }
                                        }
                                    ]
                                }
                            }
                            """.trimIndent()
                            call.respondText(toolsJson, ContentType.Application.Json)
                        }
                        else -> {
                            val errRes = JsonObject().apply {
                                addProperty("jsonrpc", "2.0")
                                addProperty("id", id)
                                add("error", JsonObject().apply {
                                    addProperty("code", -32601)
                                    addProperty("message", "Method not found: $method")
                                })
                            }
                            call.respondText(gson.toJson(errRes), ContentType.Application.Json)
                        }
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }
}
